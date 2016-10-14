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
package com.waz.model

import java.util.{Date, UUID}

import android.util.Base64
import com.waz.ZLog._
import com.waz.api.CauseForCallStateEvent
import com.waz.model.ConversationEvent.ConversationEventDecoder
import com.waz.model.Event.{CallProperties, EventDecoder}
import com.waz.model.UserData.ConnectionStatus
import com.waz.model.otr.{Client, ClientId}
import com.waz.sync.client.ConversationsClient.ConversationResponse
import com.waz.sync.client.OtrClient
import com.waz.utils.JsonDecoder._
import com.waz.utils.{JsonDecoder, JsonEncoder, _}
import com.waz.znet.ContentEncoder
import com.waz.znet.ContentEncoder.JsonContentEncoder
import org.json.JSONObject
import org.threeten.bp.Instant

import scala.util.Try

sealed trait Event {
  import Event._
  val id: Uid

  //FIXME do we still need this separation?
  var localTime: Date = UnknownDateTime
  var notificationsFetchTime: Date = UnknownDateTime

  def withCurrentLocalTime(): this.type = {
    localTime = new Date()
    this
  }

  def withLocalTime(time: Date): this.type = {
    localTime = time
    this
  }

  def hasLocalTime = localTime != UnknownDateTime

  def localOrFetchTime = if (localTime == UnknownDateTime) notificationsFetchTime else localTime

  def maybeLocalTime: Option[Instant] = if (localTime == UnknownDateTime) None else Some(localTime.instant)
}

sealed trait UserEvent extends Event
sealed trait OtrClientEvent extends UserEvent

sealed trait RConvEvent extends Event {
  val convId: RConvId
}
object RConvEvent extends (Event => RConvId) {
  def apply(ev: Event): RConvId = ev match {
    case ev: RConvEvent => ev.convId
    case _              => RConvId.Empty
  }
}

case class UserUpdateEvent(id: Uid, user: UserInfo) extends UserEvent
case class UserPropertiesSetEvent(id: Uid, key: String, value: String) extends UserEvent // value is always json string, so maybe we should parse it already (or maybe not)
case class UserConnectionEvent(id: Uid, convId: RConvId, from: UserId, to: UserId, message: Option[String], status: ConnectionStatus, lastUpdated: Date, fromUserName: Option[String] = None) extends UserEvent with RConvEvent
case class UserDeleteEvent(id: Uid, user: UserId) extends UserEvent
case class OtrClientAddEvent(id: Uid, client: Client) extends OtrClientEvent
case class OtrClientRemoveEvent(id: Uid, client: ClientId) extends OtrClientEvent

case class ContactJoinEvent(id: Uid, user: UserId, name: String) extends Event

case class GcmTokenRemoveEvent(id: Uid, token: String, senderId: String, client: Option[String]) extends Event

sealed trait ConversationEvent extends RConvEvent {
  val time: Date
  val from: UserId
}

// events that may change conversation list ordering (update lastEvent property)
sealed trait ConversationOrderEvent extends ConversationEvent

// events that affect conversation state
sealed trait ConversationStateEvent extends ConversationEvent

// events that add or modify some message
sealed trait MessageEvent extends ConversationOrderEvent

// events that can lead to automatic unarchiving
sealed trait UnarchivingEvent extends ConversationOrderEvent

case class IgnoredEvent(id: Uid, json: JSONObject) extends Event

case class UnknownEvent(id: Uid, json: JSONObject) extends Event
case class UnknownConvEvent(id: Uid, json: JSONObject) extends ConversationEvent {
  override val convId: RConvId = RConvId()
  override val from: UserId = UserId()
  override val time: Date = new Date
}

case class CreateConversationEvent(id: Uid, convId: RConvId, time: Date, from: UserId, data: ConversationResponse) extends ConversationStateEvent with ConversationOrderEvent

case class RenameConversationEvent(id: Uid, convId: RConvId, time: Date, from: UserId, name: String) extends MessageEvent with ConversationStateEvent with UnarchivingEvent

case class GenericMessageEvent(id: Uid, convId: RConvId, time: Date, from: UserId, content: GenericMessage) extends MessageEvent with UnarchivingEvent


sealed trait OtrError
case object Duplicate extends OtrError
case class DecryptionError(msg: String, from: UserId, sender: ClientId) extends OtrError
case class IdentityChangedError(from: UserId, sender: ClientId) extends OtrError

case class OtrErrorEvent(id: Uid, convId: RConvId, time: Date, from: UserId, error: OtrError) extends MessageEvent with UnarchivingEvent

case class GenericAssetEvent(id: Uid, convId: RConvId, time: Date, from: UserId, content: GenericMessage, dataId: RAssetDataId, data: Option[Array[Byte]]) extends MessageEvent with UnarchivingEvent

