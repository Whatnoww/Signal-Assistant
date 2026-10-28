/* SPDX-License-Identifier: AGPL-3.0-only */
package org.thoughtcrime.securesms.gateway

import android.app.Application
import android.content.ContentValues
import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SQLiteOpenHelper
import org.json.JSONObject
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.crypto.DatabaseSecretProvider
import org.thoughtcrime.securesms.database.SqlCipherDatabaseHook
import org.thoughtcrime.securesms.database.SqlCipherErrorHandler
import org.thoughtcrime.securesms.database.SqlCipherLibraryLoader

internal enum class GatewayPhase { INTAKE, ADMITTING, WAITING_FOR_MEMBERSHIP, PROMPTING, WAITING_FOR_RESPONSE, WAITING_FOR_DECISION, APPROVING, DENYING, FINISHED }

internal data class GatewayRequest(
  val id: Long,
  val source: String,
  val room: String,
  val aci: String,
  val generation: Long,
  val phase: GatewayPhase,
  val version: Long,
  val data: JSONObject
) {
  val active: Boolean get() = phase != GatewayPhase.FINISHED
  // Existing active reviews retain the number already posted before this field existed.
  val number: Long get() = data.optLong("number", id)
  fun transition(phase: GatewayPhase = this.phase, edit: (JSONObject) -> Unit = {}): GatewayRequest =
    copy(phase = phase, data = JSONObject(data.toString()).also(edit))
}

/** Own physical database and migrations; never joins or changes Signal's database schema. */
internal class GatewayDatabase(application: Application) : SQLiteOpenHelper(
  application, NAME, DatabaseSecretProvider.getOrCreateDatabaseSecret(application).asString(), null, 1, 0,
  SqlCipherErrorHandler(application, NAME), SqlCipherDatabaseHook(), true
) {
  override fun onCreate(db: SQLiteDatabase) = GatewayStore.createSchema(db)
  override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) { error("Unsupported Gateway schema upgrade: $oldVersion -> $newVersion") }
  companion object {
    const val NAME = "signal-assistant-gateway.db"
    @Volatile private var instance: GatewayStore? = null
    fun get(application: Application): GatewayStore = instance ?: synchronized(this) {
      instance ?: run {
        SqlCipherLibraryLoader.load()
        GatewayStore(GatewayDatabase(application).writableDatabase).also { instance = it }
      }
    }
  }
}

/** All read/modify/write decisions use SQL transactions, not read-then-write thread timing. */
internal class GatewayStore(private val db: SupportSQLiteDatabase) {
  @Synchronized
  fun <T> transaction(block: () -> T): T {
    db.beginTransaction()
    try {
      val result = block()
      db.setTransactionSuccessful()
      return result
    } finally { db.endTransaction() }
  }

  fun requests(): List<GatewayRequest> = db.query("SELECT * FROM request ORDER BY id").use { cursor ->
    buildList {
      while (cursor.moveToNext()) add(readRequest(cursor))
    }
  }

  private fun readRequest(cursor: Cursor) = GatewayRequest(
    cursor.getLong(0), cursor.getString(1), cursor.getString(2), cursor.getString(3), cursor.getLong(4),
    GatewayPhase.valueOf(cursor.getString(5)), cursor.getLong(6), JSONObject(cursor.getString(7))
  )

  fun request(id: Long): GatewayRequest? = db.query("SELECT * FROM request WHERE id=?", arrayOf(id)).use {
    if (it.moveToFirst()) readRequest(it) else null
  }

  fun intake(source: GatewayGroup, aci: String, generation: Long, defaults: GatewayDefaults, now: Long): GatewayRequest = transaction {
    requests().firstOrNull { it.source == source.id && it.aci == aci && it.generation == generation } ?: run {
      val active = requests().filter { it.room == source.destination && it.aci == aci && it.active }
      val session = session(source.destination, aci)
      val previousNumber = if (active.isEmpty()) 0L else maxOf(session.optLong("lastNumber"), active.maxOf { it.number })
      val number = Math.addExact(previousNumber, 1L)
      val data = JSONObject().put("created", now).put("admissionMillis", source.admissionMillis(defaults))
        .put("reminderMillis", source.reminderMillis(defaults)).put("removalMillis", source.removalMillis(defaults)).put("number", number)
      val values = ContentValues().apply {
        put("source", source.id); put("room", source.destination); put("aci", aci); put("generation", generation)
        put("phase", GatewayPhase.INTAKE.name); put("version", 0); put("data", data.toString())
      }
      val id = db.insert("request", 0, values)
      writeSession(source.destination, aci, session.put("lastNumber", number))
      requireNotNull(request(id))
    }
  }
  fun intakeUnlessCleaning(source: GatewayGroup, aci: String, generation: Long, defaults: GatewayDefaults, now: Long): GatewayRequest? = transaction {
    if (session(source.destination, aci).optBoolean("cleaning")) null else intake(source, aci, generation, defaults, now)
  }

