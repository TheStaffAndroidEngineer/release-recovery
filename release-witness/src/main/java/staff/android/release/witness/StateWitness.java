package staff.android.release.witness;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.app.Instrumentation;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Build;
import android.os.Bundle;
import org.json.JSONArray;
import org.json.JSONObject;

/** Same-signer lab instrumentation. Uses framework classes only, independent of target R8 names. */
public final class StateWitness extends Instrumentation {
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            Context context = getTargetContext();
            if (!context.getDatabasePath("orders.db").isFile()) throw new IllegalStateException("Missing existing order database");
            JSONObject report = new JSONObject();
            try (SQLiteDatabase db = SQLiteDatabase.openDatabase(context.getDatabasePath("orders.db").getPath(), null, SQLiteDatabase.OPEN_READONLY)) {
                report.put("schema", db.getVersion());
                try (Cursor cursor = db.rawQuery("SELECT * FROM orders WHERE id = ?", new String[]{"cg-1042"})) {
                    if (!cursor.moveToFirst()) throw new IllegalStateException("Missing seeded order");
                    JSONObject row = new JSONObject();
                    for (String name : cursor.getColumnNames()) {
                        int index = cursor.getColumnIndexOrThrow(name);
                        if (cursor.getType(index) == Cursor.FIELD_TYPE_INTEGER) row.put(name, cursor.getLong(index));
                        else row.put(name, cursor.getString(index));
                    }
                    report.put("order", row);
                }
            }
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            report.put("versionCode", Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode);
            report.put("package", context.getPackageName());
            report.put("osApi", Build.VERSION.SDK_INT);
            report.put("targetSdk", context.getApplicationInfo().targetSdkVersion);
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            NotificationChannel channel = manager.getNotificationChannel("delivery_updates");
            report.put("notifications", new JSONObject()
                .put("runtimeGranted", Build.VERSION.SDK_INT < 33 || context.checkSelfPermission("android.permission.POST_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED)
                .put("appEnabled", manager.areNotificationsEnabled())
                .put("channelImportance", channel == null ? -1 : channel.getImportance())
                .put("activeNotificationCount", manager.getActiveNotifications().length));
            JSONArray exits = new JSONArray();
            if (Build.VERSION.SDK_INT >= 30) {
                for (ApplicationExitInfo exit : context.getSystemService(ActivityManager.class).getHistoricalProcessExitReasons(context.getPackageName(), 0, 8)) {
                    exits.put(new JSONObject().put("pid", exit.getPid()).put("reason", exit.getReason()).put("timestamp", exit.getTimestamp()));
                }
            }
            report.put("priorProcessExits", exits);
            result.putString("installed_state", report.toString());
            finish(Activity.RESULT_OK, result);
        } catch (Exception error) {
            result.putString("failure", error.getClass().getName() + ": " + error.getMessage());
            finish(Activity.RESULT_CANCELED, result);
        }
    }
}
