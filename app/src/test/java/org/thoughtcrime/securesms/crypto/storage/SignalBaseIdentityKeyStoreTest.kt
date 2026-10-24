package org.thoughtcrime.securesms.crypto.storage

import android.content.Context
import io.mockk.every
import io.mockk.mockk
// Whatnoww changed: Added static mocking imports for identity acceptance tests. mackkStatic and unmockkStatic
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
// Whatnoww changed: Added the recipient ID import for identity acceptance tests.
import org.signal.core.models.recipients.RecipientId
import org.signal.core.models.ServiceId
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.state.IdentityKeyStore
// Whatnoww changed: Added database and recipient lookup imports for identity acceptance tests.
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.recipients.RecipientIds
import org.thoughtcrime.securesms.database.IdentityTable
import org.thoughtcrime.securesms.database.model.IdentityStoreRecord
import org.thoughtcrime.securesms.keyvalue.AccountValues
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.whispersystems.signalservice.test.LibSignalLibraryUtil.assumeLibSignalSupportedOnOS
import java.util.UUID

class SignalBaseIdentityKeyStoreTest {
  companion object {
    private val ADDRESS = ServiceId.parseOrThrow(UUID.randomUUID().toString()).toString()
  }

  @Before
  fun ensureNativeSupported() {
    assumeLibSignalSupportedOnOS()
  }

  @Test
  fun `getIdentity() hits disk on first retrieve but not the second`() {
    val mockDb = mockk<IdentityTable>()
    val subject = SignalBaseIdentityKeyStore(mockk<Context>(), mockDb)
    val identityKey = IdentityKey(ECPublicKey.fromPublicKeyBytes(ByteArray(32)))
    val record = mockRecord(ADDRESS, identityKey)

    every { mockDb.getIdentityStoreRecord(ADDRESS) } returns record

    assertEquals(identityKey, subject.getIdentity(SignalProtocolAddress(ADDRESS, 1)))
    verify(exactly = 1) { mockDb.getIdentityStoreRecord(ADDRESS) }

    assertEquals(identityKey, subject.getIdentity(SignalProtocolAddress(ADDRESS, 1)))
    verify(exactly = 1) { mockDb.getIdentityStoreRecord(ADDRESS) }
  }

  @Test
  fun `invalidate() evicts cache entry`() {
    val mockDb = mockk<IdentityTable>()
    val subject = SignalBaseIdentityKeyStore(mockk<Context>(), mockDb)
    val identityKey = IdentityKey(ECPublicKey.fromPublicKeyBytes(ByteArray(32)))
    val record = mockRecord(ADDRESS, identityKey)

    every { mockDb.getIdentityStoreRecord(ADDRESS) } returns record

    assertEquals(identityKey, subject.getIdentity(SignalProtocolAddress(ADDRESS, 1)))
    verify(exactly = 1) { mockDb.getIdentityStoreRecord(ADDRESS) }

    subject.invalidate(ADDRESS)

    assertEquals(identityKey, subject.getIdentity(SignalProtocolAddress(ADDRESS, 1)))
    verify(exactly = 2) { mockDb.getIdentityStoreRecord(ADDRESS) }
  }

  @Test
  fun `isTrustedIdentity() treats any casing of the self aci as self`() {
    val selfAci = ServiceId.ACI.from(UUID.randomUUID())
    val selfIdentityKey = IdentityKeyPair.generate()
    val account = mockk<AccountValues> {
      every { requireAci() } returns selfAci
      every { pni } returns null
      every { e164 } returns null
      every { aciIdentityKey } returns selfIdentityKey
    }
    mockkObject(SignalStore)
    every { SignalStore.account } returns account

    try {
      val subject = SignalBaseIdentityKeyStore(mockk<Context>(), mockk<IdentityTable>())
      val address = SignalProtocolAddress(selfAci.toString().uppercase(), 2)

      assertFalse(subject.isTrustedIdentity(address, IdentityKey(ECKeyPair.generate().publicKey), IdentityKeyStore.Direction.RECEIVING))
      assertTrue(subject.isTrustedIdentity(address, selfIdentityKey.publicKey, IdentityKeyStore.Direction.RECEIVING))
      // Whatnoww changed: Also check that automatic acceptance preserves self-identity checks when sending.
      // Next two lines
      assertFalse(subject.isTrustedIdentity(address, IdentityKeyPair.generate().publicKey, IdentityKeyStore.Direction.SENDING))
      assertTrue(subject.isTrustedIdentity(address, selfIdentityKey.publicKey, IdentityKeyStore.Direction.SENDING))
    } finally {
      unmockkObject(SignalStore)
    }
  }

  // Whatnoww changed: Added regression coverage for automatic acceptance and cached approvals.
  // LLM regression test below
  @Test
  fun `sending accepts a pending changed identity and clears previous verification`() {
    val serviceId = ServiceId.parseOrThrow(ADDRESS)
    val recipientId = RecipientId.from(42)
    val identityKey = IdentityKeyPair.generate().publicKey
    val mockDb = mockk<IdentityTable>(relaxed = true)
    val account = mockk<AccountValues> {
      every { requireAci() } returns ServiceId.ACI.from(UUID.randomUUID())
      every { pni } returns null
      every { e164 } returns null
    }
    every { mockDb.getIdentityStoreRecord(ADDRESS) } returns IdentityStoreRecord(
      ADDRESS, identityKey, IdentityTable.VerifiedStatus.UNVERIFIED, false, System.currentTimeMillis(), false
    )
    every { mockDb.writableDatabase } returns mockk(relaxed = true)
    mockkObject(SignalStore, SignalDatabase.Companion)
    mockkStatic(RecipientIds::class)
    every { SignalStore.account } returns account
    every { SignalDatabase.identities } returns mockDb
    every { RecipientIds.from(serviceId) } returns recipientId

    try {
      val subject = SignalBaseIdentityKeyStore(mockk<Context>(), mockDb)
      assertTrue(subject.isTrustedIdentity(SignalProtocolAddress(ADDRESS, 1), identityKey, IdentityKeyStore.Direction.SENDING))
      verify(exactly = 1) { mockDb.setVerified(ADDRESS, recipientId, identityKey, IdentityTable.VerifiedStatus.DEFAULT) }
      verify(exactly = 1) { mockDb.setApproval(ADDRESS, recipientId, true) }
      // Repeated sends use the accepted cache entry without writing approval again.
      assertTrue(subject.isTrustedIdentity(SignalProtocolAddress(ADDRESS, 1), identityKey, IdentityKeyStore.Direction.SENDING))
      verify(exactly = 1) { mockDb.setApproval(ADDRESS, recipientId, true) }
    } finally {
      unmockkObject(SignalStore, SignalDatabase.Companion)
      unmockkStatic(RecipientIds::class)
    }
  }

  private fun mockRecord(addressName: String, identityKey: IdentityKey): IdentityStoreRecord {
    return IdentityStoreRecord(
      addressName = addressName,
      identityKey = identityKey,
      verifiedStatus = IdentityTable.VerifiedStatus.DEFAULT,
      firstUse = false,
      timestamp = 1,
      nonblockingApproval = true
    )
  }
}
