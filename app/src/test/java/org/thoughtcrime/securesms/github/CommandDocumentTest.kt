/*
 * Whatnoww added file
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.github

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandDocumentTest {
  @Test
  fun `active command replies and omitted active tag stays compatible`() {
    val source = xml()
    val legacyReply = CommandDocument.parse(source).render("!groups") { CommandLinkState() }
    assertTrue(legacyReply != null)
    assertEquals(legacyReply, CommandDocument.parse(source.replaceFirst("<command>", "<command><active> 1 </active>")).render("!groups") { CommandLinkState() })
  }

  @Test
  fun `inactive command skips all aliases and never resolves group links`() {
    val source = xml().replaceFirst("<command>", "<command><active>0</active>")
      .replace("<trigger>!groups</trigger>", "<trigger>!groups</trigger><trigger>!links</trigger>")
    val document = CommandDocument.parse(source)
    listOf("!groups", "!links").forEach { trigger ->
      assertNull(document.render(trigger) { error("Inactive command must not check group links") })
    }
    assertEquals("Other", document.render("!other") { error("No groups") }!!.text)
  }

  @Test
  fun `empty and unrecognized active values disable command`() {
    listOf("", " ", "invalid", "2").forEach { value ->
      val document = CommandDocument.parse(xml().replaceFirst("<command>", "<command><active>$value</active>"))
      assertNull(document.render("!groups") { error("Disabled command must not check group links") })
    }
  }

  @Test
  fun `command disabled since rendering cannot publish link updates`() {
    val document = CommandDocument.parse(xml(id = "group-1", autoUpdate = "1").replaceFirst("<command>", "<command><active>0</active>"))
    assertFalse(document.applyLinkUpdates(listOf(CommandLinkUpdate("group-1", OLD_LINK, NEW_LINK))))
    val saved = document.toXml()
    assertTrue(saved.contains(OLD_LINK))
    assertTrue(saved.contains("<active>0</active>"))
  }

  @Test
  fun `only the exact trigger matches`() {
    val document = CommandDocument.parse(xml())
    listOf("!GROUPS", " !groups", "!groups ", "!groups\n", "please !groups", "!groups extra", "!group").forEach {
      assertNull(document.render(it) { CommandLinkState() })
    }
    assertFalse(CommandDocument.isCommandCandidate("!groups\n"))
    assertFalse(CommandDocument.isCommandCandidate("!groups extra"))
    assertTrue(CommandDocument.isCommandCandidate("!groups"))
  }

  @Test
  fun `category is bold and link italic with plain group description`() {
    val reply = CommandDocument.parse(xml()).render("!groups") { CommandLinkState() }!!
    assertEquals("Group Chats\n\nTech:\n\nA & B 😁:\n$OLD_LINK", reply.text)
    assertEquals(2, reply.styles.size)
    assertEquals("Tech:", reply.styledText(CommandStyle.BOLD))
    assertEquals(OLD_LINK, reply.styledText(CommandStyle.ITALIC))
  }

  @Test
  fun `non-updating outdated link is labelled before colon`() {
    val reply = CommandDocument.parse(xml()).render("!groups") { CommandLinkState(currentLink = NEW_LINK, outdated = true) }!!
    assertTrue(reply.text.contains("A & B 😁 (outdated link):\n$OLD_LINK"))
    assertTrue(reply.linkUpdates.isEmpty())
  }

  @Test
  fun `auto update uses current link and clears outdated label`() {
    val reply = CommandDocument.parse(xml(id = "group-1", autoUpdate = "1", outdated = "1"))
      .render("!groups") { CommandLinkState(currentLink = NEW_LINK, outdated = true) }!!
    assertTrue(reply.text.endsWith(NEW_LINK))
    assertFalse(reply.text.contains("outdated"))
    assertEquals(listOf(CommandLinkUpdate("group-1", OLD_LINK, NEW_LINK)), reply.linkUpdates)
  }

  @Test
  fun `zero ID never updates even if auto update was enabled`() {
    val reply = CommandDocument.parse(xml(id = "0", autoUpdate = "1"))
      .render("!groups") { CommandLinkState(currentLink = NEW_LINK) }!!
    assertTrue(reply.text.endsWith(OLD_LINK))
    assertTrue(reply.linkUpdates.isEmpty())
  }

  @Test
  fun `unavailable current link preserves configured link and known status`() {
    val reply = CommandDocument.parse(xml(id = "group-1", autoUpdate = "1", outdated = "1"))
      .render("!groups") { CommandLinkState() }!!
    assertTrue(reply.text.contains("(outdated link):"))
    assertTrue(reply.text.endsWith(OLD_LINK))
    assertTrue(reply.linkUpdates.isEmpty())
  }

  @Test
  fun `positive validation clears previous outdated status`() {
    val reply = CommandDocument.parse(xml(outdated = "1")).render("!groups") { CommandLinkState(outdated = false) }!!
    assertFalse(reply.text.contains("outdated"))
  }

  @Test
  fun `link merge preserves unrelated commands and formatted content`() {
    val document = CommandDocument.parse(xml(id = "group-1", autoUpdate = "1"))
    assertTrue(document.applyLinkUpdates(listOf(CommandLinkUpdate("group-1", OLD_LINK, NEW_LINK))))
    val saved = CommandDocument.parse(document.toXml())
    assertTrue(saved.render("!groups") { CommandLinkState() }!!.text.endsWith(NEW_LINK))
    assertEquals("Other", saved.render("!other") { CommandLinkState() }!!.text)
  }

  @Test
  fun `link merge respects changed link ID and auto update setting`() {
    val update = listOf(CommandLinkUpdate("group-1", OLD_LINK, NEW_LINK))
    assertFalse(CommandDocument.parse(xml(id = "different", autoUpdate = "1")).applyLinkUpdates(update))
    assertFalse(CommandDocument.parse(xml(id = "group-1", autoUpdate = "0")).applyLinkUpdates(update))
    assertFalse(CommandDocument.parse(xml(id = "group-1", autoUpdate = "1").replace(OLD_LINK, NEW_LINK)).applyLinkUpdates(update))
    assertFalse(CommandDocument.parse(xml(id = "0", autoUpdate = "1")).applyLinkUpdates(listOf(CommandLinkUpdate("0", OLD_LINK, NEW_LINK))))
  }

  @Test
  fun `inline formatting nests with UTF16 ranges and supports future text commands`() {
    val document = CommandDocument.parse("<commands><command><triggers><trigger>!test</trigger></triggers><response><text>😁 <b>bold <i>both</i></b> <s>old</s> <code>code</code> <spoiler>secret</spoiler></text></response></command></commands>")
    val reply = document.render("!test") { error("No groups") }!!
    assertEquals("😁 bold both old code secret", reply.text)
    assertEquals("bold both", reply.styledText(CommandStyle.BOLD))
    assertEquals("both", reply.styledText(CommandStyle.ITALIC))
    assertEquals(3, reply.styles.first { it.style == CommandStyle.BOLD }.start)
    assertEquals("old", reply.styledText(CommandStyle.STRIKETHROUGH))
    assertEquals("code", reply.styledText(CommandStyle.MONOSPACE))
    assertEquals("secret", reply.styledText(CommandStyle.SPOILER))
  }

  @Test
  fun `legacy CDATA stays plain text`() {
    val document = CommandDocument.parse("<commands><command><triggers><trigger>!test</trigger></triggers><response><![CDATA[Line one\n<b>literal</b>]]></response></command></commands>")
    assertEquals("Line one\n<b>literal</b>", document.render("!test") { error("No groups") }!!.text)
  }

  @Test
  fun `trimming final whitespace also trims formatting ranges`() {
    val document = CommandDocument.parse("<commands><command><triggers><trigger>!test</trigger></triggers><response><b>bold </b></response></command></commands>")
    val reply = document.render("!test") { error("No groups") }!!
    assertEquals("bold", reply.text)
    assertEquals(CommandStyleRange(CommandStyle.BOLD, 0, 4), reply.styles.single())
  }

  @Test(expected = IllegalArgumentException::class)
  fun `external entities and doctypes are rejected`() {
    CommandDocument.parse("<!DOCTYPE commands [<!ENTITY xxe SYSTEM 'file:///etc/passwd'>]><commands>&xxe;</commands>")
  }

  @Test(expected = IllegalStateException::class)
  fun `unsupported image tags fail explicitly rather than silently dropping media`() {
    CommandDocument.parse("<commands><command><triggers><trigger>!test</trigger></triggers><response><image src='https://example.com/image.png'/></response></command></commands>")
      .render("!test") { error("No groups") }
  }

  private fun CommandReply.styledText(style: CommandStyle): String {
    val range = styles.first { it.style == style }
    return text.substring(range.start, range.start + range.length)
  }

  private fun xml(id: String = "0", autoUpdate: String = "0", outdated: String = "0"): String = """
    <commands>
      <command>
        <triggers><trigger>!groups</trigger></triggers>
        <response>
          <text>Group Chats</text><br/><br/>
          <groups><category name="Tech">
            <group><groupid>$id</groupid><description>A &amp; B 😁</description><autoupdate>$autoUpdate</autoupdate><link>$OLD_LINK</link><outdated>$outdated</outdated></group>
          </category></groups>
        </response>
      </command>
      <command><triggers><trigger>!other</trigger></triggers><response><b>Other</b></response></command>
    </commands>
  """.trimIndent()

  companion object {
    private const val OLD_LINK = "https://signal.group/#old"
    private const val NEW_LINK = "https://signal.group/#new"
  }
}
