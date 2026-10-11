package staff.android.release.rollout

fun main() {
    val policy = GatePolicy(listOf("older-os", "newer-os"), 20, 9_000, 1_000, 200)
    val baseline = listOf(
        CohortSample("older-os", 1_000, 1_000, 20),
        CohortSample("newer-os", 100, 100, 2),
    )
    val candidate = listOf(
        CohortSample("older-os", 1_000, 1_000, 10),
        CohortSample("newer-os", 100, 100, 8),
    )
    val report = ReferenceRollout.evaluate(policy, baseline, candidate)
    println("Invented media-player counts; not measured app telemetry.")
    println("Pooled failure changes from 22/1100 to 18/1100, but the newer-os cohort changes from 2/100 to 8/100.")
    println(report)
    println("This deterministic policy decision does not establish causation or statistical significance.")
}
