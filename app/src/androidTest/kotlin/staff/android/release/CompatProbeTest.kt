package staff.android.release

import android.content.Intent
import android.os.Build
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CompatProbeTest {
    @Test fun actualOsAndTargetDetermineWhetherTheOptOutIsHonored() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val context = instrument.targetContext
        ActivityScenario.launch<CompatProbeActivity>(Intent(context, CompatProbeActivity::class.java)).use { scenario ->
            instrument.waitForIdleSync()
            var observed: JSONObject? = null
            scenario.onActivity { activity ->
                val content = activity.findViewById<View>(android.R.id.content)
                val point = IntArray(2); content.getLocationOnScreen(point)
                val topInset = content.rootWindowInsets.systemWindowInsetTop
                observed = JSONObject().put("osApi", Build.VERSION.SDK_INT).put("targetSdk", context.applicationInfo.targetSdkVersion)
                    .put("contentTop", point[1]).put("contentHeight", content.height).put("statusInset", topInset)
                    .put("requestedOptOut", true).put("enableEdgeToEdgeCalled", false)
                assertTrue("Probe must be laid out", content.height > 0)
                if (Build.VERSION.SDK_INT >= 36 && context.applicationInfo.targetSdkVersion >= 36) assertEquals(0, point[1])
                else assertTrue("Older gate retains status-bar separation", point[1] > 0)
            }
            context.filesDir.resolve("compat-probe.json").writeText(observed!!.toString(2))
        }
    }
}
