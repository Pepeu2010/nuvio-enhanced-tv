package com.nuvio.tv.core.diagnostics

import com.nuvio.tv.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ForkIdentityTest {
    @Test
    fun applicationAndUpdaterUseIndependentForkIdentity() {
        assertFalse(BuildConfig.APPLICATION_ID.startsWith("com.nuvio"))
        assertEquals("Pepeu2010", BuildConfig.GITHUB_OWNER)
        assertEquals("nuvio-enhanced-tv", BuildConfig.GITHUB_REPO)
    }
}
