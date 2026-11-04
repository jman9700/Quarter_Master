package io.github.jman9700.quartermaster.server

import io.github.jman9700.quartermaster.protocol.AccessRequest
import io.github.jman9700.quartermaster.protocol.Api
import io.github.jman9700.quartermaster.protocol.ApiError
import io.github.jman9700.quartermaster.protocol.ChangePasswordRequest
import io.github.jman9700.quartermaster.protocol.CreateInventoryRequest
import io.github.jman9700.quartermaster.protocol.ErrorCodes
import io.github.jman9700.quartermaster.protocol.Headers
import io.github.jman9700.quartermaster.protocol.PassRequest
import io.github.jman9700.quartermaster.protocol.SyncRequest
import io.github.jman9700.quartermaster.server.db.Device
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.basic
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Wires [InventoryServer] to HTTP. Shared by the real server and the tests. */
fun Application.quarterMasterModule(server: InventoryServer) {
    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; encodeDefaults = true }) }
    install(StatusPages) {
        exception<ApiException> { call, e ->
            e.retryAfterSeconds?.let { call.response.headers.append(HttpHeaders.RetryAfter, it.toString()) }
            call.respond(e.status, ApiError(e.code, e.message, e.retryAfterSeconds))
        }
        exception<BadRequestException> { call, e ->
            call.respond(HttpStatusCode.BadRequest, ApiError(ErrorCodes.BAD_REQUEST, e.message ?: "Bad request"))
        }
    }
    install(Authentication) {
        // The host's admin page. Any user name; the password is QM_ADMIN_TOKEN.
        basic("admin") {
            realm = "Quarter Master server admin"
            validate { credentials ->
                if (Security.matchesSha256(credentials.password, Security.sha256(server.config.adminToken))) UserIdPrincipal("host") else null
            }
        }
    }

    suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }
    suspend fun ApplicationCall.device(): Device =
        io { server.authenticate(request.headers[Headers.DEVICE_ID], request.headers[Headers.DEVICE_SECRET]) }
    fun ApplicationCall.bearer(): String? =
        request.headers[HttpHeaders.Authorization]?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }?.substring(7)?.trim()
    fun ApplicationCall.inventoryId(): String = parameters["id"]!!

    routing {
        route(Api.PREFIX) {
            get("/info") { call.respond(server.info()) }

            route("/devices") {
                post("/request") {
                    val req = call.receive<AccessRequest>()
                    call.respond(io { server.requestAccess(req) })
                }
                get("/me") {
                    val device = call.device()
                    call.respond(server.statusOf(device))
                }
            }

            route("/inventories") {
                get {
                    val device = call.device()
                    call.respond(io { server.listInventories(device) })
                }
                post {
                    val device = call.device()
                    val req = call.receive<CreateInventoryRequest>()
                    call.respond(HttpStatusCode.Created, io { server.createInventory(device, req) })
                }
                route("/{id}") {
                    delete {
                        val device = call.device()
                        io { server.deleteInventory(device, call.inventoryId(), call.request.headers[Headers.OWNER_KEY]) }
                        call.respond(HttpStatusCode.NoContent)
                    }
                    post("/access") {
                        val device = call.device()
                        val req = call.receive<PassRequest>()
                        call.respond(io { server.issuePass(device, call.inventoryId(), req.password) })
                    }
                    put("/password") {
                        val device = call.device()
                        val req = call.receive<ChangePasswordRequest>()
                        io { server.changePassword(device, call.inventoryId(), call.request.headers[Headers.OWNER_KEY], req.newPassword) }
                        call.respond(HttpStatusCode.NoContent)
                    }
                    post("/sync") {
                        val device = call.device()
                        io { server.requirePass(device, call.inventoryId(), call.bearer()) }
                        val req = call.receive<SyncRequest>()
                        call.respond(io { server.sync(device, call.inventoryId(), req) })
                    }
                    put("/images/{name}") {
                        val device = call.device()
                        io { server.requirePass(device, call.inventoryId(), call.bearer()) }
                        if ((call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: 0) > MAX_UPLOAD) {
                            throw ApiException(HttpStatusCode.PayloadTooLarge, ErrorCodes.BAD_REQUEST, "Photos must be under 5 MB")
                        }
                        val bytes = call.receive<ByteArray>()
                        io { server.putImage(call.inventoryId(), call.parameters["name"]!!, bytes) }
                        call.respond(HttpStatusCode.NoContent)
                    }
                    get("/images/{name}") {
                        val device = call.device()
                        io { server.requirePass(device, call.inventoryId(), call.bearer()) }
                        val bytes = io { server.getImage(call.inventoryId(), call.parameters["name"]!!) }
                        call.respondBytes(bytes, ContentType.Image.JPEG)
                    }
                }
            }

            // Same powers as the admin page, as JSON, for scripts and tests.
            authenticate("admin") {
                route("/admin") {
                    get("/devices") { call.respond(io { server.devices().map(AdminDevice::of) }) }
                    post("/devices/{deviceId}/approve") {
                        val body = runCatching { call.receive<NameBody>() }.getOrNull()
                        io { server.approve(call.parameters["deviceId"]!!, body?.personName) }
                        call.respond(HttpStatusCode.NoContent)
                    }
                    post("/devices/{deviceId}/deny") {
                        io { server.deny(call.parameters["deviceId"]!!) }
                        call.respond(HttpStatusCode.NoContent)
                    }
                    post("/devices/{deviceId}/revoke") {
                        io { server.revoke(call.parameters["deviceId"]!!) }
                        call.respond(HttpStatusCode.NoContent)
                    }
                    get("/activity") { call.respond(io { server.recentActivity().map(AdminActivity::of) }) }
                }
            }
        }

        // ---- The host's admin page (also the server monitor) ----------------------------
        authenticate("admin") {
            route("/admin") {
                get {
                    val html = io { AdminPage.render(server) }
                    call.respondText(html, ContentType.Text.Html)
                }
                post("/devices/{deviceId}/{action}") {
                    call.requireSameOrigin()
                    val form = call.receiveParameters()
                    val id = call.parameters["deviceId"]!!
                    io {
                        when (call.parameters["action"]) {
                            "approve" -> server.approve(id, form["personName"])
                            "deny" -> server.deny(id)
                            "revoke" -> server.revoke(id)
                            "rename" -> server.rename(id, form["personName"].orEmpty())
                            else -> throw ApiException(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND, "Unknown action")
                        }
                    }
                    call.respondRedirect("/admin")
                }
                post("/inventories/{id}/reset-owner-key") {
                    call.requireSameOrigin()
                    val id = call.parameters["id"]!!
                    val key = io { server.resetOwnerKey(id) }
                    // Shown once, right here; never put in a URL or stored in plain form.
                    call.respondText(io { AdminPage.render(server, newOwnerKey = key, newOwnerKeyFor = id) }, ContentType.Text.Html)
                }
            }
        }
    }
}

private const val MAX_UPLOAD = 5L * 1024 * 1024

/** Blocks forms posted from other websites (basic CSRF protection for the admin page). */
private fun ApplicationCall.requireSameOrigin() {
    val origin = request.headers[HttpHeaders.Origin] ?: return
    val host = request.headers[HttpHeaders.Host] ?: return
    if (origin.substringAfter("://") != host) {
        throw ApiException(HttpStatusCode.Forbidden, ErrorCodes.ADMIN_ONLY, "Admin actions must come from the admin page")
    }
}

@Serializable
data class NameBody(val personName: String? = null)

@Serializable
data class AdminDevice(
    val id: String,
    val personName: String,
    val deviceName: String,
    val status: String,
    val requestedAt: Long,
    val lastSeenAt: Long?,
) {
    companion object {
        fun of(d: Device) = AdminDevice(d.id, d.person_name, d.device_name, d.status, d.requested_at, d.last_seen_at)
    }
}

@Serializable
data class AdminActivity(val at: Long, val personName: String?, val inventory: String?, val action: String) {
    companion object {
        fun of(a: io.github.jman9700.quartermaster.server.db.RecentActivity) = AdminActivity(a.at, a.person_name, a.inventory_name, a.action)
    }
}