case class TypingEvent(id: Uid, convId: RConvId, time: Date, from: UserId, isTyping: Boolean) extends ConversationEvent

case class MemberJoinEvent(id: Uid, convId: RConvId, time: Date, from: UserId, userIds: Seq[UserId], firstEvent: Boolean = false) extends MessageEvent with ConversationStateEvent with UnarchivingEvent
case class MemberLeaveEvent(id: Uid, convId: RConvId, time: Date, from: UserId, userIds: Seq[UserId]) extends MessageEvent with ConversationStateEvent with UnarchivingEvent
case class MemberUpdateEvent(id: Uid, convId: RConvId, time: Date, from: UserId, state: ConversationState) extends ConversationStateEvent

case class ConnectRequestEvent(id: Uid, convId: RConvId, time: Date, from: UserId, message: String, recipient: UserId, name: String, email: Option[String]) extends MessageEvent with ConversationStateEvent with ConversationOrderEvent

case class VoiceChannelEvent(id: Uid, convId: RConvId, time: Date, from: UserId, oldCount: Int, newCount: Int) extends ConversationEvent with ConversationOrderEvent
case class VoiceChannelActivateEvent(id: Uid, convId: RConvId, time: Date, from: UserId) extends ConversationEvent with UnarchivingEvent
case class VoiceChannelDeactivateEvent(id: Uid, convId: RConvId, time: Date, from: UserId, reason: Option[String]) extends MessageEvent with ConversationStateEvent with UnarchivingEvent

sealed trait CallEvent extends Event {
  val convId: RConvId
}

case class UnknownCallEvent(id: Uid, kind: String, json: JSONObject) extends CallEvent {
  override val convId: RConvId = RConvId()
}

case class CallSequenceNumber(value: Int) extends AnyVal
case class CallStateEvent(id: Uid, convId: RConvId, participants: Option[Set[CallParticipant]], device: Option[CallDeviceState] = None, cause: CauseForCallStateEvent, sessionId: Option[CallSessionId] = None, sequenceNumber: Option[CallSequenceNumber] = None) extends CallEvent

case class CallParticipant(user: UserId, joined: Boolean, props: CallProperties)

case class CallDeviceState(joined: Boolean, props: CallProperties)

object CallDeviceState extends ((Boolean, CallProperties) => CallDeviceState) {
  implicit lazy val Encoder: JsonEncoder[CallDeviceState] = new JsonEncoder[CallDeviceState] {
    override def apply(v: CallDeviceState): JSONObject = JsonEncoder { o =>
      o.put("state", if (v.joined) EventDecoder.Joined else EventDecoder.Idle)
      v.props foreach { p => o.put(p.asJson, true) }
    }
  }

  implicit val Content: ContentEncoder[CallDeviceState] = ContentEncoder.json
}

sealed trait OtrEvent extends ConversationEvent {
  val sender: ClientId
  val recipient: ClientId
  val ciphertext: Array[Byte]
}
case class OtrMessageEvent(id: Uid, convId: RConvId, time: Date, from: UserId, sender: ClientId, recipient: ClientId, ciphertext: Array[Byte], externalData: Option[Array[Byte]] = None) extends OtrEvent with ConversationEvent

case class OtrAssetEvent(id: Uid, convId: RConvId, time: Date, from: UserId, sender: ClientId, recipient: ClientId, dataId: RAssetDataId, ciphertext: Array[Byte], imageData: Option[Array[Byte]]) extends OtrEvent with ConversationOrderEvent

case class ConversationState(archived: Option[Boolean] = None, archiveTime: Option[Instant] = None, muted: Option[Boolean] = None, muteTime: Option[Instant] = None)

object ConversationState {

  private def encode(state: ConversationState, o: JSONObject) = {
    state.archived foreach { o.put("otr_archived", _) }
    state.archiveTime foreach { time =>
      o.put("otr_archived_ref", JsonEncoder.encodeISOInstant(time))
    }
    state.muted.foreach(o.put("otr_muted", _))
    state.muteTime foreach { time =>
      o.put("otr_muted_ref", JsonEncoder.encodeISOInstant(time))
    }
  }

  implicit lazy val Encoder: JsonEncoder[ConversationState] = new JsonEncoder[ConversationState] {
    override def apply(state: ConversationState): JSONObject = JsonEncoder { o => encode(state, o) }
  }

