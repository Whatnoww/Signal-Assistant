/* SPDX-License-Identifier: AGPL-3.0-only */
package org.thoughtcrime.securesms.github

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SilentGroupsTest {
  @Test
  fun `silent entries are absent from full lists and never resolve their invite links`() {
    val document = document(categories = false)
    val resolved = mutableListOf<String>()
    val reply = document.render("!groups") { resolved += it.groupId; CommandLinkState() }!!
    assertEquals(listOf("public"), resolved)
    assertTrue(reply.text.contains("Visible group"))
    assertFalse(reply.text.contains("Secret"))
    assertFalse(reply.text.contains("private-link"))
    assertFalse(reply.text.contains("private-id"))
    assertFalse(reply.text.contains("Hidden category"))
  }

  @Test
  fun `indexes count only visible groups and hide categories containing only silent entries`() {
    val document = document()
    val index = document.render("!groups") { error("Indexes cannot resolve links") }!!.text
    assertEquals("1. Mixed (1 group)\n2. Empty (0 groups)", index)
    assertFalse(index.contains("Hidden"))
    assertFalse(index.contains("Secret"))
  }

  @Test
  fun `numbered aliases use the same visible category mapping and cannot reveal hidden categories`() {
    val document = document()
    val first = document.render("!groups 1") { group ->
      assertEquals("public", group.groupId)
      CommandLinkState()
    }!!
    assertTrue(first.text.startsWith("Mixed:"))
    assertFalse(first.text.contains("Secret"))
    assertEquals(first, document.render("!links 1") { CommandLinkState() })
    assertTrue(document.render("!groups 2") { error("Empty category") }!!.text.contains("No groups in this category"))
    assertTrue(document.render("!groups 3") { error("Hidden category must not be selected") }!!.text.contains("not available"))
  }

  @Test
  fun `standalone silent entries cannot leak or require an invite link`() {
    val xml = """<commands><command><triggers><trigger>!groups</trigger></triggers><response><groups>
      <group><groupid>private-id</groupid><description>Secret</description><autoupdate>0</autoupdate><silent>1</silent></group>
    </groups></response></command></commands>"""
    val document = CommandDocument.parse(xml)
    assertEquals("", document.render("!groups") { error("Silent group must not resolve") }!!.text)
    assertTrue(document.listedGroups("private-id").single().silent)
  }

  @Test
  fun `missing or disabled silent flags preserve public visibility`() {
    listOf("", "<silent>0</silent>").forEach { flag ->
      val xml = """<commands><command><triggers><trigger>!groups</trigger></triggers><response><groups><category name="Public">
        <group><groupid>public</groupid><description>Visible group</description><autoupdate>0</autoupdate><link>https://signal.group/#public</link>$flag</group>
      </category></groups></response></command></commands>"""
      assertTrue(CommandDocument.parse(xml).render("!groups") { CommandLinkState() }!!.text.contains("Visible group"))
    }
  }

  @Test
  fun `invalid silent flags fail closed instead of publishing the group`() {
    listOf("true", "2", "").forEach { flag ->
      val source = document().toXml().replace("<silent>1</silent>", "<silent>$flag</silent>")
      assertThrows(IllegalArgumentException::class.java) { CommandDocument.parse(source).render("!groups") { error("Invalid policy must not resolve a link") } }
    }
    val duplicate = document().toXml().replace("<silent>1</silent>", "<silent>0</silent><silent>1</silent>")
    assertThrows(IllegalArgumentException::class.java) { CommandDocument.parse(duplicate).render("!groups") { error("Ambiguous visibility must not resolve a link") } }
  }

  @Test
  fun `admin changes retain silent metadata and do not publish the group`() {
    val document = document()
    document.enableGroupAutoUpdate("Secret source", "private-id", CommandLinkState(currentLink = "https://signal.group/#new-private"))
    document.moveGroup("Mixed", "private-id")
    val restored = CommandDocument.parse(document.toXml())
    val entry = restored.listedGroups("private-id").single()
    assertTrue(entry.silent)
    assertTrue(entry.autoUpdate)
    assertEquals("https://signal.group/#new-private", entry.link)
    assertFalse(restored.render("!groups 1") { group -> assertFalse(group.silent); CommandLinkState() }!!.text.contains("Secret"))
  }

  private fun document(categories: Boolean = true): CommandDocument = CommandDocument.parse("""
    <commands><command><active>1</active><triggers><trigger>!groups</trigger><trigger>!links</trigger></triggers><response>
      <groups ${if (categories) "view=\"categories\"" else ""}>
        <category name="Hidden category">
          <group><groupid>private-id</groupid><description>Secret source</description><autoupdate>1</autoupdate><link>https://signal.group/#private-link</link><silent>1</silent></group>
        </category>
        <category name="Mixed">
          <group><groupid>public</groupid><description>Visible group</description><autoupdate>0</autoupdate><link>https://signal.group/#public</link><silent>0</silent></group>
          <group><groupid>other-private</groupid><description>Secret second</description><autoupdate>0</autoupdate><link>https://signal.group/#other-private-link</link><silent>1</silent></group>
        </category>
        <category name="Empty"/>
      </groups>
    </response></command></commands>
  """.trimIndent())
}
