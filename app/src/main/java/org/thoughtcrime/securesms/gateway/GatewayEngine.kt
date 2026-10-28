/* SPDX-License-Identifier: AGPL-3.0-only */
package org.thoughtcrime.securesms.gateway

import org.json.JSONObject

internal data class GatewayGroupState(
  val id: String,
  val name: String,
  val members: Set<String>,
  val admins: Set<String>,
  val requests: Map<String, Long>,
  val invites: Set<String>,
  val banned: Set<String>,
  val active: Boolean,
  val canPost: Boolean,
  val botAdmin: Boolean,
  val canAdd: Boolean,
  val applicantsCanPost: Boolean = true
)

internal enum class GatewayMutation { ADD, APPROVE, DENY, REMOVE, CLEANUP }
internal data class GatewayMutationResult(val added: Boolean = false, val member: Boolean = false)
internal data class GatewaySendState(val sentAt: Long? = null, val failed: Boolean = false, val responseAfter: Long? = sentAt)

/** Network and Signal effects are isolated from the transactional workflow for deterministic tests. */
internal interface GatewayPlatform {
  fun configuration(online: Boolean): GatewayConfiguration?
  fun group(id: String, fresh: Boolean): GatewayGroupState
  fun blocklist(): Set<String>?
  fun mutate(request: GatewayRequest, operation: GatewayMutation, actor: String? = null): GatewayMutationResult
  fun send(request: GatewayRequest, key: String, template: String, fields: Map<String, String> = emptyMap(), quoteId: Long? = null): Long
  fun sendState(messageId: Long): GatewaySendState
  fun notify(requests: List<GatewayRequest>, messageId: Long): Long
  fun schedule(id: Long, at: Long)
  fun now(): Long
  fun cleaned(room: String) {}
  fun promptTimestamp(request: GatewayRequest): Long? = null
  fun replayAnswer(messageId: Long) {}
  fun manualDeparture(room: String, aci: String): Boolean = false
  fun departureAt(room: String, aci: String): Long = now()
}

internal class GatewayEngine(private val store: GatewayStore, private val platform: GatewayPlatform) {
  fun intake(sourceId: String) {
    // Native events and the fallback poll already commit the discovered group state.
    // Intake only records work; membership mutations perform their own fresh checks.
    val config = platform.configuration(false) ?: return
    val source = config.groups[sourceId] ?: return
    if (config.isPaused(source)) return
    val state = platform.group(sourceId, false)
    if (!state.active) return
    state.requests.forEach { (aci, generation) ->
      if (store.session(source.destination, aci).optBoolean("cleaning")) return@forEach
      // A timestamp-less snapshot cannot prove a new generation after manual finish.
      val previous = store.requests().firstOrNull { it.source == sourceId && it.aci == aci && !it.active }
      if (previous != null && (generation == 0L || previous.generation == generation)) return@forEach
      val request = store.intakeUnlessCleaning(source, aci, generation, config.defaults, platform.now()) ?: return@forEach
      if (request.phase == GatewayPhase.INTAKE) platform.schedule(request.id, platform.now())
    }
  }

