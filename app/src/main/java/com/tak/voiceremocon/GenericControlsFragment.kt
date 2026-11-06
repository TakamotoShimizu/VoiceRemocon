package com.tak.voiceremocon

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import com.tak.voiceremocon.ui.theme.VoiceRemoconTheme

private const val TAG_GEN = "GenericControlsFragment"

class GenericControlsFragment : Fragment() {

  private lateinit var playButton: Button
  private lateinit var pauseButton: Button
  private lateinit var stopButton: Button
  private lateinit var recordButton: Button
  private lateinit var deleteButton: Button
  private lateinit var startButton: Button
  private lateinit var audioLevelProgressBar: ProgressBar
  private lateinit var amplitudeTextView: TextView

  private var voiceRemoconActivity: VoiceRemocon? = null
  private val composeAmplitudeState = GenericControlsAmplitudeState()
  private var isComposeHost = false

  override fun onCreateView(
    inflater: LayoutInflater,
    container: ViewGroup?,
    savedInstanceState: Bundle?
  ): View {
    isComposeHost = GenericControlsComposeFlag.isEnabled()
    if (!isComposeHost) {
      return inflater.inflate(R.layout.fragment_generic_controls, container, false)
    }

    composeAmplitudeState.stop()
    return ComposeView(requireContext()).apply {
      setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
      setContent {
        val amplitude by composeAmplitudeState.amplitude
        VoiceRemoconTheme {
          GenericControlsScreen(
            state = GenericControlsUiState(amplitude = amplitude),
            onRecord = ::handleRecordClick,
            onStop = ::handleStopClick,
            onPlay = ::handlePlayClick,
            onPause = ::handlePauseClick,
            onStart = ::handleStartClick,
            onDelete = ::handleDeleteClick
          )
        }
      }
    }
  }

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)
    voiceRemoconActivity = activity as? VoiceRemocon

    if (voiceRemoconActivity == null) {
      Log.e(TAG_GEN, "Activity is not VoiceRemocon or null")
      Toast.makeText(context, "Error: Could not link to main activity", Toast.LENGTH_LONG).show()
      return
    }

    if (!isComposeHost) {
      playButton = view.findViewById(R.id.play_button)
      pauseButton = view.findViewById(R.id.pause_button)
      stopButton = view.findViewById(R.id.stop_button)
      recordButton = view.findViewById(R.id.record_button)
      deleteButton = view.findViewById(R.id.delete_button)
      startButton = view.findViewById(R.id.start_button)
      audioLevelProgressBar = view.findViewById(R.id.audio_level_progressbar)
      amplitudeTextView = view.findViewById(R.id.amplitude_textview)
      setupClickListeners()
    }
  }

  private fun setupClickListeners() {
    recordButton.setOnClickListener { handleRecordClick() }
    stopButton.setOnClickListener { handleStopClick() }
    playButton.setOnClickListener { handlePlayClick() }
    pauseButton.setOnClickListener { handlePauseClick() }
    deleteButton.setOnClickListener { handleDeleteClick() }
    startButton.setOnClickListener { handleStartClick() }
  }

  private fun handleRecordClick() {
    voiceRemoconActivity?.let { activity ->
      if (!activity.isRecording) {
        if (isComposeHost) {
          activity.onRecord(true, null, null)
          if (activity.isRecording) {
            composeAmplitudeState.start { activity.mediaRecordHandler.getMaxAmplitude() }
          }
        } else {
          activity.onRecord(true, audioLevelProgressBar, amplitudeTextView)
        }
        Log.i(TAG_GEN, "Record Button: Recording started")
      } else {
        Toast.makeText(context, "Recording already in progress. Use Stop button.", Toast.LENGTH_SHORT).show()
      }
    }
  }

  private fun handleStopClick() {
    voiceRemoconActivity?.let { activity ->
      Log.i(TAG_GEN, "Stop Button clicked")
      if (activity.isRecording) {
        if (isComposeHost) {
          activity.onRecord(false, null, null)
          composeAmplitudeState.stop()
        } else {
          activity.onRecord(false, audioLevelProgressBar, amplitudeTextView)
        }
        Log.i(TAG_GEN, "Stop Button: Recording stopped. Playback may continue if active.")
      } else if (activity.mediaPlayViewModel?.isPlaying() == true) {
        activity.onPlay(false)
        Log.i(TAG_GEN, "Stop Button: Playback stopped")
      } else {
        Log.i(TAG_GEN, "Stop Button: Nothing to stop (neither recording nor playing).")
      }
    }
  }

  private fun handlePlayClick() {
    voiceRemoconActivity?.let { activity ->
      if (activity.isRecording) {
        Toast.makeText(
          context,
          "Cannot play while recording is active. Use Stop button to stop recording first.",
          Toast.LENGTH_SHORT
        ).show()
        return
      }
      activity.onPlay(true)
    }
  }

  private fun handlePauseClick() {
    voiceRemoconActivity?.let { activity ->
      if (activity.mediaPlayViewModel?.isPlaying() == true) {
        activity.mediaPlayViewModel?.pausePlaying()
        Log.d(TAG_GEN, "Playback paused")
      }
    }
  }

  private fun handleDeleteClick() {
    voiceRemoconActivity?.deleteAllRecordings()
  }

  private fun handleStartClick() {
    voiceRemoconActivity?.let { activity ->
      if (activity.isRecording) {
        Toast.makeText(
          context,
          "Recording is already in progress. Cannot start a new sequence.",
          Toast.LENGTH_SHORT
        ).show()
        return
      }

      Log.i(TAG_GEN, "Start button clicked. Starting recording.")
      if (isComposeHost) {
        activity.onRecord(true, null, null)
        if (activity.isRecording) {
          composeAmplitudeState.start { activity.mediaRecordHandler.getMaxAmplitude() }
        }
      } else {
        activity.onRecord(true, audioLevelProgressBar, amplitudeTextView)
      }

      activity.uiActionHandler.postDelayed({
        Log.i(TAG_GEN, "Start button: Playback initiated after delay.")
        activity.onPlay(true)
      }, VoiceRemocon.PLAYBACK_START_DELAY_MS_CONSTANT)
    }
  }

  override fun onResume() {
    super.onResume()
    voiceRemoconActivity?.let { activity ->
      if (activity.isRecording) {
        if (isComposeHost) {
          composeAmplitudeState.start { activity.mediaRecordHandler.getMaxAmplitude() }
        } else {
          activity.startUpdatingAmplitudeUI(
            audioLevelProgressBar,
            amplitudeTextView
          ) { activity.mediaRecordHandler.getMaxAmplitude() }
        }
      }
    }
  }

  override fun onPause() {
    super.onPause()
    voiceRemoconActivity?.let { activity ->
      if (activity.isRecording) {
        if (isComposeHost) {
          composeAmplitudeState.stop()
        } else {
          activity.uiActionHandler.removeCallbacks(activity.amplitudeUpdateRunnable)
        }
        Log.d(TAG_GEN, "Amplitude updates paused due to fragment onPause and still recording.")
      }
    }
  }

  override fun onDestroyView() {
    composeAmplitudeState.stop()
    voiceRemoconActivity = null
    super.onDestroyView()
  }
}
