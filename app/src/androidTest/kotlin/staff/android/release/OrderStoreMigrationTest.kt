package staff.android.release

import android.database.sqlite.SQLiteException
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real SQLiteOpenHelper transitions in task-owned test databases, never orders.db. */
@RunWith(AndroidJUnit4::class)
class OrderStoreMigrationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val names = mutableListOf<String>()
    private fun databaseName(): String = "migration-${UUID.randomUUID()}.db".also { names += it }
    private fun columns(store: OrderStore): Set<String> = store.readableDatabase.rawQuery("PRAGMA table_info(orders)", null).use { c ->
        buildSet { while (c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow("name"))) }
    }

    @After fun removeOwnedDatabases() { names.forEach { context.deleteDatabase(it) } }

    @Test fun freshLegacyCreatesItsActualSchemaAndSeededReceipt() {
        OrderStore(context, databaseName(), 1).use { store ->
            assertEquals(SavedOrder("cg-1042", "camera", 1_299_900, "On its way", false, 0), store.snapshot())
            assertEquals(1, store.readableDatabase.version)
            assertEquals(setOf("id", "product_id", "total_minor", "delivery_label", "delivery_updates"), columns(store))
        }
    }

    @Test fun freshCurrentSchemaAddsOnlyTheDefinedDefaultColumn() {
        OrderStore(context, databaseName(), 2).use { store ->
            assertEquals(0, store.snapshot().lastSeenBuild)
            assertEquals(2, store.readableDatabase.version)
            assertTrue(columns(store).contains("last_seen_build"))
            store.markBuild(2603)
            assertEquals(2603, store.snapshot().lastSeenBuild)
        }
    }

    @Test fun legacyToCurrentPreservesNondefaultPreferenceReceiptAndAnotherRow() {
        val name = databaseName()
        val before = OrderStore(context, name, 1).use { legacy ->
            legacy.setDeliveryUpdates(true)
            legacy.writableDatabase.execSQL("UPDATE orders SET delivery_label = 'Saved before upgrade' WHERE id = 'cg-1042'")
            legacy.writableDatabase.execSQL("INSERT INTO orders VALUES ('cg-extra', 'tea', 45000, 'Earlier order', 1)")
            legacy.snapshot()
        }
        OrderStore(context, name, 2).use { current ->
            assertEquals(before, current.snapshot())
            assertEquals(2, current.readableDatabase.version)
            current.readableDatabase.rawQuery("SELECT product_id,total_minor,delivery_label,delivery_updates,last_seen_build FROM orders WHERE id='cg-extra'", null).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("tea", c.getString(0))
                assertEquals(45_000L, c.getLong(1))
                assertEquals("Earlier order", c.getString(2))
                assertEquals(1, c.getInt(3))
                assertEquals(0, c.getInt(4))
                assertFalse(c.moveToNext())
            }
        }
    }

    @Test fun forwardBuildMarkAndPreferenceSurviveRepeatedSchemaTwoReopen() {
        val name = databaseName()
        OrderStore(context, name, 1).use { it.setDeliveryUpdates(true) }
        OrderStore(context, name, 2).use { it.markBuild(2602) }
        OrderStore(context, name, 2).use {
            assertTrue(it.snapshot().deliveryUpdates)
            assertEquals(2602, it.snapshot().lastSeenBuild)
            it.markBuild(2603)
        }
        OrderStore(context, name, 2).use {
            assertTrue(it.snapshot().deliveryUpdates)
            assertEquals(2603, it.snapshot().lastSeenBuild)
            assertEquals(1_299_900L, it.snapshot().totalMinor)
        }
    }

    @Test fun preferenceCanBeTurnedOffAfterMigrationWithoutLosingTheOrder() {
        val name = databaseName()
        OrderStore(context, name, 1).use { it.setDeliveryUpdates(true) }
        OrderStore(context, name, 2).use { it.setDeliveryUpdates(false) }
        OrderStore(context, name, 2).use {
            assertFalse(it.snapshot().deliveryUpdates)
            assertEquals("cg-1042", it.snapshot().id)
            assertEquals(1_299_900L, it.snapshot().totalMinor)
        }
    }

    @Test fun downgradeIsRejectedAndCurrentDataRemainsReadable() {
        val name = databaseName()
        val before = OrderStore(context, name, 2).use {
            it.setDeliveryUpdates(true)
            it.markBuild(2603)
            it.snapshot()
        }
        OrderStore(context, name, 1).use {
            try { it.readableDatabase; fail("A schema downgrade must not erase data") }
            catch (_: SQLiteException) { }
        }
        OrderStore(context, name, 2).use {
            assertEquals(2, it.readableDatabase.version)
            assertEquals(before, it.snapshot())
        }
    }

    @Test fun unsupportedUpgradeFailsBeforeReplacingTheOriginalDatabase() {
        val name = databaseName()
        val before = OrderStore(context, name, 1).use { it.setDeliveryUpdates(true); it.snapshot() }
        OrderStore(context, name, 3).use {
            try { it.readableDatabase; fail("No schema1 to3 migration is defined") }
            catch (_: IllegalArgumentException) { }
        }
        OrderStore(context, name, 1).use {
            assertEquals(1, it.readableDatabase.version)
            assertEquals(before, it.snapshot())
            assertFalse(columns(it).contains("last_seen_build"))
        }
    }

    @Test fun conflictingColumnFailsClosedWithoutDroppingRowsOrChangingVersion() {
        val name = databaseName()
        val before = OrderStore(context, name, 1).use {
            it.setDeliveryUpdates(true)
            it.writableDatabase.execSQL("ALTER TABLE orders ADD COLUMN last_seen_build INTEGER NOT NULL DEFAULT 777")
            it.snapshot()
        }
        OrderStore(context, name, 2).use {
            try { it.readableDatabase; fail("Malformed starting schema must not trigger destructive fallback") }
            catch (_: SQLiteException) { }
        }
        OrderStore(context, name, 1).use {
            assertEquals(1, it.readableDatabase.version)
            assertEquals(before, it.snapshot())
            assertEquals(777, it.snapshot().lastSeenBuild)
        }
    }
}
