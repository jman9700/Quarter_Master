package io.github.jman9700.quartermaster.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import app.cash.sqldelight.db.SqlDriver
import io.github.jman9700.quartermaster.db.Item
import io.github.jman9700.quartermaster.db.Product_type
import io.github.jman9700.quartermaster.db.QuarterMasterDatabase
import io.github.jman9700.quartermaster.domain.DuplicateBarcodeException
import io.github.jman9700.quartermaster.domain.InventoryItem
import io.github.jman9700.quartermaster.domain.ProductDraft
import io.github.jman9700.quartermaster.domain.ProductType
import io.github.jman9700.quartermaster.domain.RemovalReason
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import kotlin.uuid.Uuid

/** Abstracts time and id generation so tests are deterministic. */
interface Environment {
    fun nowMillis(): Long
    fun today(): LocalDate
    fun newId(): String

    object System : Environment {
        override fun nowMillis() = Clock.System.now().toEpochMilliseconds()
        override fun today() = Clock.System.todayIn(TimeZone.currentSystemDefault())
        override fun newId() = Uuid.random().toString()
    }
}

class InventoryRepository(
    driver: SqlDriver,
    internal val dispatcher: CoroutineDispatcher,
    private val env: Environment = Environment.System,
) {
    internal val db = QuarterMasterDatabase(driver)
    private val types = db.productTypeQueries
    private val items = db.itemQueries
    private val settings = db.settingQueries

    val productTypes: Flow<List<ProductType>> =
        types.selectActive().asFlow().mapToList(dispatcher).map { rows -> rows.map { it.toDomain() } }

    private val allItems: Flow<List<InventoryItem>> =
        combine(productTypes, items.selectNotDeleted().asFlow().mapToList(dispatcher)) { typeList, rows ->
            val byId = typeList.associateBy { it.id }
            rows.mapNotNull { row -> byId[row.type_id]?.let { row.toDomain(it) } }
        }

    /** Items currently in the inventory, soonest to expire first. */
    val activeItems: Flow<List<InventoryItem>> =
        allItems.map { list -> list.filter { it.removedOn == null }.sortedWith(compareBy({ it.expiresOn }, { it.type.name })) }

    /** Items that have left the inventory, most recent first. */
    val history: Flow<List<InventoryItem>> =
        allItems.map { list -> list.filter { it.removedOn != null }.sortedByDescending { it.removedOn } }

    /** Every non-deleted item of one product, past and present. Feeds [io.github.jman9700.quartermaster.domain.LifespanStats]. */
    fun itemsForType(typeId: String): Flow<List<InventoryItem>> =
        allItems.map { list -> list.filter { it.type.id == typeId } }

    suspend fun findByGtin(gtin14: String): ProductType? = io {
        types.selectActiveByGtin(gtin14).executeAsOneOrNull()?.toDomain()
    }

    suspend fun createType(draft: ProductDraft): ProductType = io {
        db.transactionWithResult {
            draft.gtin?.let { gtin -> types.selectActiveByGtin(gtin).executeAsOneOrNull()?.let { throw DuplicateBarcodeException(it.toDomain()) } }
            val now = env.nowMillis()
            val id = env.newId()
            types.insert(id, draft.gtin, draft.name, draft.lifespanDays.toLong(), draft.quantity, draft.unit, draft.imageFile, now, now, null)
            types.selectById(id).executeAsOne().toDomain()
        }
    }

    suspend fun updateType(id: String, draft: ProductDraft) = io {
        db.transaction {
            draft.gtin?.let { gtin ->
                val other = types.selectActiveByGtin(gtin).executeAsOneOrNull()
                if (other != null && other.id != id) throw DuplicateBarcodeException(other.toDomain())
            }
            types.update(draft.gtin, draft.name, draft.lifespanDays.toLong(), draft.quantity, draft.unit, draft.imageFile, env.nowMillis(), id)
        }
    }

    /** Removes a product and all of its items, history included. */
    suspend fun deleteType(id: String) = io {
        db.transaction {
            val now = env.nowMillis()
            items.softDeleteByType(now, id)
            types.softDelete(now, id)
        }
    }

    suspend fun addItems(typeId: String, count: Int, addedOn: LocalDate, bestBy: LocalDate?): List<String> = io {
        require(count >= 1) { "count must be at least 1" }
        db.transactionWithResult {
            val now = env.nowMillis()
            List(count) {
                env.newId().also { id ->
                    items.insert(id, typeId, addedOn.toString(), bestBy?.toString(), null, null, now, now, null)
                }
            }
        }
    }

    suspend fun updateItemDates(itemId: String, addedOn: LocalDate, bestBy: LocalDate?) = io {
        items.updateDates(addedOn.toString(), bestBy?.toString(), env.nowMillis(), itemId)
    }

    suspend fun removeItem(itemId: String, reason: RemovalReason, on: LocalDate = env.today()) = io {
        items.markRemoved(on.toString(), reason.name, env.nowMillis(), itemId)
    }

    suspend fun restoreItem(itemId: String) = io { items.restore(env.nowMillis(), itemId) }

    /** For items logged by mistake. Unlike [removeItem] it doesn't count toward history or stats. */
    suspend fun deleteItem(itemId: String) = io { items.softDelete(env.nowMillis(), itemId) }

    // ---- Settings -------------------------------------------------------------------------

    val onlineLookupEnabled: Flow<Boolean> =
        settings.get(KEY_ONLINE_LOOKUP).asFlow().mapToOneOrNull(dispatcher).map { it?.toBooleanStrictOrNull() ?: true }

    suspend fun setOnlineLookupEnabled(enabled: Boolean) = io { settings.put(KEY_ONLINE_LOOKUP, enabled.toString()) }

    /** Stable per-install id; a future sync server uses it to tell devices apart. */
    suspend fun deviceId(): String = io {
        db.transactionWithResult {
            settings.get(KEY_DEVICE_ID).executeAsOneOrNull()
                ?: env.newId().also { settings.put(KEY_DEVICE_ID, it) }
        }
    }

    private suspend fun <T> io(block: () -> T): T = withContext(dispatcher) { block() }

    companion object {
        private const val KEY_ONLINE_LOOKUP = "online_lookup_enabled"
        private const val KEY_DEVICE_ID = "device_id"
    }
}

internal fun Product_type.toDomain() = ProductType(
    id = id,
    gtin = gtin,
    name = name,
    lifespanDays = lifespan_days.toInt(),
    quantity = quantity,
    unit = unit,
    imageFile = image_file,
    createdAt = created_at,
    updatedAt = updated_at,
)

internal fun Item.toDomain(type: ProductType) = InventoryItem(
    id = id,
    type = type,
    addedOn = LocalDate.parse(added_on),
    bestBy = best_by?.let(LocalDate::parse),
    removedOn = removed_on?.let(LocalDate::parse),
    removalReason = removal_reason?.let { runCatching { RemovalReason.valueOf(it) }.getOrNull() },
    updatedAt = updated_at,
)
