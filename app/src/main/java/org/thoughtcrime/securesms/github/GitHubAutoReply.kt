/*
 * Whatnoww added file
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.github

import android.content.Context
import android.util.AtomicFile
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.BuildConfig
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Commands run on GitHubAutoReplyJob's serial background queue, never on the incoming-message thread. */
object GitHubAutoReply {
  private val TAG = Log.tag(GitHubAutoReply::class)
  private const val CACHE_FILE_NAME = "github-commands.json"
  private val client = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()
  private val cache = CommandsCache()
  private val refreshLock = Any()
  private val cacheLoadLock = Any()
  private var cacheLoaded = false
  private val renderedReplies = ConcurrentHashMap<String, Pair<String, CommandReply>>()
  private val refreshingInputs = ConcurrentHashMap<String, Boolean>()

  fun isCommandCandidate(input: String?): Boolean = isAdminCommand(input) ||
    (input != null && org.thoughtcrime.securesms.gateway.GatewayCommand.candidate(input)) ||
    (isConfigured() && CommandDocument.isCommandCandidate(input))

  fun isAdminCommand(input: String?): Boolean = AdminCommands.isCandidate(input)

  internal fun getAdminReply(context: Context, input: String, senderAci: String?, groupId: String?): CommandReply = synchronized(refreshLock) {
    AdminCommands(
      adminAci = BuildConfig.ADMIN_ACI,
      configured = isConfigured(),
      repository = ::repository,
      currentGroupLink = { id -> CommandGroupLinkResolver(context).currentGroupLink(id) },
      saveSnapshot = { updated ->
        loadCache(context)
        cache.replace(updated)
        renderedReplies.clear()
        org.thoughtcrime.securesms.gateway.GatewayRuntime.configurationAccepted(context)
        try {
          writeCache(context, updated)
        } catch (e: Exception) {
          Log.w(TAG, "Unable to persist commands after admin operation (${e.javaClass.simpleName}).")
        }
      }
    ).reply(input, senderAci, groupId)
  }

  private fun isConfigured(): Boolean = BuildConfig.RESPONSE_XML.isNotBlank() && BuildConfig.GITHUB_TOKEN_COMMANDS.isNotBlank()

  private fun repository() = GitHubCommandsRepository(BuildConfig.RESPONSE_XML, BuildConfig.GITHUB_TOKEN_COMMANDS, client)

  fun refreshFromGithub(context: Context): Boolean = synchronized(refreshLock) { refresh(context, force = true) }
  internal fun refreshConfigurationIfDue(context: Context): Boolean = synchronized(refreshLock) { refresh(context) }

  private fun refresh(context: Context, force: Boolean = false): Boolean {
    if (!isConfigured()) return false
    loadCache(context)
    return try {
      val changed = cache.refresh(force) { previous -> repository().fetch(previous) }
      if (changed) {
        org.thoughtcrime.securesms.gateway.GatewayRuntime.configurationAccepted(context)
        writeCache(context, cache.snapshot!!)
      }
      changed
    } catch (e: Exception) {
      Log.w(TAG, "Unable to refresh commands; retaining cache (${failureDetail(e)}).")
      false
    }
  }

  /** Keep the last good response, but force the next background check to revalidate it. */
  fun invalidateCache() = cache.invalidate()

  /** Gateway shares validated snapshots and conflict-safe publication with ordinary commands. */
  internal fun configurationSnapshot(context: Context, online: Boolean = false): CommandsSnapshot? {
    if (online) synchronized(refreshLock) { refresh(context, force = true) } else loadCache(context)
    return cache.snapshot
  }

  internal fun configurationDocument(context: Context, online: Boolean = false): CommandDocument? =
    configurationSnapshot(context, online)?.let { CommandDocument.parse(it.xml) }

  internal fun updateConfiguration(context: Context, edit: (CommandDocument) -> Boolean) = synchronized(refreshLock) {
    val updated = repository().update("Apply Gateway configuration", edit)
    loadCache(context)
    cache.replace(updated)
    renderedReplies.clear()
    org.thoughtcrime.securesms.gateway.GatewayRuntime.configurationAccepted(context)
    try { writeCache(context, updated) } catch (e: Exception) {
      Log.w(TAG, "Gateway configuration was published but its disk cache could not be saved (${e.javaClass.simpleName}).")
    }
  }

