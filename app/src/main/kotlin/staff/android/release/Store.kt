package staff.android.release

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class SavedOrder(val id: String, val productId: String, val totalMinor: Long, val deliveryLabel: String, val deliveryUpdates: Boolean, val lastSeenBuild: Int)

class OrderStore(context: Context, name: String = "orders.db", version: Int = BuildConfig.SCHEMA_VERSION) :
    SQLiteOpenHelper(context.applicationContext, name, null, version) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE orders (id TEXT PRIMARY KEY, " +
            "product_id TEXT NOT NULL, total_minor INTEGER NOT NULL, " +
            "delivery_label TEXT NOT NULL, " +
            "delivery_updates INTEGER NOT NULL)")
        db.execSQL("INSERT INTO orders VALUES " +
            "('cg-1042', 'camera', 1299900, 'On its way', 0)")
        if (requestedVersion >= 2) addReleaseColumn(db)
    }
    private val requestedVersion = version
    private fun addReleaseColumn(db: SQLiteDatabase) = db.execSQL(
        "ALTER TABLE orders ADD COLUMN " +
        "last_seen_build INTEGER NOT NULL DEFAULT 0")
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        require(oldVersion == 1 && newVersion == 2) { "No supported migration" }
        addReleaseColumn(db)
    }
    // SQLiteOpenHelper's default onDowngrade throws. No destructive fallback.
    fun snapshot(): SavedOrder = readableDatabase.rawQuery("SELECT * FROM orders WHERE id = ?", arrayOf("cg-1042")).use { c ->
        check(c.moveToFirst()) { "Saved order is missing" }
        fun text(key: String) = c.getString(c.getColumnIndexOrThrow(key))
        SavedOrder(text("id"), text("product_id"), c.getLong(c.getColumnIndexOrThrow("total_minor")), text("delivery_label"), c.getInt(c.getColumnIndexOrThrow("delivery_updates")) == 1,
            c.getColumnIndex("last_seen_build").let { if (it < 0) 0 else c.getInt(it) })
    }
    fun setDeliveryUpdates(enabled: Boolean) {
        check(writableDatabase.update("orders", ContentValues().apply { put("delivery_updates", if (enabled) 1 else 0) }, "id = ?", arrayOf("cg-1042")) == 1)
    }
    fun markBuild(build: Int) {
        if (requestedVersion >= 2) check(writableDatabase.update("orders", ContentValues().apply { put("last_seen_build", build) }, "id = ?", arrayOf("cg-1042")) == 1)
    }
}
