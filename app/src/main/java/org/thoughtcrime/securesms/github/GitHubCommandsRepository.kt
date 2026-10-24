/*
 * Whatnoww added file
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.github

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.Base64

internal data class CommandsSnapshot(val xml: String, val sha: String, val etag: String?)
internal class GitHubCommandsHttpException(val statusCode: Int, operation: String) : IOException("$operation failed: HTTP $statusCode")

/** GitHub Contents API reads private files and updates them with optimistic concurrency via SHA. */
internal class GitHubCommandsRepository(
  sourceUrl: String,
  private val token: String,
  private val client: OkHttpClient
) {
  private val url: HttpUrl = contentsUrl(sourceUrl)

  fun fetch(previous: CommandsSnapshot? = null): CommandsSnapshot {
    val request = request().apply {
      previous?.etag?.let { header("If-None-Match", it) }
    }.build()
    client.newCall(request).execute().use { response ->
      if (response.code == 304 && previous != null) return previous
      if (!response.isSuccessful) throw GitHubCommandsHttpException(response.code, "Commands download")
      val json = JSONObject(response.body.string())
      require(json.getString("encoding") == "base64") { "Expected a base64-encoded commands file" }
      val xml = Base64.getMimeDecoder().decode(json.getString("content")).toString(Charsets.UTF_8)
      CommandDocument.parse(xml) // Never replace a good cache with an invalid download.
      return CommandsSnapshot(xml, json.getString("sha"), response.header("ETag"))
    }
  }

  fun publish(updates: List<CommandLinkUpdate>): CommandsSnapshot? {
    if (updates.isEmpty()) return null
    repeat(2) {
      // Fetch again before writing, preserving edits made since the command was rendered.
      val latest = fetch()
      val document = CommandDocument.parse(latest.xml)
      if (!document.applyLinkUpdates(updates)) return latest
      val xml = document.toXml()
      val json = JSONObject()
        .put("message", "Refresh Signal group invite links")
        .put("sha", latest.sha)
        .put("content", Base64.getEncoder().encodeToString(xml.toByteArray(Charsets.UTF_8)))
      url.queryParameter("ref")?.let { json.put("branch", it) }
      val putUrl = url.newBuilder().query(null).build()
      val request = request().url(putUrl).put(json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
      client.newCall(request).execute().use { response ->
        if (response.isSuccessful) {
          val sha = JSONObject(response.body.string()).getJSONObject("content").getString("sha")
          return CommandsSnapshot(xml, sha, null)
        }
        if (response.code != 409) throw GitHubCommandsHttpException(response.code, "Commands update")
      }
    }
    throw IOException("Commands file changed concurrently; link update deferred")
  }

  private fun request(): Request.Builder = Request.Builder()
    .url(url)
    .header("Accept", "application/vnd.github+json")
    .header("Authorization", "Bearer $token")

  companion object {
    internal fun contentsUrl(sourceUrl: String): HttpUrl {
      val source = sourceUrl.toHttpUrl()
      require(source.isHttps && source.username.isEmpty() && source.password.isEmpty()) { "Commands URL must use HTTPS" }
      if (source.host == "api.github.com") {
        require(source.pathSegments.size >= 5 && source.pathSegments[0] == "repos" && source.pathSegments[3] == "contents") { "Expected GitHub Contents API URL" }
        return source
      }
      require(source.host == "raw.githubusercontent.com" && source.pathSegments.size >= 4) { "Expected GitHub Contents API or raw.githubusercontent.com URL" }
      val parts = source.pathSegments
      // GitHub's Raw button also produces /OWNER/REPO/refs/heads/BRANCH/PATH URLs.
      val branchIndex = if (parts[2] == "refs" && parts.getOrNull(3) == "heads") 4 else 2
      require(parts.size > branchIndex + 1 && parts[branchIndex].isNotEmpty()) { "Raw GitHub URL requires a branch and file path" }
      return "https://api.github.com".toHttpUrl().newBuilder()
        .addPathSegment("repos").addPathSegment(parts[0]).addPathSegment(parts[1]).addPathSegment("contents")
        .apply { parts.drop(branchIndex + 1).forEach { addPathSegment(it) } }
        .addQueryParameter("ref", parts[branchIndex])
        .build()
    }
  }
}
