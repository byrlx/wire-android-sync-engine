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
package com.waz.utils.events

import android.app.{Activity, Fragment, Service}
import android.view.View
import com.waz.ZLog
import com.waz.ZLog._

/**
  * EventContext 表示一种带有消息机制的上下文,
  * 1. 当这个 context 建立时, 会调用所有 observer 的 subscribe()函数注册消息.
  * 2. 当 context 停止时, 会调用所有观察者的 unsubscribe()函数.
  * 3. 当 context 销毁时, 调用所有观察者的 destroy()函数, 并清空观察者.
  */
trait EventContext {
  private implicit val logTag: LogTag = logTagFor[EventContext]

  private object lock

  private[this] var started = false
  private[this] var destroyed = false
  private[this] var observers = Set.empty[Subscription]

  protected implicit def eventContext: EventContext = this

  override protected def finalize(): Unit = {
    lock.synchronized { if (! destroyed) onContextDestroy() }
    super.finalize()
  }

  def onContextStart(): Unit = {
    lock.synchronized {
      if (! started) {
        started = true
        observers foreach (_.subscribe()) // XXX during this, subscribe may call Observable#onWire with in turn may call register which will change observers
      }
    }
  }

  def onContextStop(): Unit = {
    lock.synchronized {
      if (started) {
        started = false
        observers foreach (_.unsubscribe())
      }
    }
  }

  def onContextDestroy(): Unit = {
    lock.synchronized {
      destroyed = true
      val observersToDestroy = observers
      observers = Set.empty
      observersToDestroy foreach (_.destroy())
    }
  }

  def register(observer: Subscription): Unit = {
    lock.synchronized {
      assert(!destroyed, "context already destroyed")

      if (! observers.contains(observer)) {
        observers += observer
        if (started) observer.subscribe()
      }
    }
  }

  def unregister(observer: Subscription): Unit = {
    ZLog.verbose(s"unregister, observers count: ${observers.size}")
    lock.synchronized { observers -= observer }
    ZLog.verbose(s"removed, new count: ${observers.size}")
  }

  def isContextStarted: Boolean = lock.synchronized(started && ! destroyed)
}

object EventContext {

  object Implicits {
    implicit val global: EventContext = EventContext.Global
  }

  //代表最顶层的 context ???
  object Global extends EventContext {
    override def register(observer: Subscription): Unit = () // do nothing, global context will never need the observers (can not be stopped)
    override def unregister(observer: Subscription): Unit = ()
    override def onContextStart(): Unit = ()
    override def onContextStop(): Unit = ()
    override def onContextDestroy(): Unit = ()
    override def isContextStarted: Boolean = true
  }
}

/**
  * Activity 的 context 实现, 实现了 EventContext 接口,
  * 在 activity 的生命周期函数中调用了 EventContext 的周期接口
  */
trait ActivityEventContext extends Activity with EventContext {

  override def onResume(): Unit = {
    onContextStart()
    super.onResume()
  }

  override def onPause(): Unit = {
    super.onPause()
    onContextStop()
  }

  override def onDestroy(): Unit = {
    super.onDestroy()
    onContextDestroy()
  }
}

/**
  * Fragment 的 context 实现, 实现了 EventContext 接口,
  * 在 Fragment 的生命周期函数中调用了 EventContext 的周期接口
  */
trait FragmentEventContext extends Fragment with EventContext {

  override def onResume(): Unit = {
    onContextStart()
    super.onResume()
  }

  override def onPause(): Unit = {
    super.onPause()
    onContextStop()
  }

  override def onDestroy(): Unit = {
    super.onDestroy()
    onContextDestroy()
  }
}

/**
  * View 的 context 实现, 实现了 EventContext 接口,
  * 在 View 的生命周期函数中调用了 EventContext 的周期接口
  */
trait ViewEventContext extends View with EventContext {

  private var attached = false

  //view 被加载到 window 上 并可见时 调用 contextStart
  override def onAttachedToWindow(): Unit = {
    super.onAttachedToWindow()

    attached = true
    if (getVisibility != View.GONE) onContextStart()
  }

  override def setVisibility(visibility: Int): Unit = {
    super.setVisibility(visibility)

    if (visibility != View.GONE && attached) onContextStart()
    else onContextStop()
  }

  override def onDetachedFromWindow(): Unit = {
    super.onDetachedFromWindow()

    attached = false
    onContextStop()
  }
}

/**
  * Service 的 context 实现, 实现了 EventContext 接口,
  * 在 Service 的生命周期函数中调用了 EventContext 的周期接口
  */
trait ServiceEventContext extends Service with EventContext {

  override def onCreate(): Unit = {
    super.onCreate()
    onContextStart()
  }

  override def onDestroy(): Unit = {
    onContextStop()
    onContextDestroy()
    super.onDestroy()
  }
}
