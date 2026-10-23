package com.projecthub.clearcut.core.scanner

import com.google.common.truth.Truth.assertThat
import com.projecthub.clearcut.core.model.AnalysisMethod
import com.projecthub.clearcut.core.model.AnalysisTrack
import com.projecthub.clearcut.core.model.MediaInfo
import com.projecthub.clearcut.core.model.MediaSource
import com.projecthub.clearcut.core.model.MediaType
import com.projecthub.clearcut.core.model.SamplingIntensity
import com.projecthub.clearcut.core.model.ScanSettingsSnapshot
import kotlinx.coroutines.test.runTest
import org.junit.Test

class MultiMethodScannerTest {

    private val media = MediaInfo(
        uri = "synthetic://clip",
        displayName = "synthetic.mov",
        mimeType = "video/quicktime",
        sizeBytes = 4096,
        durationUs = 10_000_000L,
        mediaType = MediaType.VIDEO,
        hasAudio = true,
        hasVideo = true,
        source = MediaSource.SYNTHETIC,
    )

    private fun settings(
        compareAudio: Boolean = true,
        compareVisual: Boolean = true,
        window: Int = 10,
        sampling: SamplingIntensity = SamplingIntensity.LOW,
    ) = ScanSettingsSnapshot(
        windowSeconds = window,
        sampling = sampling,
        referenceFiles = emptyList(),
        lowResourceMode = false,
        compareAudio = compareAudio,
        compareVisual = compareVisual,
        minConfidence = 0.3f,
    )

    @Test
    fun `scanner finds repeated audio and visual in synthetic media`() = runTest {
        val pcm = SyntheticMedia.audioWithRepeatedBlock()
        val frames = SyntheticMedia.framesWithRepeatedScene()
        val scanner = MultiMethodScanner()
        scanner.audioProvider = { _, _, _ -> MultiMethodScanner.DecodedAudio(pcm, 48_000) }
        scanner.frameProvider = { _, _, _ ->
            frames.mapIndexed { i, (px, w, h) ->
                MultiMethodScanner.DecodedFrame(px, w, h, i * 33_000L, (px.sumOf { it.toInt() and 0xFF } / px.size.toFloat()))
            }
        }
        val report = scanner.scan(media, settings())
        assertThat(report.methodsUsed).contains(AnalysisMethod.AUDIO_FINGERPRINT)
        assertThat(report.methodsUsed).contains(AnalysisMethod.VISUAL_HASH)
        assertThat(report.methodsUsed).contains(AnalysisMethod.STRUCTURAL)

        val audioFindings = report.findings.filter { it.method == AnalysisMethod.AUDIO_FINGERPRINT }
        val visualFindings = report.findings.filter { it.track == AnalysisTrack.VIDEO }
        val structuralFindings = report.findings.filter { it.method == AnalysisMethod.STRUCTURAL }

        assertThat(audioFindings).isNotEmpty()
        assertThat(visualFindings).isNotEmpty()
        // Structural analysis should at least run; scene boundaries may be present.
        assertThat(report.coverage.analyzedSegments).isNotEmpty()
        assertThat(report.findings).isNotEmpty()
    }

    @Test
    fun `scanner reports audio limits when audio decode is unavailable`() = runTest {
        val scanner = MultiMethodScanner()
        scanner.audioProvider = { _, _, _ -> null }
        scanner.frameProvider = { _, _, _ ->
            SyntheticMedia.framesWithoutRepeats().mapIndexed { i, (px, w, h) ->
                MultiMethodScanner.DecodedFrame(px, w, h, i * 50_000L, 0.5f)
            }
        }
        val report = scanner.scan(media, settings())
        assertThat(report.coverage.droppedTracks).contains("audio (decode failed)")
        assertThat(report.limitations.any { it.contains("Audio", ignoreCase = true) }).isTrue()
    }

    @Test
    fun `scanner marks partial decode when video frames missing`() = runTest {
        val scanner = MultiMethodScanner()
        scanner.audioProvider = { _, _, _ -> MultiMethodScanner.DecodedAudio(SyntheticMedia.audioWithoutRepeats(48_000 * 2), 48_000) }
        scanner.frameProvider = { _, _, _ -> null }
        val report = scanner.scan(media, settings())
        assertThat(report.coverage.droppedTracks).contains("video (decode failed)")
        assertThat(report.coverage.partialDecode).isTrue()
    }

    @Test
    fun `scanner with both methods disabled returns minimal report`() = runTest {
        val scanner = MultiMethodScanner()
        val report = scanner.scan(media, settings(compareAudio = false, compareVisual = false))
        assertThat(report.findings).isEmpty()
        assertThat(report.methodsUsed).isNotEmpty()
    }

    @Test
    fun `no false positive confirmed frames on noise`() = runTest {
        val scanner = MultiMethodScanner()
        scanner.audioProvider = { _, _, _ -> MultiMethodScanner.DecodedAudio(SyntheticMedia.audioWithoutRepeats(), 48_000) }
        scanner.frameProvider = { _, _, _ ->
            SyntheticMedia.framesWithoutRepeats().mapIndexed { i, (px, w, h) ->
                MultiMethodScanner.DecodedFrame(px, w, h, i * 50_000L, (px.sumOf { it.toInt() and 0xFF } / px.size.toFloat()))
            }
        }
        val report = scanner.scan(media, settings())
        val visualOrAudio = report.findings.filter {
            it.method == AnalysisMethod.AUDIO_FINGERPRINT || it.method == AnalysisMethod.VISUAL_HASH
        }
        // Statistical randomness could produce an occasional short suspect match;
        // it must never be flagged as CONFIRMED.
        assertThat(visualOrAudio.filter { it.status == com.projecthub.clearcut.core.model.FindingStatus.CONFIRMED }).isEmpty()
    }
}