  internal fun needsRefresh(input: String): Boolean = refreshingInputs.containsKey(input) || cache.needsRefresh() || renderedReplies[input]?.first != cache.snapshot?.sha

  /** Fast path: no GitHub or Signal network requests, including when another job is refreshing. */
  internal fun getCachedReplyFor(context: Context, input: String): CommandReply? {
    if (isAdminCommand(input) || !isCommandCandidate(input)) return null
    loadCache(context)
    val saved = cache.snapshot ?: return null
    return try {
      val configured = CommandDocument.parse(saved.xml).render(input) { CommandLinkState() } ?: return null
      renderedReplies[input]?.takeIf { it.first == saved.sha }?.second ?: configured
    } catch (e: Exception) {
      Log.w(TAG, "Unable to render cached command response (${e.javaClass.simpleName}).")
      null
    }
  }

  private fun loadCache(context: Context) = synchronized(cacheLoadLock) {
    if (!cacheLoaded) {
      cache.restore(readCache(context))
      cacheLoaded = true
    }
  }

  /** Compatibility API for callers that only need plain text. */
  fun getResponseFor(context: Context, input: String): String? = getReplyFor(context, input)?.text

  internal fun getReplyFor(context: Context, input: String): CommandReply? = synchronized(refreshLock) {
    if (isAdminCommand(input) || !isCommandCandidate(input)) return null
    if (!needsRefresh(input)) return getCachedReplyFor(context, input)
    refreshingInputs[input] = true
    try {
      refresh(context)
      val cached = cache.snapshot ?: run {
        Log.w(TAG, "No downloaded or cached commands XML is available; skipping reply.")
        return null
      }
      val resolver = CommandGroupLinkResolver(context)
      val reply = try {
        CommandDocument.parse(cached.xml).render(input, resolver::resolve)
      } catch (e: Exception) {
        Log.w(TAG, "Unable to render command response (${e.javaClass.simpleName}).")
        return null
      } ?: return null

      if (reply.linkUpdates.isNotEmpty()) {
        try {
          repository().publish(reply.linkUpdates)?.let { updated ->
            cache.replace(updated)
            writeCache(context, updated)
          }
        } catch (e: Exception) {
          // A repository outage or conflict must not prevent sending the current links.
          Log.w(TAG, "Unable to publish refreshed group links (${failureDetail(e)}).")
        }
      }
      renderedReplies[input] = (cache.snapshot?.sha ?: cached.sha) to reply
      return reply
    } finally {
      refreshingInputs.remove(input)
    }
  }

  private fun readCache(context: Context): CommandsSnapshot? {
    return try {
      val json = JSONObject(cacheFile(context).openRead().bufferedReader(Charsets.UTF_8).use { it.readText() })
      if (json.getString("source") != BuildConfig.RESPONSE_XML) return null
      val xml = json.getString("xml")
      org.thoughtcrime.securesms.gateway.GatewayConfiguration.parse(CommandDocument.parse(xml))
      CommandsSnapshot(xml, json.getString("sha"), json.optString("etag").takeIf { it.isNotBlank() })
    } catch (e: Exception) {
      null
    }
  }

  private fun writeCache(context: Context, updated: CommandsSnapshot) {
    val file = cacheFile(context)
    val output = file.startWrite()
    try {
      val json = JSONObject().put("source", BuildConfig.RESPONSE_XML).put("xml", updated.xml).put("sha", updated.sha).put("etag", updated.etag ?: "")
      output.write(json.toString().toByteArray(Charsets.UTF_8))
      file.finishWrite(output)
    } catch (e: Exception) {
      file.failWrite(output)
      throw e
    }
  }

  private fun cacheFile(context: Context) = AtomicFile(File(context.filesDir, CACHE_FILE_NAME))

  // Only our controlled HTTP error text is logged; arbitrary exceptions may include private URLs.
  private fun failureDetail(e: Exception): String = if (e is GitHubCommandsHttpException) e.message.orEmpty() else e.javaClass.simpleName
}
