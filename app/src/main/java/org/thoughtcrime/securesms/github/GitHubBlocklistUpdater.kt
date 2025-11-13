/*
 * Copyright 2025 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

//Whatnoww added ACI Blocklist updater
//This package should handle all code related to GitHub update operations.
//Currently this will run on each new added member on a background thread. Value of token should be in GITHUB_TOKEN in local.properties. Or just leave it empty if you don't want updates. BUT YOU MUST PLACE IT.
package org.thoughtcrime.securesms.github

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.signal.core.util.logging.Log
import java.util.Base64
import org.thoughtcrime.securesms.BuildConfig

object GitHubBlocklistUpdater {
  private val TAG = Log.tag(GitHubBlocklistUpdater::class.java)

  // ---- CONFIG ----
  private const val OWNER   = "Whatnoww"
  private const val REPO    = "ACI-Blocklist"
  private const val PATH    = "blocklist"          // file path in repo root
  private const val BRANCH  = "main"
  private const val TOKEN   = BuildConfig.GITHUB_TOKEN
  // -----------------

  private val client = OkHttpClient()
  private val jsonMT = "application/json; charset=utf-8".toMediaType()

  // THIS RETURNS TRUE WHEN EVERYTHING WORKS FINE.
  fun appendAci(aci: String): Boolean {
    if (TOKEN.isBlank()) {
      Log.w(TAG, "No GitHub token configured; skipping append.")
      return false
    }

    val getUrl = "https://api.github.com/repos/$OWNER/$REPO/contents/$PATH?ref=$BRANCH"

    // We first grab the latest ACI-Blocklist
    val getReq = Request.Builder()
      .url(getUrl)
      .header("Accept", "application/vnd.github+json")
      .header("Authorization", "Bearer $TOKEN")
      .build()

    val getRes = client.newCall(getReq).execute()
    if (!getRes.isSuccessful) {
      Log.w(TAG, "GET failed: HTTP ${getRes.code} ${getRes.message}")
      return false
    }

    val getBody = getRes.body.string().orEmpty()
    val getJson = JSONObject(getBody)
    val sha     = getJson.getString("sha")
    val b64     = getJson.getString("content")
    val decoded = Base64.getMimeDecoder().decode(b64).toString(Charsets.UTF_8)

    // Makes sure we're appending the ACI to the last non empty line of text
    val lines = decoded.split("\n").toMutableList()
    var idx = lines.size - 1
    while (idx >= 0 && lines[idx].trim().isEmpty()) idx--

    // For an empty file or when there is no empty line.
    if (idx < 0) {
      lines.add(aci)
    } else {
      val last = lines[idx]
      val trimmed = last.trimEnd()

      // We add a comma to the end of the last ACI here then make a blank line after it:
      val newLast = if (trimmed.endsWith(",")) last else (trimmed + ",")
      lines[idx] = newLast
      while (lines.size > idx + 1 && lines.last().isBlank()) lines.removeLast()

      // ACI on it's own line.
      lines.add(aci)
    }

    val newText = lines.joinToString("\n") + "\n"
    val newB64  = Base64.getEncoder().encodeToString(newText.toByteArray(Charsets.UTF_8))

    // Upload the new list
    val putUrl = "https://api.github.com/repos/$OWNER/$REPO/contents/$PATH"
    val bodyJson = JSONObject()
      .put("message", "Append new ACI")
      .put("content", newB64)
      .put("sha", sha)
      .put("branch", BRANCH)
      .toString()
      .toRequestBody(jsonMT)

    val putReq = Request.Builder()
      .url(putUrl)
      .header("Accept", "application/vnd.github+json")
      .header("Authorization", "Bearer $TOKEN")
      .put(bodyJson)
      .build()

    client.newCall(putReq).execute().use { putRes ->
      val ok = putRes.isSuccessful
      if (!ok) {
        Log.w(TAG, "PUT failed: HTTP ${putRes.code} ${putRes.message} – ${putRes.body.string()}")
      }
      return ok
    }
  }
}
