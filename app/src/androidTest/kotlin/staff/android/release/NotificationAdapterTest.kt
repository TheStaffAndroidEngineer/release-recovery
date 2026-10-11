package staff.android.release

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Observes the actual permission/app state; changes only unique task-owned channels. */
@RunWith(AndroidJUnit4::class)
class NotificationAdapterTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val channels = mutableListOf<String>()
    private fun id() = "review-${UUID.randomUUID()}".also { channels += it }
    @After fun removeOwnedChannels() { channels.forEach(manager::deleteNotificationChannel) }

    @Test fun defaultChannelObservationMatchesActualPlatformPermissionAndAppState() {
        val channel = id()
        val adapter = DeliveryNotifications(context, channel)
        adapter.ensureChannel()
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, manager.getNotificationChannel(channel).importance)
        val access = adapter.access()
        assertEquals(Build.VERSION.SDK_INT, access.osApi)
        assertTrue(access.channelEnabled)
        assertEquals(manager.areNotificationsEnabled(), access.appEnabled)
        val granted = Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        assertEquals(granted, access.permissionGranted)
    }

    @Test fun aBlockedChannelCannotPostEvenWhenCreationIsRequestedAgain() {
        val channel = id()
        manager.createNotificationChannel(NotificationChannel(channel, "Review blocked channel", NotificationManager.IMPORTANCE_NONE))
        val adapter = DeliveryNotifications(context, channel)
        adapter.ensureChannel()
        assertEquals(NotificationManager.IMPORTANCE_NONE, manager.getNotificationChannel(channel).importance)
        assertFalse(adapter.access().channelEnabled)
        assertFalse(adapter.access().mayNotify)
        assertFalse(adapter.postIfAllowed())
        assertFalse(manager.activeNotifications.any { it.notification.channelId == channel })
    }

    @Test fun missingOrDeletedChannelNeverMeansPermissionToNotify() {
        val channel = id()
        val adapter = DeliveryNotifications(context, channel)
        assertFalse(adapter.access().channelEnabled)
        adapter.ensureChannel()
        assertTrue(adapter.access().channelEnabled)
        manager.deleteNotificationChannel(channel)
        assertFalse(adapter.access().channelEnabled)
        assertFalse(adapter.postIfAllowed())
    }
}
