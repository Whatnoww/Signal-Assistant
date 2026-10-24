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
import java.util.concurrent.TimeUnit

/** Commands run on GitHubAutoReplyJob's serial background queue, never on the incoming-message thread. */
object GitHubAutoReply {
  private val TAG = Log.tag(GitHubAutoReply::class)
  private const val CACHE_FILE_NAME = "github-commands.json"
  private val client = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()
  private var snapshot: CommandsSnapshot? = null

  fun isCommandCandidate(input: String?): Boolean = isConfigured() && CommandDocument.isCommandCandidate(input)

  private fun isConfigured(): Boolean = BuildConfig.RESPONSE_XML.isNotBlank() && BuildConfig.GITHUB_TOKEN_COMMANDS.isNotBlank()

  private fun repository() = GitHubCommandsRepository(BuildConfig.RESPONSE_XML, BuildConfig.GITHUB_TOKEN_COMMANDS, client)

  @Synchronized
  fun refreshFromGithub(context: Context): Boolean {
    if (!isConfigured()) return false
    if (snapshot == null) snapshot = readCache(context)
    return try {
      val previous = snapshot
      val updated = repository().fetch(previous)
      snapshot = updated
      if (updated != previous) writeCache(context, updated)
      updated != previous
    } catch (e: Exception) {
      Log.w(TAG, "Unable to refresh commands; retaining cache (${failureDetail(e)}).")
      false
    }
  }

  /** Compatibility API for callers that only need plain text. */
  @Synchronized
  fun getResponseFor(context: Context, input: String): String? = getReplyFor(context, input)?.text

  @Synchronized
  internal fun getReplyFor(context: Context, input: String): CommandReply? {
    if (!isCommandCandidate(input)) return null
    refreshFromGithub(context)
    val cached = snapshot ?: run {
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
          snapshot = updated
          writeCache(context, updated)
        }
      } catch (e: Exception) {
        // A repository outage or conflict must not prevent sending the current links.
        Log.w(TAG, "Unable to publish refreshed group links (${failureDetail(e)}).")
      }
    }
    return reply
  }

  private fun readCache(context: Context): CommandsSnapshot? {
    return try {
      val json = JSONObject(cacheFile(context).openRead().bufferedReader(Charsets.UTF_8).use { it.readText() })
      if (json.getString("source") != BuildConfig.RESPONSE_XML) return null
      val xml = json.getString("xml")
      CommandDocument.parse(xml)
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
