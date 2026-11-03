package io.github.jman9700.quartermaster.data

import io.github.jman9700.quartermaster.domain.Barcode
import io.github.jman9700.quartermaster.domain.Nutrients
import io.github.jman9700.quartermaster.domain.Nutrition
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** What OpenFoodFacts knows about a barcode, used to fill in a product. */
data class ProductSuggestion(
    val name: String?,
    val quantity: Double?,
    val unit: String?,
    val imageUrl: String?,
    val nutrition: Nutrition? = null,
)

sealed interface LookupResult {
    data class Found(val suggestion: ProductSuggestion) : LookupResult

    /** OpenFoodFacts answered: it doesn't have this barcode. Not worth retrying. */
    data object NotFound : LookupResult

    /** No answer: offline, weak signal, timeout or a server error. The request should be queued. */
    data object Unavailable : LookupResult
}

sealed interface ImageDownload {
    class Ok(val bytes: ByteArray) : ImageDownload

    /** The server says there's no such photo. Not worth retrying. */
    data object Missing : ImageDownload

    /** Connection trouble; worth retrying later. */
    data object Unavailable : ImageDownload
}

/**
 * Optional online lookup against the free OpenFoodFacts database. Not a server we run.
 */
class OpenFoodFactsClient(private val http: HttpClient) {

    suspend fun lookup(gtin14: String): LookupResult {
        val code = Barcode.lookupCode(gtin14)
        return try {
            val response = http.get("https://world.openfoodfacts.org/api/v2/product/$code.json?fields=$FIELDS")
            when {
                // v2 answers an unknown barcode with 404 and a JSON body saying so.
                response.status == HttpStatusCode.NotFound -> LookupResult.NotFound
                !response.status.isSuccess() -> LookupResult.Unavailable
                else -> {
                    val body = response.body<OffResponse>()
                    val product = body.product
                    if (body.status != 1 || product == null) LookupResult.NotFound else LookupResult.Found(product.toSuggestion())
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Timeouts, DNS failures, dropped connections, garbage from a captive portal...
            LookupResult.Unavailable
        }
    }

    suspend fun downloadImage(url: String): ImageDownload = try {
        val response = http.get(url)
        when {
            response.status.isSuccess() -> ImageDownload.Ok(response.body<ByteArray>())
            response.status.value in 400..499 -> ImageDownload.Missing
            else -> ImageDownload.Unavailable
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ImageDownload.Unavailable
    }

    companion object {
        private const val FIELDS = "product_name,brands,quantity,product_quantity,product_quantity_unit," +
            "image_front_url,image_front_small_url,nutriments,nutriscore_grade,nova_group,serving_size," +
            "allergens_tags,ingredients_text,ingredients_text_en"

        /** The product's page on the OpenFoodFacts website. */
        fun productPageUrl(gtin14: String) = "https://world.openfoodfacts.org/product/${Barcode.lookupCode(gtin14)}"

        fun createHttpClient(base: HttpClient, appVersion: String): HttpClient = base.config {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; isLenient = true }) }
            install(HttpTimeout) {
                // Short on purpose: in a store basement it's better to queue quickly than to hang.
                requestTimeoutMillis = 8_000
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
    val nutriments: JsonObject? = null,
    @SerialName("nutriscore_grade") val nutriscoreGrade: String? = null,
    @SerialName("nova_group") val novaGroup: JsonElement? = null,
    @SerialName("serving_size") val servingSize: String? = null,
    @SerialName("allergens_tags") val allergensTags: List<String> = emptyList(),
    @SerialName("ingredients_text") val ingredientsText: String? = null,
    @SerialName("ingredients_text_en") val ingredientsTextEn: String? = null,
) {
    fun toSuggestion(): ProductSuggestion {
        val brand = brands?.split(',')?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        val baseName = productName?.trim()?.takeIf { it.isNotEmpty() }
        val name = when {
            baseName == null -> brand
            // Brands are sometimes the company ("COCA-COLA SERVICES SA/NV"): skip them when the name already says it.
            brand == null || baseName.contains(brand.substringBefore(' '), ignoreCase = true) -> baseName
            else -> "$brand $baseName"
        }
        val parsed = QuantityParser.parse(quantity)
        val numeric = productQuantity.asDouble()
        val numericUnit = QuantityParser.mapUnit(productQuantityUnit)
        val (qty, unit) = when {
            parsed != null -> parsed
            numeric != null && numericUnit != null -> numeric to numericUnit
            else -> null to null
        }
        return ProductSuggestion(name, qty, unit, imageFrontUrl ?: imageFrontSmallUrl, toNutrition())
    }

    fun toNutrition(): Nutrition? = Nutrition(
        servingSize = servingSize?.trim()?.takeIf { it.isNotEmpty() },
        nutriScore = nutriscoreGrade?.lowercase()?.takeIf { it in setOf("a", "b", "c", "d", "e") },
        novaGroup = novaGroup.asDouble()?.toInt()?.takeIf { it in 1..4 },
        per100g = nutrients("_100g"),
        perServing = nutrients("_serving"),
        allergens = allergensTags.map(::readableTag).filter { it.isNotEmpty() }.distinct(),
        ingredients = (ingredientsTextEn ?: ingredientsText)?.trim()?.takeIf { it.isNotEmpty() },
    ).takeUnless { it.isEmpty }

    private fun nutrients(suffix: String): Nutrients? {
        val n = nutriments ?: return null
        fun value(key: String) = n["$key$suffix"].asDouble()
        val sodium = value("sodium") ?: value("salt")?.let { it / 2.5 }
        return Nutrients(
            energyKcal = value("energy-kcal"),
            fat = value("fat"),
            saturatedFat = value("saturated-fat"),
            carbohydrates = value("carbohydrates"),
            sugars = value("sugars"),
            fiber = value("fiber"),
            proteins = value("proteins"),
            sodium = sodium,
        ).takeUnless { it.isEmpty }
    }
}

/** OpenFoodFacts sends numbers either as JSON numbers or as strings. */
private fun JsonElement?.asDouble(): Double? = (this as? JsonPrimitive)?.content?.toDoubleOrNull()

/** "en:peanuts" -> "Peanuts". */
private fun readableTag(tag: String): String =
    tag.substringAfter(':').replace('-', ' ').trim().replaceFirstChar { it.uppercase() }

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
