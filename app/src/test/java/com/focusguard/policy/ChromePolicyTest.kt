package com.focusguard.policy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChromePolicyTest {

    @Test
    fun `policy lists initialise and cover every google host`() {
        // Object initialisation order once crashed the guard process (googleHosts used before declared).
        val block = ChromePolicy.urlBlocklist
        assertTrue(block.isNotEmpty())
        for (host in ChromePolicy.googleHosts) {
            assertTrue("$host images", "$host/search?tbm=isch" in block)
            assertTrue("$host images (udm)", "$host/search?udm=2" in block)
            assertTrue("$host videos", "$host/search?tbm=vid" in block)
            assertTrue("$host videos (udm)", "$host/search?udm=vid*" in block)
            assertTrue("$host shorts", "$host/search?udm=39" in block)
            assertTrue("$host imgres", "$host/imgres" in block)
        }
        assertTrue("google.com" in block)
        assertTrue("images.google.com" in block)
        assertTrue("lens.google.com" in block)
        assertTrue("bing.com/images" in block)
        assertTrue("duckduckgo.com/?ia=images" in block)
    }

    @Test
    fun `allowlist never contains a blocked pattern`() {
        for (a in ChromePolicy.urlAllowlist) assertFalse(a, a in ChromePolicy.urlBlocklist)
        // a bare google.com host in the allowlist would tie with the family block; only longer hosts may appear
        for (a in ChromePolicy.urlAllowlist) assertTrue(a, a.substringBefore('/').endsWith(".google.com"))
        assertTrue("www.google.com/recaptcha" in ChromePolicy.urlAllowlist)
        assertTrue("google.fr" in ChromePolicy.googleHosts)
        assertTrue("google.com" in ChromePolicy.googleHosts)
    }
}
