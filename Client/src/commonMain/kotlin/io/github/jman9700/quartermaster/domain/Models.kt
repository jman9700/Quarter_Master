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
    val userFields: Set<ProductField> = emptySet(),
    val lookupStatus: LookupStatus = LookupStatus.NONE,
    val nutrition: Nutrition? = null,
) {
    val amountText: String get() = "${formatQuantity(quantity)} $unit"

    /** A product saved offline can have no name yet; it shows its barcode until the lookup fills one in. */
    val displayName: String
        get() = name.ifBlank { gtin?.let { "Product ${Barcode.display(it)}" } ?: "Unnamed product" }

    val isLookupPending: Boolean get() = lookupStatus == LookupStatus.PENDING
}

/** User-editable fields of a [ProductType]. */
data class ProductDraft(
    val gtin: String?,
    val name: String,
    val lifespanDays: Int,
    val quantity: Double,
    val unit: String,
    val imageFile: String?,
    /** Fields the user set themselves; a later lookup leaves these alone. */
    val userFields: Set<ProductField> = emptySet(),
    /** Result of a lookup done in the form, or null to leave the stored lookup state alone. */
    val lookup: LookupResultState? = null,
)

data class LookupResultState(val status: LookupStatus, val nutrition: Nutrition? = null)

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