  fun step(id: Long) {
    var request = store.request(id) ?: return
    val due = deadlineDue(request, platform.now())
    val config = platform.configuration(request.phase == GatewayPhase.INTAKE || due) ?: return
    val source = config.groups[request.source] ?: run { store.setPaused(request, true, platform.now()); return }
    val now = platform.now()
    val paused = config.isPaused(source) || source.destination != request.room
    store.setPaused(request, paused, now)
    request = store.request(id) ?: return
    if (paused) return
    val decision = request.phase in setOf(GatewayPhase.APPROVING, GatewayPhase.DENYING)
    val refresh = request.phase == GatewayPhase.INTAKE || due
    val state = platform.group(request.source, refresh || decision && request.source == request.room)
    val room = if (request.room == request.source) state else platform.group(request.room, refresh || decision)
    if (!state.active || !room.active) return

    val session = store.session(request.room, request.aci)
    if (session.optBoolean("present") && request.aci !in room.members && !session.optBoolean("cleaning")) {
      departed(request.room, request.aci, platform.manualDeparture(request.room, request.aci), platform.departureAt(request.room, request.aci))
      request = store.request(id) ?: return
    }
    if (!request.active) {
      finishWork(request, state, room)
      return
    }
    if (request.data.optBoolean("departed") && !source.admin) {
      store.finish(request, "spam_test_failed_departure; source request pending; manual action required")
      platform.schedule(id, now)
      return
    }

    val provisional = request.data.optBoolean("provisional")
    if (request.aci !in state.requests && !(request.source == request.room && provisional && request.aci in state.members)) {
      val reason = when {
        request.phase == GatewayPhase.APPROVING && request.aci in state.members -> "approved"
        request.phase == GatewayPhase.DENYING && request.aci !in state.members -> request.data.optString("outcome", "denied")
        request.aci in state.members -> "externally_approved"
        else -> "externally_resolved"
      }
      store.finish(request, reason)
      platform.schedule(id, now)
      return
    }
    if (request.aci in state.requests && request.generation != state.requests[request.aci]) {
      store.finish(request, "cancelled_generation")
      platform.schedule(id, now)
      return
    }

    when (request.phase) {
      GatewayPhase.INTAKE -> {
        val blocklist = platform.blocklist() ?: return
        if (request.aci in blocklist) {
          if (source.admin) performDecision(request, approve = false, actor = null, outcome = "ACI-Blocklist")
          else store.finish(request, "ACI-Blocklist; source request pending; manual action required")
          platform.schedule(id, now)
          return
        }
        if (request.aci in room.banned) {
          store.finish(request, "gateway_banned; source request unchanged")
          platform.schedule(id, now)
          return
        }
        if (!room.applicantsCanPost || !room.botAdmin || !room.canPost) return
        val alreadyPresent = request.aci in room.members || request.aci in room.invites
        if (!session.has("owned")) store.saveSession(request.room, request.aci, session.put("owned", false).put("present", request.aci in room.members))
        val updated = request.transition(GatewayPhase.ADMITTING) {
          it.put("admissionAt", now)
          if (request.source == request.room) it.put("provisional", true)
        }
        if (!store.save(request, updated)) return
        val admitting = store.request(id)?.takeIf { it.phase == GatewayPhase.ADMITTING && it.version == request.version + 1 } ?: return
        if (!alreadyPresent) {
          val added = platform.mutate(admitting, if (request.source == request.room) GatewayMutation.APPROVE else GatewayMutation.ADD)
          // Only a confirmed bot operation owns membership. An interrupted/unknown
          // admission must never claim a member independently added by a human.
          if (added.added) store.saveSession(request.room, request.aci, store.session(request.room, request.aci).put("owned", true).put("present", added.member))
        }
        // An administrator may claim a decision while native admission is running.
        // Its newer state must survive completion of this earlier operation.
        if (store.save(admitting, admitting.transition(GatewayPhase.WAITING_FOR_MEMBERSHIP))) {
          // Confirmed membership can prompt immediately, without going to the end
          // of a room's backlog of other applicants and duplicate update jobs.
          if (request.aci in platform.group(request.room, false).members) step(id)
          else platform.schedule(id, now)
        }
      }
      GatewayPhase.ADMITTING -> {
        if (request.aci !in room.members && request.aci !in room.invites) {
          // A restart can leave no proof whether addition failed or a human
          // removed the member afterwards. Finish locally; do not turn that
          // uncertainty into a source denial or another admission attempt.
          store.finish(request, "admission_outcome_unknown; source request unchanged")
          platform.schedule(id, now)
          return
        }
        // An interrupted addition may have succeeded and then been manually undone.
        // Never blindly re-add. Wait for membership confirmation or admission timeout.
        store.save(request, request.transition(GatewayPhase.WAITING_FOR_MEMBERSHIP))
        platform.schedule(id, now)
      }
      GatewayPhase.WAITING_FOR_MEMBERSHIP -> {
        if (request.aci in room.members) {
          store.saveSession(request.room, request.aci, session.put("present", true))
          val promptId = platform.send(request, "prompt", "spam", mapOf("spamtext" to (source.spamText ?: config.defaults.spamText)))
          store.save(request, request.transition(GatewayPhase.PROMPTING) { it.put("promptId", promptId) })
          platform.schedule(id, now + 30_000)
        } else if (now >= request.data.getLong("admissionAt") + request.data.getLong("admissionMillis")) {
          timeout(request, source)
        } else {
          platform.schedule(id, request.data.getLong("admissionAt") + request.data.getLong("admissionMillis"))
        }
      }
      GatewayPhase.PROMPTING -> {
        val sent = platform.sendState(request.data.getLong("promptId"))
        if (sent.sentAt != null) {
          val timerOrigin = sent.sentAt + request.data.optLong("promptPauseMillis")
          if (!store.save(request, request.transition(GatewayPhase.WAITING_FOR_RESPONSE) {
              it.put("promptAt", timerOrigin).put("responseAfter", sent.responseAfter ?: sent.sentAt); it.remove("promptPauseMillis")
            })) return
          request.data.optLong("deferredReplyId").takeIf { it != 0L }?.let(platform::replayAnswer)
          platform.schedule(id, timerOrigin + request.data.getLong("reminderMillis"))
        } else if (sent.failed) {
          store.finish(request, "prompt_delivery_failed; source request unchanged")
          platform.schedule(id, now)
        } else platform.schedule(id, now + 30_000)
      }
      GatewayPhase.WAITING_FOR_RESPONSE -> {
        val promptAt = request.data.getLong("promptAt")
        val expiresAt = promptAt + request.data.getLong("removalMillis")
        if (now >= expiresAt) timeout(request, source) else {
          val reminderAt = promptAt + request.data.getLong("reminderMillis")
          if (now >= reminderAt && !request.data.optBoolean("reminded")) {
            platform.send(request, "reminder", "spam_reminder", quoteId = request.data.getLong("promptId"))
            store.save(request, request.transition { it.put("reminded", true) })
          }
          platform.schedule(id, if (now < reminderAt) reminderAt else expiresAt)
        }
      }
      GatewayPhase.WAITING_FOR_DECISION -> {
        if (!request.data.optBoolean("notified")) {
          val notification = platform.notify(listOf(request), request.data.getLong("replyId"))
          store.save(request, request.transition { it.put("notified", true).put("notificationId", notification) })
        }
      }
      GatewayPhase.APPROVING, GatewayPhase.DENYING -> performDecision(
        request, request.phase == GatewayPhase.APPROVING, request.data.optString("actor").ifBlank { null }, request.data.optString("outcome", "denied")
      )
      GatewayPhase.FINISHED -> Unit
    }
  }

