/* SPDX-License-Identifier: AGPL-3.0-only */
package org.thoughtcrime.securesms.gateway

import android.app.Application
import android.content.Context
import org.signal.core.models.ServiceId.ACI
import org.thoughtcrime.securesms.BuildConfig
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.database.model.GroupRecord
import org.thoughtcrime.securesms.database.model.Mention
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.github.AdminCommands
import org.thoughtcrime.securesms.github.CommandReply
import org.thoughtcrime.securesms.github.GitHubAutoReply
import org.thoughtcrime.securesms.github.GroupScanCommand
import org.thoughtcrime.securesms.groups.GroupId
import org.thoughtcrime.securesms.jobs.GatewayDmScanRefreshJob
import org.thoughtcrime.securesms.jobs.GatewayWorkJob
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.recipients.Recipient
import java.util.concurrent.TimeUnit

internal object GatewayController {
  val helpAliases = setOf("!help", "!list", "!command", "!commands")
  fun candidate(body: String): Boolean = GatewayCommand.candidate(body) || body in helpAliases || body == "!scan"

  /** Called on the existing command worker, before the ordinary contact-only reply gate. */
  fun handle(context: Context, messageId: Long): Boolean {
    val message = SignalDatabase.messages.getMessageRecordOrNull(messageId) ?: return true
    val body = message.body
    if (!candidate(body)) return false
    val sender = Recipient.resolved(message.fromRecipient.id)
    val recipient = SignalDatabase.threads.getRecipientForThreadId(message.threadId) ?: return true
    if (sender.isBlocked || sender.isSelf || sender.isHidden || recipient.isBlocked || recipient.isHidden) return true
    val config = GatewayRuntime.configuration(context) ?: GatewayRuntime.configuration(context, true) ?: return GatewayCommand.candidate(body)
    if (config.paused) return true
    if (!config.configured) return GatewayCommand.candidate(body)
    if (!SignalStore.account.isPrimaryDevice) return true
    val aci = sender.aci.orElse(null)?.toString() ?: return true
    val store = GatewayDatabase.get(context.applicationContext as Application)
    val platform = SignalGatewayPlatform(context, store)
    val document = GitHubAutoReply.configurationDocument(context) ?: return true
    fun available(trigger: String) = document.render(trigger) { org.thoughtcrime.securesms.github.CommandLinkState() } != null
    val family = when {
      body.startsWith("!gateway") -> "!gateway"
      body.startsWith("!approve") -> "!approve"
      body.startsWith("!deny") -> "!deny"
      body == "!groupid" -> "!groupid"
      else -> null
    }
    if (family != null && !available(family)) return true
    if (body == "!scan") {
      val document = GitHubAutoReply.configurationDocument(context) ?: return true
      if (document.render("!scan") { org.thoughtcrime.securesms.github.CommandLinkState() } == null) return true
      val duration = TimeUnit.MINUTES.toMillis(if (recipient.isGroup) config.defaults.groupScanMinutes else config.defaults.dmScanMinutes)
      if (recipient.isGroup && GroupScanCommand.authorizedGroup(recipient, sender) == null) return true
      val remaining = store.reserveCooldown("scan:$aci:${if (recipient.isGroup) recipient.id else "dm"}", messageId, platform.now(), duration)
      if (remaining > 0) {
        platform.sendOnce("cooldown:$messageId", 0, "scan", recipient,
          platform.template("scan_cooldown", mapOf("remaining_time" to GatewayText("${(remaining + 59999) / 60000} minutes"), "remaining_minutes" to GatewayText(((remaining + 59999) / 60000).toString()))))
        return true
      }
      if (recipient.isGroup) return false
      try {
        val reply = dmScanReply(context, sender, platform)
        val ids = platform.sendOnce("dm-scan:$messageId", 0, "scan", sender, reply, invocation = messageId)
        val pages = GroupScanCommand.pages(reply)
        AppDependencies.jobManager.add(GatewayDmScanRefreshJob(messageId, ids, pages.map { it.contentHash() }))
      } catch (e: Exception) { store.releaseCooldown(messageId); throw e }
      return true
    }
    if (body in helpAliases) {
      val ordinary = document.render(body) { org.thoughtcrime.securesms.github.CommandLinkState() } ?: return true
      val scopes = mutableListOf("public")
      if (AdminCommands.isAuthorized(BuildConfig.ADMIN_ACI, aci)) scopes += "owner"
      if (recipient.isGroup) {
        val id = recipient.requireGroupId().toString()
        val state = platform.group(id, false)
        if (!state.active || !state.canPost) return true
        if (aci in state.admins) {
          if (available("!groupid")) scopes += "group-admin"
          if (available("!scan")) scopes += "group-scan"
          if (available("!gateway") && config.groups[id]?.throttle != true) {
            if (config.groups[id] != null) scopes += "source-admin"
            if (config.groups.values.any { it.destination == id }) scopes += "gateway-admin"
          }
        }
        if (store.requests().any { it.room == id && it.active && config.groups[it.source]?.let { source -> !config.isPaused(source) } == true && aci in platform.group(it.source, false).admins }) {
          scopes += "review-admin"
          if (available("!approve")) scopes += "review-approve"
          if (available("!deny")) scopes += "review-deny"
        }
      } else if (available("!scan") && knownAdminGroups(sender).isNotEmpty()) scopes += "dm-group-admin"
      val combined = concatenate(listOf(ordinary) + scopes.mapNotNull(config.help::get))
      if (combined.text.isNotBlank()) platform.sendOnce("help:$messageId", 0, "help", recipient, combined)
      return true
    }
    val groupId = recipient.groupId.orElse(null)?.takeIf { it.isV2 }?.toString()
    if (body != "!groupid" && !body.startsWith("!admin gateway ") && config.groups[groupId]?.throttle == true) return true
    val command = try { GatewayCommand.parse(body, SignalDatabase.mentions.getMentionsForMessage(messageId)) } catch (e: IllegalArgumentException) {
      // Only an administrator/owner receives details of a malformed control request.
      if (groupId != null && aci in platform.group(groupId, false).admins || AdminCommands.isAuthorized(BuildConfig.ADMIN_ACI, aci)) {
        dm(platform, sender, messageId, "error", "Invalid Gateway command.")
      }
      return true
    }
    if (command is GatewayCommand.Throttle) {
      if (!AdminCommands.isAuthorized(BuildConfig.ADMIN_ACI, aci)) return true
      try {
        GroupId.parseOrThrow(command.group).requireV2()
        val target = platform.group(command.group, false)
        GitHubAutoReply.updateConfiguration(context) { document ->
          GatewayConfiguration.ensureRoom(document, command.group, target.name)
          GatewayConfiguration.set(document, command.group, "throttle", if (command.enabled) "1" else "0")
          true
        }
        dm(platform, sender, messageId, "dm_status", "Gateway ${if (command.enabled) "paused" else "resumed"} for the configured group.")
        GatewayWorkJob.recover()
      } catch (e: Exception) {
        dm(platform, sender, messageId, "error", "The Gateway owner operation could not be completed. Check the group ID and connectivity.")
      }
      return true
    }
    if (groupId == null) return true
    if (command != GatewayCommand.GroupId && config.groups[groupId]?.throttle == true) return true
    if (command is GatewayCommand.Decide) {
      val applicant = Recipient.resolved(command.target.recipientId).aci.orElse(null)?.toString() ?: return true
      val selected = store.requests().filter { it.room == groupId && it.aci == applicant && it.active }
        .let { rows -> if (command.number == null) rows.singleOrNull() else rows.firstOrNull { it.number == command.number } }
      if (selected != null && config.groups[selected.source]?.let(config::isPaused) == true) return true
      try {
        GatewayEngine(store, platform).decide(groupId, applicant, command.number, aci, command.approve, message.dateReceived)
      } catch (e: GatewayPausedException) {
        return true
      } catch (e: IllegalArgumentException) {
        platform.sendOnce("decision-error:$messageId", 0, "error", recipient, platform.template("error", mapOf("safe_reason" to GatewayText(e.message.orEmpty()))))
      }
      return true
    }
    if (aci !in platform.group(groupId, false).admins || aci !in platform.group(groupId, true).admins) return true
    try {
      when (command) {
        GatewayCommand.GroupId -> {
          val name = platform.group(groupId, false).name
          platform.sendOnce("group-id:$messageId", 0, "groupid", sender, platform.template("groupid", mapOf("group_name" to GatewayText(name), "group_id" to GatewayText(groupId))))
        }
        GatewayCommand.Status -> {
          val policy = config.groups[groupId]
          val isRoom = config.groups.values.any { it.destination == groupId }
          val room = config.groups[if (isRoom) groupId else policy?.destination]
          val status = policy?.let { "${it.name}${if (!it.publiclyListed) " [silent]" else ""}: Gateway ${if (it.enabled) "enabled" else "disabled"}; destination ${it.destination}; administrative actions ${if (it.admin) "enabled" else "disabled"}." }
            ?: "This group is not configured. Add a group entry to the XML; use <silent>1</silent> to keep it out of public listings."
          val media = room?.let { "Media deletion in ${it.name}: ${if (it.deleteMedia) "enabled" else "disabled"}." }.orEmpty()
          val controls = listOfNotNull(config.help["source-admin"].takeIf { policy != null }, config.help["gateway-admin"].takeIf { isRoom })
          val details = concatenate(listOf(platform.template("gateway_status", mapOf("gateway_status" to GatewayText(listOf(status, media).filter { it.isNotEmpty() }.joinToString("\n"))))) + controls)
          platform.sendOnce("command:$messageId", 0, "gateway_status", sender, details)
        }
        is GatewayCommand.Set -> {
          GitHubAutoReply.updateConfiguration(context) { document ->
            val latest = GatewayConfiguration.parse(document)
            if (command.field == "deletemedia") {
              require(latest.groups.values.any { it.destination == groupId }) { "Run !gateway deletemedia in the configured Gateway group." }
              GatewayConfiguration.ensureRoom(document, groupId, platform.group(groupId, false).name)
            }
            val policy = GatewayConfiguration.parse(document).groups[groupId] ?: error("Group is not configured.")
            if (latest.paused || policy.throttle) throw GatewayPausedException()
            require(aci in platform.group(groupId, true).admins) { "Your administrator role changed." }
            if (command.field == "groupid") {
              val destination = requireNotNull(command.value)
              GroupId.parseOrThrow(destination).requireV2()
              require(store.requests().none { it.source == groupId && it.active }) { "Resolve active reviews before changing the Gateway destination." }
              require(destination !in reportingGroups()) { "Reporting groups cannot be Gateway destinations." }
              require(platform.group(destination, true).let { it.active && it.botAdmin && it.canPost && it.canAdd && it.applicantsCanPost }) { "The destination requires bot administration and must allow applicants to send messages." }
              GatewayConfiguration.ensureRoom(document, destination, platform.group(destination, false).name)
            }
            GatewayConfiguration.set(document, groupId, command.field, command.value)
            val updated = GatewayConfiguration.parse(document).groups.getValue(groupId)
            if (updated.enabled) {
              require(updated.destination != groupId || updated.admin) { "Same-group screening requires admin=1. Disable Gateway before switching source administrative actions off." }
              latest.requireWorkflowTemplates()
              require(updated.destination !in reportingGroups()) { "Reporting groups cannot be Gateway destinations." }
              require(platform.group(updated.destination, true).let { it.active && it.botAdmin && it.canPost && it.canAdd && it.applicantsCanPost }) { "Gateway permissions are insufficient; applicants must be able to send messages." }
              GatewayConfiguration.ensureRoom(document, updated.destination, platform.group(updated.destination, false).name)
              val source = platform.group(groupId, true)
              require(source.active && (!updated.admin || source.botAdmin)) { "Source permissions are insufficient." }
            }
            require(aci in platform.group(groupId, true).admins) { "Your administrator role changed." }
            true
          }
          dm(platform, sender, messageId, "dm_status", "Gateway configuration updated.")
          GatewayWorkJob.recover()
        }
        else -> Unit
      }
    } catch (e: GatewayPausedException) {
      return true
    } catch (e: Exception) {
      // Network bodies, tokens and private repository URLs never enter the chat.
      dm(platform, sender, messageId, "error", if (e is IllegalArgumentException) e.message.orEmpty() else "The operation could not be completed; please try again.")
    }
    return true
  }

