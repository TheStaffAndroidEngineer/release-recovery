package staff.android.release

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import staff.android.release.core.NotificationAccess

class DeliveryNotifications(private val context: Context, private val channelId: String = "delivery_updates") {
    private val manager = context.getSystemService(NotificationManager::class.java)
    fun ensureChannel() { manager.createNotificationChannel(NotificationChannel(channelId, "Delivery updates", NotificationManager.IMPORTANCE_DEFAULT)) }
    fun access(): NotificationAccess = NotificationAccess(Build.VERSION.SDK_INT,
        Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
        manager.areNotificationsEnabled(), manager.getNotificationChannel(channelId)?.importance?.let { it != NotificationManager.IMPORTANCE_NONE } ?: false)
    fun postIfAllowed(): Boolean {
        if (!access().mayNotify) return false
        val intent = Intent(context, MainActivity::class.java).putExtra("open_order", true)
        val pending = PendingIntent.getActivity(context, 26, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = android.app.Notification.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_common_goods).setContentTitle("Your order updates")
            .setContentText("Open Common Goods for the latest delivery details.")
            .setContentIntent(pending).setAutoCancel(true).build()
        manager.notify(26, notification)
        return true
    }
}