  private fun deadlineDue(request: GatewayRequest, now: Long): Boolean = when (request.phase) {
    GatewayPhase.WAITING_FOR_RESPONSE -> now >= request.data.getLong("promptAt") +
      request.data.getLong(if (request.data.optBoolean("reminded")) "removalMillis" else "reminderMillis")
    GatewayPhase.WAITING_FOR_MEMBERSHIP -> request.data.has("admissionAt") &&
      now >= request.data.getLong("admissionAt") + request.data.getLong("admissionMillis")
    else -> false
  }

  fun departed(room: String, aci: String, manual: Boolean = false, before: Long = Long.MAX_VALUE) {
    val session = store.session(room, aci)
    if (session.optBoolean("cleaning")) return
    if (store.requests().none { it.room == room && it.aci == aci && it.active && it.data.optLong("created") <= before }) return
    if (manual || session.optLong("removingRequest") != 0L) {
      store.finishDeparture(room, aci, before)
    } else store.transaction {
      store.requests().filter { it.room == room && it.aci == aci && it.active && it.data.optLong("created") <= before }.forEach { request ->
        store.save(request, request.transition(GatewayPhase.DENYING) {
          it.put("departed", true).put("outcome", "spam_test_failed_departure")
          it.remove("actor"); it.remove("decisionFrom")
        })
      }
      if (store.requests().none { it.room == room && it.aci == aci && it.active && !it.data.optBoolean("departed") }) {
        store.saveSession(room, aci, session.put("present", false).put("owned", false))
      }
    }
  }

