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

class GroupCategoryBrowserTest {
  @Test
  fun `index shows bold configured heading numbered categories and current counts without resolving links`() {
    val reply = CommandDocument.parse(XML).render("!groups") { error("The category index must not check group links") }!!
    assertEquals(
      "$HEADING\n\n1. Tech (2 groups)\n2. Music (1 group)\n3. Empty (0 groups)\n\nSend !groups <number> to view a category.",
      reply.text
    )
    assertEquals(
      listOf(CommandStyleRange(CommandStyle.BOLD, 0, HEADING.length), CommandStyleRange(CommandStyle.ITALIC, reply.text.length - FOOTER.length, FOOTER.length)),
      reply.styles
    )
    assertTrue(reply.linkUpdates.isEmpty())
    assertFalse(reply.text.contains("https://"))
  }

  @Test
  fun `selected category contains only its own heading and groups with native formatting`() {
    val lookedUp = mutableListOf<String>()
    val reply = CommandDocument.parse(XML).render("!groups 2") { group ->
      lookedUp += group.groupId
      CommandLinkState()
    }!!
    assertEquals("Music:\n\nMusic group:\nhttps://signal.group/#music", reply.text)
    assertEquals(listOf("music"), lookedUp)
    assertEquals(CommandStyleRange(CommandStyle.BOLD, 0, "Music:".length), reply.styles.first())
    val italic = reply.styles.single { it.style == CommandStyle.ITALIC }
    assertEquals("https://signal.group/#music", reply.text.substring(italic.start, italic.start + italic.length))
    assertFalse(reply.text.contains(HEADING))
    assertTrue(reply.linkUpdates.isEmpty())
  }

  @Test
  fun `selected category refreshes and publishes only its own enabled groups`() {
    val lookedUp = mutableListOf<String>()
    val reply = CommandDocument.parse(XML).render("!groups 1") { group ->
      lookedUp += group.groupId
      CommandLinkState(currentLink = "https://signal.group/#new-${group.groupId}", outdated = true)
    }!!
    assertEquals(listOf("tech-1", "tech-2"), lookedUp)
    assertEquals(
      listOf(
        CommandLinkUpdate("tech-1", "https://signal.group/#tech-1", "https://signal.group/#new-tech-1"),
        CommandLinkUpdate("tech-2", "https://signal.group/#tech-2", "https://signal.group/#new-tech-2")
      ),
      reply.linkUpdates
    )
    assertFalse(reply.text.contains("Music"))
    assertFalse(reply.text.contains("outdated"))
  }

  @Test
  fun `selected non-updating group retains its configured link and outdated marker`() {
    val source = XML.replace("<groupid>music</groupid><autoupdate>1</autoupdate>", "<groupid>music</groupid><autoupdate>0</autoupdate>")
    val reply = CommandDocument.parse(source).render("!groups 2") { CommandLinkState(currentLink = "https://signal.group/#new", outdated = true) }!!
    assertTrue(reply.text.contains("Music group (outdated link):\nhttps://signal.group/#music"))
    assertTrue(reply.linkUpdates.isEmpty())
  }

  @Test
  fun `empty category and empty index give readable replies`() {
    val reply = CommandDocument.parse(XML).render("!groups 3") { error("Empty category has no links") }!!
    assertEquals("Empty:\n\nNo groups in this category yet.", reply.text)
    val empty = CommandDocument.parse("<commands><command><triggers><trigger>!groups</trigger></triggers><response><text><b>$HEADING</b></text><br/><br/><groups view=\"categories\"/></response></command></commands>")
    assertEquals("$HEADING\n\nNo categories are available yet.", empty.render("!groups") { error("No groups") }!!.text)
    assertTrue(empty.render("!groups 1") { error("No groups") }!!.text.contains("not available"))
  }

  @Test
  fun `out of range zero negative and overflowing numbers return guidance without link checks`() {
    val document = CommandDocument.parse(XML)
    listOf("0", "-1", "4", "9999999999999999999999999999999999999999").forEach { number ->
      val reply = document.render("!groups $number") { error("Invalid category must not check links") }!!
      assertEquals("That category is not available. Send !groups to see the available categories.", reply.text)
      assertTrue(reply.styles.isEmpty())
      assertTrue(reply.linkUpdates.isEmpty())
    }
  }

  @Test
  fun `non-numeric arguments surrounding whitespace and unrelated prefixes never match`() {
    val document = CommandDocument.parse(XML)
    listOf("!groups extra", "!groups 1 extra", "!groups  1", "!groups 1 ", " !groups 1", "!groups 1\n", "!groups\t1", "!groups 1.0", "!GROUPS 1", "!groups1").forEach { input ->
      assertNull(input, document.render(input) { error("Unmatched input must not resolve links") })
    }
    assertTrue(CommandDocument.isCommandCandidate("!groups 1"))
  }

