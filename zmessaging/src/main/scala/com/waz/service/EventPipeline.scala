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
package com.waz.service

import com.waz.model.Event
import com.waz.threading.Threading.Implicits.Background

import scala.concurrent.Future
import scala.concurrent.Future._

class EventPipeline(transformersByName: => Vector[Vector[Event] => Future[Vector[Event]]], schedulerByName: => Traversable[Event] => Future[Unit]) extends (Traversable[Event] => Future[Unit]) {

  private lazy val (transformers, scheduler) = (transformersByName, schedulerByName)

  def apply(input: Traversable[Event]): Future[Unit] =
    for {
      events <- transformers.foldLeft(successful(input.toVector))((l, r) => l.flatMap(r))
      _      <- scheduler(events)
    } yield ()
}
