/*
 * Whatnoww added file
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.github

import android.app.Application
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class AdminCommandsTest {
  private var remoteXml = XML
  private var gets = 0
  private var puts = 0
  private var repositories = 0
  private var linkLookups = 0
  private var status = 200
  private var putStatus = 200
  private var conflictXml: String? = null
  private var saved: CommandsSnapshot? = null
  private var linkState = CommandLinkState(currentLink = NEW_LINK)

  @Test
  fun `unknown missing malformed and PNI sender cannot access GitHub or group links`() {
    listOf(null, "", OTHER_ACI, "PNI:$ADMIN_ACI", "1-2-3-4-5").forEach { sender ->
      assertTrue(handler().reply("!admin group enable autoupdate Example", sender, GROUP_ID).text.startsWith("Error: only"))
    }
    assertEquals(0, repositories)
    assertEquals(0, linkLookups)
    assertNull(saved)
  }

  @Test
  fun `unconfigured or malformed admin ACI fails closed`() {
    listOf("", "1-2-3-4-5", "PNI:$ADMIN_ACI").forEach { configured ->
      assertTrue(handler(adminAci = configured).reply("!admin disable all", configured, GROUP_ID).text.startsWith("Error: only"))
    }
    assertEquals(0, repositories)
  }

  @Test
  fun `canonical UUID comparison ignores hex case`() {
    assertTrue(handler().reply("!admin group status", ADMIN_ACI.uppercase(), GROUP_ID).text.contains("not on the list"))
    assertEquals(1, gets)
    assertEquals(0, puts)
  }

  @Test
  fun `direct messages and missing repository configuration report errors without network`() {
    assertTrue(handler().reply("!admin disable all", ADMIN_ACI, null).text.contains("group chat"))
    assertTrue(handler(configured = false).reply("!admin disable all", ADMIN_ACI, GROUP_ID).text.contains("configure RESPONSE_XML"))
    assertEquals(0, repositories)
  }

  @Test
  fun `invalid commands return usage without network`() {
    listOf("!admin", "!admin disable", "!admin disable unknown", "!admin group enable autoupdate", "!admin group status extra", "!admin disable all\n", "!admin enable !admin", "!admin enable all ").forEach { input ->
      assertTrue(handler().reply(input, ADMIN_ACI, GROUP_ID).text.startsWith("Error: Use"))
    }
    assertEquals(0, repositories)
  }

  @Test
  fun `long group descriptions and multi-word hashtag triggers are supported`() {
    val description = "Example " + "long description ".repeat(8).trim()
    val input = "!admin group enable autoupdate $description"
    assertTrue(AdminCommands.isCandidate(input))
    assertEquals(AdminCommand.EnableGroup(description), AdminCommands.parse(input))
    assertEquals(AdminCommand.SetActive("#example locale", false), AdminCommands.parse("!admin disable #example locale"))
    assertFalse(AdminCommands.isCandidate("!administrator disable all"))
  }

  @Test
  fun `disable all silences configured replies and enable all restores them`() {
    assertTrue(run("!admin disable all").startsWith("Disabled all 2"))
    listOf("!groups", "#links", "!other").forEach { assertNull(render(it)) }
    assertTrue(run("!admin enable all").startsWith("Enabled all 2"))
    listOf("!groups", "#links", "!other").forEach { assertTrue(render(it) != null) }
    assertEquals(remoteXml, saved!!.xml)
    assertEquals(2, puts)
  }

  @Test
  fun `disabling an alias disables the entire command and can re-enable it`() {
    assertTrue(run("!admin disable #links").contains("#links and its aliases"))
    assertNull(render("!groups"))
    assertNull(render("#links"))
    assertEquals("Other content", render("!other")!!.text)
    assertTrue(run("!admin enable !groups").startsWith("Enabled !groups"))
    assertTrue(render("#links") != null)
  }

  @Test
  fun `unknown command does not write or confirm success`() {
    assertTrue(run("!admin disable !missing").startsWith("Error: No configured command"))
    assertEquals(0, puts)
    assertNull(saved)
  }

  @Test
  fun `enabling group binds manual entry even in disabled command and updates stale link`() {
    run("!admin disable all")
    assertTrue(run("!admin group enable autoupdate Example").startsWith("Enabled autoupdate for Example"))
    val group = CommandDocument.parse(remoteXml).listedGroups(GROUP_ID).single()
    assertTrue(group.autoUpdate)
    assertEquals(NEW_LINK, group.link)
    assertFalse(group.outdated)
    assertNull(render("!groups"))
    assertEquals(1, linkLookups)
    assertTrue(run("!admin group status").contains("Example: autoupdate enabled (1)"))
  }

  @Test
  fun `disable group keeps ID and link and reports disabled status`() {
    run("!admin group enable autoupdate Example")
    assertTrue(run("!admin group disable autoupdate").startsWith("Disabled autoupdate"))
    val group = CommandDocument.parse(remoteXml).listedGroups(GROUP_ID).single()
    assertFalse(group.autoUpdate)
    assertEquals(NEW_LINK, group.link)
    assertTrue(run("!admin group status").contains("Example: autoupdate disabled (0)"))
    assertTrue(CommandDocument.parse(remoteXml).render("!groups") { CommandLinkState(currentLink = OLD_LINK, outdated = true) }!!.text.contains("outdated link"))
  }

  @Test
  fun `missing and ambiguous descriptions do not change the file`() {
    assertTrue(run("!admin group enable autoupdate Missing").startsWith("Error: No listed group"))
    remoteXml = XML.replace("</category>", "<group><description>Example</description><autoupdate>0</autoupdate><link>$OLD_LINK</link></group></category>")
    assertTrue(run("!admin group enable autoupdate Example").contains("unique description"))
    assertEquals(0, puts)
    assertNull(saved)
  }

  @Test
  fun `unlisted group status is read only and disabling returns an error`() {
    assertTrue(run("!admin group status").contains("not on the list"))
    assertTrue(run("!admin group disable autoupdate").startsWith("Error: This group is not on the list"))
    assertEquals(0, puts)
    assertEquals(0, linkLookups)
  }

  @Test
  fun `disabled or unavailable invite keeps configured link and gives explicit warning`() {
    linkState = CommandLinkState(outdated = true)
    assertTrue(run("!admin group enable autoupdate Example").contains("Warning:"))
    val group = CommandDocument.parse(remoteXml).listedGroups(GROUP_ID).single()
    assertTrue(group.autoUpdate)
    assertTrue(group.outdated)
    assertEquals(OLD_LINK, group.link)
  }

  @Test
  fun `GitHub error cannot produce success or replace cache`() {
    status = 403
    assertTrue(run("!admin disable all").startsWith("Error: GitHub returned HTTP 403"))
    assertNull(saved)
    assertEquals(0, puts)
  }

  @Test
  fun `rejected GitHub write cannot produce success or replace cache`() {
    putStatus = 403
    assertTrue(run("!admin disable all").startsWith("Error: GitHub returned HTTP 403"))
    assertEquals(XML, remoteXml)
    assertNull(saved)
    assertEquals(1, puts)
  }

  @Test
  fun `unexpected exception details never appear in the group reply`() {
    val command = AdminCommands(
      ADMIN_ACI,
      true,
      repository = { throw IllegalArgumentException("private-url-and-token") },
      currentGroupLink = { error("Must not look up links") },
      saveSnapshot = { error("Must not save") }
    )
    val text = command.reply("!admin disable all", ADMIN_ACI, GROUP_ID).text
    assertTrue(text.startsWith("Error:"))
    assertFalse(text.contains("private-url-and-token"))
  }

  @Test
  fun `conflict reapplies admin change and preserves unrelated remote edits`() {
    conflictXml = XML.replace("Other content", "Edited remotely")
    assertTrue(run("!admin disable #links").startsWith("Disabled #links"))
    assertEquals(2, gets)
    assertEquals(2, puts)
    assertNull(render("!groups"))
    assertEquals("Edited remotely", render("!other")!!.text)
    assertEquals(remoteXml, saved!!.xml)
  }

  @Test
  fun `conflict that removes target returns error instead of overwriting remote changes`() {
    conflictXml = XML.replace("<trigger>#links</trigger>", "")
    assertTrue(run("!admin disable #links").startsWith("Error: No configured command"))
    assertEquals(1, puts)
    assertTrue(render("!groups") != null)
    assertNull(saved)
  }

  @Test
  fun `repeated request for existing state confirms without redundant commit`() {
    run("!admin disable all")
    assertTrue(run("!admin disable all").startsWith("Disabled all"))
    assertEquals(1, puts)
    assertEquals(2, gets)
  }

  @Test
  fun `category create add and move parse names without splitting group description`() {
    assertEquals(AdminCommand.CreateCategory("Music"), AdminCommands.parse("!admin group category create Music"))
    assertEquals(AdminCommand.CreateCategory("Live Music"), AdminCommands.parse("!admin group category create Live Music"))
    assertEquals(AdminCommand.AddGroup("Music", "#NowPlaying Music"), AdminCommands.parse("!admin group add Music #NowPlaying Music"))
    assertEquals(AdminCommand.AddGroup("Live Music", "#NowPlaying Music"), AdminCommands.parse("!admin group add \"Live Music\" #NowPlaying Music"))
    assertEquals(AdminCommand.MoveGroup("Live Music"), AdminCommands.parse("!admin group move Live Music"))
    assertEquals(AdminCommand.MoveGroup("Live Music"), AdminCommands.parse("!admin group move \"Live Music\""))
  }

  @Test
  fun `incomplete and malformed category commands return usage before any network work`() {
    listOf(
      "!admin group category create", "!admin group category create  Music", "!admin group add", "!admin group add Music",
      "!admin group add Music  Name", "!admin group add \"Live Music Name", "!admin group add \"\" Name",
      "!admin group add \"Live Music\"Name", "!admin group move", "!admin group move \"Music", "!admin group move Music\n"
    ).forEach { input -> assertTrue(input, run(input).startsWith("Error: Use")) }
    assertEquals(0, repositories)
    assertEquals(0, linkLookups)
  }

  @Test
  fun `new category is last and existing categories keep their contents`() {
    assertEquals("Created category Music.", run("!admin group category create Music"))
    val text = render("!groups")!!.text
    assertTrue(text.indexOf("Test:") < text.indexOf("Music:"))
    assertTrue(text.contains("Example"))
    assertTrue(text.endsWith("Music:"))
    assertEquals("Other content", render("!other")!!.text)
    assertEquals(remoteXml, saved!!.xml)
    assertEquals(0, linkLookups)
    assertTrue(run("!admin group category create Music").startsWith("Error: Category Music already exists"))
    assertEquals(1, puts)
  }

  @Test
  fun `create and add work in disabled listing and initialize an absent groups container`() {
    remoteXml = "<commands><command><active>0</active><triggers><trigger>!groups</trigger></triggers><response><text>Heading</text></response></command></commands>"
    assertEquals("Created category Music.", run("!admin group category create Music"))
    assertTrue(run("!admin group add Music #NowPlaying Music").startsWith("Added #NowPlaying Music"))
    assertNull(render("!groups"))
    run("!admin enable !groups")
    assertTrue(render("!groups")!!.text.contains("#NowPlaying Music:\n$NEW_LINK"))
  }

  @Test
  fun `add saves this group as last entry with enabled link and autoupdate`() {
    assertTrue(run("!admin group add Test #NowPlaying Music").startsWith("Added #NowPlaying Music as the last entry in Test"))
    val group = CommandDocument.parse(remoteXml).listedGroups(GROUP_ID).single()
    assertEquals("#NowPlaying Music", group.description)
    assertTrue(group.autoUpdate)
    assertFalse(group.outdated)
    assertEquals(NEW_LINK, group.link)
    val text = render("!groups")!!.text
    assertTrue(text.indexOf("Example") < text.indexOf("#NowPlaying Music"))
    assertEquals(1, linkLookups)
    assertTrue(run("!admin group status").contains("#NowPlaying Music: autoupdate enabled (1)"))
  }

  @Test
  fun `quoted categories and XML characters round trip through add and move`() {
    run("!admin group category create Live Music & Arts")
    assertTrue(run("!admin group add \"Live Music & Arts\" #NowPlaying <Music> & More").startsWith("Added #NowPlaying <Music> & More"))
    assertTrue(render("!groups")!!.text.contains("#NowPlaying <Music> & More:\n$NEW_LINK"))
    assertTrue(remoteXml.contains("&lt;Music&gt; &amp; More"))
    assertTrue(run("!admin group move Test").startsWith("Moved this group"))
  }

  @Test
  fun `missing category duplicate group and duplicate description do not look up link or write`() {
    assertTrue(run("!admin group add Missing New name").startsWith("Error: Category Missing does not exist"))
    assertTrue(run("!admin group add Test Example").startsWith("Error: A listed group already"))
    assertEquals(0, linkLookups)
    assertEquals(0, puts)
    run("!admin group add Test New name")
    assertTrue(run("!admin group add Test Another name").startsWith("Error: This group is already listed"))
    assertEquals(1, linkLookups)
    assertEquals(1, puts)
  }

  @Test
  fun `unauthorized category add and move cannot access GitHub or look up invite`() {
    listOf("!admin group category create Music", "!admin group add Test New name", "!admin group move Test").forEach { input ->
      assertTrue(handler().reply(input, OTHER_ACI, GROUP_ID).text.startsWith("Error: only"))
    }
    assertEquals(0, repositories)
    assertEquals(0, linkLookups)
  }

  @Test
  fun `disabled invite prevents addition and asks for enabling through Signal`() {
    linkState = CommandLinkState(outdated = true)
    val message = run("!admin group add Test New name")
    assertTrue(message.startsWith("Error: This group's invite link is disabled."))
    assertTrue(message.contains("Enable it in Signal"))
    assertEquals(1, linkLookups)
    assertEquals(0, puts)
    assertEquals(XML, remoteXml)
    assertNull(saved)
  }

  @Test
  fun `unavailable invite prevents addition without changing configuration`() {
    linkState = CommandLinkState()
    assertTrue(run("!admin group add Test New name").startsWith("Error: Unable to read this group's enabled invite link"))
    assertEquals(1, linkLookups)
    assertEquals(0, puts)
    assertEquals(XML, remoteXml)
    assertNull(saved)
  }

  @Test
  fun `GitHub failure after reading link does not confirm listing or claim Signal changes`() {
    putStatus = 403
    val message = run("!admin group add Test New name")
    assertTrue(message.startsWith("Error: GitHub returned HTTP 403"))
    assertFalse(message.contains("invite link may already be enabled"))
    assertEquals(1, linkLookups)
    assertEquals(XML, remoteXml)
    assertNull(saved)
  }

  @Test
  fun `move appends after destination groups and preserves metadata including disabled autoupdate`() {
    run("!admin group category create Music")
    run("!admin group add Music Existing music group")
    run("!admin group disable autoupdate")
    remoteXml = remoteXml.replace("</group>", "<custom>Keep me</custom></group>")
    val before = CommandDocument.parse(remoteXml).listedGroups(GROUP_ID).single()
    assertEquals("Moved this group to the last entry in Test.", run("!admin group move Test"))
    assertEquals(before, CommandDocument.parse(remoteXml).listedGroups(GROUP_ID).single())
    assertTrue(remoteXml.contains("<custom>Keep me</custom>"))
    // Read category order directly: unknown metadata is deliberately preserved even if it is not rendered.
    assertTrue(remoteXml.indexOf("Existing music group") < remoteXml.indexOf("name=\"Music\""))
    assertTrue(remoteXml.indexOf("<description>Example</description>") < remoteXml.indexOf("Existing music group"))
    assertEquals(1, linkLookups)
  }

  @Test
  fun `move rejects unlisted groups unknown or ambiguous categories and duplicate group IDs`() {
    assertTrue(run("!admin group move Test").contains("not on the !groups list"))
    assertTrue(run("!admin group move Missing").contains("does not exist"))
    remoteXml = XML.replace("</groups>", "<category name=\"Test\"/></groups>")
    assertTrue(run("!admin group move Test").contains("unique category names"))
    remoteXml = XML.replace("<groupid>0</groupid>", "<groupid>$GROUP_ID</groupid>")
      .replace("</category>", "<group><groupid>$GROUP_ID</groupid></group></category>")
    assertTrue(run("!admin group move Test").contains("multiple entries"))
    assertEquals(0, puts)
    assertEquals(0, linkLookups)
  }

  @Test
  fun `moving to existing category is a no-op and keeps group order`() {
    run("!admin group add Test New name")
    val before = remoteXml
    assertEquals("This group is already in Test.", run("!admin group move Test"))
    assertEquals(before, remoteXml)
    assertEquals(1, puts)
  }

  @Test
  fun `add conflict preserves remote edits and resolves Signal link only once`() {
    conflictXml = XML.replace("Other content", "Remote edit")
    assertTrue(run("!admin group add Test New name").startsWith("Added New name"))
    assertEquals(1, linkLookups)
    assertEquals(2, gets)
    assertEquals(2, puts)
    assertEquals("Remote edit", render("!other")!!.text)
    assertEquals(1, CommandDocument.parse(remoteXml).listedGroups(GROUP_ID).size)
  }

  @Test
  fun `conflict removing destination cannot restore deleted category`() {
    conflictXml = XML.replace("name=\"Test\"", "name=\"Renamed\"")
    val message = run("!admin group add Test New name")
    assertTrue(message.startsWith("Error: Category Test does not exist"))
    assertEquals(1, puts)
    assertEquals(1, linkLookups)
    assertTrue(CommandDocument.parse(remoteXml).listedGroups(GROUP_ID).isEmpty())
  }

  @Test
  fun `category operations require one groups command and preserve other responses`() {
    remoteXml = XML.replace("<trigger>!groups</trigger>", "<trigger>!another</trigger>")
    assertTrue(run("!admin group category create Music").contains("exactly one command"))
    assertEquals(0, puts)
  }

  private fun run(input: String): String = handler().reply(input, ADMIN_ACI, GROUP_ID).text

  private fun render(trigger: String): CommandReply? = CommandDocument.parse(remoteXml).render(trigger) { CommandLinkState() }

  private fun handler(adminAci: String = ADMIN_ACI, configured: Boolean = true) = AdminCommands(
    adminAci,
    configured,
    repository = {
      repositories++
      val client = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        var code = status
        val body = if (request.method == "GET") {
          gets++
          JSONObject().put("encoding", "base64").put("sha", "sha-$puts")
            .put("content", Base64.getEncoder().encodeToString(remoteXml.toByteArray(Charsets.UTF_8))).toString()
        } else {
          puts++
          code = putStatus
          val conflict = conflictXml
          if (conflict != null) {
            conflictXml = null
            remoteXml = conflict
            code = 409
          } else if (code == 200) {
            val json = JSONObject(Buffer().also { request.body!!.writeTo(it) }.readUtf8())
            assertEquals("sha-${puts - 1}", json.getString("sha"))
            remoteXml = Base64.getDecoder().decode(json.getString("content")).toString(Charsets.UTF_8)
          }
          """{"content":{"sha":"sha-$puts"}}"""
        }
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("test")
          .body(body.toResponseBody("application/json".toMediaType())).build()
      }.build()
      GitHubCommandsRepository("https://api.github.com/repos/owner/repo/contents/commands.xml", "test-token", client)
    },
    currentGroupLink = { id ->
      assertEquals(GROUP_ID, id)
      linkLookups++
      linkState
    },
    saveSnapshot = { saved = it }
  )

  companion object {
    private const val ADMIN_ACI = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"
    private const val OTHER_ACI = "11111111-2222-4333-8444-555555555555"
    private const val GROUP_ID = "synthetic-group-id"
    private const val OLD_LINK = "https://signal.group/#old"
    private const val NEW_LINK = "https://signal.group/#new"
    private val XML = """
      <commands><command><triggers><trigger>!groups</trigger><trigger>#links</trigger></triggers><response><groups><category name="Test">
      <group><groupid>0</groupid><description>Example</description><autoupdate>0</autoupdate><link>$OLD_LINK</link><outdated>1</outdated></group>
      </category></groups></response></command>
      <command><active>1</active><triggers><trigger>!other</trigger></triggers><response>Other content</response></command></commands>
    """.trimIndent()
  }
}
