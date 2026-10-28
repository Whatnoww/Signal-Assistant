/* SPDX-License-Identifier: AGPL-3.0-only */
package org.thoughtcrime.securesms.gateway

import android.app.Application
import android.content.Context
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.database.withAttachments
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.github.GitHubAutoReply
import org.thoughtcrime.securesms.jobs.AdminDeleteSendJob
import org.thoughtcrime.securesms.jobs.GatewayWorkJob
import org.thoughtcrime.securesms.jobs.GatewayRequestPollJob
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.util.MessageConstraintsUtil
import org.thoughtcrime.securesms.util.hasGiftBadge
import org.thoughtcrime.securesms.util.hasPoll
import org.thoughtcrime.securesms.util.hasSharedContact
import org.thoughtcrime.securesms.util.hasSticker

object GatewayRuntime {
  private val workSlots = java.util.concurrent.Semaphore(2)
  internal fun acquireWork(): Boolean = workSlots.tryAcquire()
  internal fun releaseWork() { workSlots.release() }
  private val pendingAssistantSends = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()
  @Volatile private var observedConfiguration: GatewayConfiguration? = null
  @Volatile private var watchedGroups: Set<String>? = null
  @Volatile private var parsedConfiguration: Pair<String, GatewayConfiguration>? = null

  /** Receive paths only consult this immutable routing snapshot, never XML or SQL. */
  fun observes(groupId: String): Boolean = watchedGroups?.contains(groupId) ?: true

  internal fun configurationAccepted(context: Context) {
    val current = configuration(context) ?: return
    if (current == observedConfiguration) return
    observedConfiguration = current
    val configuredGroups = current.groups.values.filter { it.enabled }.flatMap { listOf(it.id, it.destination) }.toSet()
    if (!current.configured && !context.getDatabasePath(GatewayDatabase.NAME).exists()) { watchedGroups = configuredGroups; return }
    // Already on an Assistant worker. Freeze at acceptance rather than the next deadline.
    val store = GatewayDatabase.get(context.applicationContext as Application)
    val requests = store.requests()
    watchedGroups = configuredGroups + requests.flatMap { listOf(it.source, it.room) }
    requests.forEach { request -> store.setPaused(request, current.groups[request.source]?.let { current.isPaused(it) || it.destination != request.room } ?: true, System.currentTimeMillis()) }
    GatewayRequestPollJob.schedule(current)
    GatewayWorkJob.recover()
  }
  internal fun paused(context: Context): Boolean = GitHubAutoReply.configurationDocument(context)?.let { document ->
    document.document.documentElement.direct("gateway")?.direct("paused")?.textContent?.trim() == "1"
  } ?: false

  internal fun configuration(context: Context, online: Boolean = false): GatewayConfiguration? {
    val snapshot = GitHubAutoReply.configurationSnapshot(context, online) ?: return null
    parsedConfiguration?.takeIf { it.first == snapshot.sha }?.let { return it.second }
    return GatewayConfiguration.parse(org.thoughtcrime.securesms.github.CommandDocument.parse(snapshot.xml)).also {
      parsedConfiguration = snapshot.sha to it
    }
  }

  @JvmStatic fun preservesContact(context: Context, recipientId: Long, timestamp: Long): Boolean {
    if (!context.getDatabasePath(GatewayDatabase.NAME).exists()) return false
    return try { GatewayDatabase.get(context.applicationContext as Application).preservesContact(recipientId, timestamp) } catch (e: Exception) {
      Log.w("Gateway", "Private-send tracking is unavailable (${e.javaClass.simpleName}); preserving profile-sharing state.")
      true
    }
  }

  internal fun prepareSend(context: Context, recipient: Recipient, timestamp: Long, invocation: Long, ownerRecovery: Boolean) {
    val configured = GitHubAutoReply.configurationDocument(context)?.document?.documentElement?.direct("gateway") != null
    if (!configured && !context.getDatabasePath(GatewayDatabase.NAME).exists()) return
    pendingAssistantSends += timestamp
    val store = GatewayDatabase.get(context.applicationContext as Application)
    if (store.outgoingRequest(timestamp) == null) store.reserveOutgoing("assistant:$timestamp", 0, if (ownerRecovery) "owner-recovery" else "command", timestamp, invocation)
    store.bindRecipient(timestamp, recipient.id.toLong())
    if (!recipient.isGroup) store.preserveContact(recipient.id.toLong(), timestamp)
  }

