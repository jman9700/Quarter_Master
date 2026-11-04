package io.github.jman9700.quartermaster.server

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.jman9700.quartermaster.protocol.AccessRequest
import io.github.jman9700.quartermaster.protocol.Api
import io.github.jman9700.quartermaster.protocol.CreateInventoryRequest
import io.github.jman9700.quartermaster.protocol.CreateInventoryResponse
import io.github.jman9700.quartermaster.protocol.Headers
import io.github.jman9700.quartermaster.protocol.ItemRow
import io.github.jman9700.quartermaster.protocol.PassRequest
import io.github.jman9700.quartermaster.protocol.PassResponse
import io.github.jman9700.quartermaster.protocol.ProductRow
import io.github.jman9700.quartermaster.protocol.SyncRequest
import io.github.jman9700.quartermaster.protocol.SyncResponse
import io.github.jman9700.quartermaster.server.db.ServerDatabase
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.util.Base64
import java.util.UUID

class TestClock(var now: Long = 1_800_000_000_000L) : Clock {
    override fun now() = now
    fun advanceHours(h: Int) {
        now += h * 3_600_000L
    }
}

const val ADMIN_TOKEN = "admin-secret-for-tests"

class TestDevice(val person: String, val deviceName: String = "$person's phone") {
    val id: String = UUID.randomUUID().toString()
    val secret: String = Security.randomToken()
}

fun HttpRequestBuilder.asDevice(d: TestDevice) {
    header(Headers.DEVICE_ID, d.id)
    header(Headers.DEVICE_SECRET, d.secret)
}

fun HttpRequestBuilder.asHost(token: String = ADMIN_TOKEN) {
    header(HttpHeaders.Authorization, "Basic " + Base64.getEncoder().encodeToString("host:$token".toByteArray()))
}

fun HttpRequestBuilder.json(body: Any) {
    contentType(ContentType.Application.Json)
    setBody(body)
}

/** A running server (real routes, in-memory database) plus helpers for the common flows. */
class Harness(val app: ApplicationTestBuilder, val server: InventoryServer, val clock: TestClock) {
    val client: HttpClient = app.createClient { install(ContentNegotiation) { json() } }

    suspend fun request(d: TestDevice): HttpResponse = client.post("${Api.PREFIX}/devices/request") {
        json(AccessRequest(d.id, d.secret, d.person, d.deviceName))
    }

    suspend fun approve(d: TestDevice, name: String? = null): HttpResponse =
        client.post("${Api.PREFIX}/admin/devices/${d.id}/approve") {
            asHost()
            json(NameBody(name))
        }

    suspend fun whitelisted(d: TestDevice): TestDevice {
        request(d)
        approve(d)
        return d
    }

    suspend fun create(d: TestDevice, name: String = "Pantry", password: String = "correct horse"): CreateInventoryResponse =
        client.post("${Api.PREFIX}/inventories") {
            asDevice(d)
            json(CreateInventoryRequest(name, password))
        }.body()

    suspend fun passResponse(d: TestDevice, inventoryId: String, password: String = "correct horse"): HttpResponse =
        client.post("${Api.PREFIX}/inventories/$inventoryId/access") {
            asDevice(d)
            json(PassRequest(password))
        }

    suspend fun pass(d: TestDevice, inventoryId: String, password: String = "correct horse"): String =
        passResponse(d, inventoryId, password).body<PassResponse>().token

    suspend fun syncResponse(d: TestDevice, inventoryId: String, token: String?, req: SyncRequest): HttpResponse =
        client.post("${Api.PREFIX}/inventories/$inventoryId/sync") {
            asDevice(d)
            token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            json(req)
        }

    suspend fun sync(d: TestDevice, inventoryId: String, token: String, req: SyncRequest): SyncResponse =
        syncResponse(d, inventoryId, token, req).also { check(it.status.value == 200) { "sync failed: ${it.status}" } }.body()
}

fun serverTest(block: suspend Harness.() -> Unit) {
    Security.passwordIterations = 1_000 // fast hashes; strength isn't under test
    val clock = TestClock()
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { ServerDatabase.Schema.create(it) }
    val config = ServerConfig(
        dataDir = Files.createTempDirectory("qm-server-test").toFile(),
        serverName = "Test server",
        adminToken = ADMIN_TOKEN,
        discovery = false,
        version = "test",
        commit = "abc1234",
    )
    val server = InventoryServer(driver, config, clock)
    testApplication {
        application { quarterMasterModule(server) }
        Harness(this, server, clock).block()
    }
}

fun product(id: String, name: String, updatedAt: Long, image: String? = null, deletedAt: Long? = null) =
    ProductRow(id = id, name = name, lifespanDays = 7, quantity = 1.0, unit = "Gal", imageFile = image, createdAt = 1, updatedAt = updatedAt, deletedAt = deletedAt)

fun item(id: String, typeId: String, updatedAt: Long, removedOn: String? = null, reason: String? = null, deletedAt: Long? = null) =
    ItemRow(id = id, typeId = typeId, addedOn = "2026-11-03", removedOn = removedOn, removalReason = reason, createdAt = 1, updatedAt = updatedAt, deletedAt = deletedAt)