  implicit lazy val Decoder: JsonDecoder[ConversationState] = new JsonDecoder[ConversationState] {
    import com.waz.utils.JsonDecoder._

    override def apply(implicit js: JSONObject): ConversationState = {
      val archiveTime = decodeOptISOInstant('otr_archived_ref)
      val archived = archiveTime.map( _ => decodeBool('otr_archived))

      val (muted, muteTime) = (decodeOptISOInstant('otr_muted_ref), decodeOptISOInstant('muted_time)) match {
        case (Some(t), Some(t1)) if t1.isAfter(t) => (decodeOptBoolean('muted), Some(t1))
        case (t @ Some(_), _)                     => (decodeOptBoolean('otr_muted), t)
        case (_, t @ Some(_))                     => (decodeOptBoolean('muted), t)
        case _                                    => (None, None)
      }

      ConversationState(archived, archiveTime, muted, muteTime)
    }
  }

  implicit val StateContentEncoder = JsonContentEncoder map { (state: ConversationState) => JsonEncoder { encode(state, _) } }
}

object Event {
  type CallProperties = Set[CallProperty]

  val UnknownDateTime = MessageData.UnknownInstant.javaDate

  implicit object EventDecoder extends JsonDecoder[Event] {
    val Joined = "joined"
    val Idle = "idle"

    private implicit val tag: LogTag = logTagFor(EventDecoder)
    import com.waz.utils.JsonDecoder._

    import scala.collection.JavaConverters._

    def connectionEvent(id: Uid)(implicit js: JSONObject, name: Option[String]) = UserConnectionEvent(id, 'conversation, 'from, 'to, 'message, ConnectionStatus('status), 'last_update, fromUserName = name)

    def contactJoinEvent(id: Uid)(implicit js: JSONObject) = ContactJoinEvent(id, 'id, 'name)

    def gcmTokenRemoveEvent(id: Uid)(implicit js: JSONObject) = GcmTokenRemoveEvent(id, token = 'token, senderId = 'app, client = 'client)

    def joined(d: JSONObject): Boolean = d.getString("state") == Joined

    def cause(d: JSONObject): CauseForCallStateEvent = if (d.has("cause")) try CauseForCallStateEvent.fromJson(d.getString("cause")) catch { case e: IllegalArgumentException =>
      warn("unknown cause for call state event: " + e)
      CauseForCallStateEvent.REQUESTED
    } else CauseForCallStateEvent.REQUESTED

    def callParticipants(js: JSONObject): Set[CallParticipant] = {
      val parts = js.getJSONObject("participants")
      parts.keys().asInstanceOf[java.util.Iterator[String]].asScala.map { key => // cast is needed since some android versions don't return generic iterator
        val d = parts.getJSONObject(key)
        CallParticipant(UserId(key), joined(d), callProperties(d))
      }.toSet
    }

    def callDeviceState(js: JSONObject) = CallDeviceState(joined(js), callProperties(js))

    def callProperties(js: JSONObject): CallProperties = {
      CallProperty.values .filter (p => js.optBoolean(p.asJson)) .toSet
    }

    def callStateEvent(id: Uid)(implicit js: JSONObject) = {
      CallStateEvent(id, 'conversation,
        participants = if (js.has("participants") && !js.isNull("participants")) Some(callParticipants(js)) else None,
        device = if (js.has("self") && !js.isNull("self")) Some(callDeviceState(js.getJSONObject("self"))) else None,
        cause = cause(js),
        sessionId = JsonDecoder.decodeOptId('session)(js, CallSessionId.Id),
        sequenceNumber = JsonDecoder.decodeOptCallSequenceNumber('sequence)(js))
    }

    val CallEventType = """call\.(.+)""".r

    override def apply(implicit js: JSONObject): Event = LoggedTry {

      lazy val data = if (js.has("data") && !js.isNull("data")) Try(js.getJSONObject("data")).toOption else None
      lazy val id = data.flatMap(decodeOptUid('nonce)(_)).getOrElse(Uid())

      val evType = decodeString('type)
      if (evType.startsWith("conversation")) ConversationEventDecoder(js)
      else evType match {
        case "user.update" => UserUpdateEvent(id, JsonDecoder[UserInfo]('user))
        case "user.connection" => connectionEvent(id)(js.getJSONObject("connection"), JsonDecoder.opt('user, _.getJSONObject("user")) flatMap (JsonDecoder.decodeOptString('name)(_)))
        case "user.contact-join" => contactJoinEvent(id)(js.getJSONObject("user"))
        case "user.push-remove" => gcmTokenRemoveEvent(id)(js.getJSONObject("token"))
        case "user.properties-set" => UserPropertiesSetEvent(id, 'key, 'value)
        case "user.delete" => UserDeleteEvent(id, user = 'id)
        case "user.client-add" => OtrClientAddEvent(id, OtrClient.ClientsResponse.client(js.getJSONObject("client")))
        case "user.client-remove" => OtrClientRemoveEvent(id, decodeId[ClientId]('id)(js.getJSONObject("client"), implicitly))
        case "call.state" => callStateEvent(id)
        case "call.info" => IgnoredEvent(Uid(), js)
        case CallEventType(kind) => UnknownCallEvent(id, kind, js)
        case _ =>
          error(s"unhandled event: $js")
          UnknownEvent(id, js)
      }
    } .getOrElse(UnknownEvent(Uid(), js))
  }
}

object MissedCallEvent {
  val MissedCallReason = Some("missed")

  def unapply(e: VoiceChannelDeactivateEvent): Option[(Uid, RConvId, Date, UserId)] = if (e.reason == MissedCallReason) Some((e.id, e.convId, e.time, e.from)) else None
}

object UserConnectionEvent {
  implicit lazy val Decoder: JsonDecoder[UserConnectionEvent] = new JsonDecoder[UserConnectionEvent] {
    override def apply(implicit js: JSONObject): UserConnectionEvent = EventDecoder.connectionEvent(Uid())(js, name = None)
  }
}

object ConversationEvent {

  val EventId = """(\d+).([0-9a-fA-F]+)""".r

  def unapply(e: ConversationEvent): Option[(Uid, RConvId, Date, UserId)] =Some((e.id, e.convId, e.time, e.from))

  implicit lazy val ConversationEventDecoder: JsonDecoder[ConversationEvent] = new JsonDecoder[ConversationEvent] {
    private implicit val tag: LogTag = "ConversationEventDecoder"

    def decodeBytes(str: String) = Base64.decode(str, Base64.NO_WRAP)

    def otrMessageEvent(id: Uid, convId: RConvId, time: Date, from: UserId)(implicit data: JSONObject) =
      OtrMessageEvent(id, convId, time, from, ClientId('sender), ClientId('recipient), decodeBytes('text), decodeOptString('data) map decodeBytes)

    def otrAssetEvent(id: Uid, convId: RConvId, time: Date, from: UserId)(implicit data: JSONObject) =
      OtrAssetEvent(id, convId, time, from, ClientId('sender), ClientId('recipient), RAssetDataId('id), decodeBytes('key), decodeOptString('data).map(decodeBytes))

    override def apply(implicit js: JSONObject): ConversationEvent = LoggedTry {

      lazy val data = if (js.has("data") && !js.isNull("data")) Try(js.getJSONObject("data")).toOption else None
      lazy val id = decodeOptString('id) match {
        case Some(EventId(seq, hex)) =>
          // id is generated from conversation id and eventId, this should give us pretty unique and stable id
          val uid = UUID.fromString('conversation)
          Uid(uid.getMostSignificantBits ^ seq.toLong, java.lang.Long.parseLong(hex.substring(1), 16))
        case _ =>
          Uid()
      }

      decodeString('type) match {
        case "conversation.create" => CreateConversationEvent('conversation, 'conversation, 'time, 'from, JsonDecoder[ConversationResponse]('data))
        case "conversation.rename" => RenameConversationEvent(id, 'conversation, 'time, 'from, decodeString('name)(data.get))
        case "conversation.member-join" => MemberJoinEvent(id, 'conversation, 'time, 'from, decodeUserIdSeq('user_ids)(data.get), decodeString('id).startsWith("1."))
        case "conversation.member-leave" => MemberLeaveEvent(id, 'conversation, 'time, 'from, decodeUserIdSeq('user_ids)(data.get))
        case "conversation.member-update" => MemberUpdateEvent(id, 'conversation, 'time, 'from, ConversationState.Decoder(data.get))
        case "conversation.connect-request" => ConnectRequestEvent(id, 'conversation, 'time, 'from, decodeString('message)(data.get), decodeUserId('recipient)(data.get), decodeString('name)(data.get), decodeOptString('email)(data.get))
        case "conversation.voice-channel" => VoiceChannelEvent(id, 'conversation, 'time, 'from, decodeInt('old_member_count)(data.get), decodeInt('new_member_count)(data.get))
        case "conversation.voice-channel-activate" => VoiceChannelActivateEvent(id, 'conversation, 'time, 'from)
        case "conversation.voice-channel-deactivate" => VoiceChannelDeactivateEvent(id, 'conversation, 'time, 'from, data.flatMap(d => decodeOptString('reason)(d)))
        case "conversation.typing" => TypingEvent(id, 'conversation, 'time, 'from, isTyping = data.fold(false)(data => decodeString('status)(data) == "started"))
        case "conversation.otr-message-add" => otrMessageEvent(id, 'conversation, 'time, 'from)(data.get)
        case "conversation.otr-asset-add" => otrAssetEvent(id, 'conversation, 'time, 'from)(data.get)
        case _ =>
          error(s"unhandled event: $js")
          UnknownConvEvent(id, js)
      }
    } .getOrElse {
      error(s"unhandled event: $js")
      UnknownConvEvent(Uid(), js)
    }
  }
}
