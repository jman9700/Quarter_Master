package io.github.jman9700.quartermaster.domain

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FreshnessTest {
    private val oct1 = LocalDate(2026, 10, 1)

    private val bananas = ProductType("t1", null, "Bananas", 7, 6.0, "Count", null, 0, 0)

    private fun item(added: LocalDate, bestBy: LocalDate? = null, removed: LocalDate? = null, reason: RemovalReason? = null) =
        InventoryItem("i-$added-$bestBy-$removed", bananas, added, bestBy, removed, reason, 0)

    @Test
    fun lifespanIsUsedWithoutBestBy() {
        assertEquals(LocalDate(2026, 10, 8), Freshness.expiresOn(oct1, null, 7))
    }

    @Test
    fun bestByOverridesLifespan() {
        assertEquals(LocalDate(2026, 10, 20), Freshness.expiresOn(oct1, LocalDate(2026, 10, 20), 7))
    }

    @Test
    fun fractionMatchesTheAlphasHealthBar() {
        val expires = Freshness.expiresOn(oct1, null, 10)
        assertEquals(1f, Freshness.fraction(oct1, expires, oct1))
        assertEquals(0.5f, Freshness.fraction(oct1, expires, LocalDate(2026, 10, 6)))
        assertEquals(0f, Freshness.fraction(oct1, expires, LocalDate(2026, 10, 30)))
    }

    @Test
    fun zeroLifespanIsEmptyNotACrash() {
        assertEquals(0f, Freshness.fraction(oct1, oct1, oct1))
    }

    @Test
    fun levelsUseTheAlphaThresholds() {
        assertEquals(FreshnessLevel.GOOD, Freshness.level(0.51f))
        assertEquals(FreshnessLevel.WARNING, Freshness.level(0.5f))
        assertEquals(FreshnessLevel.WARNING, Freshness.level(0.26f))
        assertEquals(FreshnessLevel.CRITICAL, Freshness.level(0.25f))
    }

    @Test
    fun describe() {
        val exp = LocalDate(2026, 10, 10)
        assertEquals("9 days left", Freshness.describe(exp, oct1))
        assertEquals("1 day left", Freshness.describe(exp, LocalDate(2026, 10, 9)))
        assertEquals("Expires today", Freshness.describe(exp, exp))
        assertEquals("Expired yesterday", Freshness.describe(exp, LocalDate(2026, 10, 11)))
        assertEquals("Expired 3 days ago", Freshness.describe(exp, LocalDate(2026, 10, 13)))
    }

    @Test
    fun spoiledEarlyIsMeasuredAgainstTheExpectedDate() {
        val early = item(oct1, removed = LocalDate(2026, 10, 5), reason = RemovalReason.SPOILED)
        assertEquals(3, early.spoiledDaysEarly)
        val late = item(oct1, removed = LocalDate(2026, 10, 9), reason = RemovalReason.SPOILED)
        assertNull(late.spoiledDaysEarly)
        val used = item(oct1, removed = LocalDate(2026, 10, 2), reason = RemovalReason.USED)
        assertNull(used.spoiledDaysEarly)
    }

    @Test
    fun lifespanStatsLearnFromBestByDatesAndSpoilage() {
        val stats = LifespanStats.from(
            listOf(
                item(oct1, bestBy = LocalDate(2026, 10, 6)), // 5 days by label
                item(oct1, bestBy = LocalDate(2026, 10, 20), removed = LocalDate(2026, 10, 4), reason = RemovalReason.SPOILED), // actually 3
                item(oct1, removed = LocalDate(2026, 10, 3), reason = RemovalReason.USED), // no information
                item(oct1), // still in the pantry, no best-by
            ),
        )
        assertEquals(2, stats.sampleCount)
        assertEquals(4.0, stats.averageDays)
        assertEquals(4, stats.suggestedLifespanDays)
        assertEquals(1, stats.spoiledCount)
        assertEquals(1, stats.spoiledEarlyCount)
    }

    @Test
    fun lifespanStatsWithNoSamples() {
        val stats = LifespanStats.from(listOf(item(oct1)))
        assertEquals(0, stats.sampleCount)
        assertNull(stats.averageDays)
        assertNull(stats.suggestedLifespanDays)
    }
}