  /** Checked on native transport workers too: queued Assistant work cannot bypass a pause. */
  @JvmStatic fun maySend(context: Context, timestamp: Long, recipient: Long = 0, messageId: Long = 0): Boolean {
    return try { maySendTracked(context, timestamp, recipient, messageId) } catch (e: Exception) {
      Log.w("Gateway", "Queued-send tracking is unavailable (${e.javaClass.simpleName}).")
      // A separate Assistant storage failure must not fail ordinary Signal sends.
      timestamp !in pendingAssistantSends
    }
  }

  private fun maySendTracked(context: Context, timestamp: Long, recipient: Long, messageId: Long): Boolean {
    if (!context.getDatabasePath(GatewayDatabase.NAME).exists()) return true
    val store = GatewayDatabase.get(context.applicationContext as Application)
    if (!store.matchesOutgoing(timestamp, recipient, messageId)) return true
    val outgoing = store.outgoingRequest(timestamp) ?: return true
    if (outgoing.second == "owner-recovery") return true
    val config = configuration(context)
    val request = store.request(outgoing.first)
    val source = request?.let { config?.groups?.get(it.source) }
    val stopped = outgoing.second == "cancelled-review" || paused(context) || request != null && (source == null || config?.isPaused(source) == true || source.destination != request.room ||
      !request.active && outgoing.second in setOf("spam", "spam_reminder", "administrator_notification"))
    if (!stopped) return true
    store.transaction {
      val invocation = store.outgoingInvocation(timestamp)
      if (request != null) {
        when (outgoing.second) {
          "spam" -> if (request.phase == GatewayPhase.PROMPTING) store.save(request, request.transition(GatewayPhase.WAITING_FOR_MEMBERSHIP) { it.remove("promptId") })
          "spam_reminder" -> store.save(request, request.transition { it.remove("reminded") })
          "administrator_notification" -> store.requests().filter { it.room == request.room && it.aci == request.aci && it.data.optLong("notificationId") == store.outgoingEvent(store.outgoingMessage(timestamp)).firstOrNull()?.second }
            .forEach { store.save(it, it.transition { data -> data.remove("notified"); data.remove("notificationId") }) }
          else -> if (request.data.has("auditId")) store.save(request, request.transition { it.remove("auditId") })
        }
      }
      store.forgetTimestamp(timestamp)
      store.releaseIfIdle(invocation)
    }
    return false
  }

  @JvmStatic fun acknowledged(context: Context, recipientId: Long, timestamp: Long, messageId: Long) {
    val now = System.currentTimeMillis()
    if (!context.getDatabasePath(GatewayDatabase.NAME).exists()) return
    try {
      val store = GatewayDatabase.get(context.applicationContext as Application)
      if (store.matchesOutgoing(timestamp, recipientId, messageId)) store.recordAck(timestamp, now)
    } catch (e: Exception) { Log.w("Gateway", "Native acknowledgement tracking deferred (${e.javaClass.simpleName}).") }
  }

  @JvmStatic fun sent(context: Context, recipientId: Long, timestamp: Long, messageId: Long = 0) {
    if (!context.getDatabasePath(GatewayDatabase.NAME).exists()) return
    try {
      val store = GatewayDatabase.get(context.applicationContext as Application)
      if (!store.matchesOutgoing(timestamp, recipientId, messageId)) return
      val requestId = store.transaction {
        val id = store.confirmOutgoing(timestamp, System.currentTimeMillis())
        store.forgetPrivateSend(recipientId, timestamp)
        id
      }
      pendingAssistantSends -= timestamp
      requestId?.let { id -> store.request(id)?.let { AppDependencies.jobManager.add(GatewayWorkJob(id, 0, it.room, urgent = true)) } }
    } catch (e: Exception) {
      // The native message is already sent. Tracking failure cannot retry it or
      // convert it to a failed Signal message; startup reconciles the receipt.
      Log.w("Gateway", "Send receipt tracking deferred (${e.javaClass.simpleName}).")
    }
  }

  @JvmStatic fun failed(context: Context, messageId: Long) {
    try { recordFailure(context, messageId) } catch (e: Exception) {
      Log.w("Gateway", "Failed-send tracking deferred (${e.javaClass.simpleName}).")
    }
  }

