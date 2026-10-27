package io.github.jman9700.quartermaster.domain

import kotlinx.datetime.LocalDate

val UNIT_OPTIONS = listOf("Count", "Oz", "Fl Oz", "Lb", "Gal", "Grams", "Kg", "mL", "L")

data class ProductType(
    val id: String,
    /** Normalized GTIN-14, or null for products without a barcode. */
    val gtin: String?,
    val name: String,
    val lifespanDays: Int,
    val quantity: Double,
    val unit: String,
    val imageFile: String?,
    val createdAt: Long,
    val updatedAt: Long,
) {
    val amountText: String get() = "${formatQuantity(quantity)} $unit"
}

/** User-editable fields of a [ProductType]. */
data class ProductDraft(
    val gtin: String?,
    val name: String,
    val lifespanDays: Int,
    val quantity: Double,
    val unit: String,
    val imageFile: String?,
)

enum class RemovalReason(val label: String) {
    USED("Used"),
    SPOILED("Went bad"),
    DISCARDED("Discarded"),
}

data class InventoryItem(
    val id: String,
    val type: ProductType,
    val addedOn: LocalDate,
    val bestBy: LocalDate?,
    val removedOn: LocalDate?,
    val removalReason: RemovalReason?,
    val updatedAt: Long,
) {
    val expiresOn: LocalDate get() = Freshness.expiresOn(addedOn, bestBy, type.lifespanDays)

    /** Days before its expected expiry that a spoiled item went bad, or null if it didn't spoil early. */
    val spoiledDaysEarly: Int?
        get() {
            if (removalReason != RemovalReason.SPOILED || removedOn == null) return null
            return Freshness.daysBetween(removedOn, expiresOn).takeIf { it > 0 }
        }
}

class DuplicateBarcodeException(val existing: ProductType) :
    IllegalStateException("Barcode is already used by \"${existing.name}\"")

fun formatQuantity(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