  /** False means another event won: callers must reload, never apply the stale action. */
  fun save(expected: GatewayRequest, updated: GatewayRequest): Boolean {
    require(expected.id == updated.id)
    val changed = db.update("request", 0, ContentValues().apply {
      put("phase", updated.phase.name); put("version", expected.version + 1); put("data", updated.data.toString())
    }, "id=? AND version=? AND phase=?", arrayOf(expected.id, expected.version, expected.phase.name)) == 1
    if (changed && expected.phase != updated.phase) Log.i("Gateway", "Review ${expected.id} (${expected.aci.take(8)}, source …${expected.source.takeLast(8)}): ${expected.phase} -> ${updated.phase}")
    return changed
  }

  fun finish(expected: GatewayRequest, reason: String): Boolean = expected.active && save(expected, expected.transition(GatewayPhase.FINISHED) {
    // Drop prompt/reply bookkeeping and timers. Retain only suppression and pending audit/cleanup.
    val audit = it.optLong("auditId", 0)
    val keys = it.keys().asSequence().toList()
    keys.forEach(it::remove)
    it.put("reason", reason)
    if (audit != 0L) it.put("auditId", audit)
  })

  fun prune(request: GatewayRequest, nativeRequestGone: Boolean, outgoingComplete: Boolean, cleanupComplete: Boolean) {
    if (!request.active && nativeRequestGone && outgoingComplete && cleanupComplete) {
      transaction {
        if (db.delete("request", "id=? AND version=?", arrayOf(request.id, request.version)) == 1) {
          requests().firstOrNull { it.active && it.room == request.room && it.aci == request.aci }?.let {
            // A shared notification's idempotence key lives until the whole batch finishes.
            db.execSQL("UPDATE outgoing SET request_id=? WHERE request_id=? AND kind='administrator_notification'", arrayOf(it.id, request.id))
          }
          db.delete("outgoing", "request_id=?", arrayOf(request.id))
        }
      }
    }
  }

  fun setPaused(request: GatewayRequest, paused: Boolean, now: Long): Boolean {
    val since = request.data.optLong("pausedAt", 0)
    if ((since > 0) == paused) return false
    return save(request, request.transition { data ->
      if (paused) data.put("pausedAt", now) else {
        val duration = maxOf(0, now - since)
        listOf("promptAt", "admissionAt").forEach { key -> if (data.has(key)) data.put(key, data.getLong(key) + duration) }
        if (!data.has("promptAt") && data.has("promptId")) {
          val confirmations = outgoingEvent(data.getLong("promptId")).map { confirmedAt(it.second) }
          if (confirmations.isNotEmpty() && confirmations.all { it != null }) {
            val acknowledged = confirmations.maxOf { it!! }
            val frozen = maxOf(0, now - maxOf(since, acknowledged))
            data.put("promptPauseMillis", data.optLong("promptPauseMillis") + frozen)
          }
        }
        data.remove("pausedAt")
      }
    })
  }

  fun satisfy(room: String, aci: String, messageId: Long, messageTime: Long, allowed: Set<Long>? = null): List<Long> = transaction {
    requests().filter {
      it.room == room && it.aci == aci && (allowed == null || it.id in allowed) && it.phase == GatewayPhase.WAITING_FOR_RESPONSE &&
        it.data.optLong("pausedAt", 0) == 0L && it.data.optLong("responseAfter", it.data.optLong("promptAt", Long.MAX_VALUE)) <= messageTime
    }.mapNotNull { request ->
      if (save(request, request.transition(GatewayPhase.WAITING_FOR_DECISION) { it.put("replyId", messageId); it.remove("deferredReplyId") })) request.id else null
    }
  }

