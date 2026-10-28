/* SPDX-License-Identifier: AGPL-3.0-only */
package org.thoughtcrime.securesms.gateway

import android.app.Application
import android.content.Context
import org.signal.core.models.ServiceId.ACI
import org.signal.storageservice.storage.protos.groups.Member
import org.signal.storageservice.storage.protos.groups.local.DecryptedGroup
import org.thoughtcrime.securesms.database.GroupTable
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.database.model.Mention
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.github.CommandReply
import org.thoughtcrime.securesms.github.GitHubAutoReply
import org.thoughtcrime.securesms.github.GroupScanCommand
import org.thoughtcrime.securesms.groups.GatewayGroupActions
import org.thoughtcrime.securesms.groups.GroupAccessControl
import org.thoughtcrime.securesms.groups.GroupId
import org.thoughtcrime.securesms.jobs.GatewayWorkJob
import org.thoughtcrime.securesms.jobs.GitHubAutoReplyJob
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.mms.QuoteModel
import org.thoughtcrime.securesms.recipients.Recipient

internal class SignalGatewayPlatform(private val context: Context, private val store: GatewayStore) : GatewayPlatform {
  private data class Snapshot(val group: DecryptedGroup, val active: Boolean, val me: String, val state: GatewayGroupState)
  private val snapshots = mutableMapOf<String, Snapshot>()
  override fun now(): Long = System.currentTimeMillis()
  override fun configuration(online: Boolean): GatewayConfiguration? = GatewayRuntime.configuration(context, online)?.also {
    if (online && it.groups.values.any { source -> !it.isPaused(source) }) it.requireWorkflowTemplates()
  }

  override fun group(id: String, fresh: Boolean): GatewayGroupState {
    val groupId = GroupId.parseOrThrow(id).requireV2()
    var record = SignalDatabase.groups.requireGroup(groupId)
    if (fresh) {
      org.thoughtcrime.securesms.groups.GroupManager.forceSanityUpdateFromServer(context, record.requireV2GroupProperties().groupMasterKey, now())
      record = SignalDatabase.groups.requireGroup(groupId)
    }
    val group = record.requireV2GroupProperties().decryptedGroup
    val me = Recipient.self().requireAci().toString()
    val name = record.title ?: id
    // Reuse only conversion of an unchanged local snapshot, never a permission TTL.
    // A fresh read above always contacts Signal; conflict rebases change the snapshot.
    snapshots[id]?.takeIf { it.group == group && it.active == record.isActive && it.me == me && it.state.name == name }?.let { return it.state }
    val members = group.members.mapNotNull { ACI.parseOrNull(it.aciBytes)?.toString() }.toSet()
    val admins = group.members.filter { it.role == Member.Role.ADMINISTRATOR }.mapNotNull { ACI.parseOrNull(it.aciBytes)?.toString() }.toSet()
    val botAdmin = me in admins
    return GatewayGroupState(
      id, name, members, admins,
      group.requestingMembers.mapNotNull { member -> ACI.parseOrNull(member.aciBytes)?.toString()?.let { it to member.timestamp } }.toMap(),
      group.pendingMembers.mapNotNull { ACI.parseOrNull(it.serviceIdBytes)?.toString() }.toSet(),
      group.bannedMembers.mapNotNull { ACI.parseOrNull(it.serviceIdBytes)?.toString() }.toSet(),
      record.isActive && me in members,
      record.isActive && me in members && (!record.isAnnouncementGroup || botAdmin), botAdmin,
      botAdmin || (me in members && record.membershipAdditionAccessControl == GroupAccessControl.ALL_MEMBERS), !record.isAnnouncementGroup
    ).also { snapshots[id] = Snapshot(group, record.isActive, me, it) }
  }

  override fun blocklist(): Set<String>? {
    GroupTable.getBlocklist(forceRefresh = true)
    // A failed download retains the last valid cache. Never interpret no cache as clean.
    return GroupTable.getCachedBlocklist()
  }

