/*
 * Whatnoww added file
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.database

import android.app.Application
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.core.models.ServiceId.ACI
import org.signal.storageservice.storage.protos.groups.local.DecryptedGroup
import org.signal.storageservice.storage.protos.groups.local.DecryptedMember
import org.signal.storageservice.storage.protos.groups.local.DecryptedPendingMember
import org.signal.storageservice.storage.protos.groups.local.DecryptedRequestingMember
import org.thoughtcrime.securesms.github.GitHubBlocklistUpdater
import org.thoughtcrime.securesms.groups.GroupId
import org.thoughtcrime.securesms.mms.OutgoingMessage
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.sms.MessageSender
import org.thoughtcrime.securesms.testutil.RecipientTestRule
import org.whispersystems.signalservice.api.groupsv2.GroupChangeReconstruct

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class AciBlocklistReportingTest {

  @get:Rule
  val recipients = RecipientTestRule()

  private val reportingGroup = GroupId.parseOrThrow("__signal_group__v2__!ec3eec474383dfb32495497353a3fe4450325a46824075e58c872ca327774162").requireV2()
  private val adminGroup = GroupId.parseOrThrow("__signal_group__v2__!ad56ed63e15f7b71285b3e96efef739d3ca78797150b3e0f7141f9fe130bf2e4").requireV2()
  private val otherGroup = GroupId.parseOrThrow("__signal_group__v2__!1111111111111111111111111111111111111111111111111111111111111111").requireV2()
  private val sentMessages = mutableListOf<OutgoingMessage>()
  private lateinit var aci: ACI

  @Before
  fun setUp() {
    aci = Recipient.resolved(recipients.createRecipient("Reported Account")).requireAci()
    mockkStatic(MessageSender::class)
    every { MessageSender.send(any(), capture(sentMessages), any(), any(), any(), any()) } returns 1L
    mockkObject(GitHubBlocklistUpdater)
    every { GitHubBlocklistUpdater.appendAci(any()) } returns true
    mockkObject(GroupTable.Companion)
    every { GroupTable.getBlocklist() } returns emptySet()
  }

  @After
  fun tearDown() {
    unmockkObject(GroupTable.Companion, GitHubBlocklistUpdater)
    unmockkStatic(MessageSender::class)
  }

  @Test
  fun `comma separated ACIs match even with trailing commas and CRLF`() {
    val otherAci = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
    val blocklist = GroupTable.parseBlocklist("\r\n ${aci.toString().uppercase()},\r\n$otherAci, \n${aci},\n")

    assertEquals(setOf(aci.toString(), otherAci), blocklist)
  }

  @Test
  fun `added member in reporting group sends ACI and appends blocklist without needing a match`() {
    reportChange(reportingGroup, DecryptedGroup(members = listOf(DecryptedMember(aciBytes = aci.toByteString()))))

    assertEquals("Reported Account $aci", sentMessages.single().body)
    assertEquals(reportingGroup, sentMessages.single().threadRecipient.groupId.orElseThrow())
    verify(exactly = 1) { GitHubBlocklistUpdater.appendAci(aci.toString()) }
    verify(exactly = 0) { GroupTable.getBlocklist() }
  }

  @Test
  fun `added member in admin group sends ACI without updating blocklist`() {
    reportChange(adminGroup, DecryptedGroup(members = listOf(DecryptedMember(aciBytes = aci.toByteString()))))

    assertEquals("Reported Account $aci", sentMessages.single().body)
    verify(exactly = 0) { GitHubBlocklistUpdater.appendAci(any()) }
  }

  @Test
  fun `approved join request in reporting group announces and reports the new member`() {
    val before = DecryptedGroup(requestingMembers = listOf(DecryptedRequestingMember(aciBytes = aci.toByteString())))
    val after = DecryptedGroup(members = listOf(DecryptedMember(aciBytes = aci.toByteString())))

    reportChange(reportingGroup, after, before)

    assertEquals("Reported Account $aci", sentMessages.single().body)
    verify(exactly = 1) { GitHubBlocklistUpdater.appendAci(aci.toString()) }
  }

  @Test
  fun `accepted invitation in admin group announces the new member`() {
    val before = DecryptedGroup(pendingMembers = listOf(DecryptedPendingMember(serviceIdBytes = aci.toByteString())))
    val after = DecryptedGroup(members = listOf(DecryptedMember(aciBytes = aci.toByteString())))

    reportChange(adminGroup, after, before)

    assertEquals("Reported Account $aci", sentMessages.single().body)
    verify(exactly = 0) { GitHubBlocklistUpdater.appendAci(any()) }
  }

  @Test
  fun `added member outside reporting groups does not announce or update blocklist`() {
    reportChange(otherGroup, DecryptedGroup(members = listOf(DecryptedMember(aciBytes = aci.toByteString()))))

    assertEquals(emptyList<OutgoingMessage>(), sentMessages)
    verify(exactly = 0) { GitHubBlocklistUpdater.appendAci(any()) }
  }

  @Test
  fun `blocklisted join request sends warning to the requesting group`() {
    every { GroupTable.getBlocklist() } returns GroupTable.parseBlocklist("$aci,\r\n")

    reportChange(otherGroup, DecryptedGroup(requestingMembers = listOf(DecryptedRequestingMember(aciBytes = aci.toByteString()))))

    assertEquals("!!! User: Reported Account ($aci) in blocklist has requested to join.", sentMessages.single().body)
    assertEquals(otherGroup, sentMessages.single().threadRecipient.groupId.orElseThrow())
    verify(exactly = 0) { GitHubBlocklistUpdater.appendAci(any()) }
  }

  @Test
  fun `unlisted join request does not send a warning`() {
    reportChange(otherGroup, DecryptedGroup(requestingMembers = listOf(DecryptedRequestingMember(aciBytes = aci.toByteString()))))

    assertEquals(emptyList<OutgoingMessage>(), sentMessages)
  }

  @Test
  fun `existing members and requests are not announced again on unrelated group updates`() {
    val before = DecryptedGroup(
      members = listOf(DecryptedMember(aciBytes = aci.toByteString())),
      requestingMembers = listOf(DecryptedRequestingMember(aciBytes = aci.toByteString()))
    )

    reportChange(reportingGroup, before.copy(title = "New title"), before)

    assertEquals(emptyList<OutgoingMessage>(), sentMessages)
    verify(exactly = 0) { GitHubBlocklistUpdater.appendAci(any()) }
    verify(exactly = 0) { GroupTable.getBlocklist() }
  }

  private fun reportChange(groupId: GroupId.V2, after: DecryptedGroup, before: DecryptedGroup = DecryptedGroup()) {
    val change = GroupChangeReconstruct.reconstructGroupChange(before, after)
    SignalDatabase.groups.reportAciBlocklistChange(groupId, change)
  }
}