  fun finishDeparture(room: String, aci: String) = transaction {
    val session = session(room, aci)
    if (session.optBoolean("cleaning")) return@transaction
    val removing = session.optLong("removingRequest")
    requests().filter { it.room == room && it.aci == aci && it.active }.forEach {
      val reason = when {
        it.id == removing && it.phase == GatewayPhase.DENYING -> session.optString("removingOutcome", "denied")
        removing != 0L -> "gateway_departure_after_source_decision; source request unchanged"
        else -> "unplanned_gateway_departure"
      }
      finish(it, reason)
    }
    saveSession(room, aci, session.put("owned", false).put("present", false))
  }

  fun claimCleanup(request: GatewayRequest): Boolean = transaction {
    if (request.active || requests().any { it.room == request.room && it.aci == request.aci && it.active }) return@transaction false
    val session = session(request.room, request.aci)
    if (!session.optBoolean("owned")) return@transaction false
    saveSession(request.room, request.aci, session.put("cleaning", true))
    true
  }

  fun session(room: String, aci: String): JSONObject = db.query("SELECT data FROM session WHERE room=? AND aci=?", arrayOf(room, aci)).use {
    if (it.moveToFirst()) JSONObject(it.getString(0)) else JSONObject()
  }
  fun saveSession(room: String, aci: String, data: JSONObject) = transaction {
    // Membership work can carry an older session snapshot across a network call.
    // Only intake allocates numbers; such a snapshot must never roll back its counter.
    val latest = session(room, aci)
    val merged = JSONObject(data.toString())
    if (latest.has("lastNumber")) merged.put("lastNumber", latest.getLong("lastNumber")) else merged.remove("lastNumber")
    writeSession(room, aci, merged)
  }
  private fun writeSession(room: String, aci: String, data: JSONObject) {
    db.insert("session", 5, ContentValues().apply { put("room", room); put("aci", aci); put("data", data.toString()) })
  }
  fun removeSession(room: String, aci: String) = transaction {
    if (requests().none { it.room == room && it.aci == aci && it.active }) db.delete("session", "room=? AND aci=?", arrayOf(room, aci))
  }

  /** A durable reservation prevents concurrent invocations from bypassing cooldowns. */
  fun reserveCooldown(key: String, invocation: Long, now: Long, duration: Long): Long = transaction {
    db.delete("cooldown", "until_at<=? AND consumed=1", arrayOf(now))
    // An abandoned invocation cannot leave a permanent reservation after its jobs expire.
    db.delete("cooldown", "until_at+86400000<=? AND consumed=0", arrayOf(now))
    val current = db.query("SELECT invocation,until_at FROM cooldown WHERE key=?", arrayOf(key)).use {
      if (it.moveToFirst()) it.getLong(0) to it.getLong(1) else null
    }
    if (current != null && current.first != invocation) maxOf(1, current.second - now) else {
      if (duration > 0 && current == null) db.insert("cooldown", 0, ContentValues().apply {
        put("key", key); put("invocation", invocation); put("until_at", now + duration); put("duration", duration); put("consumed", 0)
      })
      0L
    }
  }
  fun consumeCooldown(invocation: Long, now: Long? = null) {
    if (now == null) db.execSQL("UPDATE cooldown SET consumed=1 WHERE invocation=?", arrayOf(invocation))
    else db.execSQL("UPDATE cooldown SET consumed=1,until_at=?+duration WHERE invocation=? AND consumed=0", arrayOf(now, invocation))
  }
  fun releaseCooldown(invocation: Long) { db.delete("cooldown", "invocation=? AND consumed=0", arrayOf(invocation)) }
  fun releaseIfIdle(invocation: Long) {
    val pending = db.query("SELECT 1 FROM outgoing WHERE invocation=? LIMIT 1", arrayOf(invocation)).use { it.moveToFirst() }
    if (!pending) releaseCooldown(invocation)
  }

  /** Pending send metadata only. Successful/failed sends remove it after transport settles. */
  fun preserveContact(recipient: Long, sentAt: Long) {
    db.insert("private_send", 4, ContentValues().apply { put("recipient", recipient); put("sent_at", sentAt) })
  }
  fun preservesContact(recipient: Long, sentAt: Long): Boolean = db.query("SELECT 1 FROM private_send WHERE recipient=? AND sent_at=?", arrayOf(recipient, sentAt)).use { it.moveToFirst() }
  fun forgetPrivateSend(recipient: Long, sentAt: Long) { db.delete("private_send", "recipient=? AND sent_at=?", arrayOf(recipient, sentAt)) }