  override fun mutate(request: GatewayRequest, operation: GatewayMutation, actor: String?): GatewayMutationResult {
    require(SignalStore.account.isPrimaryDevice) { "Gateway actions require the primary instance." }
    val config = configuration(true) ?: error("Configuration is unavailable.")
    val source = config.groups[request.source] ?: error("Source configuration is unavailable.")
    if (config.isPaused(source) || source.destination != request.room) throw GatewayPausedException()
    group(request.source, true)
    val targetId = if (operation in setOf(GatewayMutation.ADD, GatewayMutation.CLEANUP)) request.room else request.source
    if (targetId != request.source) group(targetId, true)
    val applicant = Recipient.externalPush(ACI.parseOrThrow(request.aci))
    val result = GatewayGroupActions.apply(context, GroupId.parseOrThrow(targetId).requireV2(), applicant, operation.name) {
      // Runs under the native group lock and again after each conflict rebase.
      val current = configuration(false) ?: error("Configuration is unavailable.")
      val policy = current.groups[request.source] ?: error("Source configuration is unavailable.")
      if (current.isPaused(policy) || policy.destination != request.room) throw GatewayPausedException()
      val latest = store.request(request.id) ?: error("Review is no longer current.")
      require(latest.generation == request.generation && (latest.active || operation == GatewayMutation.CLEANUP)) { "Review is no longer active." }
      val expected = when (operation) {
        GatewayMutation.ADD -> setOf(GatewayPhase.ADMITTING)
        GatewayMutation.APPROVE -> setOf(GatewayPhase.ADMITTING, GatewayPhase.APPROVING)
        GatewayMutation.DENY, GatewayMutation.REMOVE -> setOf(GatewayPhase.DENYING)
        GatewayMutation.CLEANUP -> setOf(GatewayPhase.FINISHED)
      }
      require(latest.phase in expected) { "The claimed operation changed." }
      if (operation == GatewayMutation.ADD || operation == GatewayMutation.APPROVE) {
        val blocked = GroupTable.getCachedBlocklist()
        if (blocked == null || request.aci in blocked) throw java.io.IOException("Gateway blocklist must be rechecked before admission.")
      }
      val sourceState = group(request.source, false)
      val target = group(targetId, false)
      require(target.active) { "Signal Assistant is no longer a group member." }
      if (actor != null) require(actor in sourceState.admins) { "The administrator's role changed." }
      if (operation in setOf(GatewayMutation.APPROVE, GatewayMutation.DENY, GatewayMutation.REMOVE)) {
        require(policy.admin && target.botAdmin) { "Source administrative permission is unavailable." }
      } else if (operation == GatewayMutation.ADD) {
        require(target.canAdd && target.canPost && target.applicantsCanPost && request.aci !in target.banned && !store.session(request.room, request.aci).optBoolean("cleaning")) { "Gateway admission is unavailable." }
      } else {
        require(target.botAdmin && store.session(request.room, request.aci).optBoolean("owned")) { "Gateway cleanup is unavailable." }
        require(store.requests().none { it.room == request.room && it.aci == request.aci && it.active }) { "Other reviews still need this member." }
      }
      if (operation == GatewayMutation.APPROVE || operation == GatewayMutation.DENY) {
        require(sourceState.requests[request.aci] == request.generation) { "The native request changed." }
      }
    }
    val member = result != null && result.addedMemberCount > 0
    return GatewayMutationResult(member || result?.invitedMembers?.contains(applicant.id) == true, member)
  }

  fun mention(aci: String): GatewayText = GatewayText("\uFFFC", listOf(Mention(Recipient.externalPush(ACI.parseOrThrow(aci)).id, 0, 1)))

  fun template(id: String, values: Map<String, GatewayText> = emptyMap()): CommandReply {
    val config = configuration(false) ?: error("Configuration is unavailable.")
    val text = if (id == "spam") CommandReply("{applicant}: {spamtext}", emptyList(), emptyList()) else
      config.messages[id] ?: error("The Gateway response '$id' is not configured.")
    return gatewayReply(text, values)
  }