  private fun recordFailure(context: Context, messageId: Long) {
    if (!context.getDatabasePath(GatewayDatabase.NAME).exists()) return
    val message = SignalDatabase.messages.getMessageRecordOrNull(messageId) ?: return
    val store = GatewayDatabase.get(context.applicationContext as Application)
    if (!store.matchesOutgoing(message.dateSent, message.toRecipient.id.toLong(), message.id)) return
    val outgoing = store.outgoingRequest(message.dateSent) ?: return
    val invocation = store.outgoingInvocation(message.dateSent)
    store.forgetPrivateSend(message.toRecipient.id.toLong(), message.dateSent)
    if (outgoing.first == 0L) { store.forgetTimestamp(message.dateSent); store.releaseIfIdle(invocation) }
    else store.request(outgoing.first)?.let { AppDependencies.jobManager.add(GatewayWorkJob(it.id, 0, it.room, urgent = true)) }
    pendingAssistantSends -= message.dateSent
  }

  internal fun releaseUnsentInvocation(context: Context, invocation: Long) {
    if (context.getDatabasePath(GatewayDatabase.NAME).exists()) GatewayDatabase.get(context.applicationContext as Application).releaseIfIdle(invocation)
  }

  internal fun mayDelete(context: Context, messageId: Long): Boolean {
    if (!context.getDatabasePath(GatewayDatabase.NAME).exists()) return true
    val store = GatewayDatabase.get(context.applicationContext as Application)
    if (!store.mediaDeleteTracked(messageId)) return true
    val config = configuration(context, true) ?: return false
    val message = SignalDatabase.messages.getMessageRecordOrNull(messageId) ?: return false
    val room = SignalDatabase.threads.getRecipientForThreadId(message.threadId)?.groupId?.orElse(null)?.toString() ?: return false
    val aci = message.fromRecipient.aci.orElse(null)?.toString() ?: return false
    if (config.paused || config.groups[room]?.throttle == true || config.groups[room]?.deleteMedia != true) return false
    if (store.requests().none { it.active && it.room == room && it.aci == aci && config.groups[it.source]?.let { source -> !config.isPaused(source) } == true }) return false
    val state = SignalGatewayPlatform(context, store).group(room, true)
    return state.botAdmin && state.active && MessageConstraintsUtil.isValidAdminDeleteSend(listOf(message), System.currentTimeMillis(), true)
  }

  internal fun deleteFinished(context: Context, messageId: Long) {
    try {
      if (context.getDatabasePath(GatewayDatabase.NAME).exists()) GatewayDatabase.get(context.applicationContext as Application).forgetMediaDelete(messageId)
    } catch (e: Exception) { Log.w("Gateway", "Media bookkeeping deferred (${e.javaClass.simpleName}).") }
  }

  @JvmStatic fun groupChanged(groupId: String, removed: List<String>) {
    if (observes(groupId)) AppDependencies.jobManager.add(GatewayWorkJob(groupId, removed))
  }

