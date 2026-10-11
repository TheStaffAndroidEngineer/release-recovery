package staff.android.release.rollout

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CohortGateTest {
    private val gate: RolloutGate = when (System.getProperty("rollout.solution", "reference")) {
        "reference" -> ReferenceRollout
        "learner" -> LearnerRollout
        else -> error("Unknown rollout.solution")
    }
    private val policy = GatePolicy(listOf("phone"), 20, 9_000, 1_000, 200)
    private fun sample(failed: Long = 0, completed: Long = 100, eligible: Long = completed, id: String = "phone") =
        CohortSample(id, eligible, completed, failed)
    private fun report(b: CohortSample = sample(), c: CohortSample = sample(), p: GatePolicy = policy) =
        gate.evaluate(p, listOf(b), listOf(c))

    @Test fun healthyCompleteCohortContinuesAndPreservesItsCounts() {
        val actual = report(sample(2), sample(3))
        assertEquals(GateReport(Disposition.Continue, listOf(CohortAssessment("phone", sample(2), sample(3), emptyList()))), actual)
    }
    @Test fun pooledImprovementCannotHideOneRequiredCohortRegression() {
        val p = policy.copy(requiredCohorts = listOf("older-os", "newer-os"))
        val b = listOf(sample(20, 1_000, id="older-os"), sample(2, id="newer-os"))
        val c = listOf(sample(10, 1_000, id="older-os"), sample(8, id="newer-os"))
        val actual = gate.evaluate(p, b, c)
        assertEquals(Disposition.Halt, actual.disposition)
        assertEquals(listOf(GateIssue.Regression), actual.cohorts.first().issues)
        assertEquals("newer-os", actual.cohorts.first().cohort)
    }
    @Test fun candidateAbsoluteBreachHaltsEvenWithoutABaseline() {
        val actual = gate.evaluate(policy, emptyList(), listOf(sample(11)))
        assertEquals(Disposition.Halt, actual.disposition)
        assertEquals(listOf(GateIssue.MissingBaseline, GateIssue.CandidateFailureRate), actual.cohorts.single().issues)
    }
    @Test fun knownBreachDominatesMissingEvidenceInAnotherCohort() {
        val actual = gate.evaluate(policy.copy(requiredCohorts=listOf("phone", "tablet")), listOf(sample()), listOf(sample(11)))
        assertEquals(Disposition.Halt, actual.disposition)
        assertEquals(listOf(GateIssue.MissingBaseline, GateIssue.MissingCandidate), actual.cohorts.last().issues)
    }
    @Test fun missingCandidateIsHoldNotZeroFailures() {
        val actual = gate.evaluate(policy, listOf(sample()), emptyList())
        assertEquals(Disposition.Hold, actual.disposition)
        assertEquals(listOf(GateIssue.MissingCandidate), actual.cohorts.single().issues)
    }
    @Test fun noExposureHasUndefinedCoverageAndASeparateReason() {
        val actual = report(c=sample(0, 0, 0))
        assertEquals(Disposition.Hold, actual.disposition)
        assertEquals(listOf(GateIssue.NoCandidateExposure), actual.cohorts.single().issues)
    }
    @Test fun minimumEvidenceGatesBothFailureAndRegressionComparisons() {
        val actual = report(c=sample(1, 1))
        assertEquals(Disposition.Hold, actual.disposition)
        assertEquals(listOf(GateIssue.CandidateTooSmall), actual.cohorts.single().issues)
    }
    @Test fun missingTerminalOutcomesDoNotBecomeSuccessfulJourneys() {
        val actual = report(c=sample(0, 80, 100))
        assertEquals(Disposition.Hold, actual.disposition)
        assertEquals(listOf(GateIssue.CandidateCoverageLow), actual.cohorts.single().issues)
    }
    @Test fun sampleSizeAndCoverageCanBothBeInsufficient() {
        val actual = report(c=sample(5, 10, 100))
        assertEquals(Disposition.Hold, actual.disposition)
        assertEquals(listOf(GateIssue.CandidateTooSmall, GateIssue.CandidateCoverageLow), actual.cohorts.single().issues)
    }
    @Test fun baselineNeedsItsOwnCoverageBeforeRelativeComparison() {
        val actual = report(b=sample(0, 50, 100), c=sample(4))
        assertEquals(Disposition.Hold, actual.disposition)
        assertEquals(listOf(GateIssue.BaselineCoverageLow), actual.cohorts.single().issues)
    }
    @Test fun exactFailureAndRegressionThresholdsAreAllowed() {
        assertEquals(Disposition.Continue, report(sample(8), sample(10)).disposition)
    }
    @Test fun exactMinimumAndCoverageAreAllowed() {
        assertEquals(Disposition.Continue, report(sample(0, 20), sample(0, 20)).disposition)
        assertEquals(Disposition.Continue, report(sample(0, 90, 100), sample(0, 90, 100)).disposition)
    }
    @Test fun failureUsesCompletedRatherThanEligibleDenominator() {
        val actual = report(sample(8, 90, 100), sample(9, 90, 100), policy.copy(maximumFailureBp=950))
        assertEquals(Disposition.Halt, actual.disposition)
        assertEquals(listOf(GateIssue.CandidateFailureRate), actual.cohorts.single().issues)
    }
    @Test fun fractionalBasisPointAboveLimitIsNotRoundedDown() {
        val p = policy.copy(minimumCompleted=1, maximumFailureBp=3333, maximumRegressionBp=10_000)
        val actual = report(sample(1, 3), sample(1, 3), p)
        assertEquals(Disposition.Halt, actual.disposition)
        assertEquals(listOf(GateIssue.CandidateFailureRate), actual.cohorts.single().issues)
    }
    @Test fun relativeComparisonKeepsDifferentDenominatorsExact() {
        val p = policy.copy(minimumCompleted=1, maximumFailureBp=10_000, maximumRegressionBp=1666)
        val actual = report(sample(1, 3), sample(1, 2), p)
        assertEquals(Disposition.Halt, actual.disposition)
        assertEquals(listOf(GateIssue.Regression), actual.cohorts.single().issues)
    }
    @Test fun improvementDoesNotBecomeUnsignedRegression() {
        val actual = report(sample(10), sample(1), policy.copy(maximumRegressionBp=0))
        assertEquals(Disposition.Continue, actual.disposition)
    }
    @Test fun zeroFailureToleranceRejectsAnyQualifiedFailure() {
        assertEquals(Disposition.Halt, report(sample(), sample(1), policy.copy(maximumFailureBp=0)).disposition)
    }
    @Test fun upperPolicyBoundaryAcceptsACompleteFailureRate() {
        val p = policy.copy(maximumFailureBp=10_000, maximumRegressionBp=10_000)
        assertEquals(Disposition.Continue, report(sample(), sample(100), p).disposition)
    }
    @Test fun longMaximumCountsCannotOverflowTheAbsoluteComparison() {
        val p = policy.copy(maximumFailureBp=9999, maximumRegressionBp=10_000)
        assertEquals(Disposition.Halt, report(sample(0, Long.MAX_VALUE), sample(Long.MAX_VALUE, Long.MAX_VALUE), p).disposition)
    }
    @Test fun aSingleFailureDifferenceAtLongMaximumIsNotLostToDoubleRounding() {
        val p = policy.copy(maximumFailureBp=10_000, maximumRegressionBp=0)
        val actual = report(sample(Long.MAX_VALUE-1, Long.MAX_VALUE), sample(Long.MAX_VALUE, Long.MAX_VALUE), p)
        assertEquals(Disposition.Halt, actual.disposition)
        assertEquals(listOf(GateIssue.Regression), actual.cohorts.single().issues)
    }
    @Test fun validUnrequestedCohortsAreValidatedButNotPooledOrReported() {
        val actual = gate.evaluate(policy, listOf(sample(), sample(100, id="extra")), listOf(sample(), sample(100, id="extra")))
        assertEquals(Disposition.Continue, actual.disposition)
        assertEquals(listOf("phone"), actual.cohorts.map { it.cohort })
    }
    @Test fun assessmentsAndIssuesHaveStableDefinedOrder() {
        val actual = gate.evaluate(policy.copy(requiredCohorts=listOf("z", "a")), emptyList(), emptyList())
        assertEquals(listOf("a", "z"), actual.cohorts.map { it.cohort })
        assertTrue(actual.cohorts.all { it.issues == listOf(GateIssue.MissingBaseline, GateIssue.MissingCandidate) })
    }
    @Test fun laterCallerMutationCannotChangeTheReport() {
        val required = mutableListOf("phone")
        val b = mutableListOf(sample(2)); val c = mutableListOf(sample(3))
        val actual = gate.evaluate(policy.copy(requiredCohorts=required), b, c)
        required.clear(); b.clear(); c[0] = sample(100)
        assertEquals(listOf("phone"), actual.cohorts.map { it.cohort })
        assertEquals(3L, actual.cohorts.single().candidate!!.failedJourneys)
    }
    @Test fun returnedCollectionViewsCannotBeMutated() {
        val actual = gate.evaluate(policy, emptyList(), emptyList())
        assertThrows(UnsupportedOperationException::class.java) { (actual.cohorts as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (actual.cohorts.single().issues as MutableList).clear() }
    }
    @Test fun duplicateCohortInAnArmIsInvalid() {
        assertThrows(IllegalArgumentException::class.java) { gate.evaluate(policy, listOf(sample(), sample()), emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { gate.evaluate(policy, emptyList(), listOf(sample(), sample())) }
    }
    @Test fun malformedUnusedSampleStillFailsBeforeAnyDecision() {
        assertThrows(IllegalArgumentException::class.java) { gate.evaluate(policy, emptyList(), listOf(sample(11), sample(-1, id="extra"))) }
    }
    @Test fun negativeEligibleCountIsInvalid() {
        assertThrows(IllegalArgumentException::class.java) { report(c=sample(0, 0, -1)) }
    }
    @Test fun completedCountMustBeInsideTheEligiblePopulation() {
        assertThrows(IllegalArgumentException::class.java) { report(c=sample(0, -1, 100)) }
        assertThrows(IllegalArgumentException::class.java) { report(c=sample(0, 101, 100)) }
    }
    @Test fun failureCountMustBeInsideCompletedOutcomes() {
        assertThrows(IllegalArgumentException::class.java) { report(c=sample(-1)) }
        assertThrows(IllegalArgumentException::class.java) { report(c=sample(101)) }
    }
    @Test fun idsAreExactBoundedLowercaseIdentifiers() {
        for (id in listOf("", "Phone", " phone", "phone/tablet", "a".repeat(33))) {
            assertThrows(IllegalArgumentException::class.java) { report(c=sample(id=id)) }
        }
    }
    @Test fun requiredPopulationCannotBeEmptyOrDuplicate() {
        assertThrows(IllegalArgumentException::class.java) { report(p=policy.copy(requiredCohorts=emptyList())) }
        assertThrows(IllegalArgumentException::class.java) { report(p=policy.copy(requiredCohorts=listOf("phone", "phone"))) }
    }
    @Test fun policyBoundsAreValidatedEvenWhenAllSamplesAreMissing() {
        val invalid = listOf(policy.copy(minimumCompleted=0), policy.copy(minimumCoverageBp=-1), policy.copy(minimumCoverageBp=10_001), policy.copy(maximumFailureBp=-1), policy.copy(maximumFailureBp=10_001), policy.copy(maximumRegressionBp=-1), policy.copy(maximumRegressionBp=10_001))
        for (p in invalid) assertThrows(IllegalArgumentException::class.java) { gate.evaluate(p, emptyList(), emptyList()) }
    }
    @Test fun boundedInputSizesAreEnforced() {
        val many = (0..100).map { "c$it" }
        assertThrows(IllegalArgumentException::class.java) { gate.evaluate(policy.copy(requiredCohorts=many), emptyList(), emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { gate.evaluate(policy, many.map { sample(id=it) }, emptyList()) }
    }
    @Test fun zeroCoverageThresholdStillCannotInventUnobservedSuccess() {
        val actual = report(c=sample(0, 0, 100), p=policy.copy(minimumCoverageBp=0))
        assertEquals(Disposition.Hold, actual.disposition)
        assertEquals(listOf(GateIssue.CandidateTooSmall), actual.cohorts.single().issues)
    }
}
