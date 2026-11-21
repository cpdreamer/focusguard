package com.focusguard.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EscrowTest {
    private val h = 3_600_000L

    @Test fun revealedOnlyAfterDelayWhileCommitted() {
        assertFalse(Escrow.revealed(sealed = false, dueMillis = 0, nowMillis = 10 * h, commitmentActive = true))
        assertFalse(Escrow.revealed(sealed = true, dueMillis = -1, nowMillis = 10 * h, commitmentActive = true))
        assertFalse(Escrow.revealed(sealed = true, dueMillis = 72 * h, nowMillis = 71 * h, commitmentActive = true))
        assertTrue(Escrow.revealed(sealed = true, dueMillis = 72 * h, nowMillis = 72 * h, commitmentActive = true))
        assertTrue(Escrow.revealed(sealed = true, dueMillis = -1, nowMillis = 0, commitmentActive = false))
        assertFalse(Escrow.revealed(sealed = false, dueMillis = -1, nowMillis = 0, commitmentActive = false))
    }
}
