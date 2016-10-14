/*
 * Wire
 * Copyright (C) 2016 Wire Swiss GmbH
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.waz.service.push

import java.util.Date

import android.content.Context
import com.waz.ZLog._
import com.waz.content.KeyValueStorage
import com.waz.model._
import com.waz.model.otr.ClientId
import com.waz.service.EventPipeline
import com.waz.service.push.PushService.SlowSyncRequest
import com.waz.sync.client.EventsClient.NotificationsResponse
import com.waz.sync.client.{EventsClient, PushNotification}
import com.waz.threading.{CancellableFuture, SerialDispatchQueue}
import com.waz.utils._
import com.waz.utils.events._

import scala.concurrent.{Future, Promise}

class PushService(context: Context, keyValue: KeyValueStorage, client: EventsClient, clientId: ClientId, signals: PushServiceSignals, pipeline: EventPipeline, webSocket: WebSocketClientService, gcmService: GcmService) { self =>
  private implicit val dispatcher = new SerialDispatchQueue(name = "PushService")
  private implicit val tag: LogTag = logTagFor[PushService]
  private implicit val ec = EventContext.Global

  private val wakeLock = new WakeLock(context)

  val lastNotification = new LastNotificationIdService(keyValue, signals, client, clientId)

  var connectedPushPromise = Promise[PushService]()

  webSocket.connected { signals.pushConnected ! _ }

  client.onNotificationsPageLoaded.on(dispatcher) { notifications =>
    // XXX: we will process all available history notifications even for the case that last id was not found,
    //      maybe this would be enough for some conversations and we would not need to sync so much
    processHistoryEvents(notifications.notifications, new Date)

    if (notifications.lastIdWasFound) debug("got missing notifications, great")
    else {
      info(s"server couldn't provide all missing notifications, will schedule slow sync, after processing available events")
      signals.onSlowSyncNeeded ! SlowSyncRequest(System.currentTimeMillis(), lostHistory = true)
    }
  }

  private var subs = Seq.empty[Subscription]

  webSocket.client.on(dispatcher) {
    case None =>
      subs.foreach(_.destroy())
      subs = Nil

    case Some(ws) =>
      subs.foreach(_.destroy())
      subs = Seq(
        ws.onMessage { content =>
          verbose(s"got websocket message: $content")
          content match {
            case NotificationsResponse(notifications @ _*) =>
              debug(s"got notifications from data: $content")
              notifications.foreach(onPushNotification)
            case resp =>
              error(s"unexpected push response: $resp")
          }
        },
        ws.onError { ex =>
          warn(s"some PushNotification processing failed, will schedule slow sync", ex)
          signals.onSlowSyncNeeded ! SlowSyncRequest(System.currentTimeMillis())
        }
      )
  }

  webSocket.connected {
    case true =>
      debug("onConnected")
      syncHistoryOnConnect()
      connectedPushPromise.trySuccess(self)
    case false =>
      debug("onDisconnected")
      connectedPushPromise.tryFailure(new Exception("onDisconnected"))
      connectedPushPromise = Promise()
  }

  def onPushNotification(notification: PushNotification): Unit = {
    debug(s"gotPushNotification: $notification")

    val localTime = new Date
    notification.events foreach { ev =>
      ev.localTime = localTime
      verbose(s"event: $ev")
    }
    if (notification.hasEventForClient(clientId)) {
      val f = wakeLock { pipeline(notification.events) }
      if (!notification.transient) lastNotification.updateLastIdOnNotification(notification.id, f)
    } else {
      verbose(s"received notification intended for other client: $notification")
    }
  }

  private def syncHistoryOnConnect(): Unit =
    lastNotification.lastNotificationId() map {
      case None =>
        debug("no last notification Id on connect, will schedule slow sync")
        signals.onSlowSyncNeeded ! SlowSyncRequest(System.currentTimeMillis())
      case Some(LastNotificationIdService.NoNotificationsId) =>
        verbose(s"no last notification available, will try loading all notifications")
        loadNotifications(None)
      case Some(id) =>
        verbose(s"found last notification: $id, will load notifications")
        loadNotifications(Some(id))
    }

  private def loadNotifications(lastId: Option[Uid]): Future[Unit] =
    client.loadNotifications(lastId, clientId, pageSize = EventsClient.PageSize).flatMap {
      case Left(error) =>
        warn(s"/notifications failed with error: $error, will schedule slow sync")
        Future.successful(signals.onSlowSyncNeeded ! SlowSyncRequest(System.currentTimeMillis()))
      case Right(updatedLastId) =>
        verbose(s"notifications loaded, last id: $updatedLastId")
        // update last notification id once all events are processed
        // (the results themselves will be handled by the event stream)
        lastNotification.updateLastIdOnHistorySynced(updatedLastId.orElse(lastId))
    }.andThen { case _ => gcmService.notificationsToProcess ! false } //Finished loading notifications, allow websocket to close


  private def processHistoryEvents(notifications: Seq[PushNotification], fetchTime: Date) = {
    val events = notifications.flatMap(_.events)
    events.foreach(_.notificationsFetchTime = fetchTime)
    pipeline(events)
  }
}

object PushService {
  case class SlowSyncRequest(time: Long, lostHistory: Boolean = false) // lostHistory is set if there were lost notifications, meaning that some messages might be lost
}

class PushServiceSignals {
  val onSlowSyncNeeded = new SourceSignal[SlowSyncRequest] with BgEventSource
  val pushConnected = Signal[Boolean]()
}

/**
 * Keeps track of last received notifications and updates lastNotificationId preference accordingly.
 * New notifications are ignored as long as historical events are not processed.
 * Last id is fetched from backend whenever slow sync is requested.
 */