  fun outgoing(key: String): Pair<Long, Long>? = db.query("SELECT message_id,sent_at FROM outgoing WHERE key=?", arrayOf(key)).use {
    if (it.moveToFirst()) it.getLong(0) to it.getLong(1) else null
  }
  fun reserveOutgoing(key: String, requestId: Long, kind: String, sentAt: Long, invocation: Long = 0) {
    db.insert("outgoing", 4, ContentValues().apply { put("key", key); put("request_id", requestId); put("kind", kind); put("sent_at", sentAt); put("message_id", 0); put("confirmed_at", 0); put("invocation", invocation) })
  }
  fun outgoingInserted(key: String, messageId: Long) { db.execSQL("UPDATE outgoing SET message_id=? WHERE key=?", arrayOf(messageId, key)) }
  fun pendingOutgoing(): List<Triple<String, Long, Long>> = db.query("SELECT key,message_id,sent_at FROM outgoing").use { cursor ->
    buildList { while (cursor.moveToNext()) add(Triple(cursor.getString(0), cursor.getLong(1), cursor.getLong(2))) }
  }
  fun compactFinished(request: GatewayRequest) = transaction {
    if (request.active) return@transaction
    requests().firstOrNull { it.active && it.room == request.room && it.aci == request.aci }?.let {
      db.execSQL("UPDATE outgoing SET request_id=? WHERE request_id=? AND kind='administrator_notification'", arrayOf(it.id, request.id))
    }
    db.delete("outgoing", "request_id=? AND confirmed_at>0", arrayOf(request.id))
    // Pending native sends can outlive this review. Preserve a cancellation
    // marker until transport consumes it, even after the request is pruned.
    db.execSQL("UPDATE outgoing SET request_id=0,kind='cancelled-review' WHERE request_id=? AND kind IN ('spam','spam_reminder','administrator_notification')", arrayOf(request.id))
  }
  fun forgetOutgoing(key: String) { db.delete("outgoing", "key=?", arrayOf(key)) }
  fun outgoingRequest(sentAt: Long): Pair<Long, String>? = db.query("SELECT request_id,kind FROM outgoing WHERE sent_at=?", arrayOf(sentAt)).use {
    if (it.moveToFirst()) it.getLong(0) to it.getString(1) else null
  }
  fun outgoingMessage(sentAt: Long): Long = db.query("SELECT message_id FROM outgoing WHERE sent_at=?", arrayOf(sentAt)).use { if (it.moveToFirst()) it.getLong(0) else 0 }
  fun bindRecipient(sentAt: Long, recipient: Long) { db.execSQL("UPDATE outgoing SET recipient=? WHERE sent_at=?", arrayOf(recipient, sentAt)) }
  fun matchesOutgoing(sentAt: Long, recipient: Long, messageId: Long): Boolean = db.query("SELECT recipient,message_id FROM outgoing WHERE sent_at=?", arrayOf(sentAt)).use {
    it.moveToFirst() && (recipient == 0L || it.getLong(0) == 0L || it.getLong(0) == recipient) && (messageId == 0L || it.getLong(1) == 0L || it.getLong(1) == messageId)
  }
  fun outgoingEvent(messageId: Long): List<Triple<String, Long, Long>> {
    val all = pendingOutgoing()
    val prefix = all.firstOrNull { it.second == messageId }?.first?.substringBeforeLast(':') ?: return emptyList()
    return all.filter { it.first.substringBeforeLast(':') == prefix }.sortedBy { it.first.substringAfterLast(':').toIntOrNull() ?: 0 }
  }
  fun confirmedAt(messageId: Long): Long? = db.query("SELECT confirmed_at FROM outgoing WHERE message_id=?", arrayOf(messageId)).use {
    if (it.moveToFirst()) it.getLong(0).takeIf { time -> time > 0 } else null
  }
  fun recordAck(timestamp: Long, now: Long) { db.execSQL("UPDATE outgoing SET confirmed_at=? WHERE sent_at=? AND confirmed_at=0", arrayOf(now, timestamp)) }
  fun confirmOutgoing(timestamp: Long, now: Long): Long? = transaction {
    val record = db.query("SELECT request_id,invocation FROM outgoing WHERE sent_at=?", arrayOf(timestamp)).use {
      if (it.moveToFirst()) it.getLong(0) to it.getLong(1) else null
    } ?: return@transaction null
    db.execSQL("UPDATE outgoing SET confirmed_at=? WHERE sent_at=? AND confirmed_at=0", arrayOf(now, timestamp))
    if (record.second != 0L) consumeCooldown(record.second, now)
    if (record.first == 0L) db.delete("outgoing", "sent_at=?", arrayOf(timestamp))
    record.first.takeIf { it != 0L }
  }
  fun forgetTimestamp(timestamp: Long) {
    db.delete("outgoing", "sent_at=?", arrayOf(timestamp))
    db.delete("private_send", "sent_at=?", arrayOf(timestamp))
  }
  fun outgoingInvocation(timestamp: Long): Long = db.query("SELECT invocation FROM outgoing WHERE sent_at=?", arrayOf(timestamp)).use { if (it.moveToFirst()) it.getLong(0) else 0 }
  fun invocationSent(invocation: Long): Boolean = db.query("SELECT 1 FROM cooldown WHERE invocation=? AND consumed=1", arrayOf(invocation)).use { it.moveToFirst() }
  fun trackMediaDelete(id: Long) { db.insert("media_delete", 4, ContentValues().apply { put("message_id", id) }) }
  fun mediaDeleteTracked(id: Long): Boolean = db.query("SELECT 1 FROM media_delete WHERE message_id=?", arrayOf(id)).use { it.moveToFirst() }
  fun forgetMediaDelete(id: Long) { db.delete("media_delete", "message_id=?", arrayOf(id)) }
  fun claimScanRefresh(id: Long): Boolean = transaction {
    if (db.query("SELECT 1 FROM scan_refresh WHERE invocation=?", arrayOf(id)).use { it.moveToFirst() }) false else {
      db.insert("scan_refresh", 0, ContentValues().apply { put("invocation", id) })
      true
    }
  }
  fun finishScanRefresh(id: Long) { db.delete("scan_refresh", "invocation=?", arrayOf(id)) }
  fun clearExpired(now: Long) {
    db.delete("cooldown", "until_at<=? AND consumed=1 OR until_at+86400000<=? AND consumed=0", arrayOf(now, now))
  }

