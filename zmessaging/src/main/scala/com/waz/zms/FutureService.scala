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
package com.waz.zms

import android.app.Service
import android.content.Intent
import android.os.{IBinder, PowerManager}
import android.support.v4.content.WakefulBroadcastReceiver
import com.waz.ZLog._
import com.waz.service.ZMessaging
import com.waz.threading.Threading
import com.waz.utils.WakeLock

import scala.concurrent.Future

/**
  * 一般情况下,开机后这个 service 会被启动, 然后在线程中执行 onIntent()函数
 */
abstract class FutureService extends Service {
  private implicit val logTag: LogTag = logTagFor[FutureService]

  protected val wakeLockLevel = PowerManager.PARTIAL_WAKE_LOCK //保持 cpu 运行
  protected lazy val wakeLock = new WakeLock(getApplicationContext, wakeLockLevel)

  override def onBind(intent: Intent): IBinder = null

  override def onStartCommand(intent: Intent, flags: Int, startId: Int): Int = wakeLock {
    debug(s"onStartCommand: $startId, intent: $intent")
    Option(intent) foreach WakefulBroadcastReceiver.completeWakefulIntent

    val future = if (intent == null) Future.successful({}) else onIntent(intent, startId).recover { case ex => error("onIntent failed", ex) } (Threading.Background)
    future.onComplete { _ => onComplete(startId) }(Threading.Ui)

    Service.START_REDELIVER_INTENT
  }

  protected def onIntent(intent: Intent, id: Int): Future[Any]

  protected def onComplete(startId: Int): Unit = {
    debug(s"onCompleted: $startId")
    stopSelf(startId)
  }
}

trait ZMessagingService extends Service {
  final abstract override def onCreate(): Unit = {
    super.onCreate()
    ZMessaging.onCreate(getApplicationContext)
  }
}
