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
package com.waz.utils

import android.app.ActivityManager
import android.content.res.Configuration
import android.content.{ComponentCallbacks2, Context}
import android.support.v4.util.LruCache
import com.waz.ZLog._
import com.waz.utils.TrimmingLruCache.CacheSize

class TrimmingLruCache[K, V](val context: Context, size: CacheSize) extends LruCache[K, V](size.bytes(context)) with AutoTrimming

object TrimmingLruCache {
  private var _memoryClass = 0

  def memorySize(c: Context) = {
    if (_memoryClass == 0)
      _memoryClass = c.getSystemService(Context.ACTIVITY_SERVICE).asInstanceOf[ActivityManager].getMemoryClass
    _memoryClass * 1024 * 1024
  }

  sealed trait CacheSize { self =>
    def bytes(c: Context): Int

    def min(other: CacheSize) = new CacheSize {
      override def bytes(c: Context): Int = self.bytes(c) min other.bytes(c)
    }
    def max(other: CacheSize) = new CacheSize {
      override def bytes(c: Context): Int = self.bytes(c) max other.bytes(c)
    }
  }

  case class Fixed(bytes: Int) extends CacheSize {
    override def bytes(c: Context): Int = bytes
  }
  case class Relative(factor: Float) extends CacheSize {
    override def bytes(c: Context): Int = (memorySize(c) * factor).toInt
  }

  object CacheSize {
    def apply(f: Int => Int): CacheSize = new CacheSize {
      override def bytes(c: Context): Int = f(memorySize(c))
    }
  }
}

trait AutoTrimming extends ComponentCallbacks2 { self: LruCache[_, _] =>
  import com.waz.utils.AutoTrimming._
  private implicit val tag: LogTag = logTagFor[AutoTrimming]

  def context: Context

  override def onTrimMemory(level: Int): Unit =
    TrimFactors.collectFirst { case (l, factor) if l >= level =>
      val trimmedSize = (factor * maxSize()).toInt
      verbose(s"onTrimMemory($level) - trimToSize: $trimmedSize")
      trimToSize(trimmedSize)
      System.gc()
    }

  override def onLowMemory(): Unit = ()
  override def onConfigurationChanged(newConfig: Configuration): Unit = ()

  context.registerComponentCallbacks(this)

  def destroy() = {
    context.unregisterComponentCallbacks(this)
  }
}

object AutoTrimming {
  val TrimFactors = Seq(
    ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> .75f,
    ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> .5f,
    ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> .25f,
    ComponentCallbacks2.TRIM_MEMORY_MODERATE -> 0f
  )
}

class UnlimitedLruCache[K, V] extends LruCache[K, V](Integer.MAX_VALUE)