  fun answer(room: String, aci: String, messageId: Long, messageTime: Long) {
    val config = platform.configuration(false) ?: return
    if (config.paused) return
    // Save a reply that races the native send receipt or the PROMPTING commit.
    // Bind it only to already-issued prompts; future prompts cannot consume it.
    store.requests().filter { it.room == room && it.aci == aci && it.phase in setOf(GatewayPhase.PROMPTING, GatewayPhase.WAITING_FOR_MEMBERSHIP) &&
      config.groups[it.source]?.let { source -> !config.isPaused(source) } == true }.forEach { request ->
      val queued = platform.promptTimestamp(request)
      if (queued != null && queued <= messageTime) store.save(request, request.transition { it.put("deferredReplyId", messageId) })
    }
    // First reconcile prompt send confirmations so a fast reply cannot beat PROMPTING.
    store.requests().filter { it.room == room && it.aci == aci && it.phase == GatewayPhase.PROMPTING }.forEach { step(it.id) }
    val eligible = store.requests().filter { it.room == room && it.aci == aci && it.phase == GatewayPhase.WAITING_FOR_RESPONSE &&
      config.groups[it.source]?.let { source -> !config.isPaused(source) } == true }
    if (eligible.isEmpty()) return
    val ids = store.satisfy(room, aci, messageId, messageTime, eligible.map { it.id }.toSet())
    val requests = ids.mapNotNull(store::request)
    if (requests.isNotEmpty()) {
      val notification = platform.notify(requests, messageId)
      requests.forEach { request -> store.save(request, request.transition { it.put("notified", true).put("notificationId", notification) }) }
    }
  }

  fun decide(room: String, aci: String, number: Long?, actor: String, approve: Boolean, receivedAt: Long = platform.now()): GatewayRequest {
    val unresolved = store.requests().filter { it.room == room && it.aci == aci && it.active }
    val request = if (number == null) unresolved.singleOrNull() else unresolved.firstOrNull { it.number == number }
    requireNotNull(request) { "Select the request using its displayed number." }
    require(request.data.optLong("created", Long.MAX_VALUE) <= receivedAt) { "This command predates the current review. Use its current Gateway request mapping." }
    val config = platform.configuration(true) ?: error("Configuration is not available.")
    val source = config.groups[request.source] ?: error("Source configuration is not available.")
    if (config.isPaused(source) || source.destination != request.room) throw GatewayPausedException()
    require(actor in platform.group(request.source, true).admins) { "You are not an administrator of ${source.name}." }
    require(source.admin) { "Source administrative actions are disabled; manual action is required." }
    if (request.phase in setOf(GatewayPhase.APPROVING, GatewayPhase.DENYING)) {
      require((request.phase == GatewayPhase.APPROVING) == approve) { "A different decision is already in progress." }
      platform.schedule(request.id, platform.now())
      return request
    }
    val claimed = request.transition(if (approve) GatewayPhase.APPROVING else GatewayPhase.DENYING) {
      it.put("actor", actor).put("outcome", if (approve) "approved" else "denied").put("decisionFrom", request.phase.name)
    }
    require(store.save(request, claimed)) { "The request changed; try again." }
    platform.schedule(request.id, platform.now())
    return request
  }

