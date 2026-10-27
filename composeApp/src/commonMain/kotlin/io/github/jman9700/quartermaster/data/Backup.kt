package io.github.jman9700.quartermaster.data

import io.github.jman9700.quartermaster.db.Item
import io.github.jman9700.quartermaster.db.Product_type
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64

/**
 * The backup file format. It's deliberately the same shape a sync server will exchange:
 * whole rows with ids, timestamps and tombstones, merged last-write-wins on updatedAt.
 */
@Serializable
data class BackupDocument(
    val format: String = FORMAT,
    val version: Int = VERSION,
    val appVersion: String,
    val deviceId: String,
    val exportedAt: Long,
    val productTypes: List<BackupProductType>,
    val items: List<BackupItem>,
    /** Image file name -> base64 JPEG. */
    val images: Map<String, String> = emptyMap(),
) {
    companion object {
        const val FORMAT = "quarter-master-backup"
        const val VERSION = 1
    }
}

@Serializable
data class BackupProductType(
    val id: String,
    val gtin: String? = null,
    val name: String,
    val lifespanDays: Int,
    val quantity: Double,
    val unit: String,
    val imageFile: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
)

@Serializable
data class BackupItem(
    val id: String,
    val typeId: String,
    val addedOn: String,
    val bestBy: String? = null,
    val removedOn: String? = null,
    val removalReason: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
)

data class ImportSummary(
    val productsAdded: Int = 0,
    val productsUpdated: Int = 0,
    val itemsAdded: Int = 0,
    val itemsUpdated: Int = 0,
) {
    val isEmpty get() = productsAdded + productsUpdated + itemsAdded + itemsUpdated == 0
}

class BackupFormatException(message: String) : Exception(message)

class BackupService(
    private val repository: InventoryRepository,
    private val images: ImageStore,
    private val appVersion: String,
    private val env: Environment = Environment.System,
) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }
    private val db get() = repository.db

    suspend fun export(): String = withContext(repository.dispatcher) {
        val types = db.productTypeQueries.selectAll().executeAsList()
        val items = db.itemQueries.selectAll().executeAsList()
        val imageData = types
            .filter { it.deleted_at == null }
            .mapNotNull { t -> t.image_file?.let { name -> images.read(name)?.let { name to Base64.encode(it) } } }
            .toMap()
        val doc = BackupDocument(
            appVersion = appVersion,
            deviceId = repository.deviceId(),
            exportedAt = env.nowMillis(),
            productTypes = types.map { it.toBackup() },
            items = items.map { it.toBackup() },
            images = imageData,
        )
        json.encodeToString(BackupDocument.serializer(), doc)
    }

    /**
     * Merges a backup into the local database. Nothing local is lost: for each row the newer
     * updatedAt wins, and rows that exist only locally are kept.
     */
    suspend fun import(content: String): ImportSummary = withContext(repository.dispatcher) {
        val doc = try {
            json.decodeFromString(BackupDocument.serializer(), content)
        } catch (e: Exception) {
            throw BackupFormatException("This file isn't a Quarter Master backup")
        }
        if (doc.format != BackupDocument.FORMAT) throw BackupFormatException("This file isn't a Quarter Master backup")
        if (doc.version > BackupDocument.VERSION) throw BackupFormatException("This backup was made by a newer version of Quarter Master")

        for ((name, data) in doc.images) {
            if (!images.exists(name)) runCatching { images.write(name, Base64.decode(data)) }
        }

        val types = db.productTypeQueries
        val items = db.itemQueries
        var summary = ImportSummary()
        db.transaction {
            // Products created separately on two devices can share a barcode; fold the incoming one into ours.
            val remap = mutableMapOf<String, String>()
            for (t in doc.productTypes) {
                val local = types.selectById(t.id).executeAsOneOrNull()
                val sameBarcode = t.gtin?.takeIf { t.deletedAt == null }
                    ?.let { types.selectActiveByGtin(it).executeAsOneOrNull() }
                    ?.takeIf { it.id != t.id }
                when {
                    sameBarcode != null -> remap[t.id] = sameBarcode.id
                    local == null -> {
                        types.insert(t.id, t.gtin, t.name, t.lifespanDays.toLong(), t.quantity, t.unit, t.imageFile, t.createdAt, t.updatedAt, t.deletedAt)
                        summary = summary.copy(productsAdded = summary.productsAdded + 1)
                    }
                    t.updatedAt > local.updated_at -> {
                        types.replace(t.gtin, t.name, t.lifespanDays.toLong(), t.quantity, t.unit, t.imageFile, t.createdAt, t.updatedAt, t.deletedAt, t.id)
                        summary = summary.copy(productsUpdated = summary.productsUpdated + 1)
                    }
                }
            }
            for (i in doc.items) {
                val typeId = remap[i.typeId] ?: i.typeId
                if (types.selectById(typeId).executeAsOneOrNull() == null) continue
                val local = items.selectById(i.id).executeAsOneOrNull()
                when {
                    local == null -> {
                        items.insert(i.id, typeId, i.addedOn, i.bestBy, i.removedOn, i.removalReason, i.createdAt, i.updatedAt, i.deletedAt)
                        summary = summary.copy(itemsAdded = summary.itemsAdded + 1)
                    }
                    i.updatedAt > local.updated_at -> {
                        items.replace(typeId, i.addedOn, i.bestBy, i.removedOn, i.removalReason, i.createdAt, i.updatedAt, i.deletedAt, i.id)
                        summary = summary.copy(itemsUpdated = summary.itemsUpdated + 1)
                    }
                }
            }
        }
        summary
    }
}

private fun Product_type.toBackup() = BackupProductType(
    id, gtin, name, lifespan_days.toInt(), quantity, unit, image_file, created_at, updated_at, deleted_at,
)

private fun Item.toBackup() = BackupItem(
    id, type_id, added_on, best_by, removed_on, removal_reason, created_at, updated_at, deleted_at,
)
