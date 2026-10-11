package staff.android.release.core

import java.util.Locale

/** A process-local lease. Restart has no retained enablement; time is monotonic elapsed time. */
class TimelineGate(private val nowMillis: () -> Long) {
    private var acceptedAt = -1L
    private var validFor = 0L
    private var allowed = false

    @Synchronized fun accept(enabled: Boolean, ttlSeconds: Int) {
        require(ttlSeconds in 0..60) { "Unsupported policy lifetime" }
        val now = nowMillis()
        require(now >= 0) { "Invalid monotonic time" }
        acceptedAt = now
        validFor = ttlSeconds * 1_000L
        allowed = enabled
    }

    @Synchronized fun enabled(): Boolean {
        val now = nowMillis()
        return allowed && acceptedAt >= 0 && now >= acceptedAt && now - acceptedAt < validFor
    }

    @Synchronized fun disable() { allowed = false }
}

data class DeliveryMessage(val title: String, val detail: String, val complete: Boolean)
enum class KnownStatus { IN_TRANSIT, DELIVERED }

/** Deliberate release defect, built only into the candidate's executed route. */
fun candidateMessage(raw: String): DeliveryMessage = when (KnownStatus.valueOf(raw.uppercase(Locale.ROOT))) {
    KnownStatus.IN_TRANSIT -> DeliveryMessage("On its way", "Your good things are travelling to you.", false)
    KnownStatus.DELIVERED -> DeliveryMessage("Delivered", "Your order has arrived.", true)
}

/** Unknown transport vocabulary cannot silently become a successful delivery. */
fun repairedMessage(raw: String): DeliveryMessage = when (raw) {
    "in_transit" -> DeliveryMessage("On its way", "Your good things are travelling to you.", false)
    "delivered" -> DeliveryMessage("Delivered", "Your order has arrived.", true)
    else -> DeliveryMessage("A new delivery update", "Check back for the latest details. " +
        "Your order and receipt are still available.", false)
}

data class NotificationAccess(val osApi: Int, val permissionGranted: Boolean, val appEnabled: Boolean, val channelEnabled: Boolean) {
    val mayNotify: Boolean get() = (osApi < 33 || permissionGranted) && appEnabled && channelEnabled
}

enum class Outcome { STARTED, SHOWN, DISABLED, UNAVAILABLE }

/** Closed diagnostic vocabulary; purchase IDs, raw status and personal data have no field. */
data class JourneyEvent(val attempt: String, val build: Int, val osApi: Int, val outcome: Outcome) {
    init {
        require(attempt.matches(Regex("[a-f0-9]{32}")))
        require(build in 2601..2603 && osApi in 26..99)
    }
}
