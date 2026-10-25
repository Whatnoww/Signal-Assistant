/*
 * Whatnoww added file
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.github

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class CommandDocumentAndroidTest {
  @Test
  fun `commands parse with Android DOM provider which lacks JAXP DTD flags`() {
    val document = CommandDocument.parse("<commands><command><triggers><trigger>!example</trigger></triggers><response><b>Android</b></response></command></commands>", androidFactory())
    val reply = document.render("!example") { error("No groups") }!!
    assertEquals("Android", reply.text)
    assertEquals(CommandStyleRange(CommandStyle.BOLD, 0, 7), reply.styles.single())
  }

  @Test(expected = IllegalArgumentException::class)
  fun `Android parsing rejects entity declarations before reading external resources`() {
    CommandDocument.parse("<!DOCTYPE commands [<!ENTITY xxe SYSTEM 'file:///etc/passwd'>]><commands>&xxe;</commands>", androidFactory())
  }

  @Test
  fun `synthetic groups render native formatting and outdated markers`() {
    val xml = """
      <commands><command><active>1</active><triggers><trigger>!groups</trigger></triggers><response>
        <text>Example groups</text><br/><br/>
        <groups><category name="Example category">
          <group><groupid>0</groupid><description>Example manual group</description><autoupdate>0</autoupdate><link>https://signal.group/#manual-placeholder</link><outdated>1</outdated></group>
          <group><groupid>synthetic-id</groupid><description>Example managed group</description><autoupdate>1</autoupdate><link>https://signal.group/#managed-placeholder</link><outdated>0</outdated></group>
        </category></groups>
      </response></command></commands>
    """.trimIndent()
    val reply = CommandDocument.parse(xml, androidFactory()).render("!groups") { CommandLinkState() }!!
    assertEquals(2, reply.styles.count { it.style == CommandStyle.ITALIC })
    assertEquals(1, reply.styles.count { it.style == CommandStyle.BOLD })
    assertTrue(reply.text.startsWith("Example groups\n\nExample category:"))
    assertTrue(reply.text.contains("Example manual group (outdated link):"))
    assertFalse(reply.text.contains("Example managed group (outdated link)"))
    assertTrue(reply.linkUpdates.isEmpty())
  }

  @Test
  fun `public template uses valid exact triggers and supported response tags`() {
    val xml = File("../docs/commands/commands.template.xml").readText()
    val dom = androidFactory().newDocumentBuilder().parse(xml.byteInputStream())
    val nodes = dom.getElementsByTagName("trigger")
    val triggers = (0 until nodes.length).map { nodes.item(it).textContent }
    assertEquals(triggers.size, triggers.toSet().size)
    val document = CommandDocument.parse(xml, androidFactory())
    triggers.forEach { trigger ->
      assertTrue("Unsupported example trigger: $trigger", CommandDocument.isCommandCandidate(trigger))
      val reply = document.render(trigger) { CommandLinkState() }
      if (trigger == "!disabled-example") {
        assertNull(reply)
      } else {
        assertNotNull("Example command did not render: $trigger", reply)
        reply!!.styles.forEach { range ->
          assertTrue(range.start >= 0 && range.length > 0 && range.start + range.length <= reply.text.length)
        }
      }
    }
  }

  @Test
  fun `synthetic aliases preserve formatting and exact multi-word selection`() {
    val xml = """
      <commands>
        <command><active>1</active><triggers><trigger>!example</trigger><trigger>#example</trigger></triggers>
          <response><text><b>Example heading</b><br/><i>Example description</i></text></response>
        </command>
        <command><active>1</active><triggers><trigger>!example locale</trigger><trigger>!Example locale</trigger></triggers>
          <response><text>Example localized response</text></response>
        </command>
        <command><active>0</active><triggers><trigger>!disabled-example</trigger></triggers>
          <response><text>Disabled example</text></response>
        </command>
        <command><active>1</active><triggers><trigger>!empty-example</trigger></triggers><response><text/></response></command>
      </commands>
    """.trimIndent()
    val document = CommandDocument.parse(xml, androidFactory())
    fun reply(trigger: String): CommandReply = document.render(trigger) { error("Static examples must not resolve groups") }!!
    val example = reply("!example")
    assertEquals(example, reply("#example"))
    assertEquals("Example heading\nExample description", example.text)
    assertTrue(example.styles.any { it.style == CommandStyle.BOLD })
    assertTrue(example.styles.any { it.style == CommandStyle.ITALIC })
    assertEquals(reply("!example locale"), reply("!Example locale"))
    assertEquals("Example localized response", reply("!example locale").text)
    listOf("!example extra", "!example locale extra", "!example  locale", "#example extra", "!disabled-example").forEach {
      assertNull(document.render(it) { error("Unconfigured or disabled examples must not match") })
    }
    assertEquals("", reply("!empty-example").text)
  }

  @Test
  fun `Android writes readable categories and valid emoji without changing mixed response text`() {
    val source = """<commands><command><triggers><trigger>!groups</trigger></triggers><response><text><b>Heading 😁</b><br/>First <i>line</i> and more</text><br/><br/><groups><category name="Test"><group><groupid>0</groupid><description>Example</description><autoupdate>0</autoupdate><link>https://signal.group/#example</link></group></category></groups></response></command></commands>"""
    val document = CommandDocument.parse(source, androidFactory())
    document.createCategory("Music 🎵")
    document.addGroup("Music 🎵", "New group 🌍", "group-2", "https://signal.group/#new")
    val expected = document.render("!groups") { CommandLinkState() }
    val saved = document.toXml()
    assertTrue(saved.contains("\n          <group>\n            <groupid>group-2</groupid>\n"))
    // Validate the actual serialized XML directly, without our legacy-reference repair.
    val dom = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(saved.byteInputStream())
    assertEquals("New group 🌍", dom.getElementsByTagName("description").item(1).textContent)
    assertEquals(expected, CommandDocument.parse(saved, androidFactory()).render("!groups") { CommandLinkState() })
    assertEquals(saved, document.toXml())
  }

  private fun androidFactory(): DocumentBuilderFactory = Class.forName("org.apache.harmony.xml.parsers.DocumentBuilderFactoryImpl").getDeclaredConstructor().newInstance() as DocumentBuilderFactory
}
