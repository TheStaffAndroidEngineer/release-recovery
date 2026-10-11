package staff.android.release

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import staff.android.release.core.*
import java.io.IOException
import java.util.UUID

data class ShoppingState(val order: SavedOrder? = null, val loading: Boolean = false, val message: DeliveryMessage? = null,
    val unavailable: Boolean = false, val disabled: Boolean = false, val notificationAccess: NotificationAccess? = null)

class ReleaseModel(context: android.content.Context) : ViewModel() {
    private val store = OrderStore(context)
    private val api = ReleaseApi()
    private val gate = TimelineGate { android.os.SystemClock.elapsedRealtime() }
    private val notifications = DeliveryNotifications(context)
    private val mutable = MutableStateFlow(ShoppingState())
    val state = mutable.asStateFlow()
    init {
        notifications.ensureChannel()
        viewModelScope.launch {
            val order = withContext(Dispatchers.IO) { store.markBuild(BuildConfig.VERSION_CODE); store.snapshot() }
            mutable.update { it.copy(order = order, notificationAccess = notifications.access()) }
        }
    }
    fun refreshNotificationAccess() { mutable.update { it.copy(notificationAccess = notifications.access()) } }
    fun setDeliveryUpdates(enabled: Boolean, requestPermission: () -> Unit) {
        viewModelScope.launch {
            val order = withContext(Dispatchers.IO) { store.setDeliveryUpdates(enabled); store.snapshot() }
            mutable.update { it.copy(order = order) }
            if (enabled) requestPermission()
        }
    }
    fun notifyIfRequested() {
        refreshNotificationAccess()
        if (mutable.value.order?.deliveryUpdates == true) notifications.postIfAllowed()
    }
    fun openUpdates() {
        if (mutable.value.loading) return
        mutable.update { it.copy(loading = true, message = null, disabled = false, unavailable = false) }
        viewModelScope.launch {
            if (BuildConfig.RELEASE_STAGE == "legacy") {
                mutable.update { it.copy(loading = false, message = DeliveryMessage("On its way", "Your order and receipt are available below.", false)) }
                return@launch
            }
            val attempt = UUID.randomUUID().toString().replace("-", "")
            suspend fun record(outcome: Outcome) = api.record(JourneyEvent(attempt, BuildConfig.VERSION_CODE, Build.VERSION.SDK_INT, outcome))
            record(Outcome.STARTED)
            try {
                // Fetch is before risky decoding. An unavailable fetch can retain only the unexpired process lease.
                try { api.policy().let { gate.accept(it.enabled, it.ttlSeconds) } } catch (_: IOException) { }
                if (!gate.enabled()) {
                    mutable.update { it.copy(loading = false, disabled = true) }
                    record(Outcome.DISABLED)
                    return@launch
                }
                val raw = api.status()
                if (!gate.enabled()) {
                    mutable.update { it.copy(loading = false, disabled = true) }
                    record(Outcome.DISABLED)
                    return@launch
                }
                // Candidate deliberately crashes here for a new service status; do not catch it as a network error.
                val message = if (BuildConfig.RELEASE_STAGE == "candidate") candidateMessage(raw) else repairedMessage(raw)
                mutable.update { it.copy(loading = false, message = message) }
                record(Outcome.SHOWN)
            } catch (_: IOException) {
                mutable.update { it.copy(loading = false, unavailable = true) }
                record(Outcome.UNAVAILABLE)
            }
        }
    }
    override fun onCleared() { store.close() }
}

class MainActivity : ComponentActivity() {
    private lateinit var model: ReleaseModel
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { model.notifyIfRequested() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        model = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = ReleaseModel(applicationContext) as T
        })[ReleaseModel::class.java]
        setContent { ShoppingApp(model, intent.getBooleanExtra("open_order", false), ::requestNotifications) }
    }
    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        else model.notifyIfRequested()
    }
    override fun onResume() { super.onResume(); if (::model.isInitialized) model.refreshNotificationAccess() }
}
