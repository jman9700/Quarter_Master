package io.github.jman9700.quartermaster.server

import io.github.jman9700.quartermaster.protocol.Api
import io.github.jman9700.quartermaster.protocol.ApiError
import io.github.jman9700.quartermaster.protocol.ChangePasswordRequest
import io.github.jman9700.quartermaster.protocol.DeviceStatus
import io.github.jman9700.quartermaster.protocol.DeviceStatusResponse
import io.github.jman9700.quartermaster.protocol.ErrorCodes
import io.github.jman9700.quartermaster.protocol.Headers
import io.github.jman9700.quartermaster.protocol.InventorySummary
import io.github.jman9700.quartermaster.protocol.ServerInfo
import io.github.jman9700.quartermaster.protocol.SyncRequest
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ServerTest {

    private suspend fun io.ktor.client.statement.HttpResponse.error() = body<ApiError>()

    // ---- Server info ----------------------------------------------------------------------

    @Test
    fun infoReportsTheCommitForTheDeployPipeline() = serverTest {
        val info = client.get("${Api.PREFIX}/info").body<ServerInfo>()
        assertEquals("Test server", info.name)
        assertEquals("abc1234", info.commit)
        assertEquals(Api.PROTOCOL_VERSION, info.protocolVersion)
    }

    // ---- Whitelist: request, approve, name ------------------------------------------------

    @Test
    fun aDeviceRequestsAccessAndTheHostApprovesItWithAName() = serverTest {
        val phone = TestDevice("jordan")
        val requested = request(phone).body<DeviceStatusResponse>()
        assertEquals(DeviceStatus.PENDING, requested.status)

        // Waiting: nothing on the server is visible yet.
        val waiting = client.get("${Api.PREFIX}/inventories") { asDevice(phone) }
        assertEquals(HttpStatusCode.Forbidden, waiting.status)
        assertEquals(ErrorCodes.DEVICE_PENDING, waiting.error().code)

        // The host approves and corrects the name for the log.
        assertEquals(HttpStatusCode.NoContent, approve(phone, "Jordan").status)
        val me = client.get("${Api.PREFIX}/devices/me") { asDevice(phone) }.body<DeviceStatusResponse>()
        assertEquals(DeviceStatus.APPROVED, me.status)
        assertEquals("Jordan", me.personName)
        assertEquals(HttpStatusCode.OK, client.get("${Api.PREFIX}/inventories") { asDevice(phone) }.status)
    }

    @Test
    fun onlyTheHostCanApprove() = serverTest {
        val phone = TestDevice("Jordan")
        request(phone)
        val noAuth = client.post("${Api.PREFIX}/admin/devices/${phone.id}/approve")
        assertEquals(HttpStatusCode.Unauthorized, noAuth.status)
        val wrong = client.post("${Api.PREFIX}/admin/devices/${phone.id}/approve") { asHost("guess") }
        assertEquals(HttpStatusCode.Unauthorized, wrong.status)
    }

    @Test
    fun deniedDevicesAreRefusedAndCanAskAgain() = serverTest {
        val phone = TestDevice("Jordan")
        request(phone)
        client.post("${Api.PREFIX}/admin/devices/${phone.id}/deny") { asHost() }
        val refused = client.get("${Api.PREFIX}/inventories") { asDevice(phone) }
        assertEquals(ErrorCodes.DEVICE_DENIED, refused.error().code)
        assertEquals(DeviceStatus.PENDING, request(phone).body<DeviceStatusResponse>().status)
    }

    @Test
    fun anotherDeviceCantTakeOverAnId() = serverTest {
        val phone = whitelisted(TestDevice("Jordan"))
        val impostor = object {
            val id = phone.id
            val secret = Security.randomToken()
        }
        val takeover = client.post("${Api.PREFIX}/devices/request") {
            json(io.github.jman9700.quartermaster.protocol.AccessRequest(impostor.id, impostor.secret, "Mallory", "laptop"))
        }
        assertEquals(HttpStatusCode.Forbidden, takeover.status)
        val wrongSecret = client.get("${Api.PREFIX}/devices/me") {
            header(Headers.DEVICE_ID, phone.id)
            header(Headers.DEVICE_SECRET, impostor.secret)
        }
        assertEquals(HttpStatusCode.Unauthorized, wrongSecret.status)
    }

    // ---- Inventories and the daily pass ---------------------------------------------------

    @Test
    fun createListAndJoin() = serverTest {
        val owner = whitelisted(TestDevice("Jordan"))
        val member = whitelisted(TestDevice("Sam"))
        val created = create(owner, "Costco haul")
        assertTrue(created.ownerKey.length >= 32)

        val listed = client.get("${Api.PREFIX}/inventories") { asDevice(member) }.body<List<InventorySummary>>()
        assertEquals(listOf("Costco haul"), listed.map { it.name })
        assertEquals("Jordan", listed.single().createdBy)

        assertEquals(HttpStatusCode.OK, passResponse(member, created.inventory.id).status)
    }

    @Test
    fun passwordsMustBeReasonable() = serverTest {
        val owner = whitelisted(TestDevice("Jordan"))
        val short = client.post("${Api.PREFIX}/inventories") {
            asDevice(owner)
            json(io.github.jman9700.quartermaster.protocol.CreateInventoryRequest("Pantry", "123"))
        }
        assertEquals(HttpStatusCode.BadRequest, short.status)
    }

    @Test
    fun passesLastTwentyFourHours() = serverTest {
        val d = whitelisted(TestDevice("Jordan"))
        val inv = create(d).inventory.id
        val token = pass(d, inv)
        assertEquals(HttpStatusCode.OK, syncResponse(d, inv, token, SyncRequest(0)).status)

        clock.advanceHours(23)
        assertEquals(HttpStatusCode.OK, syncResponse(d, inv, token, SyncRequest(0)).status)

        clock.advanceHours(1)
        val expired = syncResponse(d, inv, token, SyncRequest(0))
        assertEquals(HttpStatusCode.Unauthorized, expired.status)
        assertEquals(ErrorCodes.PASS_EXPIRED, expired.error().code)
    }

    @Test
    fun aPassOnlyWorksForItsDeviceAndInventory() = serverTest {
        val a = whitelisted(TestDevice("Jordan"))
        val b = whitelisted(TestDevice("Sam"))
        val pantry = create(a, "Pantry").inventory.id
        val garage = create(a, "Garage").inventory.id
        val token = pass(a, pantry)
        assertEquals(ErrorCodes.PASS_REQUIRED, syncResponse(b, pantry, token, SyncRequest(0)).error().code)
        assertEquals(ErrorCodes.PASS_REQUIRED, syncResponse(a, garage, token, SyncRequest(0)).error().code)
        assertEquals(ErrorCodes.PASS_REQUIRED, syncResponse(a, pantry, null, SyncRequest(0)).error().code)
    }

    @Test
    fun wrongPasswordsSlowDown() = serverTest {
        val d = whitelisted(TestDevice("Jordan"))
        val inv = create(d).inventory.id
        repeat(5) { assertEquals(ErrorCodes.WRONG_PASSWORD, passResponse(d, inv, "nope").error().code) }
        val locked = passResponse(d, inv, "correct horse")
        assertEquals(HttpStatusCode.TooManyRequests, locked.status)
        assertNotNull(locked.headers[HttpHeaders.RetryAfter])

        clock.now += 31_000
        assertEquals(HttpStatusCode.OK, passResponse(d, inv).status)
    }

    @Test
    fun changingThePasswordEndsEveryonesPass() = serverTest {
        val owner = whitelisted(TestDevice("Jordan"))
        val member = whitelisted(TestDevice("Sam"))
        val created = create(owner)
        val inv = created.inventory.id
        val memberToken = pass(member, inv)

        val notOwner = client.put("${Api.PREFIX}/inventories/$inv/password") {
            asDevice(member)
            header(Headers.OWNER_KEY, "not-the-key")
            json(ChangePasswordRequest("new password"))
        }
        assertEquals(HttpStatusCode.Forbidden, notOwner.status)

        val changed = client.put("${Api.PREFIX}/inventories/$inv/password") {
            asDevice(owner)
            header(Headers.OWNER_KEY, created.ownerKey)
            json(ChangePasswordRequest("new password"))
        }
        assertEquals(HttpStatusCode.NoContent, changed.status)
        assertEquals(ErrorCodes.PASS_REQUIRED, syncResponse(member, inv, memberToken, SyncRequest(0)).error().code)
        assertEquals(ErrorCodes.WRONG_PASSWORD, passResponse(member, inv, "correct horse").error().code)
        assertEquals(HttpStatusCode.OK, passResponse(member, inv, "new password").status)
    }

    @Test
    fun revokingADeviceEndsItsAccess() = serverTest {
        val owner = whitelisted(TestDevice("Jordan"))
        val member = whitelisted(TestDevice("Sam"))
        val inv = create(owner).inventory.id
        val token = pass(member, inv)
        client.post("${Api.PREFIX}/admin/devices/${member.id}/revoke") { asHost() }
        assertEquals(ErrorCodes.DEVICE_REVOKED, syncResponse(member, inv, token, SyncRequest(0)).error().code)
    }

    @Test
    fun theOwnerCanDeleteAnInventory() = serverTest {
        val owner = whitelisted(TestDevice("Jordan"))
        val created = create(owner)
        val gone = client.delete("${Api.PREFIX}/inventories/${created.inventory.id}") {
            asDevice(owner)
            header(Headers.OWNER_KEY, created.ownerKey)
        }
        assertEquals(HttpStatusCode.NoContent, gone.status)
        assertTrue(client.get("${Api.PREFIX}/inventories") { asDevice(owner) }.body<List<InventorySummary>>().isEmpty())
    }

    // ---- Sync -----------------------------------------------------------------------------

    @Test
    fun changesFlowBetweenDevices() = serverTest {
        val a = whitelisted(TestDevice("Jordan"))
        val b = whitelisted(TestDevice("Sam"))
        val inv = create(a).inventory.id
        val ta = pass(a, inv)
        val tb = pass(b, inv)

        val first = sync(a, inv, ta, SyncRequest(0, listOf(product("milk", "Milk", 10)), listOf(item("i1", "milk", 10), item("i2", "milk", 10))))
        // A gets its own accepted rows back, stamped with who changed them.
        assertEquals(a.id, first.products.single().changedBy)

        val bView = sync(b, inv, tb, SyncRequest(0))
        assertEquals(listOf("Milk"), bView.products.map { it.name })
        assertEquals(setOf("i1", "i2"), bView.items.map { it.id }.toSet())

        // B uses one; A only receives what changed since its cursor.
        sync(b, inv, tb, SyncRequest(bView.cursor, items = listOf(item("i1", "milk", 20, removedOn = "2026-11-04", reason = "USED"))))
        val aUpdate = sync(a, inv, ta, SyncRequest(first.cursor))
        assertTrue(aUpdate.products.isEmpty())
        assertEquals("USED", aUpdate.items.single().removalReason)
    }

    @Test
    fun newerChangesWinAndOlderOnesAreIgnored() = serverTest {
        val a = whitelisted(TestDevice("Jordan"))
        val b = whitelisted(TestDevice("Sam"))
        val inv = create(a).inventory.id
        val ta = pass(a, inv)
        val tb = pass(b, inv)
        sync(a, inv, ta, SyncRequest(0, listOf(product("milk", "Whole milk", 100))))
        // B was offline and edited earlier; that edit loses.
        val after = sync(b, inv, tb, SyncRequest(0, listOf(product("milk", "Milk (old edit)", 50))))
        assertEquals("Whole milk", after.products.single().name)
    }

    @Test
    fun resendingTheSameChangesIsHarmless() = serverTest {
        val a = whitelisted(TestDevice("Jordan"))
        val inv = create(a).inventory.id
        val t = pass(a, inv)
        val req = SyncRequest(0, listOf(product("milk", "Milk", 10)))
        val first = sync(a, inv, t, req)
        // e.g. the reply was lost to a bad connection and the app retries
        val again = sync(a, inv, t, req.copy(cursor = first.cursor))
        assertEquals(first.cursor, again.cursor)
        assertTrue(again.products.isEmpty())
    }

    @Test
    fun deletionsSyncAsTombstones() = serverTest {
        val a = whitelisted(TestDevice("Jordan"))
        val b = whitelisted(TestDevice("Sam"))
        val inv = create(a).inventory.id
        val ta = pass(a, inv)
        val tb = pass(b, inv)
        sync(a, inv, ta, SyncRequest(0, listOf(product("milk", "Milk", 10))))
        val cursor = sync(b, inv, tb, SyncRequest(0)).cursor
        sync(a, inv, ta, SyncRequest(0, listOf(product("milk", "Milk", 20, deletedAt = 20))))
        assertEquals(20L, sync(b, inv, tb, SyncRequest(cursor)).products.single().deletedAt)
    }

    @Test
    fun inventoriesDontSeeEachOther() = serverTest {
        val a = whitelisted(TestDevice("Jordan"))
        val pantry = create(a, "Pantry").inventory.id
        val garage = create(a, "Garage").inventory.id
        sync(a, pantry, pass(a, pantry), SyncRequest(0, listOf(product("milk", "Milk", 10))))
        assertTrue(sync(a, garage, pass(a, garage), SyncRequest(0)).products.isEmpty())
    }

    // ---- Photos ---------------------------------------------------------------------------

    @Test
    fun photosAreRequestedUploadedAndServed() = serverTest {
        val a = whitelisted(TestDevice("Jordan"))
        val inv = create(a).inventory.id
        val t = pass(a, inv)
        val photo = "763746ce-edd0-4dc9-a9b0-5c1776cef0d5.jpg"
        val reply = sync(a, inv, t, SyncRequest(0, listOf(product("milk", "Milk", 10, image = photo))))
        assertEquals(listOf(photo), reply.missingImages)

        val upload = client.put("${Api.PREFIX}/inventories/$inv/images/$photo") {
            asDevice(a)
            header(HttpHeaders.Authorization, "Bearer $t")
            setBody(byteArrayOf(1, 2, 3))
        }
        assertEquals(HttpStatusCode.NoContent, upload.status)
        assertTrue(sync(a, inv, t, SyncRequest(reply.cursor)).missingImages.isEmpty())

        val download = client.get("${Api.PREFIX}/inventories/$inv/images/$photo") {
            asDevice(a)
            header(HttpHeaders.Authorization, "Bearer $t")
        }
        assertContentEquals(byteArrayOf(1, 2, 3), download.readRawBytes())

        val noPass = client.get("${Api.PREFIX}/inventories/$inv/images/$photo") { asDevice(a) }
        assertEquals(HttpStatusCode.Unauthorized, noPass.status)
    }

    @Test
    fun photoNamesCantEscapeTheFolder() = serverTest {
        val a = whitelisted(TestDevice("Jordan"))
        val inv = create(a).inventory.id
        val t = pass(a, inv)
        val sneaky = client.put("${Api.PREFIX}/inventories/$inv/images/..%2F..%2Fadmin-token.txt") {
            asDevice(a)
            header(HttpHeaders.Authorization, "Bearer $t")
            setBody(byteArrayOf(1))
        }
        assertFalse(sneaky.status.value in 200..299)
        val badRow = syncResponse(a, inv, t, SyncRequest(0, listOf(product("milk", "Milk", 10, image = "../x.jpg"))))
        assertEquals(HttpStatusCode.BadRequest, badRow.status)
    }

    // ---- Activity log and admin page ------------------------------------------------------

    @Test
    fun theLogSaysWhoDidWhat() = serverTest {
        val a = whitelisted(TestDevice("jordan-phone"))
        approve(a, "Jordan")
        val inv = create(a, "Pantry").inventory.id
        val t = pass(a, inv)
        val c = sync(a, inv, t, SyncRequest(0, listOf(product("milk", "Milk", 10)), listOf(item("i1", "milk", 10), item("i2", "milk", 10)))).cursor
        sync(a, inv, t, SyncRequest(c, items = listOf(item("i1", "milk", 20, removedOn = "2026-11-05", reason = "SPOILED"))))

        val log = client.get("${Api.PREFIX}/admin/activity") { asHost() }.body<List<AdminActivity>>()
        val lines = log.map { "${it.personName}: ${it.action}" }
        assertContains(lines, "Jordan: created the inventory \"Pantry\"")
        assertContains(lines, "Jordan: added the product \"Milk\"")
        assertContains(lines, "Jordan: added 2 × Milk")
        assertContains(lines, "Jordan: marked Milk as went bad")
        assertContains(lines, "Host: approved \"Jordan\" on \"jordan-phone's phone\"")
        assertTrue(log.filter { it.action.startsWith("added 2") }.all { it.inventory == "Pantry" })
    }

    @Test
    fun theAdminPageShowsRequestsSafely() = serverTest {
        request(TestDevice("<script>alert(1)</script>"))
        val page = client.get("/admin") { asHost() }
        assertEquals(HttpStatusCode.OK, page.status)
        val html = page.bodyAsText()
        assertContains(html, "Requests waiting for you (1)")
        assertContains(html, "&lt;script&gt;")
        assertFalse(html.contains("<script>alert"))
        assertEquals(HttpStatusCode.Unauthorized, client.get("/admin").status)
    }

    @Test
    fun adminFormsFromOtherSitesAreRejected() = serverTest {
        val d = TestDevice("Jordan")
        request(d)
        val forged = client.post("/admin/devices/${d.id}/approve") {
            asHost()
            header(HttpHeaders.Origin, "https://evil.example")
            header(HttpHeaders.Host, "localhost")
        }
        assertEquals(HttpStatusCode.Forbidden, forged.status)
    }
}
