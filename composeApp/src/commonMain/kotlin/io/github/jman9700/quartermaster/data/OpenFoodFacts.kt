package io.github.jman9700.quartermaster.data

import io.github.jman9700.quartermaster.domain.Barcode
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/** What OpenFoodFacts knows about a barcode, used to prefill the product form. */
data class ProductSuggestion(
    val name: String?,
    val quantity: Double?,
    val unit: String?,
    val imageUrl: String?,
)

/**
 * Optional online lookup against the free OpenFoodFacts database. Not a server we run;
 * every failure (offline, timeout, unknown product) just returns null.
 */
class OpenFoodFactsClient(private val http: HttpClient) {

    suspend fun lookup(gtin14: String): ProductSuggestion? = runCatching {
        val code = Barcode.lookupCode(gtin14)
        val response = http.get("https://world.openfoodfacts.org/api/v2/product/$code.json?fields=$FIELDS")
        if (!response.status.isSuccess()) return null
        val body = response.body<OffResponse>()
        if (body.status != 1) return null
        body.product?.toSuggestion()
    }.getOrNull()

    suspend fun downloadImage(url: String): ByteArray? = runCatching {
        val response = http.get(url)
        if (response.status.isSuccess()) response.body<ByteArray>() else null
    }.getOrNull()

    companion object {
        private const val FIELDS = "product_name,brands,quantity,product_quantity,product_quantity_unit,image_front_url,image_front_small_url"

        fun createHttpClient(base: HttpClient, appVersion: String): HttpClient = base.config {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; isLenient = true }) }
            install(HttpTimeout) {
                requestTimeoutMillis = 10_000
                connectTimeoutMillis = 5_000
            }
            defaultRequest {
                // OpenFoodFacts asks every app to identify itself.
                header(HttpHeaders.UserAgent, "QuarterMaster/$appVersion (https://github.com/jman9700/Quarter_Master)")
            }
        }
    }
}

@Serializable
internal data class OffResponse(
    val status: Int = 0,
    val product: OffProduct? = null,
)

@Serializable
internal data class OffProduct(
    @SerialName("product_name") val productName: String? = null,
    val brands: String? = null,
    val quantity: String? = null,
    @SerialName("product_quantity") val productQuantity: JsonElement? = null,
    @SerialName("product_quantity_unit") val productQuantityUnit: String? = null,
    @SerialName("image_front_url") val imageFrontUrl: String? = null,
    @SerialName("image_front_small_url") val imageFrontSmallUrl: String? = null,
) {
    fun toSuggestion(): ProductSuggestion {
        val brand = brands?.split(',')?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        val baseName = productName?.trim()?.takeIf { it.isNotEmpty() }
        val name = when {
            baseName == null -> brand
            brand == null || baseName.contains(brand, ignoreCase = true) -> baseName
            else -> "$brand $baseName"
        }
        val parsed = QuantityParser.parse(quantity)
        val numeric = (productQuantity as? JsonPrimitive)?.content?.toDoubleOrNull()
        val numericUnit = QuantityParser.mapUnit(productQuantityUnit)
        val (qty, unit) = when {
            parsed != null -> parsed
            numeric != null && numericUnit != null -> numeric to numericUnit
            else -> null to null
        }
        return ProductSuggestion(name, qty, unit, imageFrontUrl ?: imageFrontSmallUrl)
    }
}

/** Turns OpenFoodFacts quantity text like "1 gal", "16 oz (453 g)" or "500ml" into the app's units. */
internal object QuantityParser {
    private val pattern = Regex("""(\d+(?:[.,]\d+)?)\s*(fl\.?\s*oz|oz|lbs?|gal(?:lons?)?|kg|g|ml|cl|l)\b""", RegexOption.IGNORE_CASE)

    fun parse(text: String?): Pair<Double, String>? {
        if (text.isNullOrBlank()) return null
        val match = pattern.find(text) ?: return null
        var value = match.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        var rawUnit = match.groupValues[2].lowercase()
        if (rawUnit == "cl") { value *= 10; rawUnit = "ml" }
        return mapUnit(rawUnit)?.let { value to it }
    }

    fun mapUnit(raw: String?): String? = when (raw?.lowercase()?.replace(".", "")?.replace(" ", "")) {
        "g" -> "Grams"
        "kg" -> "Kg"
        "ml" -> "mL"
        "l" -> "L"
        "oz" -> "Oz"
        "floz" -> "Fl Oz"
        "lb", "lbs" -> "Lb"
        "gal", "gallon", "gallons" -> "Gal"
        else -> null
    }
}
