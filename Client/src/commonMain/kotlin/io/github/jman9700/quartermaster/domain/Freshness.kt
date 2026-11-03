package io.github.jman9700.quartermaster.domain

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import kotlinx.datetime.plus
import kotlin.math.roundToInt

enum class FreshnessLevel { GOOD, WARNING, CRITICAL }

object Freshness {

    /** A known best-by date wins; otherwise the product's default lifespan counts from the day it was added. */
    fun expiresOn(addedOn: LocalDate, bestBy: LocalDate?, lifespanDays: Int): LocalDate =
        bestBy ?: addedOn.plus(DatePeriod(days = lifespanDays))

    fun daysBetween(from: LocalDate, to: LocalDate): Int = from.daysUntil(to)

    /** Remaining life as a fraction 0..1 (the alpha's health bar percentage / 100). */
    fun fraction(addedOn: LocalDate, expiresOn: LocalDate, today: LocalDate): Float {
        val total = daysBetween(addedOn, expiresOn)
        val left = daysBetween(today, expiresOn)
        if (total <= 0) return if (left > 0) 1f else 0f
        return (left.toFloat() / total).coerceIn(0f, 1f)
    }

    /** Same thresholds as the alpha: green above 50%, yellow above 25%, red otherwise. */
    fun level(fraction: Float): FreshnessLevel = when {
        fraction > 0.5f -> FreshnessLevel.GOOD
        fraction > 0.25f -> FreshnessLevel.WARNING
        else -> FreshnessLevel.CRITICAL
    }

    fun describe(expiresOn: LocalDate, today: LocalDate): String {
        val left = daysBetween(today, expiresOn)
        return when {
            left > 1 -> "$left days left"
            left == 1 -> "1 day left"
            left == 0 -> "Expires today"
            left == -1 -> "Expired yesterday"
            else -> "Expired ${-left} days ago"
        }
    }
}

/**
 * What the app has learned about how long a product really lasts.
 *
 * Each item contributes at most one sample:
 *  - it went bad: the days it actually lasted (what really happened beats the label)
 *  - otherwise, a known best-by date: the days from being added to its best-by date
 */
data class LifespanStats(
    val sampleCount: Int,
    val averageDays: Double?,
    val spoiledCount: Int,
    val spoiledEarlyCount: Int,
) {
    val suggestedLifespanDays: Int? get() = averageDays?.roundToInt()

    companion object {
        fun from(items: List<InventoryItem>): LifespanStats {
            val samples = items.mapNotNull { item ->
                val actualEnd = item.removedOn.takeIf { item.removalReason == RemovalReason.SPOILED }
                val end = actualEnd ?: item.bestBy ?: return@mapNotNull null
                Freshness.daysBetween(item.addedOn, end).takeIf { it >= 0 }
            }
            val spoiled = items.filter { it.removalReason == RemovalReason.SPOILED }
            return LifespanStats(
                sampleCount = samples.size,
                averageDays = samples.takeIf { it.isNotEmpty() }?.average(),
                spoiledCount = spoiled.size,
                spoiledEarlyCount = spoiled.count { it.spoiledDaysEarly != null },
            )
        }
    }
}
