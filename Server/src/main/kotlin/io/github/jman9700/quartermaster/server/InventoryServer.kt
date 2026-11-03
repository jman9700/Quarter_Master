package io.github.jman9700.quartermaster.server

import app.cash.sqldelight.db.SqlDriver
import io.github.jman9700.quartermaster.protocol.AccessRequest
import io.github.jman9700.quartermaster.protocol.Api
import io.github.jman9700.quartermaster.protocol.CreateInventoryRequest
import io.github.jman9700.quartermaster.protocol.CreateInventoryResponse
import io.github.jman9700.quartermaster.protocol.DeviceStatus
import io.github.jman9700.quartermaster.protocol.DeviceStatusResponse
import io.github.jman9700.quartermaster.protocol.ErrorCodes
import io.github.jman9700.quartermaster.protocol.InventorySummary
import io.github.jman9700.quartermaster.protocol.ItemRow
import io.github.jman9700.quartermaster.protocol.Merge
import io.github.jman9700.quartermaster.protocol.PassResponse
import io.github.jman9700.quartermaster.protocol.ProductRow
import io.github.jman9700.quartermaster.protocol.ServerInfo
import io.github.jman9700.quartermaster.protocol.SyncRequest
import io.github.jman9700.quartermaster.protocol.SyncResponse
import io.github.jman9700.quartermaster.server.db.Device
import io.github.jman9700.quartermaster.server.db.ServerDatabase
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import java.util.UUID

/** A request the server refuses; the routes turn it into an HTTP status plus an [io.github.jman9700.quartermaster.protocol.ApiError]. */
class ApiException(
    val status: HttpStatusCode,
    val code: String,
    override val message: String,
    val retryAfterSeconds: Long? = null,
) : RuntimeException(message)

/**
 * The server's behavior, independent of HTTP: whitelist, inventories, 24-hour passes, sync,
 * photos and the activity log.
 *
 * SQLite allows one writer at a time and a household server is never busy, so every operation
 * simply runs one at a time under [lock].
 */
