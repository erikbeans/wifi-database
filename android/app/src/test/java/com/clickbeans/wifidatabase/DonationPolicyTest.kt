package com.clickbeans.wifidatabase

import com.clickbeans.wifidatabase.domain.DonationPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DonationPolicyTest {

    @Test
    fun `never before the third confirmed-working password`() {
        assertFalse(DonationPolicy.shouldAsk(neverAsk = false, askCount = 0, successfulConfirms = 0, confirmsSinceLastAsk = 0))
        assertFalse(DonationPolicy.shouldAsk(neverAsk = false, askCount = 0, successfulConfirms = 2, confirmsSinceLastAsk = 2))
        assertTrue(DonationPolicy.shouldAsk(neverAsk = false, askCount = 0, successfulConfirms = 3, confirmsSinceLastAsk = 3))
    }

    @Test
    fun `never flag suppresses permanently`() {
        assertFalse(DonationPolicy.shouldAsk(neverAsk = true, askCount = 0, successfulConfirms = 99, confirmsSinceLastAsk = 99))
    }

    @Test
    fun `at most two asks per lifetime`() {
        assertFalse(DonationPolicy.shouldAsk(neverAsk = false, askCount = 2, successfulConfirms = 50, confirmsSinceLastAsk = 50))
    }

    @Test
    fun `not now defers until ten further confirms`() {
        assertFalse(DonationPolicy.shouldAsk(neverAsk = false, askCount = 1, successfulConfirms = 10, confirmsSinceLastAsk = 9))
        assertTrue(DonationPolicy.shouldAsk(neverAsk = false, askCount = 1, successfulConfirms = 11, confirmsSinceLastAsk = 10))
    }
}
