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

import java.util
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
    onPushNotifications(notifications.notifications, wereFetched = true)

    if (notifications.lastIdWasFound) debug(s"Loaded page of ${notifications.notifications.size} notifications")
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
              onPushNotifications(notifications, wereFetched = false)
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

  def onPushNotification(n: PushNotification) = onPushNotifications(Seq(n), wereFetched = false) //used in tests

  private def onPushNotifications(ns: Seq[PushNotification], wereFetched: Boolean): Unit = if (ns.nonEmpty) {
    debug(s"gotPushNotifications: $ns")
    ns.lift(ns.lastIndexWhere(!_.transient)).foreach { n =>
      lastNotification.updateLastIdOnNotification(n.id, processNotifications(ns, wereFetched))
    }
  }

  private def processNotifications(notifications: Seq[PushNotification], wereFetched: Boolean) = wakeLock { pipeline {
    returning(notifications.flatMap(_.eventsForClient(clientId))) {
      _.foreach { ev =>
        verbose(s"event: $ev")
        if (wereFetched) ev.notificationsFetchTime = new Date
        else ev.localTime = new util.Date()
      }
    }
  }.map {
    _ => gcmService.notificationsToProcess ! false //Finished loading notifications, allow websocket to close
  }}

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
      case _ => Future.successful(()) //notifications results handled by event stream due to paging
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
 * Last id is fetched from backend whenever slow sync is requested.
 */
class LastNotificationIdService(keyValueService: KeyValueStorage, signals: PushServiceSignals, client: EventsClient, clientId: ClientId) {
  import LastNotificationIdService._

  private implicit val logTag = logTagFor[LastNotificationIdService]
  private implicit val dispatcher = new SerialDispatchQueue(name = "LastNotificationIdService")
  private implicit val ec = EventContext.Global
  import keyValueService._

  private var fetchLast = CancellableFuture.successful(())

  val lastNotificationIdPref = keyValuePref[Option[Uid]](LastNotificationIdKey, None)

  def lastNotificationId() = Future(lastNotificationIdPref()).flatten // future and flatten ensure that there is no race with onNotification

  signals.pushConnected.filter(_ == false).on(dispatcher)(_ => fetchLast.cancel())

  signals.onSlowSyncNeeded.on(dispatcher) { _ =>
    verbose(s"onSlowSync()")
    fetchLast = client.loadLastNotification(clientId) map {
      case Right(Some(notification)) =>
        verbose(s"fetched last notification: $notification")
        lastNotificationIdPref := Some(notification.id)
        verbose(s"updated pref from backend on slow sync: ${notification.id}")
      case Right(None) =>
        verbose(s"no last notification available on backend")
        lastNotificationIdPref := Some(NoNotificationsId)
      case Left(error) =>
        warn(s"/notifications/last failed with error: $error")
    }
  }

  def updateLastIdOnNotification(id: Uid, processing: Future[Unit]): Unit = {
    fetchLast.cancel()
    processing.onSuccess {
      case _ =>
        lastNotificationIdPref := Some(id)
        verbose(s"updated last id: $id")
    }
  }
}

object LastNotificationIdService {
  val LastNotificationIdKey = "last_notification_id"
  val NoNotificationsId = Uid(0, 0)
}