class LastNotificationIdService(keyValueService: KeyValueStorage, signals: PushServiceSignals, client: EventsClient, clientId: ClientId) {
  import LastNotificationIdService.State._
  import LastNotificationIdService._

  private implicit val logTag = logTagFor[LastNotificationIdService]
  private implicit val dispatcher = new SerialDispatchQueue(name = "LastNotificationIdService")
  private implicit val ec = EventContext.Global
  import keyValueService._

  private var state: State = Disconnected
  private var freshNotificationId = Option.empty[Uid]
  private var fetchLast = CancellableFuture.successful(())

  val lastNotificationIdPref = keyValuePref[Option[Uid]](LastNotificationIdKey, None)

  def lastNotificationId() = Future(lastNotificationIdPref()).flatten // future and flatten ensure that there is no race with onNotification

  private[service] def currentState = Future { state }

  signals.pushConnected.on(dispatcher) { connected =>
    if (connected) {
      verbose(s"onConnected() in state: $state")
      state = Waiting
      freshNotificationId = None
    } else {
      verbose(s"onDisconnected() in state: $state")
      fetchLast.cancel()
      state = Disconnected
    }
  }

  signals.onSlowSyncNeeded.on(dispatcher) { _ =>
    verbose(s"onSlowSync() in state: $state")
    if (state == Waiting) state = Running
    freshNotificationId foreach { id =>
      lastNotificationIdPref := Some(id)
      verbose(s"updated pref on slow sync request: $id")
    }
    freshNotificationId = None

    fetchLast = client.loadLastNotification(clientId) map {
      case Right(Some(notification)) =>
        verbose(s"fetched last notification: $notification in state: $state")
        if (state == Running && freshNotificationId.isEmpty) {
          lastNotificationIdPref := Some(notification.id)
          verbose(s"updated pref from backend on slow sync: ${notification.id}")
        }
      case Right(None) =>
        verbose(s"no last notification available on backend, in state: $state")
        if (state == Running && freshNotificationId.isEmpty) {
          lastNotificationIdPref := Some(NoNotificationsId)
        }
      case Left(error) =>
        warn(s"/notifications/last failed with error: $error")
    }
  }

  def updateLastIdOnHistorySynced(id: Option[Uid]) = Future {
    verbose(s"onHistorySynced($id) in state: $state")
    if (state == Waiting) {
      state = Running
      lastNotificationIdPref := freshNotificationId.orElse(id).orElse(Some(NoNotificationsId))
      verbose(s"updated pref on history synced: $id, fresh: $freshNotificationId")
    }
  }

  def updateLastIdOnNotification(id: Uid, processed: Future[Any]): Unit = {
    verbose(s"onNotification($id) in state: $state")
    freshNotificationId = Some(id)
    fetchLast.cancel()
    processed.onSuccess {
      case _ => if (state == Running && freshNotificationId.contains(id)) {
        lastNotificationIdPref := freshNotificationId
        verbose(s"updated pref on fresh notification: $id")
      }
    }
  }
}

object LastNotificationIdService {
  val LastNotificationIdKey = "last_notification_id"
  val NoNotificationsId = Uid(0, 0)

  sealed trait State
  object State {
    case object Disconnected extends State
    case object Waiting extends State
    case object Running extends State // webSocket is connected and historical notifications are synced
  }
}
