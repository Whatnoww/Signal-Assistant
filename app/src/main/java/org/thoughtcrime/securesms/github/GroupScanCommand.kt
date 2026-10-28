/*
 * Whatnoww added file
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.github

import org.signal.core.util.splitByByteLength
import org.thoughtcrime.securesms.database.GroupTable
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.database.model.GroupRecord
import org.thoughtcrime.securesms.database.model.Mention
import org.thoughtcrime.securesms.recipients.Recipient
import org.whispersystems.signalservice.api.messages.SignalServiceMessageLimits

internal object GroupScanCommand {
  const val TRIGGER = "!scan"

  /** Check the stored sender's role in this chat, independently of the configured bot admin ACI. */
  fun authorizedGroup(recipient: Recipient, sender: Recipient): GroupRecord? {
    if (!recipient.isPushV2Group) return null
    return SignalDatabase.groups.getGroup(recipient.id).orElse(null)
      ?.takeIf { it.isActive && it.isAdmin(sender) }
  }

  /** The XML response is the heading; membership and blocklist matches are never cached as replies. */
  fun reply(heading: CommandReply, group: GroupRecord, blocklist: Set<String>? = GroupTable.getCachedBlocklist()): CommandReply {
    val text = StringBuilder(heading.text)
    val mentions = mutableListOf<Mention>()
    fun startLine() {
      if (text.isNotEmpty()) text.append('\n')
    }

    if (blocklist == null) {
      startLine()
      text.append("The ACI-Blocklist cache is not available yet.")
    } else {
      val members = group.requireV2GroupProperties().getMemberRecipients(GroupTable.MemberSet.FULL_MEMBERS_INCLUDING_SELF)
      for (member in members) {
        val aci = member.aci.orElse(null)?.toString() ?: continue
        if (aci !in blocklist) continue
        startLine()
        text.append(aci).append(' ')
        // Signal expands this native mention placeholder to the recipient's @display name.
        mentions += Mention(member.id, text.length, 1)
        text.append('\uFFFC')
      }
      if (mentions.isEmpty()) {
        startLine()
        text.append("No group members were found on the cached ACI-Blocklist.")
      }
    }
    return heading.copy(text = text.toString(), mentions = mentions)
  }

  /** Keep every native mention inline; long-text attachments cannot retain all mention ranges. */
  fun pages(reply: CommandReply): List<CommandReply> {
    val pages = mutableListOf<CommandReply>()
    var offset = 0
    while (offset < reply.text.length) {
      val remaining = reply.text.substring(offset)
      val (inline, remainder) = remaining.splitByByteLength(SignalServiceMessageLimits.MAX_INLINE_BODY_SIZE_BYTES)
      val end = offset + if (remainder != null) inline.lastIndexOf('\n').takeIf { it > 0 } ?: inline.length else inline.length
      val styles = reply.styles.mapNotNull { range ->
        val start = maxOf(offset, range.start)
        val length = minOf(end, range.start + range.length) - start
        if (length > 0) range.copy(start = start - offset, length = length) else null
      }
      val mentions = reply.mentions.filter { it.start >= offset && it.start + it.length <= end }
        .map { Mention(it.recipientId, it.start - offset, it.length) }
      pages += reply.copy(text = reply.text.substring(offset, end), styles = styles, mentions = mentions)
      offset = if (reply.text.getOrNull(end) == '\n') end + 1 else end
    }
    return pages
  }
}
