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
package com.waz.api.impl.search

import com.waz.ZLog._
import com.waz.api
import com.waz.api.impl.CoreList
import com.waz.model.SearchQuery.TopPeople
import com.waz.model.{SearchQuery, UserData, UserId}
import com.waz.ui.{SignalLoading, UiModule}
import com.waz.utils.SeqMap

class UserSearchResult(query: SearchQuery = TopPeople, limit: Int, filter: Set[String])(implicit val ui: UiModule) extends api.UserSearchResult with CoreList[api.User] with SignalLoading {
  import UserSearchResult._

  private var users = Option.empty[SeqMap[UserId, UserData]]

  addLoader(_.userSearch.searchUserData(query, Some(limit + filter.size)), SeqMap.empty[UserId, UserData]) { us =>
    verbose(s"users[$query, $limit, $filter] loaded: ${us.size} user(s)")
    val changed = users.forall(_.keys != us.keys)
    users = Some(us)
    if (changed) notifyChanged()
  }

  private[this] def currentUsers = users.getOrElse(SeqMap.empty)

  override def get(position: Int): api.User = ui.users.getUser(currentUsers at position)
  override def size: Int = currentUsers.size
  override def getAll: Array[api.User] = currentUsers.valuesIterator.map(ui.users.getUser).toArray
}

object UserSearchResult {
  private implicit val tag: LogTag = logTagFor[UserSearchResult]
}
