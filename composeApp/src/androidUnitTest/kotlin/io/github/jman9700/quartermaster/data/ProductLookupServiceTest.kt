package io.github.jman9700.quartermaster.data

import io.github.jman9700.quartermaster.domain.LookupResultState
import io.github.jman9700.quartermaster.domain.LookupStatus
import io.github.jman9700.quartermaster.domain.ProductDraft
import io.github.jman9700.quartermaster.domain.ProductField
import io.github.jman9700.quartermaster.domain.ProductType
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProductLookupServiceTest {
    private val repo = inMemoryRepository()
    private val images = FakeImageStore()
    private var online = true
    private val requested = mutableListOf<String>()

    /** OpenFoodFacts code -> JSON body; missing codes answer "not found"; codes in [failing] drop the connection. */
    private val products = mutableMapOf<String, String>()
    private val failing = mutableSetOf<String>()
    private var photosFail = false

    private val service = ProductLookupService(
        repo,
        OpenFoodFactsClient(
            OpenFoodFactsClient.createHttpClient(
                HttpClient(MockEngine { request ->
                    val url = request.url.toString()
                    requested += url
                    if (url.startsWith("https://img.example/")) {
                        if (photosFail) throw IllegalStateException("connection dropped mid-download")
                        if (url.endsWith("gone.jpg")) return@MockEngine respond("", HttpStatusCode.NotFound)
                        return@MockEngine respond(byteArrayOf(9, 9, 9), HttpStatusCode.OK)
                    }
                    val code = url.substringAfter("/product/").substringBefore(".json")
                    if (code in failing) throw IllegalStateException("connection reset")
                    val body = products[code] ?: return@MockEngine respond("""{"status":0}""", HttpStatusCode.NotFound, json)
                    respond(body, HttpStatusCode.OK, json)
                }),
                "test",
            ),
        ),
        images,
    ) { online }

    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    private val peanutButter = """
        {"status":1,"product":{"product_name":"Alaska Peanut Butter","quantity":"16 oz",
          "image_front_url":"https://img.example/pb.jpg","nutriscore_grade":"c",
          "nutriments":{"energy-kcal_100g":588,"proteins_100g":25}}}
    """.trimIndent()

    private suspend fun queued(
        gtin: String,
        name: String = "",
        userFields: Set<ProductField> = emptySet(),
        imageFile: String? = null,
    ): ProductType = repo.createType(
        ProductDraft(gtin, name, 30, 1.0, "Count", imageFile, userFields, LookupResultState(LookupStatus.PENDING)),
    )

    private suspend fun reload(p: ProductType) = assertNotNull(repo.getType(p.id))

    @Test
    fun offlineLeavesTheQueueAlone() = runTest {
        val p = queued("00036000291452")
        online = false
        assertNull(service.processQueue())
        assertEquals(LookupStatus.PENDING, reload(p).lookupStatus)
        assertTrue(requested.isEmpty(), "no requests while offline")
    }

    @Test
    fun fillsInPendingProductsButNeverWhatTheUserSet() = runTest {
        products["0036000291452"] = peanutButter
        val p = queued("00036000291452", name = "Grandma's PB", userFields = setOf(ProductField.NAME))

        val run = assertNotNull(service.processQueue())

        assertEquals(QueueRunResult(filledIn = 1, notFound = 0, stillPending = 0), run)
        assertEquals(run, service.lastRun.value)
        val after = reload(p)
        assertEquals("Grandma's PB", after.name) // the user's name wins
        assertEquals("16 Oz", after.amountText) // not set by the user, so filled in
        assertNotNull(after.imageFile)
        assertEquals("c", after.nutrition?.nutriScore)
        assertEquals(25.0, after.nutrition?.per100g?.proteins)
        assertEquals(LookupStatus.FOUND, after.lookupStatus)
    }

    @Test
    fun aNamelessOfflineProductGetsItsName() = runTest {
        products["0036000291452"] = peanutButter
        val p = queued("00036000291452")
        assertEquals("Product 036000291452", p.displayName)
        service.processQueue()
        assertEquals("Alaska Peanut Butter", reload(p).displayName)
    }

    @Test
    fun aRemovedPhotoStaysRemoved() = runTest {
        products["0036000291452"] = peanutButter
        val p = queued("00036000291452", name = "PB", userFields = setOf(ProductField.IMAGE))
        service.processQueue()
        assertNull(reload(p).imageFile)
        assertTrue(requested.none { it.startsWith("https://img.example/") }, "no photo download")
    }

    @Test
    fun stopsAtTheFirstSignOfABadConnection() = runTest {
        products["0036000291452"] = peanutButter
        products["4006381333931"] = peanutButter
        failing += "0012345678905"
        val first = queued("00036000291452")
        val second = queued("00012345678905")
        val third = queued("04006381333931")

        val run = assertNotNull(service.processQueue())

        assertEquals(1, run.filledIn)
        assertEquals(2, run.stillPending)
        assertEquals(LookupStatus.FOUND, reload(first).lookupStatus)
        assertEquals(LookupStatus.PENDING, reload(second).lookupStatus)
        assertEquals(LookupStatus.PENDING, reload(third).lookupStatus)
        assertTrue(requested.none { "4006381333931" in it }, "didn't keep hammering a bad connection")

        // Next time the app opens with a good connection, the rest go through.
        failing.clear()
        products["0012345678905"] = peanutButter
        assertEquals(2, service.processQueue()?.filledIn)
    }

    @Test
    fun aPhotoLostToAWeakConnectionIsRetriedNextTime() = runTest {
        products["0036000291452"] = peanutButter
        products["4006381333931"] = peanutButter
        val p = queued("00036000291452")
        val other = queued("04006381333931")
        photosFail = true

        val run = assertNotNull(service.processQueue())

        // The details arrived and are kept...
        val after = reload(p)
        assertEquals("Alaska Peanut Butter", after.name)
        assertNotNull(after.nutrition)
        assertNull(after.imageFile)
        // ...but it stays queued for its photo, and the run stops on the bad connection.
        assertEquals(LookupStatus.PENDING, after.lookupStatus)
        assertEquals(LookupStatus.PENDING, reload(other).lookupStatus)
        assertEquals(2, run.stillPending)

        photosFail = false
        service.processQueue()
        assertNotNull(reload(p).imageFile)
        assertEquals(LookupStatus.FOUND, reload(p).lookupStatus)
        assertEquals("Alaska Peanut Butter", reload(p).name)
    }

    @Test
    fun aPhotoMissingOnTheServerIsNotRetried() = runTest {
        products["0036000291452"] = peanutButter.replace("pb.jpg", "gone.jpg")
        val p = queued("00036000291452")
        service.processQueue()
        assertNull(reload(p).imageFile)
        assertEquals(LookupStatus.FOUND, reload(p).lookupStatus)
    }

    @Test
    fun unknownBarcodesLeaveTheQueue() = runTest {
        val p = queued("00036000291452", name = "Store brand")
        val run = assertNotNull(service.processQueue())
        assertEquals(1, run.notFound)
        assertEquals(LookupStatus.NOT_FOUND, reload(p).lookupStatus)
        requested.clear()
        service.processQueue()
        assertTrue(requested.isEmpty(), "not retried")
    }

    @Test
    fun productsNeverLookedUpAreBackfilled() = runTest {
        products["0036000291452"] = peanutButter
        // Saved while lookup was off, or by version 1.0: no lookup state at all.
        val p = repo.createType(ProductDraft("00036000291452", "PB", 30, 1.0, "Count", null, setOf(ProductField.NAME, ProductField.AMOUNT)))
        assertEquals(LookupStatus.NONE, p.lookupStatus)
        service.processQueue()
        val after = reload(p)
        assertEquals(LookupStatus.FOUND, after.lookupStatus)
        assertEquals("PB", after.name)
        assertEquals("1 Count", after.amountText)
        assertNotNull(after.nutrition)
    }

    @Test
    fun productsWithoutBarcodesAreNeverQueued() = runTest {
        val p = repo.createType(ProductDraft(null, "Bananas", 5, 6.0, "Count", null, lookup = LookupResultState(LookupStatus.PENDING)))
        assertEquals(LookupStatus.NONE, p.lookupStatus)
        assertNull(service.processQueue())
    }

    @Test
    fun turnedOffMeansNothingIsSent() = runTest {
        repo.setOnlineLookupEnabled(false)
        val p = queued("00036000291452")
        assertNull(service.processQueue())
        assertEquals(LookupStatus.PENDING, reload(p).lookupStatus)
        assertTrue(requested.isEmpty())
    }

    @Test
    fun interactiveLookupWhileOfflineIsUnavailable() = runTest {
        online = false
        assertEquals(LookupResult.Unavailable, service.lookup("00036000291452"))
        assertTrue(requested.isEmpty())
    }
}
