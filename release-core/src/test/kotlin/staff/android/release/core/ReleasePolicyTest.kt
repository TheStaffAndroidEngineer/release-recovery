package staff.android.release.core

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** Independent boundary tests; device notification delivery is a separate experiment. */
class ReleasePolicyTest {
    private class Clock(var now: Long = 1_000L)

    @Test fun noPositivePolicyExistsBeforeAcceptanceOrAfterRestart() {
        val clock = Clock()
        val first = TimelineGate { clock.now }
        assertFalse(first.enabled())
        first.accept(true, 60)
        assertTrue(first.enabled())
        assertFalse(TimelineGate { clock.now }.enabled())
        assertTrue(first.enabled(), "A different process-local gate does not revoke this live lease")
    }

    @Test fun zeroLifetimeNeverPermitsTheRiskyPath() {
        val clock = Clock(0)
        val gate = TimelineGate { clock.now }
        gate.accept(true, 0)
        assertFalse(gate.enabled())
        clock.now = 1
        assertFalse(gate.enabled())
    }

    @Test fun positiveLeaseUsesAnExclusiveExpiryBoundary() {
        val clock = Clock(2_000)
        val gate = TimelineGate { clock.now }
        gate.accept(true, 1)
        assertTrue(gate.enabled())
        clock.now = 2_999
        assertTrue(gate.enabled())
        clock.now = 3_000
        assertFalse(gate.enabled())
        clock.now = 3_001
        assertFalse(gate.enabled())
    }

    @Test fun maximumAcceptedLifetimeIsSixtySeconds() {
        val clock = Clock(0)
        val gate = TimelineGate { clock.now }
        gate.accept(true, 60)
        clock.now = 59_999
        assertTrue(gate.enabled())
        clock.now = 60_000
        assertFalse(gate.enabled())
    }

    @Test fun outOfRangePolicyLifetimesAreRejected() {
        val gate = TimelineGate { 100L }
        for (ttl in listOf(Int.MIN_VALUE, -1, 61, Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { gate.accept(true, ttl) }
            assertFalse(gate.enabled())
        }
    }

    @Test fun negativeAcceptedTimeIsRejectedWithoutIssuingALease() {
        val gate = TimelineGate { -1L }
        assertThrows(IllegalArgumentException::class.java) { gate.accept(true, 1) }
        assertFalse(gate.enabled())
    }

    @Test fun clockBeforeAcceptanceCannotAuthorize() {
        val clock = Clock(1_000)
        val gate = TimelineGate { clock.now }
        gate.accept(true, 60)
        clock.now = 999
        assertFalse(gate.enabled())
        clock.now = Long.MIN_VALUE
        assertFalse(gate.enabled(), "A reversed clock must not wrap into a valid positive age")
    }

    @Test fun nearMaximumTimeDoesNotOverflowAnAbsoluteDeadline() {
        val clock = Clock(Long.MAX_VALUE - 999)
        val gate = TimelineGate { clock.now }
        gate.accept(true, 1)
        clock.now = Long.MAX_VALUE
        assertTrue(gate.enabled(), "Age is 999ms even though acceptedAt+1000 would overflow")
        clock.now = Long.MIN_VALUE
        assertFalse(gate.enabled())
    }

    @Test fun enormousNonnegativeElapsedTimeIsExpired() {
        val clock = Clock(0)
        val gate = TimelineGate { clock.now }
        gate.accept(true, 60)
        clock.now = Long.MAX_VALUE
        assertFalse(gate.enabled())
    }

    @Test fun aNewAcceptedPolicyReplacesThePreviousLease() {
        val clock = Clock(0)
        val gate = TimelineGate { clock.now }
        gate.accept(true, 60)
        clock.now = 10_000
        gate.accept(true, 1)
        clock.now = 10_999
        assertTrue(gate.enabled())
        clock.now = 11_000
        assertFalse(gate.enabled(), "The old sixty-second lease must not survive a replacement")
    }

    @Test fun explicitDisableAndNegativePolicyContainThePathImmediately() {
        val clock = Clock()
        val gate = TimelineGate { clock.now }
        gate.accept(true, 60)
        gate.disable()
        assertFalse(gate.enabled())
        clock.now += 1
        assertFalse(gate.enabled())
        gate.accept(true, 60)
        assertTrue(gate.enabled())
        gate.accept(false, 60)
        assertFalse(gate.enabled())
    }

    @Test fun candidateKnowsTwoStatesButThrowsForTheNewServiceValue() {
        assertFalse(candidateMessage("in_transit").complete)
        assertEquals("Delivered", candidateMessage("delivered").title)
        assertTrue(candidateMessage("delivered").complete)
        assertThrows(IllegalArgumentException::class.java) {
            candidateMessage("awaiting_collection")
        }
    }

    @Test fun repairPreservesKnownMeaningAndNeverCompletesAnUnknownState() {
        assertTrue(repairedMessage("delivered").complete)
        assertFalse(repairedMessage("in_transit").complete)
        for (raw in listOf("awaiting_collection", "cancelled", "DELIVERED", "delivered ", "", "<new-state>")) {
            val message = repairedMessage(raw)
            assertFalse(message.complete, raw)
            assertEquals("A new delivery update", message.title, raw)
            assertFalse(message.title.contains(raw) && raw.isNotEmpty(), "Do not expose raw service vocabulary")
        }
    }

    @Test fun notificationCapabilityRequiresAllApplicableGates() {
        for (api in listOf(26, 32, 33, 36, 37)) {
            for (permission in listOf(false, true)) {
                for (app in listOf(false, true)) {
                    for (channel in listOf(false, true)) {
                        val actual = NotificationAccess(api, permission, app, channel).mayNotify
                        if (!app || !channel) assertFalse(actual, "App and channel gate api=$api")
                        else if (api >= 33 && !permission) assertFalse(actual, "Runtime gate api=$api")
                        else assertTrue(actual, "All applicable gates satisfied api=$api")
                    }
                }
            }
        }
    }

    @Test fun diagnosticEventAcceptsOnlyTheEditionBuildAndBoundedDeviceVocabulary() {
        val attempt = "0123456789abcdef0123456789abcdef"
        for (build in 2601..2603) {
            for (api in listOf(26, 36, 99)) {
                for (outcome in Outcome.entries) {
                    assertEquals(outcome, JourneyEvent(attempt, build, api, outcome).outcome)
                }
            }
        }
        for (build in listOf(Int.MIN_VALUE, 2600, 2604, Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { JourneyEvent(attempt, build, 36, Outcome.STARTED) }
        }
        for (api in listOf(Int.MIN_VALUE, 25, 100, Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { JourneyEvent(attempt, 2602, api, Outcome.SHOWN) }
        }
    }

    @Test fun purchaseIdsRawTextAndMalformedAttemptIdsAreNotDiagnosticIdentifiers() {
        for (attempt in listOf("cg-1042", "Maya Camera", "", "a".repeat(31), "a".repeat(33), "A".repeat(32), "g".repeat(32), "a".repeat(31) + "\n")) {
            assertThrows(IllegalArgumentException::class.java) {
                JourneyEvent(attempt, 2602, 36, Outcome.UNAVAILABLE)
            }
        }
    }
}
