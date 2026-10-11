package staff.android.release.rollout

import java.math.BigInteger
import java.util.Collections

/** Counts from one agreed window and eligibility/outcome definition, not raw events. */
data class CohortSample(
    val cohort: String,
    val eligibleJourneys: Long,
    val completedJourneys: Long,
    val failedJourneys: Long,
)
data class GatePolicy(
    val requiredCohorts: List<String>,
    val minimumCompleted: Long,
    val minimumCoverageBp: Int,
    val maximumFailureBp: Int,
    val maximumRegressionBp: Int,
)
enum class Disposition { Continue, Hold, Halt }
enum class GateIssue {
    MissingBaseline, MissingCandidate,
    NoBaselineExposure, NoCandidateExposure,
    BaselineTooSmall, CandidateTooSmall,
    BaselineCoverageLow, CandidateCoverageLow,
    CandidateFailureRate, Regression,
}
data class CohortAssessment(
    val cohort: String,
    val baseline: CohortSample?,
    val candidate: CohortSample?,
    val issues: List<GateIssue>,
)
data class GateReport(
    val disposition: Disposition,
    val cohorts: List<CohortAssessment>,
)
fun interface RolloutGate {
    fun evaluate(
        policy: GatePolicy,
        baseline: List<CohortSample>,
        candidate: List<CohortSample>,
    ): GateReport
}

/** Deliberately unfinished. Keep learner work separate from ReferenceRollout. */
object LearnerRollout : RolloutGate {
    override fun evaluate(
        policy: GatePolicy,
        baseline: List<CohortSample>,
        candidate: List<CohortSample>,
    ): GateReport = GateReport(Disposition.Continue, emptyList())
}

/** Inputs must remain stable while this synchronous function copies them. */
object ReferenceRollout : RolloutGate {
    override fun evaluate(
        policy: GatePolicy,
        baseline: List<CohortSample>,
        candidate: List<CohortSample>,
    ): GateReport {
        val required = policy.requiredCohorts.toList()
        val before = baseline.toList()
        val after = candidate.toList()
        validate(policy, required, before, after)
        val baselineById = before.associateBy { it.cohort }
        val candidateById = after.associateBy { it.cohort }
        val assessments = required.sorted().map { id ->
            val b = baselineById[id]
            val c = candidateById[id]
            val issues = mutableListOf<GateIssue>()
            val baselineReady = ready(b, policy, true, issues)
            val candidateReady = ready(c, policy, false, issues)
            if (candidateReady && exceeds(c!!, policy.maximumFailureBp)) {
                issues += GateIssue.CandidateFailureRate
            }
            if (baselineReady && candidateReady &&
                regressed(b!!, c!!, policy.maximumRegressionBp)) {
                issues += GateIssue.Regression
            }
            CohortAssessment(id, b, c, frozen(issues.sortedBy { it.ordinal }))
        }
        val allIssues = assessments.flatMap { it.issues }
        val disposition = when {
            allIssues.any { it == GateIssue.CandidateFailureRate || it == GateIssue.Regression } -> Disposition.Halt
            allIssues.isNotEmpty() -> Disposition.Hold
            else -> Disposition.Continue
        }
        return GateReport(disposition, frozen(assessments))
    }

    private fun validate(
        policy: GatePolicy,
        required: List<String>,
        baseline: List<CohortSample>,
        candidate: List<CohortSample>,
    ) {
        require(required.size in 1..100)
        require(required.all(::validId) && required.toSet().size == required.size)
        require(policy.minimumCompleted > 0)
        require(policy.minimumCoverageBp in 0..10_000)
        require(policy.maximumFailureBp in 0..10_000)
        require(policy.maximumRegressionBp in 0..10_000)
        for (arm in listOf(baseline, candidate)) {
            require(arm.size <= 100)
            require(arm.map { it.cohort }.toSet().size == arm.size)
            for (row in arm) {
                require(validId(row.cohort))
                require(row.eligibleJourneys >= 0)
                require(row.completedJourneys in 0..row.eligibleJourneys)
                require(row.failedJourneys in 0..row.completedJourneys)
            }
        }
    }

    private fun ready(
        row: CohortSample?,
        policy: GatePolicy,
        baseline: Boolean,
        issues: MutableList<GateIssue>,
    ): Boolean {
        if (row == null) {
            issues += if (baseline) GateIssue.MissingBaseline else GateIssue.MissingCandidate
            return false
        }
        if (row.eligibleJourneys == 0L) {
            issues += if (baseline) GateIssue.NoBaselineExposure else GateIssue.NoCandidateExposure
            return false
        }
        var ready = true
        if (row.completedJourneys < policy.minimumCompleted) {
            issues += if (baseline) GateIssue.BaselineTooSmall else GateIssue.CandidateTooSmall
            ready = false
        }
        if (big(row.completedJourneys) * big(10_000) <
            big(row.eligibleJourneys) * big(policy.minimumCoverageBp.toLong())) {
            issues += if (baseline) GateIssue.BaselineCoverageLow else GateIssue.CandidateCoverageLow
            ready = false
        }
        return ready
    }

    private fun exceeds(row: CohortSample, maximumBp: Int): Boolean =
        big(row.failedJourneys) * big(10_000) >
            big(row.completedJourneys) * big(maximumBp.toLong())

    private fun regressed(b: CohortSample, c: CohortSample, maximumBp: Int): Boolean {
        val change = big(c.failedJourneys) * big(b.completedJourneys) -
            big(b.failedJourneys) * big(c.completedJourneys)
        return change * big(10_000) > big(maximumBp.toLong()) *
            big(c.completedJourneys) * big(b.completedJourneys)
    }

    private fun validId(id: String): Boolean = id.matches(Regex("[a-z][a-z0-9_-]{0,31}"))
    private fun big(value: Long): BigInteger = BigInteger.valueOf(value)
    private fun <T> frozen(values: List<T>): List<T> = Collections.unmodifiableList(values.toList())
}