class InventoryServer(
    driver: SqlDriver,
    val config: ServerConfig,
    private val clock: Clock = Clock.System,
) {
    private val db = ServerDatabase(driver)
    private val q = db.serverQueries
    private val lock = Any()
    private val limiter = AttemptLimiter(clock)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val imagesRoot = File(config.dataDir, "images")
    private val log = LoggerFactory.getLogger(InventoryServer::class.java)

    fun info() = ServerInfo(config.serverName, config.version, config.commit, port = config.port)

    // ---- Devices ----------------------------------------------------------------------------

    fun requestAccess(req: AccessRequest): DeviceStatusResponse = synchronized(lock) {
        val id = req.deviceId.trim()
        val person = req.personName.trim()
        val deviceName = req.deviceName.trim().ifEmpty { "Unnamed device" }
        if (!ID.matches(id)) bad("Device id is malformed")
        if (req.deviceSecret.length < 32) bad("Device secret is too short")
        if (person.isEmpty() || person.length > 60) bad("Enter your name (up to 60 characters)")
        if (deviceName.length > 80) bad("Device name is too long")
        val now = clock.now()
        val existing = q.deviceById(id).executeAsOneOrNull()
        when {
            existing == null -> q.insertDevice(id, Security.sha256(req.deviceSecret), person, deviceName, now)
            // Only the device holding the secret may change its own request.
            !Security.matchesSha256(req.deviceSecret, existing.secret_hash) ->
                throw ApiException(HttpStatusCode.Forbidden, ErrorCodes.UNKNOWN_DEVICE, "This device id belongs to another device")
            existing.status == DeviceStatus.APPROVED.name -> return statusOf(existing)
            else -> q.renewRequest(Security.sha256(req.deviceSecret), person, deviceName, now, id)
        }
        log(id, person, null, "requested access from \"$deviceName\"")
        DeviceStatusResponse(DeviceStatus.PENDING, person)
    }

    /** Who's calling. Every API call except /info and the access request carries these. */
    fun authenticate(deviceId: String?, deviceSecret: String?): Device = synchronized(lock) {
        val device = deviceId?.let { q.deviceById(it).executeAsOneOrNull() }
        if (device == null || deviceSecret == null || !Security.matchesSha256(deviceSecret, device.secret_hash)) {
            throw ApiException(HttpStatusCode.Unauthorized, ErrorCodes.UNKNOWN_DEVICE, "This device hasn't requested access to this server")
        }
        q.touchDevice(clock.now(), device.id)
        device
    }

    fun statusOf(device: Device) = DeviceStatusResponse(DeviceStatus.valueOf(device.status), device.person_name)

    /** Everything beyond asking for access needs the host's approval. */
    private fun requireApproved(device: Device) {
        val (code, message) = when (DeviceStatus.valueOf(device.status)) {
            DeviceStatus.APPROVED -> return
            DeviceStatus.PENDING -> ErrorCodes.DEVICE_PENDING to "Waiting for the host to approve this device"
            DeviceStatus.DENIED -> ErrorCodes.DEVICE_DENIED to "The host declined this device's request"
            DeviceStatus.REVOKED -> ErrorCodes.DEVICE_REVOKED to "The host removed this device's access"
        }
        throw ApiException(HttpStatusCode.Forbidden, code, message)
    }

    // ---- Inventories ------------------------------------------------------------------------

    fun listInventories(device: Device): List<InventorySummary> = synchronized(lock) {
        requireApproved(device)
        q.activeInventories().executeAsList().map { InventorySummary(it.id, it.name, it.created_by_name) }
    }

    fun createInventory(device: Device, req: CreateInventoryRequest): CreateInventoryResponse = synchronized(lock) {
        requireApproved(device)
        val name = req.name.trim()
        if (name.isEmpty() || name.length > 60) bad("Give the inventory a name (up to 60 characters)")
        checkPassword(req.password)
        val id = UUID.randomUUID().toString()
        val ownerKey = Security.randomToken()
        q.insertInventory(id, name, Security.hashPassword(req.password), Security.sha256(ownerKey), device.id, clock.now())
        log(device, id, "created the inventory \"$name\"")
        CreateInventoryResponse(InventorySummary(id, name, device.person_name), ownerKey)
    }

    /** The daily sign-in: the inventory's password buys this device a 24-hour pass. */
    fun issuePass(device: Device, inventoryId: String, password: String): PassResponse = synchronized(lock) {
        requireApproved(device)
        val inventory = inventory(inventoryId)
        // Per device: only whitelisted devices get this far, and behind Docker every device shares one address.
        val key = "${device.id}:$inventoryId"
        limiter.retryAfterSeconds(key)?.let { wait ->
            throw ApiException(HttpStatusCode.TooManyRequests, ErrorCodes.TOO_MANY_ATTEMPTS, "Too many wrong passwords. Try again in $wait seconds.", wait)
        }
        if (!Security.verifyPassword(password, inventory.password_hash)) {
            limiter.recordFailure(key)
            throw ApiException(HttpStatusCode.Unauthorized, ErrorCodes.WRONG_PASSWORD, "Wrong password for \"${inventory.name}\"")
        }
        limiter.recordSuccess(key)
        val now = clock.now()
        q.deleteExpiredPasses(now)
        val token = Security.randomToken()
        val expiresAt = now + Api.PASS_HOURS * 3_600_000L
        q.insertPass(Security.sha256(token), inventoryId, device.id, now, expiresAt)
        log(device, inventoryId, "signed in for 24 hours")
        PassResponse(token, expiresAt)
    }

    /** A request that touches an inventory's contents needs a live pass for that inventory, from the device it was issued to. */
    fun requirePass(device: Device, inventoryId: String, token: String?) = synchronized(lock) {
        requireApproved(device)
        inventory(inventoryId)
        val pass = token?.let { q.passByToken(Security.sha256(it)).executeAsOneOrNull() }
        if (pass == null || pass.inventory_id != inventoryId || pass.device_id != device.id) {
            throw ApiException(HttpStatusCode.Unauthorized, ErrorCodes.PASS_REQUIRED, "Enter the inventory's password")
        }
        if (pass.expires_at <= clock.now()) {
            throw ApiException(HttpStatusCode.Unauthorized, ErrorCodes.PASS_EXPIRED, "Your 24-hour pass ran out. Enter the password again")
        }
    }

    fun changePassword(device: Device, inventoryId: String, ownerKey: String?, newPassword: String) = synchronized(lock) {
        requireApproved(device)
        val inventory = inventory(inventoryId)
        requireOwner(inventory.owner_key_hash, ownerKey)
        checkPassword(newPassword)
        q.setPassword(Security.hashPassword(newPassword), inventoryId)
        // Everyone signs in again with the new password.
        q.revokePassesForInventory(inventoryId)
        log(device, inventoryId, "changed the password (everyone's passes were ended)")
    }

    fun deleteInventory(device: Device, inventoryId: String, ownerKey: String?) = synchronized(lock) {
        requireApproved(device)
        val inventory = inventory(inventoryId)
        requireOwner(inventory.owner_key_hash, ownerKey)
        q.deleteInventory(clock.now(), inventoryId)
        q.revokePassesForInventory(inventoryId)
        log(device, inventoryId, "deleted the inventory \"${inventory.name}\"")
    }

    // ---- Sync -------------------------------------------------------------------------------

    fun sync(device: Device, inventoryId: String, req: SyncRequest): SyncResponse = synchronized(lock) {
        val actions = mutableListOf<String>()
        db.transaction {
            for (incoming in req.products) {
                validateId(incoming.id)
                incoming.imageFile?.let(::validateImageName)
                val stored = q.productById(inventoryId, incoming.id).executeAsOneOrNull()
                if (stored == null || Merge.incomingWins(incoming.updatedAt, device.id, stored.updated_at, stored.changed_by)) {
                    val row = incoming.copy(changedBy = device.id)
                    q.upsertProduct(inventoryId, row.id, json.encodeToString(ProductRow.serializer(), row), row.updatedAt, device.id, nextSeq())
                    describeProduct(stored?.let { json.decodeFromString(ProductRow.serializer(), it.body) }, row)?.let(actions::add)
                }
            }
            for (incoming in req.items) {
                validateId(incoming.id)
                val stored = q.itemById(inventoryId, incoming.id).executeAsOneOrNull()
                if (stored == null || Merge.incomingWins(incoming.updatedAt, device.id, stored.updated_at, stored.changed_by)) {
                    val row = incoming.copy(changedBy = device.id)
                    q.upsertItem(inventoryId, row.id, json.encodeToString(ItemRow.serializer(), row), row.updatedAt, device.id, nextSeq())
                    describeItem(inventoryId, stored?.let { json.decodeFromString(ItemRow.serializer(), it.body) }, row)?.let(actions::add)
                }
            }
        }
        summarize(actions).forEach { log(device, inventoryId, it) }

        val products = q.productsSince(inventoryId, req.cursor).executeAsList().map { json.decodeFromString(ProductRow.serializer(), it) }
        val items = q.itemsSince(inventoryId, req.cursor).executeAsList().map { json.decodeFromString(ItemRow.serializer(), it) }
        SyncResponse(
            cursor = currentSeq(),
            products = products,
            items = items,
            missingImages = missingImages(inventoryId),
        )
    }

    // ---- Photos -----------------------------------------------------------------------------

    fun putImage(inventoryId: String, name: String, bytes: ByteArray) = synchronized(lock) {
        validateImageName(name)
        if (bytes.isEmpty() || bytes.size > MAX_IMAGE_BYTES) bad("Photos must be between 1 byte and 5 MB")
        File(imagesRoot, inventoryId).apply { mkdirs() }.resolve(name).writeBytes(bytes)
    }

    fun getImage(inventoryId: String, name: String): ByteArray = synchronized(lock) {
        validateImageName(name)
        File(File(imagesRoot, inventoryId), name).takeIf { it.isFile }?.readBytes()
            ?: throw ApiException(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND, "No such photo")
    }

    private fun missingImages(inventoryId: String): List<String> {
        val dir = File(imagesRoot, inventoryId)
        return q.productsSince(inventoryId, 0).executeAsList()
            .map { json.decodeFromString(ProductRow.serializer(), it) }
            .filter { it.deletedAt == null }
            .mapNotNull { it.imageFile }
            .filter { Api.IMAGE_NAME.matches(it) && !File(dir, it).isFile }
            .distinct()
    }

    // ---- Host administration ----------------------------------------------------------------

    fun devices(): List<Device> = synchronized(lock) { q.allDevices().executeAsList() }

    fun approve(deviceId: String, personName: String? = null) = decide(deviceId, DeviceStatus.APPROVED, personName)

    fun deny(deviceId: String) = decide(deviceId, DeviceStatus.DENIED, null)

    /** Takes away an approved device's access and ends its passes. */
    fun revoke(deviceId: String) = decide(deviceId, DeviceStatus.REVOKED, null)

    fun rename(deviceId: String, personName: String) = synchronized(lock) {
        val device = deviceRow(deviceId)
        val name = personName.trim()
        if (name.isEmpty() || name.length > 60) bad("Names are 1 to 60 characters")
        q.renameDevice(name, deviceId)
        log(null, "Host", null, "renamed \"${device.person_name}\" to \"$name\"")
    }

    private fun decide(deviceId: String, status: DeviceStatus, personName: String?) = synchronized(lock) {
        val device = deviceRow(deviceId)
        personName?.trim()?.takeIf { it.isNotEmpty() && it.length <= 60 }?.let { q.renameDevice(it, deviceId) }
        q.decideDevice(status.name, clock.now(), deviceId)
        if (status != DeviceStatus.APPROVED) q.revokePassesForDevice(deviceId)
        val who = personName?.takeIf { it.isNotBlank() } ?: device.person_name
        val verb = when (status) {
            DeviceStatus.APPROVED -> "approved"
            DeviceStatus.DENIED -> "denied"
            DeviceStatus.REVOKED -> "revoked"
            DeviceStatus.PENDING -> "reset"
        }
        log(null, "Host", null, "$verb \"$who\" on \"${device.device_name}\"")
    }

    fun inventoriesForAdmin(): List<AdminInventory> = synchronized(lock) {
        q.activeInventories().executeAsList().map {
            AdminInventory(it.id, it.name, it.created_by_name, it.created_at, q.productCount(it.id).executeAsOne(), q.itemCount(it.id).executeAsOne())
        }
    }

    /** For an owner who lost their owner key. Returns the new key, shown once. */
    fun resetOwnerKey(inventoryId: String): String = synchronized(lock) {
        val inventory = inventory(inventoryId)
        val key = Security.randomToken()
        q.setOwnerKey(Security.sha256(key), inventoryId)
        log(null, "Host", inventoryId, "reset the owner key of \"${inventory.name}\"")
        key
    }

    fun activePasses() = synchronized(lock) { q.activePasses(clock.now()).executeAsList() }

    fun recentActivity(limit: Long = 200) = synchronized(lock) { q.recentActivity(limit).executeAsList() }

    // ---- Helpers ----------------------------------------------------------------------------

    private fun inventory(id: String) = q.inventoryById(id).executeAsOneOrNull()
        ?: throw ApiException(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND, "No such inventory on this server")

    private fun deviceRow(id: String) = q.deviceById(id).executeAsOneOrNull()
        ?: throw ApiException(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND, "No such device")

    private fun requireOwner(ownerKeyHash: String, ownerKey: String?) {
        if (ownerKey == null || !Security.matchesSha256(ownerKey, ownerKeyHash)) {
            throw ApiException(HttpStatusCode.Forbidden, ErrorCodes.WRONG_OWNER_KEY, "Only the inventory's owner can do that")
        }
    }

    private fun checkPassword(password: String) {
        if (password.length < 6) bad("Passwords need at least 6 characters")
        if (password.length > 200) bad("That password is too long")
    }

    private fun validateId(id: String) {
        if (!ID.matches(id)) bad("Malformed row id")
    }

    private fun validateImageName(name: String) {
        if (!Api.IMAGE_NAME.matches(name)) bad("Malformed photo name")
    }

    private fun nextSeq(): Long {
        val next = currentSeq() + 1
        q.setCounter(SEQ, next)
        return next
    }

    private fun currentSeq(): Long = q.counterValue(SEQ).executeAsOneOrNull() ?: 0L

    private fun log(device: Device, inventoryId: String?, action: String) = log(device.id, device.person_name, inventoryId, action)

    private fun log(deviceId: String?, person: String?, inventoryId: String?, action: String) {
        q.logActivity(clock.now(), deviceId, person, inventoryId, action)
        log.info("{}: {}", person ?: "?", action)
    }

    private fun productName(p: ProductRow) = p.name.ifBlank { p.gtin?.let { "product $it" } ?: "unnamed product" }

    private fun describeProduct(old: ProductRow?, new: ProductRow): String? = when {
        old == null && new.deletedAt == null -> "added the product \"${productName(new)}\""
        old == null -> null
        old.deletedAt == null && new.deletedAt != null -> "deleted the product \"${productName(new)}\""
        else -> "edited the product \"${productName(new)}\""
    }

    private fun describeItem(inventoryId: String, old: ItemRow?, new: ItemRow): String? {
        val name = q.productById(inventoryId, new.typeId).executeAsOneOrNull()
            ?.let { productName(json.decodeFromString(ProductRow.serializer(), it.body)) } ?: "an unknown product"
        val reason = when (new.removalReason) {
            "USED" -> "used"
            "SPOILED" -> "went bad"
            "DISCARDED" -> "discarded"
            else -> "removed"
        }
        return when {
            old == null && new.deletedAt == null && new.removedOn == null -> "added $name"
            old == null && new.deletedAt == null -> "added $name (already $reason)"
            old == null -> null
            old.deletedAt == null && new.deletedAt != null -> "deleted $name (logged by mistake)"
            old.removedOn == null && new.removedOn != null -> "marked $name as $reason"
            old.removedOn != null && new.removedOn == null -> "put $name back in the inventory"
            else -> "changed the dates on $name"
        }
    }

    /** "added Milk" ×3 becomes "added 3 × Milk". */
    private fun summarize(actions: List<String>): List<String> =
        actions.groupingBy { it }.eachCount().map { (action, count) ->
            if (count == 1) action else {
                val verb = action.substringBefore(' ')
                "$verb $count × ${action.substringAfter(' ')}"
            }
        }

    companion object {
        private const val SEQ = "seq"
        private const val MAX_IMAGE_BYTES = 5 * 1024 * 1024
        private val ID = Regex("""[A-Za-z0-9-]{1,64}""")

        private fun bad(message: String): Nothing = throw ApiException(HttpStatusCode.BadRequest, ErrorCodes.BAD_REQUEST, message)
    }
}

data class AdminInventory(
    val id: String,
    val name: String,
    val createdBy: String,
    val createdAt: Long,
    val products: Long,
    val items: Long,
)
