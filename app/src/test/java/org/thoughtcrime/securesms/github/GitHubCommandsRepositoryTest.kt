/*
 * Whatnoww added file
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.github

import android.app.Application
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class GitHubCommandsRepositoryTest {
  @Test
  fun `private contents request authenticates and decodes XML`() {
    val repository = repository { request ->
      assertEquals("Bearer test-token", request.header("Authorization"))
      assertEquals("application/vnd.github+json", request.header("Accept"))
      response(request, 200, fileJson(XML, "original-sha"), "etag-1")
    }
    val result = repository.fetch()
    assertEquals(XML, result.xml)
    assertEquals("original-sha", result.sha)
    assertEquals("etag-1", result.etag)
  }

  @Test
  fun `304 retains validated previous snapshot`() {
    val previous = CommandsSnapshot(XML, "original-sha", "etag-1")
    val repository = repository { request ->
      assertEquals("etag-1", request.header("If-None-Match"))
      response(request, 304)
    }
    assertSame(previous, repository.fetch(previous))
  }

  @Test
  fun `publish includes branch SHA and changed link`() {
    val requests = mutableListOf<String>()
    val repository = repository { request ->
      requests += request.method
      if (request.method == "GET") {
        response(request, 200, fileJson(XML, "latest-sha"))
      } else {
        assertFalse(request.url.queryParameterNames.contains("ref"))
        val body = JSONObject(request.bodyText())
        assertEquals("main", body.getString("branch"))
        assertEquals("latest-sha", body.getString("sha"))
        val publishedXml = Base64.getDecoder().decode(body.getString("content")).toString(Charsets.UTF_8)
        assertTrue(publishedXml.contains(NEW_LINK))
        assertTrue(publishedXml.contains("Unrelated content"))
        response(request, 200, """{"content":{"sha":"new-sha"}}""")
      }
    }
    val result = repository.publish(listOf(UPDATE))!!
    assertEquals(listOf("GET", "PUT"), requests)
    assertEquals("new-sha", result.sha)
    assertTrue(result.xml.contains(NEW_LINK))
  }

  @Test
  fun `concurrent write refetches and respects administrator disabling updates`() {
    var calls = 0
    val repository = repository { request ->
      when (++calls) {
        1 -> response(request, 200, fileJson(XML, "original-sha"))
        2 -> response(request, 409)
        3 -> response(request, 200, fileJson(XML.replace("<autoupdate>1", "<autoupdate>0"), "edited-sha"))
        else -> error("Must not overwrite disabled entry")
      }
    }
    val result = repository.publish(listOf(UPDATE))!!
    assertEquals(3, calls)
    assertEquals("edited-sha", result.sha)
    assertTrue(result.xml.contains(OLD_LINK))
  }

  @Test
  fun `conflict retries with newest SHA and preserves unrelated edits`() {
    var calls = 0
    val repository = repository { request ->
      when (++calls) {
        1 -> response(request, 200, fileJson(XML, "original-sha"))
        2 -> response(request, 409)
        3 -> response(request, 200, fileJson(XML.replace("Unrelated content", "Edited content"), "edited-sha"))
        4 -> {
          val body = JSONObject(request.bodyText())
          assertEquals("edited-sha", body.getString("sha"))
          val xml = Base64.getDecoder().decode(body.getString("content")).toString(Charsets.UTF_8)
          assertTrue(xml.contains("Edited content"))
          response(request, 200, """{"content":{"sha":"new-sha"}}""")
        }
        else -> error("Too many retries")
      }
    }
    assertEquals("new-sha", repository.publish(listOf(UPDATE))!!.sha)
    assertEquals(4, calls)
  }

  @Test(expected = java.io.IOException::class)
  fun `authorization failure is not treated as an empty file`() {
    repository { request -> response(request, 401) }.fetch()
  }

  @Test
  fun `raw GitHub URL converts to private contents API`() {
    assertEquals(URL, GitHubCommandsRepository.contentsUrl("https://raw.githubusercontent.com/owner/repo/main/commands.xml").toString())
  }

  @Test
  fun `GitHub Raw button refs heads URL downloads the correct branch and file`() {
    val repository = repository("https://raw.githubusercontent.com/owner/repo/refs/heads/main/commands.xml") { request ->
      assertEquals(URL, request.url.toString())
      assertEquals("Bearer test-token", request.header("Authorization"))
      response(request, 200, fileJson(XML, "sha"))
    }
    assertEquals(XML, repository.fetch().xml)
  }

  @Test
  fun `refs heads raw URL preserves nested file path`() {
    assertEquals(
      "https://api.github.com/repos/owner/repo/contents/config/commands.xml?ref=main",
      GitHubCommandsRepository.contentsUrl("https://raw.githubusercontent.com/owner/repo/refs/heads/main/config/commands.xml").toString()
    )
  }

  @Test
  fun `raw refs heads URL missing a file is rejected`() {
    assertThrows(IllegalArgumentException::class.java) {
      GitHubCommandsRepository.contentsUrl("https://raw.githubusercontent.com/owner/repo/refs/heads/main")
    }
  }

  @Test
  fun `HTTP download failure preserves status for diagnostics`() {
    val error = assertThrows(GitHubCommandsHttpException::class.java) {
      repository { request -> response(request, 404) }.fetch()
    }
    assertEquals(404, error.statusCode)
    assertEquals("Commands download failed: HTTP 404", error.message)
  }

  @Test(expected = IllegalArgumentException::class)
  fun `token cannot be sent to a non GitHub host`() {
    GitHubCommandsRepository.contentsUrl("https://example.com/commands.xml")
  }

  private fun repository(sourceUrl: String = URL, handler: (Request) -> Response): GitHubCommandsRepository {
    val client = OkHttpClient.Builder().addInterceptor { handler(it.request()) }.build()
    return GitHubCommandsRepository(sourceUrl, "test-token", client)
  }

  private fun response(request: Request, code: Int, body: String = "", etag: String? = null): Response = Response.Builder()
    .request(request).protocol(Protocol.HTTP_1_1).code(code).message("test")
    .body(body.toResponseBody("application/json".toMediaType()))
    .apply { etag?.let { header("ETag", it) } }.build()

  private fun fileJson(xml: String, sha: String): String = JSONObject()
    .put("encoding", "base64").put("content", Base64.getEncoder().encodeToString(xml.toByteArray(Charsets.UTF_8))).put("sha", sha).toString()

  private fun Request.bodyText(): String = Buffer().also { body!!.writeTo(it) }.readUtf8()

  companion object {
    private const val URL = "https://api.github.com/repos/owner/repo/contents/commands.xml?ref=main"
    private const val OLD_LINK = "https://signal.group/#old"
    private const val NEW_LINK = "https://signal.group/#new"
    private val UPDATE = CommandLinkUpdate("group-1", OLD_LINK, NEW_LINK)
    private val XML = """
      <commands><command><triggers><trigger>!groups</trigger></triggers><response><groups><category name="Test">
        <group><groupid>group-1</groupid><description>Example</description><autoupdate>1</autoupdate><link>$OLD_LINK</link></group>
      </category></groups></response></command>
      <command><triggers><trigger>!other</trigger></triggers><response>Unrelated content</response></command></commands>
    """.trimIndent()
  }
}