  @Test
  fun `aliases share the XML footer and use the matching alias in invalid-category guidance`() {
    val document = CommandDocument.parse(XML)
    assertEquals(
      document.render("!groups 2") { CommandLinkState() },
      document.render("#public 2") { CommandLinkState() }
    )
    assertEquals(document.render("!groups") { error("Index must not resolve") }, document.render("#public") { error("Index must not resolve") })
    assertTrue(document.render("#public 9") { error("Invalid category must not resolve") }!!.text.contains("Send #public"))
  }

  @Test
  fun `disabled catalog silences both its index and every numbered category alias`() {
    val document = CommandDocument.parse(XML.replace("<active>1</active>", "<active>0</active>"))
    listOf("!groups", "!groups 1", "!groups 99", "#public", "#public 2").forEach { input ->
      assertNull(document.render(input) { error("Disabled catalog must not resolve") })
    }
    document.setCommandsActive("#public", true)
    assertTrue(document.render("!groups 2") { CommandLinkState() } != null)
  }

  @Test
  fun `numbers and counts follow category creation group addition and moves after XML round trip`() {
    val document = CommandDocument.parse(XML)
    document.createCategory("Games")
    document.addGroup("Games", "New game group", "game", "https://signal.group/#game")
    document.moveGroup("Music", "tech-1")
    val restored = CommandDocument.parse(document.toXml())
    val index = restored.render("!groups") { error("Index must not resolve") }!!.text
    assertTrue(index.contains("1. Tech (1 group)\n2. Music (2 groups)\n3. Empty (0 groups)\n4. Games (1 group)"))
    assertTrue(restored.render("!groups 4") { CommandLinkState() }!!.text.contains("New game group:\nhttps://signal.group/#game"))
    val music = restored.render("!groups 2") { CommandLinkState() }!!.text
    assertTrue(music.indexOf("Music group") < music.indexOf("First tech group"))
    assertFalse(restored.render("!groups 1") { CommandLinkState() }!!.text.contains("First tech group"))
  }

  @Test
  fun `legacy lists keep full replies and do not create numbered commands`() {
    val document = CommandDocument.parse(XML.replace(" view=\"categories\"", ""))
    val reply = document.render("!groups") { CommandLinkState() }!!
    assertTrue(reply.text.contains("First tech group"))
    assertTrue(reply.text.contains("Music group"))
    assertNull(document.render("!groups 1") { error("Legacy list has no numbered categories") })
  }

  @Test
  fun `explicit multi-word commands retain exact matching precedence`() {
    val source = XML.replace("</commands>", "<command><triggers><trigger>!groups locale</trigger></triggers><response>Localized text</response></command></commands>")
    assertEquals("Localized text", CommandDocument.parse(source).render("!groups locale") { error("Static response") }!!.text)
  }

  @Test
  fun `XML footer is customizable without duplicated instructions and is omitted from numbered replies`() {
    val custom = "Choose a category with !groups 1."
    val source = XML.replace(FOOTER_XML, "<text><i>$custom</i></text>")
    val document = CommandDocument.parse(source)
    val index = document.render("!groups") { error("Index must not resolve links") }!!
    assertTrue(index.text.endsWith("3. Empty (0 groups)\n\n$custom"))
    assertFalse(index.text.contains("Send !groups"))
    val italic = index.styles.single { it.style == CommandStyle.ITALIC }
    assertEquals(custom, index.text.substring(italic.start, italic.start + italic.length))
    assertFalse(document.render("!groups 2") { CommandLinkState() }!!.text.contains(custom))
    assertEquals(index, CommandDocument.parse(document.toXml()).render("!groups") { error("Index must not resolve links") })
  }

  @Test
  fun `index has no implicit footer or trailing break when XML footer is removed`() {
    val source = XML.replace("<br/><br/>$FOOTER_XML", "")
    val index = CommandDocument.parse(source).render("!groups") { error("Index must not resolve links") }!!
    assertEquals("$HEADING\n\n1. Tech (2 groups)\n2. Music (1 group)\n3. Empty (0 groups)", index.text)
    assertEquals(listOf(CommandStyleRange(CommandStyle.BOLD, 0, HEADING.length)), index.styles)
  }

  companion object {
    private const val HEADING = "Explore public Signal groups"
    private const val FOOTER = "Send !groups <number> to view a category."
    private const val FOOTER_XML = "<text><i>Send !groups &lt;number&gt; to view a category.</i></text>"
    private val XML = """
      <commands><command><active>1</active><triggers><trigger>!groups</trigger><trigger>#public</trigger></triggers><response>
      <text><b>$HEADING</b></text><br/><br/><groups view="categories">
        <category name="Tech">
          <group><groupid>tech-1</groupid><autoupdate>1</autoupdate><description>First tech group</description><link>https://signal.group/#tech-1</link></group>
          <group><groupid>tech-2</groupid><autoupdate>1</autoupdate><description>Second tech group</description><link>https://signal.group/#tech-2</link></group>
        </category>
        <category name="Music"><group><groupid>music</groupid><autoupdate>1</autoupdate><description>Music group</description><link>https://signal.group/#music</link></group></category>
        <category name="Empty"/>
      </groups><br/><br/>$FOOTER_XML</response></command></commands>
    """.trimIndent()
  }
}