  private fun dm(platform: SignalGatewayPlatform, sender: Recipient, id: Long, template: String, text: String) {
    val values = listOf("gateway_status", "operation_status", "safe_reason").associateWith { GatewayText(text) }
    platform.sendOnce("command:$id", 0, template, sender, platform.template(template, values))
  }

  fun knownAdminGroups(sender: Recipient): List<GroupRecord> = SignalDatabase.groups.getGroups().use { reader ->
    generateSequence { reader.getNext() }.filter { it.isActive && it.hasV2GroupProperties && it.isAdmin(sender) }.toList()
  }

  fun dmScanReply(context: Context, sender: Recipient, platform: SignalGatewayPlatform): CommandReply {
    val config = requireNotNull(GatewayRuntime.configuration(context))
    val blocklist = org.thoughtcrime.securesms.database.GroupTable.getCachedBlocklist()
    val reports = knownAdminGroups(sender).map { record ->
      val heading = CommandReply(record.title.orEmpty(), emptyList(), emptyList())
      GroupScanCommand.reply(heading, record, blocklist)
    }
    val result = concatenate(reports)
    val minutes = config.defaults.dmScanMinutes
    val cooldown = platform.template(if (minutes == 0L) "scan_ready" else "scan_wait", mapOf("minutes" to GatewayText(minutes.toString())))
    return platform.template("scan", mapOf(
      "scan_results" to GatewayText(result.text.ifBlank { platform.template("scan_no_groups").text }, result.mentions),
      "cooldown_status" to GatewayText(cooldown.text, cooldown.mentions)
    ))
  }

  fun concatenate(replies: List<CommandReply>): CommandReply {
    val text = StringBuilder()
    val mentions = mutableListOf<Mention>()
    val styles = mutableListOf<org.thoughtcrime.securesms.github.CommandStyleRange>()
    replies.filter { it.text.isNotBlank() }.forEach { reply ->
      if (text.isNotEmpty()) text.append("\n\n")
      val offset = text.length
      text.append(reply.text)
      mentions += reply.mentions.map { Mention(it.recipientId, it.start + offset, it.length) }
      styles += reply.styles.map { it.copy(start = it.start + offset) }
    }
    return CommandReply(text.toString(), styles, emptyList(), mentions)
  }

  private fun reportingGroups(): Set<String> = org.thoughtcrime.securesms.database.GroupTable.aciReportingGroups()
}
