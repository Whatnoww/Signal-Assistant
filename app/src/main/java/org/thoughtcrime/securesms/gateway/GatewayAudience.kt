/* SPDX-License-Identifier: AGPL-3.0-only */
package org.thoughtcrime.securesms.gateway

import android.app.Application
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.signal.core.models.ServiceId.ACI
import org.signal.core.models.recipients.RecipientId
import org.thoughtcrime.securesms.BuildConfig
import org.thoughtcrime.securesms.database.GroupReceiptTable
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.github.AdminCommands
import org.thoughtcrime.securesms.github.GitHubAutoReply
import org.thoughtcrime.securesms.recipients.Recipient

/** Durable audience cap; an empty restricted audience must never become a broadcast. */
internal data class GatewayAudience(val acis: Set<String>, val scopes: Set<String>, val rootTimestamp: Long = 0) {
  fun encode() = PREFIX + JSONObject().put("acis", JSONArray(acis.toList())).put("scopes", JSONArray(scopes.toList())).put("root", rootTimestamp).toString()

  companion object {
    const val PREFIX = "audience:"
    private fun strings(json: JSONObject, key: String) = json.getJSONArray(key).let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
    private fun decode(value: String) = JSONObject(value.removePrefix(PREFIX)).let { GatewayAudience(strings(it, "acis"), strings(it, "scopes"), it.optLong("root")) }
    private fun store(context: Context) = GatewayDatabase.get(context.applicationContext as Application)

    fun reserve(context: Context, audience: GatewayAudience, timestamp: Long, invocation: Long) {
      val store = store(context)
      // Keep audience metadata only while its native message exists or is pending insertion.
      store.pendingOutgoing().filter { store.outgoingRequest(it.third)?.second?.startsWith(PREFIX) == true && it.second != 0L }
        .filter { SignalDatabase.messages.getMessageRecordOrNull(it.second) == null }.forEach { store.forgetTimestamp(it.third) }
      store.reserveOutgoing("audience:$timestamp", 0, audience.encode(), timestamp, invocation)
      store.setAudience(timestamp, audience.encode())
    }

    @JvmStatic fun metadata(context: Context, messageId: Long): String {
      if (!context.getDatabasePath(GatewayDatabase.NAME).exists()) return ""
      val message = SignalDatabase.messages.getMessageRecordOrNull(messageId) ?: return ""
      return store(context).outgoingRequest(message.dateSent)?.second?.takeIf { it.startsWith(PREFIX) }.orEmpty()
    }

    @JvmStatic @JvmOverloads fun prepareReceipts(context: Context, messageId: Long, metadata: String = metadata(context, messageId)) {
      if (metadata.isEmpty()) return
      val audience = decode(metadata)
      val ids = audience.acis.map { Recipient.externalPush(ACI.parseOrThrow(it)).id }
      SignalDatabase.groupReceipts.deleteRowsForMessage(messageId)
      SignalDatabase.groupReceipts.insert(ids, messageId, GroupReceiptTable.STATUS_UNDELIVERED, System.currentTimeMillis())
      val message = SignalDatabase.messages.getMessageRecord(messageId)
      store(context).bindMessage(message.dateSent, messageId)
    }

    @JvmStatic fun targets(context: Context, metadata: String, recipient: Recipient): Set<RecipientId> {
      val audience = decode(metadata)
      val config = GatewayRuntime.configuration(context) ?: return emptySet()
      if (config.paused) return emptySet()
      val platform = SignalGatewayPlatform(context, store(context))
      fun current(id: String) = try { platform.group(id, true) } catch (e: org.signal.core.util.groups.GroupChangeBusyException) {
        throw org.thoughtcrime.securesms.transport.RetryLaterException(e, 1000)
      }
      val state = current(recipient.requireGroupId().toString())
      if (!state.active || !state.canPost) return emptySet()
      if (audience.scopes.any { it.startsWith("review-") }) {
        store(context).requests().filter { it.active && it.room == state.id }.map { it.source }.distinct().forEach { current(it) }
      }
      val document = GitHubAutoReply.configurationDocument(context) ?: return emptySet()
      return audience.acis.filter { aci ->
        aci != Recipient.self().requireAci().toString() && aci in state.members && audience.scopes.isNotEmpty() && when {
          audience.scopes == setOf("owner") -> AdminCommands.isAuthorized(BuildConfig.ADMIN_ACI, aci)
          audience.scopes == setOf("scan-admin") -> aci in state.admins
          else -> GatewayController.helpScopes(context, recipient, aci, config, document, platform).containsAll(audience.scopes)
        }
      }.map { Recipient.externalPush(ACI.parseOrThrow(it)).id }.toSet()
    }

    @JvmStatic fun editTarget(metadata: String, fallback: Long): Long =
      if (metadata.isEmpty()) fallback else decode(metadata).rootTimestamp.takeIf { it > 0 } ?: fallback

    fun scan(group: org.thoughtcrime.securesms.database.model.GroupRecord) = GatewayAudience(
      group.members.map(Recipient::resolved).filter { group.isAdmin(it) && !it.isSelf }.mapNotNull { it.aci.orElse(null)?.toString() }.toSet(), setOf("scan-admin")
    )
    fun forMessage(context: Context, messageId: Long): GatewayAudience? = metadata(context, messageId).takeIf { it.isNotEmpty() }?.let(::decode)
  }
}
