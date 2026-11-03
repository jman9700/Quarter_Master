package io.github.jman9700.quartermaster.server

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Hashing for everything secret. Nothing secret is ever stored in plain form:
 *  - inventory passwords (chosen by people, so slow PBKDF2 hashes)
 *  - device secrets, owner keys and pass tokens (random 256-bit values, so a plain SHA-256 is enough)
 */
object Security {
    private val random = SecureRandom()
    private const val PBKDF2_ITERATIONS = 210_000

    /** Tests lower this; brute-force resistance doesn't matter there. */
    @Volatile
    var passwordIterations = PBKDF2_ITERATIONS

    fun randomToken(bytes: Int = 32): String {
        val b = ByteArray(bytes).also(random::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    }

    fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    fun matchesSha256(value: String, hash: String): Boolean =
        MessageDigest.isEqual(sha256(value).toByteArray(), hash.toByteArray())

    /** "pbkdf2-sha256$iterations$salt$hash", all base64. */
    fun hashPassword(password: String): String {
        val salt = ByteArray(16).also(random::nextBytes)
        val iterations = passwordIterations
        val hash = pbkdf2(password, salt, iterations)
        val b64 = Base64.getEncoder()
        return "pbkdf2-sha256$$iterations$${b64.encodeToString(salt)}$${b64.encodeToString(hash)}"
    }

    fun verifyPassword(password: String, stored: String): Boolean {
        val parts = stored.split('$')
        if (parts.size != 4 || parts[0] != "pbkdf2-sha256") return false
        val iterations = parts[1].toIntOrNull() ?: return false
        val b64 = Base64.getDecoder()
        val salt = runCatching { b64.decode(parts[2]) }.getOrNull() ?: return false
        val expected = runCatching { b64.decode(parts[3]) }.getOrNull() ?: return false
        return MessageDigest.isEqual(pbkdf2(password, salt, iterations), expected)
    }

    private fun pbkdf2(password: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }
}

/**
 * Slows down password guessing: after 5 wrong passwords for the same inventory from the same
 * device (or address), each further attempt is locked out for longer, up to 15 minutes.
 */
class AttemptLimiter(private val clock: Clock) {
    private data class State(val failures: Int, val lockedUntil: Long)

    private val states = HashMap<String, State>()

    /** Seconds to wait, or null if an attempt is allowed now. */
    @Synchronized
    fun retryAfterSeconds(key: String): Long? {
        val s = states[key] ?: return null
        val wait = s.lockedUntil - clock.now()
        return if (wait > 0) (wait + 999) / 1000 else null
    }

    @Synchronized
    fun recordFailure(key: String) {
        val failures = (states[key]?.failures ?: 0) + 1
        val lockMs = if (failures < FREE_ATTEMPTS) 0L else minOf(MAX_LOCK_MS, BASE_LOCK_MS shl (failures - FREE_ATTEMPTS).coerceAtMost(10))
        states[key] = State(failures, clock.now() + lockMs)
    }

    @Synchronized
    fun recordSuccess(key: String) {
        states.remove(key)
    }

    private companion object {
        const val FREE_ATTEMPTS = 5
        const val BASE_LOCK_MS = 30_000L
        const val MAX_LOCK_MS = 15 * 60_000L
    }
}
