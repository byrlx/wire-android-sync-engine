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

import com.waz.ZLog.ImplicitTag._
import com.waz.ZLog._
import com.waz.content.KeyValueStorage
import com.waz.model._
import com.waz.service._
import com.waz.service.conversation.ConversationsContentUpdater
import com.waz.service.push.GcmGlobalService.GcmRegistration
import com.waz.sync.SyncServiceHandle
import com.waz.sync.client.{EventsClient, PushNotification}
import com.waz.utils._
import com.waz.utils.events.{EventContext, Signal}
import org.threeten.bp.Instant

import scala.collection.breakOut
import scala.concurrent.Future

class GcmService(accountId: AccountId, gcmGlobalService: GcmGlobalService, keyVale: KeyValueStorage, convsContent: ConversationsContentUpdater,
                 eventsClient: EventsClient, eventPipeline: EventPipeline, sync: SyncServiceHandle, lifecycle: ZmsLifecycle) {
  import GcmService._
  implicit val dispatcher = gcmGlobalService.dispatcher

  private implicit val ev = EventContext.Global

  val notificationsToProcess = Signal(false)

  val gcmAvailable = gcmGlobalService.gcmAvailable

  val lastReceivedConvEventTime = keyVale.keyValuePref[Instant]("last_received_conv_event_time", Instant.EPOCH)
  val lastFetchedConvEventTime = keyVale.keyValuePref[Instant]("last_fetched_conv_event_time", Instant.ofEpochMilli(1))
  val lastFetchedLocalTime = keyVale.keyValuePref[Instant]("last_fetched_local_time", Instant.EPOCH)
  val lastRegistrationTime = keyVale.keyValuePref[Instant]("gcm_registration_time", Instant.EPOCH)
  val registrationRetryCount = keyVale.keyValuePref[Int]("gcm_registration_retry_count", 0)

  /**
    * Current GCM state, true if we are receiving notifications on it.
    * We are only comparing timestamps of conversation events, considering events received on GCM and fetched by EventsClient.
    * Events are fetched only when web socket connects (meaning GCM was considered active), this means that this state should rarely change.
    *
    * GcmState will be active if the last received event is up-to-date or newer than the last fetched event, OR we have registered our GCM token since the
    * last time we fetched a message (meaning we 'just registered')
    */
  val gcmState = for {
    lastFetched <- lastFetchedConvEventTime.signal
    lastReceived <- lastReceivedConvEventTime.signal
    localFetchTime <- lastFetchedLocalTime.signal
    lastRegistered <- lastRegistrationTime.signal
  } yield {
    verbose(s"gcmState, fetched: $lastFetched, received: $lastReceived, fetchTime: $localFetchTime, register: $lastReceived")
    GcmState(lastFetched <= lastReceived, localFetchTime <= lastRegistered)
  }

  eventsClient.onNotificationsPageLoaded.map(_.notifications.flatMap(_.events).collect{ case ce: ConversationEvent => ce })
    .filter(_.nonEmpty).on(dispatcher) (updateFetchedTimes)

  private def updateFetchedTimes(ces: Vector[ConversationEvent]) = {
    Future.traverse(ces.groupBy(_.convId)) { case (convId, evs) =>
      convsContent.convByRemoteId(convId) collect {
        case Some(conv) if !conv.muted => evs.maxBy(_.time)
      }
    } foreach { evs =>
      if (evs.nonEmpty) {
        val last = evs.maxBy(_.time).time.instant
        if (last != Instant.EPOCH) {
          lastFetchedConvEventTime := last
          lastFetchedLocalTime := Instant.now
        }
      }
    }
  }

  val shouldReRegister = for {
    state <- gcmState
    loggedIn <- lifecycle.loggedIn
    time <- lastRegistrationTime.signal
    retries <- registrationRetryCount.signal
  } yield {
    verbose(s"should re-register, available: ${gcmGlobalService.gcmAvailable}, loggedIn: $loggedIn, state: $state, lastTry: $time, retries: $retries")
    gcmGlobalService.gcmAvailable && loggedIn && !state.active && RegistrationRetryBackoff.delay(retries).elapsedSince(time)
  }

  gcmState {
    case GcmState(true, _) => registrationRetryCount := 0
    case _ =>
  }

  shouldReRegister {
    case true =>
      verbose(s"shouldReRegister == true")
      for {
        retries <- registrationRetryCount()
        _ <- registrationRetryCount := retries + 1
        _ <- gcmGlobalService.unregister()
        _ <- ensureGcmRegistered()
      } yield ()

    case false =>
      verbose(s"shouldReRegister == false")
  }

  def gcmSenderId = gcmGlobalService.gcmSenderId

  def ensureGcmRegistered(): Future[Any] =
    gcmGlobalService.getGcmRegistration.future map {
      case r @ GcmRegistration(_, userId, _) if userId == accountId => verbose(s"ensureGcmRegistered() - already registered: $r")
      case _ => sync.registerGcm()
    }

  ensureGcmRegistered()

  lifecycle.lifecycleState.map(_ == LifecycleState.Stopped) {
    case false => ensureGcmRegistered()
    case true =>
      // lifecycle got to Stopped, means that were logged out
      gcmGlobalService.getGcmRegistration map {
        case GcmRegistration(token, userId, _) if userId == accountId =>
          gcmGlobalService.clearGcmRegistrationUser(accountId)
          sync.deleteGcmToken(GcmId(token))
        case _ => // do nothing
      }
  }

  val eventProcessingStage = EventScheduler.Stage[GcmTokenRemoveEvent] { (convId, events) =>
    gcmGlobalService.getGcmRegistration.future map { reg =>
      events find (reg.token == _.token) foreach { _ =>
        gcmGlobalService.unregister() flatMap (_ => ensureGcmRegistered())
      }
    }
  }

  def register(post: GcmRegistration => Future[Boolean]): Future[Option[GcmRegistration]] =
    gcmGlobalService.registerGcm(accountId).future flatMap {
      case Some(reg) => post(reg) flatMap {
        case true =>
          lastRegistrationTime := Instant.now
          gcmGlobalService.updateRegisteredUser(reg.token, accountId).future map { Some(_) }
        case false => Future.successful(Some(reg))
      }
      case None => Future.successful(None)
    }

  def addNotificationToProcess(n: PushNotification): Future[Any] = {
    val time = n.lastConvEventTime
    if (time != Instant.EPOCH) lastReceivedConvEventTime := time

    lifecycle.lifecycleState.head flatMap {
      case LifecycleState.UiActive | LifecycleState.Active => Future.successful(()) // no need to process GCM when ui is active
      case _ =>
        verbose(s"addNotification: $n")
        // call state events can not be directly dispatched like the other events because they might be stale
        syncCallStateForConversations(n.events.collect { case e: CallStateEvent => e }.map(_.withCurrentLocalTime()))
        Future.successful(notificationsToProcess ! true)
    }
  }

  private def syncCallStateForConversations(events: Seq[Event]): Unit = {
    val convs: Set[RConvId] = events .collect { case e: CallStateEvent => e.convId } (breakOut)
    Future.traverse(convs) { convId =>
      convsContent.processConvWithRemoteId(convId, retryAsync = false) { conv =>
        sync.syncCallState(conv.id, fromFreshNotification = true)
      }
    }
  }
}

object GcmService {
  import scala.concurrent.duration._

  val RegistrationRetryBackoff = new ExponentialBackoff(5.minutes, 30.days)

  case class GcmState(received: Boolean, justRegistered: Boolean) {
    def active = received || justRegistered
  }
}
