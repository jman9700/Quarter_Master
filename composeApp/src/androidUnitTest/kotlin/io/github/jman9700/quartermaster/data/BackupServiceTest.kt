package io.github.jman9700.quartermaster.data

import io.github.jman9700.quartermaster.domain.ProductDraft
import io.github.jman9700.quartermaster.domain.RemovalReason
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BackupServiceTest {
    private val oct1 = LocalDate(2026, 10, 1)
    private val milk = ProductDraft("00036000291452", "Whole Milk", 7, 1.0, "Gal", null)

    private class Device(prefix: String, start: Long = 1_000L) {
        val env = FakeEnvironment(now = start, prefix = prefix)
        val repo = inMemoryRepository(env)
        val images = FakeImageStore()
        val backup = BackupService(repo, images, "test", env)
    }

    @Test
    fun roundTripToANewDevice() = runTest {
        val phone = Device("phone")
        val photo = phone.images.save(byteArrayOf(1, 2, 3))
        val m = phone.repo.createType(milk.copy(imageFile = photo))
        val (a, _) = phone.repo.addItems(m.id, 2, oct1, LocalDate(2026, 10, 9))
        phone.repo.removeItem(a, RemovalReason.USED, LocalDate(2026, 10, 3))

        val tablet = Device("tablet")
        val summary = tablet.backup.import(phone.backup.export())

        assertEquals(1, summary.productsAdded)
        assertEquals(2, summary.itemsAdded)
        assertEquals(phone.repo.productTypes.first(), tablet.repo.productTypes.first())
        assertEquals(phone.repo.activeItems.first(), tablet.repo.activeItems.first())
        assertEquals(phone.repo.history.first(), tablet.repo.history.first())
        assertContentEquals(byteArrayOf(1, 2, 3), tablet.images.read(photo))
    }

    @Test
    fun importingTwiceChangesNothing() = runTest {
        val phone = Device("phone")
        phone.repo.addItems(phone.repo.createType(milk).id, 1, oct1, null)
        val json = phone.backup.export()
        val tablet = Device("tablet")
        tablet.backup.import(json)
        assertTrue(tablet.backup.import(json).isEmpty)
    }

    @Test
    fun newerChangeWinsAndLocalOnlyDataIsKept() = runTest {
        val phone = Device("phone")
        val m = phone.repo.createType(milk)
        val id = phone.repo.addItems(m.id, 1, oct1, null).single()

        val tablet = Device("tablet", start = 5_000L)
        tablet.backup.import(phone.backup.export())
        // Tablet edits later than the phone, and adds something of its own.
        tablet.repo.updateItemDates(id, oct1, LocalDate(2026, 10, 20))
        tablet.repo.createType(ProductDraft(null, "Bananas", 5, 6.0, "Count", null))

        // An older phone backup must not undo the tablet's newer edit.
        tablet.backup.import(phone.backup.export())
        assertEquals(LocalDate(2026, 10, 20), tablet.repo.activeItems.first().single().bestBy)
        assertEquals(listOf("Bananas", "Whole Milk"), tablet.repo.productTypes.first().map { it.name })
    }

    @Test
    fun deletionsTravelAsTombstones() = runTest {
        val phone = Device("phone")
        val m = phone.repo.createType(milk)
        val id = phone.repo.addItems(m.id, 1, oct1, null).single()
        val tablet = Device("tablet", start = 0L)
        tablet.backup.import(phone.backup.export())

        phone.repo.deleteItem(id)
        tablet.backup.import(phone.backup.export())
        assertTrue(tablet.repo.activeItems.first().isEmpty())
    }

    @Test
    fun sameBarcodeCreatedOnTwoDevicesIsMerged() = runTest {
        val phone = Device("phone")
        val tablet = Device("tablet")
        val phoneMilk = phone.repo.createType(milk)
        phone.repo.addItems(phoneMilk.id, 1, oct1, null)
        val tabletMilk = tablet.repo.createType(milk.copy(name = "Milk"))

        tablet.backup.import(phone.backup.export())

        val types = tablet.repo.productTypes.first()
        assertEquals(1, types.size)
        assertEquals(tabletMilk.id, tablet.repo.activeItems.first().single().type.id)
    }

    @Test
    fun rejectsOtherFiles() = runTest {
        val d = Device("d")
        assertFailsWith<BackupFormatException> { d.backup.import("not json") }
        assertFailsWith<BackupFormatException> { d.backup.import("""{"format":"something-else","version":1,"appVersion":"x","deviceId":"x","exportedAt":0,"productTypes":[],"items":[]}""") }
        assertFailsWith<BackupFormatException> { d.backup.import("""{"format":"quarter-master-backup","version":99,"appVersion":"x","deviceId":"x","exportedAt":0,"productTypes":[],"items":[]}""") }
    }
}
