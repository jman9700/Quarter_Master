package io.github.jman9700.quartermaster.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import io.github.jman9700.quartermaster.domain.Nutrients
import io.github.jman9700.quartermaster.domain.Nutrition
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenFoodFactsTest {

    private fun client(status: HttpStatusCode = HttpStatusCode.OK, body: String, onUrl: (String) -> Unit = {}) =
        OpenFoodFactsClient(
            OpenFoodFactsClient.createHttpClient(
                HttpClient(MockEngine { request ->
                    onUrl(request.url.toString())
                    respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
                }),
                "test",
            ),
        )

    private fun found(result: LookupResult) = assertIs<LookupResult.Found>(result).suggestion

    @Test
    fun parsesAProduct() = runTest {
        var url = ""
        val off = client(
            body = """
                {"code":"0036000291452","status":1,"product":{
                  "product_name":"Whole Milk","brands":"Great Value, Walmart","quantity":"1 gal",
                  "image_front_url":"https://images.example/milk.jpg","unknown_field":42}}
            """.trimIndent(),
            onUrl = { url = it },
        )
        val s = found(off.lookup("00036000291452"))
        assertTrue(url.contains("/product/0036000291452.json"), url)
        assertTrue(url.contains("nutriments"), url)
        assertEquals("Great Value Whole Milk", s.name)
        assertEquals(1.0, s.quantity)
        assertEquals("Gal", s.unit)
        assertEquals("https://images.example/milk.jpg", s.imageUrl)
        assertNull(s.nutrition)
    }

    @Test
    fun parsesNutrition() = runTest {
        val off = client(
            body = """
                {"status":1,"product":{"product_name":"Peanut Butter",
                  "nutriscore_grade":"D","nova_group":"3","serving_size":"2 tbsp (32 g)",
                  "allergens_tags":["en:peanuts","en:soybeans"],
                  "ingredients_text":"Arachides","ingredients_text_en":"Roasted peanuts, sugar, salt",
                  "nutriments":{"energy-kcal_100g":588,"energy-kcal_serving":"190","fat_100g":50,"saturated-fat_100g":10,
                    "carbohydrates_100g":20,"sugars_100g":9,"fiber_100g":6,"proteins_100g":25,"salt_100g":1.25,
                    "sodium_serving":0.14}}}
            """.trimIndent(),
        )
        val n = assertNotNull(found(off.lookup("00036000291452")).nutrition)
        assertEquals("d", n.nutriScore)
        assertEquals(3, n.novaGroup)
        assertEquals("2 tbsp (32 g)", n.servingSize)
        assertEquals(listOf("Peanuts", "Soybeans"), n.allergens)
        assertEquals("Roasted peanuts, sugar, salt", n.ingredients)
        val per100 = assertNotNull(n.per100g)
        assertEquals(588.0, per100.energyKcal)
        assertEquals(25.0, per100.proteins)
        assertEquals(0.5, per100.sodium) // derived from salt / 2.5
        val serving = assertNotNull(n.perServing)
        assertEquals(190.0, serving.energyKcal) // numbers may arrive as strings
        assertEquals(0.14, serving.sodium)
        assertNull(serving.fat)
    }

    @Test
    fun unknownGradesAreDropped() {
        val n = OffProduct(
            nutriscoreGrade = "unknown",
            novaGroup = kotlinx.serialization.json.JsonPrimitive(9),
            servingSize = "1 cup",
            allergensTags = listOf("en:milk"),
        ).toNutrition()
        assertEquals(Nutrition(servingSize = "1 cup", allergens = listOf("Milk")), n)
        assertNull(OffProduct(nutriscoreGrade = "not-applicable").toNutrition())
        // A serving size alone isn't nutrition info worth showing.
        assertNull(OffProduct(servingSize = "1 cup").toNutrition())
    }

    @Test
    fun nutritionSurvivesStorage() {
        val n = Nutrition(nutriScore = "a", per100g = Nutrients(energyKcal = 52.0), allergens = listOf("Milk"))
        assertEquals(n, Nutrition.fromJson(n.toJson()))
        assertNull(Nutrition.fromJson("not json"))
        assertNull(Nutrition.fromJson(null))
    }

    @Test
    fun unknownProductIsNotFound() = runTest {
        assertEquals(LookupResult.NotFound, client(body = """{"status":0,"status_verbose":"product not found"}""").lookup("00036000291452"))
        assertEquals(LookupResult.NotFound, client(HttpStatusCode.NotFound, """{"status":0}""").lookup("00036000291452"))
    }

    @Test
    fun serverErrorIsUnavailable() = runTest {
        assertEquals(LookupResult.Unavailable, client(HttpStatusCode.InternalServerError, "oops").lookup("00036000291452"))
        assertEquals(LookupResult.Unavailable, client(HttpStatusCode.ServiceUnavailable, "busy").lookup("00036000291452"))
    }

    @Test
    fun captivePortalOrGarbageIsUnavailable() = runTest {
        assertEquals(LookupResult.Unavailable, client(body = "<html>Sign in to Wi-Fi</html>").lookup("00036000291452"))
    }

    @Test
    fun networkFailureIsUnavailable() = runTest {
        val off = OpenFoodFactsClient(
            OpenFoodFactsClient.createHttpClient(HttpClient(MockEngine { throw IllegalStateException("no route to host") }), "test"),
        )
        assertEquals(LookupResult.Unavailable, off.lookup("00036000291452"))
    }

    @Test
    fun numericQuantityFallback() {
        val product = OffProduct(productName = "Rice", productQuantity = kotlinx.serialization.json.JsonPrimitive(907), productQuantityUnit = "g")
        val s = product.toSuggestion()
        assertEquals(907.0, s.quantity)
        assertEquals("Grams", s.unit)
    }

    @Test
    fun quantityParser() {
        assertEquals(16.0 to "Oz", QuantityParser.parse("16 oz (453 g)"))
        assertEquals(12.0 to "Fl Oz", QuantityParser.parse("12 fl. oz"))
        assertEquals(500.0 to "mL", QuantityParser.parse("500ml"))
        assertEquals(330.0 to "mL", QuantityParser.parse("33 cl"))
        assertEquals(1.5 to "Kg", QuantityParser.parse("1,5 kg"))
        assertEquals(2.0 to "Lb", QuantityParser.parse("2 lbs"))
        assertNull(QuantityParser.parse("6 rolls"))
        assertNull(QuantityParser.parse(null))
    }

    @Test
    fun brandIsNotDuplicatedInTheName() {
        assertEquals("Cheerios", OffProduct(productName = "Cheerios", brands = "Cheerios").toSuggestion().name)
        assertEquals("Heinz", OffProduct(brands = "Heinz").toSuggestion().name)
        assertEquals("Coca-Cola Original", OffProduct(productName = "Coca-Cola Original", brands = "COCA-COLA SERVICES SA/NV").toSuggestion().name)
        assertEquals("Great Value Whole Milk", OffProduct(productName = "Whole Milk", brands = "Great Value").toSuggestion().name)
    }
}