  private fun performDecision(request: GatewayRequest, approve: Boolean, actor: String?, outcome: String) {
    val config = platform.configuration(true) ?: return
    val source = config.groups[request.source] ?: return
    if (config.isPaused(source) || source.destination != request.room) return
    val state = platform.group(request.source, true)
    if (actor != null && actor !in state.admins) {
      releaseDecision(request)
      platform.send(request, "permission-${request.version}", "permission_error")
      return
    }
    if (!source.admin || !state.botAdmin) {
      platform.send(request, "permission-${request.version}", "permission_error")
      return
    }
    val checked = if (approve) platform.blocklist() ?: return else null
    val blocked = checked?.contains(request.aci) == true
    val allow = approve && !blocked
    val finalOutcome = if (blocked) "ACI-Blocklist" else outcome
    val current = store.request(request.id) ?: return
    if (!current.active || current.version != request.version || current.phase != request.phase) return
    val pending = request.aci in state.requests
    val provisional = request.source == request.room && request.data.optBoolean("provisional") && request.aci in state.members
    if (pending || provisional) {
      val phase = if (allow) GatewayPhase.APPROVING else GatewayPhase.DENYING
      if (current.phase != phase && !store.save(current, current.transition(phase) { it.put("outcome", finalOutcome) })) return
      val claimed = store.request(request.id) ?: return
      if (!allow && provisional) store.saveSession(request.room, request.aci, store.session(request.room, request.aci)
        .put("removingRequest", request.id).put("removingOutcome", finalOutcome))
      if (!(allow && provisional)) platform.mutate(claimed, if (allow) GatewayMutation.APPROVE else if (provisional) GatewayMutation.REMOVE else GatewayMutation.DENY, actor)
      val latestState = platform.group(request.source, true)
      if (allow && request.aci !in latestState.members) return
      if (!allow && (request.aci in latestState.requests || (provisional && request.aci in latestState.members))) return
      if (allow && provisional) {
        // Local same-group approval has no native patch/conflict guard, so
        // revalidate after the potentially slow blocklist check as well.
        val latestConfig = platform.configuration(false) ?: return
        val latestPolicy = latestConfig.groups[request.source] ?: return
        if (latestConfig.isPaused(latestPolicy) || latestPolicy.destination != request.room || !latestState.botAdmin) return
        if (actor != null && actor !in latestState.admins) {
          releaseDecision(request)
          platform.send(request, "permission-${request.version}", "permission_error")
          return
        }
      }
      if (allow && request.source == request.room) {
        // Approval makes room membership permanent, even if separate source reviews remain.
        store.saveSession(request.room, request.aci, store.session(request.room, request.aci).put("owned", false))
      }
      store.request(request.id)?.let { store.finish(it, if (allow) "approved" else finalOutcome) }
    } else store.finish(current, if (request.aci in state.members) "externally_approved" else "externally_resolved")
    platform.schedule(request.id, platform.now())
  }

  private fun releaseDecision(request: GatewayRequest) {
    val previous = GatewayPhase.entries.firstOrNull { it.name == request.data.optString("decisionFrom") && it !in setOf(GatewayPhase.APPROVING, GatewayPhase.DENYING, GatewayPhase.FINISHED) }
      ?: when {
        request.data.has("replyId") -> GatewayPhase.WAITING_FOR_DECISION
        request.data.has("promptAt") -> GatewayPhase.WAITING_FOR_RESPONSE
        request.data.has("promptId") -> GatewayPhase.PROMPTING
        request.data.has("admissionAt") -> GatewayPhase.WAITING_FOR_MEMBERSHIP
        else -> GatewayPhase.INTAKE
      }
    if (store.save(request, request.transition(previous) { it.remove("actor"); it.remove("outcome"); it.remove("decisionFrom") })) {
      platform.schedule(request.id, platform.now())
    }
  }

  private fun timeout(request: GatewayRequest, source: GatewayGroup) {
    if (source.admin) performDecision(request, false, null, "expired")
    else store.finish(request, "expired locally; source request pending; manual action required")
    platform.schedule(request.id, platform.now())
  }

  private fun finishWork(request: GatewayRequest, source: GatewayGroupState, room: GatewayGroupState) {
    val reason = request.data.optString("reason", "finished")
    var auditId = request.data.optLong("auditId", 0)
    if (auditId == 0L) {
      auditId = platform.send(request, "outcome", "outcome", mapOf("confirmed_result" to reason))
      if (!store.save(request, request.transition { it.put("auditId", auditId) })) return
    }
    val session = store.session(request.room, request.aci)
    val others = store.requests().any { it.id != request.id && it.room == request.room && it.aci == request.aci && it.active }
    val owned = session.optBoolean("owned")
    val needsCleanup = request.source != request.room && !others && owned && (request.aci in room.members || request.aci in room.invites)
    if (needsCleanup) {
      if (!store.claimCleanup(request)) { platform.schedule(request.id, platform.now()); return }
      platform.mutate(request, GatewayMutation.CLEANUP)
      platform.schedule(request.id, platform.now())
      return
    }
    if (!others && !needsCleanup) {
      store.removeSession(request.room, request.aci)
      if (session.optBoolean("cleaning")) platform.cleaned(request.room)
    }
    val complete = platform.sendState(auditId).let { it.sentAt != null || it.failed }
    if (complete) store.compactFinished(request)
    store.request(request.id)?.let { store.prune(it, source.requests[request.aci] != request.generation, complete, !needsCleanup) }
    if (!complete) platform.schedule(request.id, platform.now() + 30_000)
  }
}