  fun work(context: Context, requestId: Long, groupId: String, removed: List<String>, messageId: Long, intake: Boolean = false) {
    if (!SignalStore.account.isPrimaryDevice) return
    val config = configuration(context, requestId == 0L && groupId.isBlank() && messageId == 0L)
    if (config?.configured != true && !context.getDatabasePath(GatewayDatabase.NAME).exists()) return
    val store = GatewayDatabase.get(context.applicationContext as Application)
    store.clearExpired(System.currentTimeMillis())
    if (requestId == 0L && groupId.isBlank() && messageId == 0L) {
      store.pendingOutgoing().forEach { (_, storedId, timestamp) ->
        pendingAssistantSends += timestamp
        val message = if (storedId != 0L) SignalDatabase.messages.getMessageRecordOrNull(storedId) else SignalDatabase.messages.getMessageFor(timestamp, Recipient.self().id)
        when {
          message == null && store.outgoingRequest(timestamp)?.first == 0L -> {
            val invocation = store.outgoingInvocation(timestamp)
            store.forgetTimestamp(timestamp); store.releaseIfIdle(invocation); pendingAssistantSends -= timestamp
          }
          message != null && message.isFailed -> failed(context, message.id)
          message != null && !message.isPending -> sent(context, message.toRecipient.id.toLong(), timestamp)
        }
      }
    }
    // Departure suppression precedes pause handling and survives process restart.
    removed.forEach { store.finishDeparture(groupId, it) }
    if (config?.configured != true) {
      store.requests().filter { it.active }.forEach { store.setPaused(it, true, System.currentTimeMillis()) }
      return
    }
    if (requestId == 0L && groupId.isBlank() && messageId == 0L) GatewayRequestPollJob.schedule(config)
    watchedGroups = config.groups.values.filter { it.enabled }.flatMap { listOf(it.id, it.destination) }.toSet() + store.requests().flatMap { listOf(it.source, it.room) }
    val platform = SignalGatewayPlatform(context, store)
    val engine = GatewayEngine(store, platform)
    if (intake) { engine.intake(groupId); return }
    if (requestId != 0L) {
      engine.step(requestId)
      return
    }
    if (messageId != 0L) {
      val message = SignalDatabase.messages.getMessageRecordOrNull(messageId)?.withAttachments() ?: return
      if (message.isOutgoing || message.isRemoteDelete || message.isEditMessage || message.fromRecipient.isSelf || message.fromRecipient.isBlocked) return
      val recipient = SignalDatabase.threads.getRecipientForThreadId(message.threadId) ?: return
      val room = recipient.groupId.orElse(null)?.toString() ?: return
      val aci = message.fromRecipient.aci.orElse(null)?.toString() ?: return
      val active = store.requests().filter { it.room == room && it.aci == aci && it.active && config.groups[it.source]?.let { source -> !config.isPaused(source) } == true }
      if (config.paused || config.groups[room]?.throttle == true || active.isEmpty()) return
      if (store.session(room, aci).optBoolean("present") && aci !in platform.group(room, false).members) {
        store.finishDeparture(room, aci)
        active.forEach { platform.schedule(it.id, platform.now()) }
        return
      }
      val mms = message as? org.thoughtcrime.securesms.database.model.MmsMessageRecord
      // Link-preview thumbnails are stored separately from the message's slide deck.
      val media = mms?.linkPreviews?.isNotEmpty() == true || mms?.slideDeck?.slides?.any { !org.thoughtcrime.securesms.util.MediaUtil.isLongTextType(it.contentType) } == true
      if (media) {
        if (config.groups[room]?.deleteMedia == true && platform.group(room, true).botAdmin && MessageConstraintsUtil.isValidAdminDeleteSend(listOf(message), platform.now(), true)) {
          AdminDeleteSendJob.create(message.id, emptyList())?.let { store.trackMediaDelete(message.id); AppDependencies.jobManager.add(it) }
        }
      } else if (message.body.isNotBlank() && message.body.trimStart().first() !in "!#" && mms?.hasSharedContact() != true && mms?.hasPoll() != true &&
        mms?.hasSticker() != true && mms?.hasGiftBadge() != true && !message.isPaymentNotification && !message.isPaymentTombstone &&
        !message.isUpdate) {
        // A reply may quote any message. Only the applicant's own body counts;
        // quoted text/attachments are separate from the body and media checked above.
        // Compare local receive/send clocks, never applicant or server clock skew.
        engine.answer(room, aci, message.id, message.dateReceived)
      }
      return
    }
    if (groupId.isNotBlank()) {
      // A room-membership update is not a change to every source's join-request list.
      if (!config.paused) config.groups[groupId]?.takeIf { !config.isPaused(it) }?.let { AppDependencies.jobManager.add(GatewayWorkJob.intake(it.id)) }
      val affected = store.requests().filter { it.source == groupId || it.room == groupId }
      if (affected.isEmpty()) return
      val changed = platform.group(groupId, false)
      affected.forEach { request ->
        val waiting = request.phase == GatewayPhase.WAITING_FOR_RESPONSE || request.phase == GatewayPhase.WAITING_FOR_DECISION && request.data.optBoolean("notified")
        val sourceResolved = request.source == groupId && changed.requests[request.aci] != request.generation &&
          !(request.source == request.room && request.data.optBoolean("provisional") && request.aci in changed.members)
        val departed = request.room == groupId && store.session(request.room, request.aci).optBoolean("present") && request.aci !in changed.members
        if (!waiting || sourceResolved || departed) platform.schedule(request.id, platform.now())
      }
    } else {
      // Startup recovery: no periodic permission/blocklist polling.
      if (!config.paused) config.groups.values.filter { it.enabled }.forEach { AppDependencies.jobManager.add(GatewayWorkJob.intake(it.id)) }
      store.requests().forEach { platform.schedule(it.id, platform.now()) }
    }
  }
}
