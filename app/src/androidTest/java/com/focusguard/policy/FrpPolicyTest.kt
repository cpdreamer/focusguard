package com.focusguard.policy

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs on a device where the app is device owner (`adb shell dpm set-device-owner
 * com.focusguard/.policy.GuardAdminReceiver` on a wiped emulator). Checks that the FRP policy is
 * accepted by the platform, read back, and cleared.
 */
@RunWith(AndroidJUnit4::class)
class FrpPolicyTest {
    private val policy = PolicyController(InstrumentationRegistry.getInstrumentation().targetContext)

    @Test fun frpPolicyIsSetReadBackAndCleared() {
        assumeTrue("needs device owner", policy.isDeviceOwner)
        assumeTrue("needs an FRP partition (ro.frp.pst); the stock emulator has none", policy.frpSupported())
        val id = "100000000000000000001"
        assertTrue(policy.applyFrp(id))
        assertEquals(listOf(id), policy.frpAccounts())
        assertTrue(policy.applyFrp(null))
        assertEquals(emptyList<String>(), policy.frpAccounts())
    }
}
