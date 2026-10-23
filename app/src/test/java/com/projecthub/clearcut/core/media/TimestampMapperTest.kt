package com.projecthub.clearcut.core.media

import com.google.common.truth.Truth.assertThat
import com.projecthub.clearcut.core.model.TimeRange
import org.junit.Test

class TimestampMapperTest {

    @Test
    fun `normalize rebases nonzero start to zero`() {
        val raw = listOf(5_000_000L, 5_033_000L, 5_066_000L, 5_100_000L)
        val normalized = TimestampMapper.normalizeTimestamps(raw)
        assertThat(normalized.first()).isEqualTo(0L)
        assertThat(normalized.last()).isEqualTo(100_000L)
    }

    @Test
    fun `normalize handles out of order input`() {
        val raw = listOf(33_000L, 0L, 66_000L, 33_000L)
        val normalized = TimestampMapper.normalizeTimestamps(raw)
        assertThat(normalized).containsExactly(0L, 33_000L, 66_000L)
    }

    @Test
    fun `mergeIntervals joins adjacent windows within tolerance`() {
        val merged = TimestampMapper.mergeIntervals(
            listOf(TimeRange(0, 1_000_000), TimeRange(2_500_000, 4_000_000), TimeRange(10_000_000, 20_000_000)),
            gapToleranceUs = 2_000_000L,
        )
        assertThat(merged).containsExactly(TimeRange(0, 4_000_000), TimeRange(10_000_000, 20_000_000))
    }

    @Test
    fun `mergeIntervals does not merge unrelated distant events`() {
        val merged = TimestampMapper.mergeIntervals(
            listOf(TimeRange(0, 1_000_000), TimeRange(30_000_000, 40_000_000)),
            gapToleranceUs = 2_000_000L,
        )
        assertThat(merged.size).isEqualTo(2)
    }

    @Test
    fun `computeCoverage marks gaps unanalyzed`() {
        val (analyzed, unanalyzed) = TimestampMapper.computeCoverage(
            durationUs = 30_000_000L,
            analyzedWindowsUs = listOf(TimeRange(0, 10_000_000), TimeRange(20_000_000, 30_000_000)),
            partialDecode = false,
        )
        assertThat(analyzed).hasSize(2)
        assertThat(unanalyzed).containsExactly(TimeRange(10_000_000, 20_000_000))
    }

    @Test
    fun `computeCoverage with no windows fully unanalyzed`() {
        val (_, unanalyzed) = TimestampMapper.computeCoverage(10_000_000L, emptyList(), false)
        assertThat(unanalyzed).containsExactly(TimeRange(0, 10_000_000))
    }

    @Test
    fun `mapSourceToOutput shifts by cut duration`() {
        val cuts = listOf(TimeRange(1_000_000, 3_000_000))
        val out = TimestampMapper.mapSourceToOutput(5_000_000L, 10_000_000L, cuts)
        // 5s in source minus 2s cut = 3s output.
        assertThat(out).isEqualTo(3_000_000L)
    }

    @Test
    fun `mapSourceToOutput returns minus one inside a cut`() {
        val cuts = listOf(TimeRange(1_000_000, 3_000_000))
        assertThat(TimestampMapper.mapSourceToOutput(2_000_000L, 10_000_000L, cuts)).isEqualTo(-1L)
    }

    @Test
    fun `mapSourceToOutput accumulates multiple cuts`() {
        val cuts = listOf(TimeRange(0, 1_000_000), TimeRange(2_000_000, 4_000_000))
        // 5s source minus 1s minus 2s = 2s output.
        assertThat(TimestampMapper.mapSourceToOutput(5_000_000L, 10_000_000L, cuts)).isEqualTo(2_000_000L)
    }
}