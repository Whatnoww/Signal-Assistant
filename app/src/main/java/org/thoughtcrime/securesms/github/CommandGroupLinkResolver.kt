/*
 * Whatnoww added file
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.github

import android.content.Context
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.groups.GroupId
import org.thoughtcrime.securesms.groups.GroupManager
import org.thoughtcrime.securesms.groups.v2.GroupInviteLinkUrl
import org.thoughtcrime.securesms.groups.v2.processing.GroupsV2StateProcessor
import org.thoughtcrime.securesms.recipients.Recipient
import org.whispersystems.signalservice.api.groupsv2.GroupLinkNotActiveException

/** Lives for one reply: a repeated link/group is only checked once per request. */
internal class CommandGroupLinkResolver(private val lookup: CommandGroupLinkLookup) {
  constructor(context: Context) : this(SignalCommandGroupLinkLookup(context))

  private val groups = mutableMapOf<String, CommandLinkState>()
  private val links = mutableMapOf<String, CommandLinkState>()

  fun currentGroupLink(groupId: String): CommandLinkState = groups.getOrPut(groupId) { lookup.currentGroupLink(groupId) }

  fun resolve(group: CommandGroup): CommandLinkState {
    // A manual entry must never derive an update identity from its link.
    if (group.groupId == "0") return validateLink(group.link)
    val current = currentGroupLink(group.groupId)
    return when {
      current.currentLink != null -> current.copy(outdated = current.currentLink != group.link)
      current.outdated == true -> current
      else -> validateLink(group.link)
    }
  }

  private fun validateLink(link: String): CommandLinkState = links.getOrPut(link) { lookup.validateLink(link) }
}

internal interface CommandGroupLinkLookup {
  fun currentGroupLink(encodedId: String): CommandLinkState
  fun validateLink(link: String): CommandLinkState
}

private class SignalCommandGroupLinkLookup(private val context: Context) : CommandGroupLinkLookup {
  override fun currentGroupLink(encodedId: String): CommandLinkState {
    return try {
      val id = GroupId.parse(encodedId).requireV2()
      val record = SignalDatabase.groups.getGroup(id).orElse(null) ?: return CommandLinkState()
      if (!record.isActive || !record.hasV2GroupProperties || Recipient.externalGroupExact(id).isBlocked) return CommandLinkState()
      GroupManager.updateGroupFromServer(context, record.requireV2GroupProperties().groupMasterKey, GroupsV2StateProcessor.LATEST, System.currentTimeMillis())
      val refreshed = SignalDatabase.groups.requireGroup(id)
      if (!refreshed.isGroupLinkEnabled) return CommandLinkState(outdated = true)
      val properties = refreshed.requireV2GroupProperties()
      CommandLinkState(currentLink = GroupInviteLinkUrl.forGroup(properties.groupMasterKey, properties.decryptedGroup).url)
    } catch (e: Exception) {
      // Unknown ID, missing membership or a transient failure cannot prove that an invite is outdated.
      Log.w(TAG, "Unable to refresh command group link (${e.javaClass.simpleName}).")
      CommandLinkState()
    }
  }

  override fun validateLink(link: String): CommandLinkState {
    return try {
      val invite = GroupInviteLinkUrl.fromUri(link) ?: return CommandLinkState(outdated = true)
      GroupManager.getGroupJoinInfoFromServer(context, invite.groupMasterKey, invite.password)
      CommandLinkState(outdated = false)
    } catch (e: GroupLinkNotActiveException) {
      // A ban is specific to this account and does not prove the link is outdated for others.
      CommandLinkState(outdated = if (e.reason == GroupLinkNotActiveException.Reason.BANNED) null else true)
    } catch (e: GroupInviteLinkUrl.InvalidGroupLinkException) {
      CommandLinkState(outdated = true)
    } catch (e: Exception) {
      Log.w(TAG, "Unable to validate command invite link (${e.javaClass.simpleName}).")
      CommandLinkState()
    }
  }

  companion object {
    private val TAG = Log.tag(SignalCommandGroupLinkLookup::class)
  }
}