  override fun send(request: GatewayRequest, key: String, template: String, fields: Map<String, String>, quoteId: Long?): Long {
    val reason = fields["confirmed_result"].orEmpty()
    val response = if (template != "outcome") template else when {
      reason.startsWith("ACI-Blocklist") -> "blocklist_rejection"
      reason == "approved" -> "approval"
      reason == "denied" -> "denial"
      reason.startsWith("expired") -> "timeout"
      reason == "unplanned_gateway_departure" -> "manual_finish"
      else -> "status"
    }
    val config = configuration(false) ?: error("Configuration is unavailable.")
    val sourceName = config.groups[request.source]?.name ?: request.source
    val values = fields.mapValues { GatewayText(it.value) }.toMutableMap()
    values["applicant"] = mention(request.aci)
    values["source_name"] = GatewayText(sourceName)
    values["confirmed_action"] = GatewayText(when (reason) {
      "ACI-Blocklist" -> "Denied"
      "expired" -> "Denied after the screening deadline"
      "externally_resolved" -> "No longer pending; resolved or cancelled outside Gateway"
      else -> reason.replace('_', ' ')
    })
    values["operation_status"] = GatewayText(reason.replace('_', ' '))
    values["gateway_status"] = GatewayText(reason.replace('_', ' '))
    values["safe_reason"] = GatewayText(reason.replace('_', ' '))
    values["source_requests_status"] = GatewayText("Source requests are reconciled separately.")
    val reply = template(response, values)
    return sendOnce("review:${request.id}:$key", request.id, response, Recipient.externalGroupExact(GroupId.parseOrThrow(request.room)), reply, quoteId).first()
  }

  override fun notify(requests: List<GatewayRequest>, messageId: Long): Long {
    val first = requests.first()
    val config = configuration(false) ?: error("Configuration is unavailable.")
    val room = group(first.room, false)
    require(room.canPost)
    val all = store.requests().filter { it.room == first.room && it.aci == first.aci && it.active && config.groups[it.source]?.let { source -> !config.isPaused(source) } == true }
    val ping = requests.flatMap { request ->
      val source = config.groups[request.source]
      if (source?.mentionAdmins == true && !config.isPaused(source)) group(request.source, false).admins else emptySet()
    }.toSet().intersect(room.members) - Recipient.self().requireAci().toString()
    val adminsText = GatewayText(ping.joinToString(" ") { "\uFFFC" }, ping.mapIndexed { index, aci -> Mention(Recipient.externalPush(ACI.parseOrThrow(aci)).id, index * 2, 1) })
    val known = config.groups.values.filter { it.publiclyListed }.filter { policy ->
      runCatching { first.aci in group(policy.id, false).members }.getOrDefault(false)
    }.joinToString("\n") { "• ${it.name}" }
    val commands = GatewayController.concatenate(all.filter { it.phase == GatewayPhase.WAITING_FOR_DECISION && config.groups[it.source]?.let { source -> !config.isPaused(source) } == true }.map {
      gatewayReply(CommandReply("{name}\n!approve {applicant} {number}\n!deny {applicant} {number}", emptyList(), emptyList()), mapOf(
        "name" to GatewayText(config.groups[it.source]?.name.orEmpty()), "applicant" to mention(it.aci), "number" to GatewayText(it.number.toString())
      ))
    })
    val reply = template("administrator_notification", mapOf(
      "applicant" to mention(first.aci), "admins" to adminsText,
      "requested_groups" to GatewayText(all.joinToString("\n") { "${it.number} → ${config.groups[it.source]?.name ?: it.source}" }),
      "known_memberships" to GatewayText(known.ifBlank { "No memberships in the configured group set are known." }),
      "decision_commands" to GatewayText(commands.text, commands.mentions)
    ))
    return sendOnce("review-batch:${first.room}:${first.aci}:$messageId", first.id, "administrator_notification", Recipient.externalGroupExact(GroupId.parseOrThrow(first.room)), reply, messageId).first()
  }

