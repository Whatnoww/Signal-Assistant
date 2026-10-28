/* SPDX-License-Identifier: AGPL-3.0-only */
package org.thoughtcrime.securesms.gateway

import org.junit.Assert.assertEquals
import org.junit.Test
import org.thoughtcrime.securesms.github.CommandReply

class GatewayReplyTest {
  @Test fun `gateway status and error templates substitute brace-delimited fields`() {
    val values = mapOf("gateway_status" to GatewayText("Gateway disabled"), "safe_reason" to GatewayText("Group is not configured"))
    val reply = gatewayReply(CommandReply("{gateway_status}\n{safe_reason}", emptyList(), emptyList()), values)
    assertEquals("Gateway disabled\nGroup is not configured", reply.text)
  }

  @Test fun `substitution values containing braces are literal and never expanded again`() {
    val reply = gatewayReply(CommandReply("{spamtext}", emptyList(), emptyList()), mapOf("spamtext" to GatewayText("Explain {applicant}, <b>text</b> and $1\\value")))
    assertEquals("Explain {applicant}, <b>text</b> and $1\\value", reply.text)
  }

  @Test fun `ordinary braces and unsupported placeholder names remain literal`() {
    val text = "A } brace, { text, {} and {UPPER} and {field1}"
    assertEquals(text, gatewayReply(CommandReply(text, emptyList(), emptyList()), emptyMap()).text)
  }
}
