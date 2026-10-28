/* SPDX-License-Identifier: AGPL-3.0-only */
package org.thoughtcrime.securesms.gateway

/** Control flow only. Throttled and emergency-paused Gateway commands stay silent. */
internal class GatewayPausedException : RuntimeException()
