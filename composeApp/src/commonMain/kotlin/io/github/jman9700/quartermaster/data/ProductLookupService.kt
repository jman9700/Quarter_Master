package io.github.jman9700.quartermaster.data

import io.github.jman9700.quartermaster.domain.ProductField
import io.github.jman9700.quartermaster.domain.ProductType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex

/** What one pass over the lookup queue did. */
data class QueueRunResult(val filledIn: Int, val notFound: Int, val stillPending: Int)

/**
 * Looks products up in OpenFoodFacts, and keeps a queue of lookups that couldn't run because
 * there was no usable connection.
 *
 * The queue is low priority by design: there's no polling. It runs when the app is opened (and
 * when a lookup is asked for explicitly), and stops at the first sign the connection is still bad.
 */
class ProductLookupService(
    private val repository: InventoryRepository,
    private val client: OpenFoodFactsClient,
    private val images: ImageStore,
    private val isOnline: suspend () -> Boolean,
) {
    private val running = Mutex()

    private val _lastRun = MutableStateFlow<QueueRunResult?>(null)

    /** The latest queue run that changed something, for the UI to announce once and then [consumeLastRun]. */
    val lastRun: StateFlow<QueueRunResult?> = _lastRun.asStateFlow()

    fun consumeLastRun() {
        _lastRun.value = null
    }

    /** A single interactive lookup (the product form). Never queues anything itself. */
    suspend fun lookup(gtin14: String): LookupResult =
        if (!isOnline()) LookupResult.Unavailable else client.lookup(gtin14)

    /** Downloads and stores a product photo; returns its file name, or null if it couldn't be fetched. */
    suspend fun downloadImage(url: String): String? = (fetchPhoto(url) as? PhotoResult.Saved)?.file

    private sealed interface PhotoResult {
        class Saved(val file: String) : PhotoResult
        data object NotWanted : PhotoResult
        data object Unavailable : PhotoResult
    }

    private suspend fun fetchPhoto(url: String): PhotoResult = when (val d = client.downloadImage(url)) {
        is ImageDownload.Ok -> runCatching { images.save(d.bytes) }.fold({ PhotoResult.Saved(it) }, { PhotoResult.NotWanted })
        ImageDownload.Missing -> PhotoResult.NotWanted
        ImageDownload.Unavailable -> PhotoResult.Unavailable
    }

    /** Runs the queue. Safe to call often: it does nothing when offline, empty or already running. */
    suspend fun processQueue(): QueueRunResult? {
        if (!repository.onlineLookupEnabled.first()) return null
        if (!running.tryLock()) return null
        try {
            repository.queueNeverLookedUp()
            val pending = repository.pendingLookups()
            if (pending.isEmpty() || !isOnline()) return null

            var filledIn = 0
            var notFound = 0
            var index = 0
            while (index < pending.size) {
                val product = pending[index]
                val gtin = product.gtin
                if (gtin != null) {
                    when (val result = client.lookup(gtin)) {
                        is LookupResult.Found -> {
                            val photo = photoFor(product, result.suggestion.imageUrl)
                            repository.applyLookup(product.id, result.suggestion, (photo as? PhotoResult.Saved)?.file)
                            filledIn++
                            if (photo == PhotoResult.Unavailable) {
                                // The details made it but the photo didn't: a shaky connection. Keep what
                                // arrived, leave the product queued for its photo, and stop for now.
                                repository.requestLookup(product.id)
                                break
                            }
                        }
                        LookupResult.NotFound -> {
                            repository.markLookupNotFound(product.id)
                            notFound++
                        }
                        // Still no real connection: leave the rest queued for next time.
                        LookupResult.Unavailable -> break
                    }
                }
                index++
            }
            return QueueRunResult(filledIn, notFound, stillPending = pending.size - index).also {
                if (it.filledIn + it.notFound > 0) _lastRun.value = it
            }
        } finally {
            running.unlock()
        }
    }

    /** Only fetch a photo when the user hasn't chosen one and there isn't one yet. */
    private suspend fun photoFor(product: ProductType, url: String?): PhotoResult {
        if (url == null || ProductField.IMAGE in product.userFields || product.imageFile != null) return PhotoResult.NotWanted
        return fetchPhoto(url)
    }
}
