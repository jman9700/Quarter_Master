package io.github.jman9700.quartermaster.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The slice of OpenFoodFacts' nutrition data the app keeps: enough for the product page, not a copy
 * of their whole database. The product page links to OpenFoodFacts for everything else.
 */
@Serializable
data class Nutrition(
    val servingSize: String? = null,
    /** "a".."e", or null when not graded. */
    val nutriScore: String? = null,
    /** 1..4 (unprocessed .. ultra-processed). */
    val novaGroup: Int? = null,
    val per100g: Nutrients? = null,
    val perServing: Nutrients? = null,
    /** Readable names, e.g. "Milk", "Peanuts". */
    val allergens: List<String> = emptyList(),
    val ingredients: String? = null,
) {
    val isEmpty: Boolean
        get() = nutriScore == null && novaGroup == null && per100g.isNullOrEmpty() &&
            perServing.isNullOrEmpty() && allergens.isEmpty() && ingredients == null

    fun toJson(): String = json.encodeToString(serializer(), this)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun fromJson(text: String?): Nutrition? =
            text?.let { runCatching { json.decodeFromString(serializer(), it) }.getOrNull() }
    }
}

/** Grams unless noted. */
@Serializable
data class Nutrients(
    val energyKcal: Double? = null,
    val fat: Double? = null,
    val saturatedFat: Double? = null,
    val carbohydrates: Double? = null,
    val sugars: Double? = null,
    val fiber: Double? = null,
    val proteins: Double? = null,
    /** Grams; shown in mg. */
    val sodium: Double? = null,
) {
    val isEmpty: Boolean
        get() = listOf(energyKcal, fat, saturatedFat, carbohydrates, sugars, fiber, proteins, sodium).all { it == null }
}

private fun Nutrients?.isNullOrEmpty() = this == null || isEmpty

enum class LookupStatus {
    /** Never looked up: no barcode, online lookup turned off, or saved before nutrition existed. */
    NONE,

    /** Waiting in the queue for an internet connection. */
    PENDING,
    FOUND,

    /** OpenFoodFacts doesn't know this barcode. */
    NOT_FOUND,
}

/** Product fields an online lookup can fill in. Once the user sets one, it's theirs and is never overwritten. */
enum class ProductField(val key: String) {
    NAME("name"),

    /** Quantity and unit together. */
    AMOUNT("amount"),
    IMAGE("image"),
    ;

    companion object {
        fun parse(text: String): Set<ProductField> =
            text.split(',').mapNotNull { k -> entries.firstOrNull { it.key == k.trim() } }.toSet()

        fun format(fields: Set<ProductField>): String = entries.filter { it in fields }.joinToString(",") { it.key }
    }
}
