package io.github.jman9700.quartermaster.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
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
        val s = off.lookup("00036000291452")!!
        assertTrue(url.contains("/product/0036000291452.json"), url)
        assertEquals("Great Value Whole Milk", s.name)
        assertEquals(1.0, s.quantity)
        assertEquals("Gal", s.unit)
        assertEquals("https://images.example/milk.jpg", s.imageUrl)
    }

    @Test
    fun unknownProductIsNull() = runTest {
        assertNull(client(body = """{"status":0,"status_verbose":"product not found"}""").lookup("00036000291452"))
    }

    @Test
    fun httpErrorIsNull() = runTest {
        assertNull(client(HttpStatusCode.InternalServerError, "oops").lookup("00036000291452"))
    }

    @Test
    fun garbageIsNull() = runTest {
        assertNull(client(body = "<html>not json</html>").lookup("00036000291452"))
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
    }
}