  companion object {
    fun createSchema(db: SupportSQLiteDatabase) {
      db.execSQL("CREATE TABLE request(id INTEGER PRIMARY KEY AUTOINCREMENT, source TEXT NOT NULL, room TEXT NOT NULL, aci TEXT NOT NULL, generation INTEGER NOT NULL, phase TEXT NOT NULL, version INTEGER NOT NULL, data TEXT NOT NULL, UNIQUE(source,aci,generation))")
      db.execSQL("CREATE INDEX request_room_aci ON request(room,aci,phase)")
      db.execSQL("CREATE TABLE session(room TEXT NOT NULL, aci TEXT NOT NULL, data TEXT NOT NULL, PRIMARY KEY(room,aci))")
      db.execSQL("CREATE TABLE cooldown(key TEXT PRIMARY KEY, invocation INTEGER NOT NULL, until_at INTEGER NOT NULL, duration INTEGER NOT NULL, consumed INTEGER NOT NULL)")
      db.execSQL("CREATE TABLE private_send(recipient INTEGER NOT NULL, sent_at INTEGER NOT NULL, PRIMARY KEY(recipient,sent_at))")
      db.execSQL("CREATE TABLE outgoing(key TEXT PRIMARY KEY, request_id INTEGER NOT NULL, kind TEXT NOT NULL, sent_at INTEGER NOT NULL UNIQUE, message_id INTEGER NOT NULL, confirmed_at INTEGER NOT NULL, invocation INTEGER NOT NULL, recipient INTEGER NOT NULL DEFAULT 0)")
      db.execSQL("CREATE TABLE media_delete(message_id INTEGER PRIMARY KEY)")
      db.execSQL("CREATE TABLE scan_refresh(invocation INTEGER PRIMARY KEY)")
    }
  }
}
