package de.pascal.pillarone.analysis

import org.junit.Assert.assertEquals
import org.junit.Test

class AccelerationSortTest {
    private val records = listOf(
        AccelerationRecord(1, AccelerationInterval.ZERO_TO_HUNDRED, 1_000, "UTC", null, 10_000, 30_000, null),
        AccelerationRecord(2, AccelerationInterval.ZERO_TO_HUNDRED, 2_000, "UTC", null, 20_000, 15_000, null),
        AccelerationRecord(3, AccelerationInterval.HUNDRED_TO_TWO_HUNDRED, 3_000, "UTC", null, null, 20_000, null),
        AccelerationRecord(4, AccelerationInterval.ZERO_TO_HUNDRED, 4_000, "UTC", null, 15_000, null, null),
    )

    private fun assertOrder(sort: AccelerationSort, vararg ids: Long) {
        assertEquals(ids.toList(), sort.sorted(records).map { it.id })
    }

    @Test fun sortsEachSourceIndependentlyWithMissingTimesLast() {
        assertOrder(AccelerationSort.GPS_FASTEST, 1, 4, 2, 3)
        assertOrder(AccelerationSort.GPS_SLOWEST, 2, 4, 1, 3)
        assertOrder(AccelerationSort.CAR_FASTEST, 2, 3, 1, 4)
        assertOrder(AccelerationSort.CAR_SLOWEST, 1, 3, 2, 4)
    }

    @Test fun sortsByFullTimestampInBothDirections() {
        assertOrder(AccelerationSort.NEWEST, 4, 3, 2, 1)
        assertOrder(AccelerationSort.OLDEST, 1, 2, 3, 4)
    }

    @Test fun equalTimesHaveStableNewestFirstOrdering() {
        val tied = records.map { it.copy(gpsDurationMs = 10_000, startedAt = 0) }
        assertEquals(listOf(4L, 3L, 2L, 1L), AccelerationSort.GPS_FASTEST.sorted(tied).map { it.id })
        assertEquals(emptyList<AccelerationRecord>(), AccelerationSort.NEWEST.sorted(emptyList()))
    }
}