  fun sendOnce(key: String, requestId: Long, kind: String, recipient: Recipient, reply: CommandReply, quoteId: Long? = null, invocation: Long = 0): List<Long> {
    require(!recipient.isBlocked && !recipient.isHidden && !recipient.isSelf)
    if (recipient.isGroup) require(group(recipient.requireGroupId().toString(), false).canPost)
    return GroupScanCommand.pages(reply).mapIndexed { index, page ->
      val pageKey = "$key:$index"
      val existing = store.outgoing(pageKey)
      val timestamp = existing?.second ?: GitHubAutoReplyJob.nextSentTimeMillis().also { store.reserveOutgoing(pageKey, requestId, kind, it, invocation) }
      val recovered = if (existing?.first != null && existing.first != 0L) SignalDatabase.messages.getMessageRecordOrNull(existing.first) else SignalDatabase.messages.getMessageFor(timestamp, Recipient.self().id)
      if (existing != null && existing.first != 0L && recovered == null) return@mapIndexed existing.first
      if (recovered != null) {
        require(recovered.toRecipient.id == recipient.id) { "Outgoing identity collision." }
        store.outgoingInserted(pageKey, recovered.id)
        return@mapIndexed recovered.id
      }
      if (!recipient.isGroup) store.preserveContact(recipient.id.toLong(), timestamp)
      val quote = if (index == 0) quoteId?.let { SignalDatabase.messages.getMessageRecordOrNull(it) }?.let {
        QuoteModel(it.dateSent, it.fromRecipient.id, it.body, false, null, SignalDatabase.mentions.getMentionsForMessage(it.id), QuoteModel.Type.NORMAL, null)
      } else null
      val threadId = SignalDatabase.threads.getOrCreateThreadIdFor(recipient)
      requireNotNull(GitHubAutoReplyJob.sendReply(context, page, recipient, threadId, timestamp = timestamp, quote = quote))
      val inserted = requireNotNull(SignalDatabase.messages.getMessageFor(timestamp, Recipient.self().id))
      store.outgoingInserted(pageKey, inserted.id)
      inserted.id
    }
  }

  override fun sendState(messageId: Long): GatewaySendState {
    val pages = store.outgoingEvent(messageId)
    if (pages.size > 1) {
      val states = pages.map { sendPageState(it.second) }
      return GatewaySendState(if (states.all { it.sentAt != null }) states.maxOf { it.sentAt!! } else null, states.any { it.failed })
    }
    return sendPageState(messageId)
  }

  private fun sendPageState(messageId: Long): GatewaySendState {
    val message = SignalDatabase.messages.getMessageRecordOrNull(messageId) ?: return GatewaySendState(failed = true)
    val confirmed = store.confirmedAt(messageId)
    // Recover a crash between Signal's send commit and our separate receipt commit.
    if (!message.isPending && !message.isFailed && confirmed == null) store.confirmOutgoing(message.dateSent, now())
    return GatewaySendState(if (!message.isPending && !message.isFailed) confirmed ?: store.confirmedAt(messageId) ?: message.dateSent else null, message.isFailed || message.isRemoteDelete)
  }

  override fun schedule(id: Long, at: Long) {
    val request = store.request(id) ?: return
    AppDependencies.jobManager.add(GatewayWorkJob.at(id, request.room, at, request.phase != GatewayPhase.INTAKE))
  }
  override fun cleaned(room: String) { GatewayRuntime.groupChanged(room, emptyList()) }
  override fun promptTimestamp(request: GatewayRequest): Long? = store.outgoing("review:${request.id}:prompt:0")?.second
  override fun replayAnswer(messageId: Long) {
    val room = SignalDatabase.messages.getMessageRecordOrNull(messageId)?.let { SignalDatabase.threads.getRecipientForThreadId(it.threadId)?.groupId?.orElse(null)?.toString() }.orEmpty()
    AppDependencies.jobManager.add(GatewayWorkJob(messageId, true, room))
  }
}
