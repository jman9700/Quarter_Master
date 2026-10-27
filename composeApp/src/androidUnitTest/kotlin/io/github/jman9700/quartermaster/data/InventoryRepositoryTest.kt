package io.github.jman9700.quartermaster.data

import io.github.jman9700.quartermaster.domain.DuplicateBarcodeException
import io.github.jman9700.quartermaster.domain.ProductDraft
import io.github.jman9700.quartermaster.domain.RemovalReason
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InventoryRepositoryTest {
    private val env = FakeEnvironment()
    private val repo = inMemoryRepository(env)
    private val oct1 = LocalDate(2026, 10, 1)

    private val milk = ProductDraft("00036000291452", "Whole Milk", 7, 1.0, "Gal", null)
    private val bananas = ProductDraft(null, "Bananas", 5, 6.0, "Count", null)

    @Test
    fun createAndFindByBarcode() = runTest {
        val created = repo.createType(milk)
        assertEquals(created, repo.findByGtin("00036000291452"))
        assertEquals(listOf("Whole Milk"), repo.productTypes.first().map { it.name })
    }

    @Test
    fun productsWithoutBarcodesAreAllowed() = runTest {
        repo.createType(bananas)
        repo.createType(bananas.copy(name = "Apples"))
        assertEquals(listOf("Apples", "Bananas"), repo.productTypes.first().map { it.name })
    }

    @Test
    fun duplicateBarcodeIsRejected() = runTest {
        repo.createType(milk)
        val e = assertFailsWith<DuplicateBarcodeException> { repo.createType(milk.copy(name = "Other")) }
        assertEquals("Whole Milk", e.existing.name)
    }

    @Test
    fun updatingToAnotherProductsBarcodeIsRejected() = runTest {
        repo.createType(milk)
        val b = repo.createType(bananas)
        assertFailsWith<DuplicateBarcodeException> { repo.updateType(b.id, bananas.copy(gtin = milk.gtin)) }
        // Keeping your own barcode is fine.
        val m = repo.findByGtin(milk.gtin!!)!!
        repo.updateType(m.id, milk.copy(lifespanDays = 10))
        assertEquals(10, repo.findByGtin(milk.gtin!!)!!.lifespanDays)
    }

    @Test
    fun activeItemsAreSortedBySoonestExpiry() = runTest {
        val m = repo.createType(milk) // 7 days
        val b = repo.createType(bananas) // 5 days
        repo.addItems(m.id, 1, oct1, null) // Oct 8
        repo.addItems(b.id, 2, oct1, null) // Oct 6
        repo.addItems(m.id, 1, oct1, LocalDate(2026, 10, 3)) // best-by Oct 3
        val active = repo.activeItems.first()
        assertEquals(4, active.size)
        assertEquals(listOf("2026-10-03", "2026-10-06", "2026-10-06", "2026-10-08"), active.map { it.expiresOn.toString() })
    }

    @Test
    fun removeRestoreAndDelete() = runTest {
        val m = repo.createType(milk)
        val (a, b) = repo.addItems(m.id, 2, oct1, null)

        repo.removeItem(a, RemovalReason.SPOILED, LocalDate(2026, 10, 4))
        assertEquals(listOf(b), repo.activeItems.first().map { it.id })
        val removed = repo.history.first().single()
        assertEquals(RemovalReason.SPOILED, removed.removalReason)
        assertEquals(4, removed.spoiledDaysEarly)

        repo.restoreItem(a)
        assertEquals(2, repo.activeItems.first().size)
        assertTrue(repo.history.first().isEmpty())

        repo.deleteItem(b)
        assertEquals(listOf(a), repo.activeItems.first().map { it.id })
        assertEquals(1, repo.itemsForType(m.id).first().size)
    }

    @Test
    fun updatingItemDatesChangesExpiry() = runTest {
        val m = repo.createType(milk)
        val id = repo.addItems(m.id, 1, oct1, null).single()
        repo.updateItemDates(id, oct1, LocalDate(2026, 10, 15))
        assertEquals(LocalDate(2026, 10, 15), repo.activeItems.first().single().expiresOn)
    }

    @Test
    fun deletingAProductRemovesItsItemsAndFreesItsBarcode() = runTest {
        val m = repo.createType(milk)
        repo.addItems(m.id, 3, oct1, null)
        repo.deleteType(m.id)
        assertTrue(repo.productTypes.first().isEmpty())
        assertTrue(repo.activeItems.first().isEmpty())
        assertNull(repo.findByGtin(milk.gtin!!))
        val again = repo.createType(milk)
        assertNotEquals(m.id, again.id)
    }

    @Test
    fun writesBumpUpdatedAtForFutureSync() = runTest {
        val m = repo.createType(milk)
        val id = repo.addItems(m.id, 1, oct1, null).single()
        val before = repo.activeItems.first().single().updatedAt
        repo.updateItemDates(id, oct1, LocalDate(2026, 10, 9))
        assertTrue(repo.activeItems.first().single().updatedAt > before)
    }

    @Test
    fun settings() = runTest {
        assertTrue(repo.onlineLookupEnabled.first())
        repo.setOnlineLookupEnabled(false)
        assertEquals(false, repo.onlineLookupEnabled.first())
        assertEquals(repo.deviceId(), repo.deviceId())
    }
}
