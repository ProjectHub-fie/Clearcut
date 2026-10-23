package com.projecthub.clearcut.feature.editor

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.projecthub.clearcut.core.model.AnalysisTrack
import com.projecthub.clearcut.core.model.EditOperationType
import com.projecthub.clearcut.core.model.MediaInfo
import com.projecthub.clearcut.core.model.MediaSource
import com.projecthub.clearcut.core.model.SyncPolicy
import com.projecthub.clearcut.core.model.TimeRange
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EditorViewModelTest {

    private val media = MediaInfo(
        uri = "content://media/1",
        displayName = "clip.mp4",
        sizeBytes = 1000,
        durationUs = 30_000_000L,
        hasAudio = true,
        hasVideo = true,
        source = MediaSource.LOCAL,
    )

    @Test
    fun `initial state keeps full duration`() {
        val vm = EditorViewModel(ApplicationProvider.getApplicationContext())
        vm.setMedia(media)
        val state = vm.uiState.value
        assertThat(state.edl).isNotNull()
        assertThat(state.outputDurationUs).isEqualTo(30_000_000L)
        assertThat(state.validationWarnings).isEmpty()
    }

    @Test
    fun `cut reduces expected output`() {
        val vm = EditorViewModel(ApplicationProvider.getApplicationContext())
        vm.setMedia(media)
        vm.cutRange(TimeRange(2_000_000, 8_000_000), AnalysisTrack.VIDEO, SyncPolicy.CUT_AUDIO_WITH_VIDEO)
        assertThat(vm.uiState.value.outputDurationUs).isEqualTo(24_000_000L)
        assertThat(vm.uiState.value.edl?.operations).hasSize(1)
    }

    @Test
    fun `duplicate cut is ignored`() {
        val vm = EditorViewModel(ApplicationProvider.getApplicationContext())
        vm.setMedia(media)
        val range = TimeRange(2_000_000, 8_000_000)
        vm.cutRange(range, AnalysisTrack.VIDEO, SyncPolicy.CUT_AUDIO_WITH_VIDEO)
        vm.cutRange(range, AnalysisTrack.VIDEO, SyncPolicy.CUT_AUDIO_WITH_VIDEO)
        assertThat(vm.uiState.value.edl?.operations).hasSize(1)
    }

    @Test
    fun `undo pops the last operation`() {
        val vm = EditorViewModel(ApplicationProvider.getApplicationContext())
        vm.setMedia(media)
        vm.cutRange(TimeRange(1_000_000, 2_000_000), AnalysisTrack.AUDIO, SyncPolicy.KEEP_AUDIO)
        assertThat(vm.uiState.value.edl?.operations).hasSize(1)
        vm.undo()
        assertThat(vm.uiState.value.edl?.operations).isEmpty()
        assertThat(vm.uiState.value.outputDurationUs).isEqualTo(30_000_000L)
    }

    @Test
    fun `overlapping cuts produce warning`() {
        val vm = EditorViewModel(ApplicationProvider.getApplicationContext())
        vm.setMedia(media)
        vm.cutRange(TimeRange(1_000_000, 5_000_000), AnalysisTrack.VIDEO, SyncPolicy.CUT_AUDIO_WITH_VIDEO)
        vm.cutRange(TimeRange(4_000_000, 6_000_000), AnalysisTrack.VIDEO, SyncPolicy.CUT_AUDIO_WITH_VIDEO)
        assertThat(vm.uiState.value.validationWarnings.any { it.contains("Overlapping", ignoreCase = true) }).isTrue()
    }

    @Test
    fun `remove operation restores duration`() {
        val vm = EditorViewModel(ApplicationProvider.getApplicationContext())
        vm.setMedia(media)
        vm.cutRange(TimeRange(5_000_000, 10_000_000), AnalysisTrack.AUDIO, SyncPolicy.KEEP_AUDIO)
        val opId = vm.uiState.value.edl!!.operations.first().id
        vm.removeOperation(opId)
        assertThat(vm.uiState.value.outputDurationUs).isEqualTo(30_000_000L)
    }

    @Test
    fun `sync policy and crossfade are stored on the operation`() {
        val vm = EditorViewModel(ApplicationProvider.getApplicationContext())
        vm.setMedia(media)
        vm.cutRange(TimeRange(2_000_000, 6_000_000), AnalysisTrack.AUDIO, SyncPolicy.KEEP_AUDIO)
        val op = vm.uiState.value.edl!!.operations.first()
        assertThat(op.syncPolicy).isEqualTo(SyncPolicy.KEEP_AUDIO)
        assertThat(op.operationType).isEqualTo(EditOperationType.CUT)
        vm.setSyncPolicy(op.id, SyncPolicy.CUT_AUDIO_WITH_VIDEO)
        vm.setCrossfade(op.id, 250)
        val updated = vm.uiState.value.edl!!.operations.first()
        assertThat(updated.syncPolicy).isEqualTo(SyncPolicy.CUT_AUDIO_WITH_VIDEO)
        assertThat(updated.crossfadeMs).isEqualTo(250)
    }
}