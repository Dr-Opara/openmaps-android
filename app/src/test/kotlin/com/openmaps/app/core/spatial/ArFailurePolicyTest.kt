package com.openmaps.app.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArFailurePolicyTest {
    @Test fun authorizationAndConfigurationFailuresAreNeverRetried() {
        for (f in listOf(SpatialFailure.EarthUnavailable, SpatialFailure.GeospatialDisabled, SpatialFailure.GeospatialUnsupported)) {
            val r = ArFailurePolicy.decide(f, attemptsSoFar = 0)
            assertTrue("$f must fall back immediately", r is ArRecovery.Fallback)
            assertTrue("$f must be reported as permanent configuration", (r as ArRecovery.Fallback).permanentConfiguration)
        }
    }

    @Test fun transientFailuresRetryWithGrowingBackoff() {
        assertEquals(ArRecovery.Retry(1_000), ArFailurePolicy.decide(SpatialFailure.CameraUnavailable, 0))
        assertEquals(ArRecovery.Retry(2_000), ArFailurePolicy.decide(SpatialFailure.CameraUnavailable, 1))
        assertEquals(ArRecovery.Retry(4_000), ArFailurePolicy.decide(SpatialFailure.ArCoreError, 2))
    }

    @Test fun quotaBackoffIsLongerThanOrdinaryRetries() =
        assertEquals(ArRecovery.Retry(10_000), ArFailurePolicy.decide(SpatialFailure.ResourceExhausted, 0))

    @Test fun exhaustedRetriesFallBackButAreNotMarkedPermanent() {
        val r = ArFailurePolicy.decide(SpatialFailure.ArCoreError, ArFailurePolicy.MAX_RETRIES) as ArRecovery.Fallback
        assertFalse("a cause that can clear must be eligible for a fresh attempt on re-entry", r.permanentConfiguration)
    }

    @Test fun sessionCreationFailureDoesNotSpinInARetryLoop() =
        assertTrue(ArFailurePolicy.decide(SpatialFailure.SessionCreationFailed, 0) is ArRecovery.Fallback)

    @Test fun everyFailureHasAnExplanationAndNoneEchoesCredentials() {
        for (f in SpatialFailure.values()) {
            for (keyed in listOf(true, false)) {
                val text = ArFailurePolicy.explanation(f, keyed)
                assertTrue(text.isNotBlank())
                assertFalse(text.contains("AIza")) // Google API key prefix
            }
        }
    }

    @Test fun notAuthorizedExplanationDistinguishesMissingFromRejectedKey() {
        val missing = ArFailurePolicy.explanation(SpatialFailure.EarthUnavailable, keyCompiledIn = false)
        val rejected = ArFailurePolicy.explanation(SpatialFailure.EarthUnavailable, keyCompiledIn = true)
        assertTrue(missing.contains("no ARCORE_API_KEY"))
        assertTrue(rejected.contains("SHA-1"))
    }
}
