/*
 * Whatnoww added file
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.github

import android.app.Application
import org.junit.Assert.assertEquals
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
    val document = CommandDocument.parse("<commands><command><triggers><trigger>!test</trigger></triggers><response><b>Android</b></response></command></commands>", androidFactory())
    val reply = document.render("!test") { error("No groups") }!!
    assertEquals("Android", reply.text)
    assertEquals(CommandStyleRange(CommandStyle.BOLD, 0, 7), reply.styles.single())
  }

  @Test(expected = IllegalArgumentException::class)
  fun `Android parsing rejects entity declarations before reading external resources`() {
    CommandDocument.parse("<!DOCTYPE commands [<!ENTITY xxe SYSTEM 'file:///etc/passwd'>]><commands>&xxe;</commands>", androidFactory())
  }

  @Test
  fun `seed renders all 46 groups with native styles and initial outdated markers`() {
    val seed = File("../docs/commands/commands.xml").readText()
    val reply = CommandDocument.parse(seed, androidFactory()).render("!groups") { CommandLinkState() }!!
    assertEquals(46, reply.styles.count { it.style == CommandStyle.ITALIC })
    assertEquals(6, reply.styles.count { it.style == CommandStyle.BOLD })
    assertEquals(13, Regex("\\(outdated link\\)").findAll(reply.text).count())
    assertTrue(reply.text.startsWith("Group Chats\n\nSignal related:"))
    assertTrue(reply.linkUpdates.isEmpty())
  }

  private fun androidFactory(): DocumentBuilderFactory = Class.forName("org.apache.harmony.xml.parsers.DocumentBuilderFactoryImpl").getDeclaredConstructor().newInstance() as DocumentBuilderFactory
}
