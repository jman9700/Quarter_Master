package io.github.jman9700.quartermaster.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.jman9700.quartermaster.db.QuarterMasterDatabase
import io.github.jman9700.quartermaster.domain.LookupStatus
import io.github.jman9700.quartermaster.domain.ProductField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Phones already have a 1.0 database; it must upgrade in place without losing anything. */
class MigrationTest {

    // The schema exactly as version 1.0 shipped it.
    private val v1Schema = listOf(
        """CREATE TABLE product_type (id TEXT NOT NULL PRIMARY KEY, gtin TEXT, name TEXT NOT NULL, lifespan_days INTEGER NOT NULL,
           quantity REAL NOT NULL, unit TEXT NOT NULL, image_file TEXT, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL,
           deleted_at INTEGER)""",
        "CREATE UNIQUE INDEX product_type_active_gtin ON product_type(gtin) WHERE gtin IS NOT NULL AND deleted_at IS NULL",
        """CREATE TABLE item (id TEXT NOT NULL PRIMARY KEY, type_id TEXT NOT NULL REFERENCES product_type(id), added_on TEXT NOT NULL,
           best_by TEXT, removed_on TEXT, removal_reason TEXT, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, deleted_at INTEGER)""",
        "CREATE INDEX item_type ON item(type_id)",
        "CREATE TABLE setting (key TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL)",
        "INSERT INTO product_type VALUES ('pb', '00036000291452', 'My Peanut Butter', 30, 16, 'Oz', 'pb.jpg', 1, 1, NULL)",
        "INSERT INTO product_type VALUES ('milk', '04006381333931', 'Milk', 7, 1, 'Gal', NULL, 1, 1, NULL)",
        "INSERT INTO product_type VALUES ('banana', NULL, 'Bananas', 5, 6, 'Count', NULL, 1, 1, NULL)",
        "INSERT INTO item VALUES ('i1', 'pb', '2026-10-27', NULL, NULL, NULL, 1, 1, NULL)",
    )

    @Test
    fun upgradesA10Database() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        v1Schema.forEach { driver.execute(null, it, 0) }

        assertEquals(2L, QuarterMasterDatabase.Schema.version)
        QuarterMasterDatabase.Schema.migrate(driver, 1, QuarterMasterDatabase.Schema.version)

        val repo = InventoryRepository(driver, Dispatchers.Unconfined, FakeEnvironment())
        val types = repo.productTypes.first().associateBy { it.id }
        val pb = types.getValue("pb")
        assertEquals("My Peanut Butter", pb.name)
        assertEquals("16 Oz", pb.amountText)
        assertEquals("pb.jpg", pb.imageFile)
        // 1.0 data was checked by the user: a later lookup may only add nutrition (and a missing photo).
        assertEquals(setOf(ProductField.NAME, ProductField.AMOUNT, ProductField.IMAGE), pb.userFields)
        assertEquals(setOf(ProductField.NAME, ProductField.AMOUNT), types.getValue("milk").userFields)
        assertEquals(LookupStatus.NONE, pb.lookupStatus)
        assertNull(pb.nutrition)
        assertEquals(1, repo.activeItems.first().size)

        // ...and the products with barcodes get queued for nutrition on the next app open.
        repo.queueNeverLookedUp()
        assertEquals(setOf("pb", "milk"), repo.pendingLookups().map { it.id }.toSet())
    }
}
