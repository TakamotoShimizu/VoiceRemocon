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
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer

private const val TAG_RCO = "RecognitionFragment"

class RecognitionFragment : Fragment() {

  private lateinit var startRecognitionButton: Button
  private lateinit var stopRecognitionButton: Button
  private lateinit var startRecButton: Button
  private lateinit var recognitionResultTextView: TextView
  private lateinit var audioLevelProgressBarRec: ProgressBar
  private lateinit var amplitudeTextViewRec: TextView
  private var voiceRemoconActivity: VoiceRemocon? = null
  private var isModelReady = false
  private var fromFile = false

  override fun onCreateView(
    inflater: LayoutInflater,
    container: ViewGroup?,
    savedInstanceState: Bundle?
  ): View? {
    // Inflate the layout for this fragment
    return inflater.inflate(R.layout.fragment_recognition, container, false)
  }

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)
    
    startRecognitionButton = view.findViewById(R.id.start_recognition_button)
    stopRecognitionButton = view.findViewById(R.id.stop_recognition_button)
    startRecButton = view.findViewById(R.id.start_rec_button)
    recognitionResultTextView = view.findViewById(R.id.recognition_result_textview)
    audioLevelProgressBarRec = view.findViewById(R.id.audio_level_progressbar_rec)
    amplitudeTextViewRec = view.findViewById(R.id.amplitude_textview_rec)

    voiceRemoconActivity = activity as? VoiceRemocon

    // Disable buttons on startup until the model is ready
    updateButtonStates(false)
    voiceRemoconActivity?.mediaRecogniseViewModel?.recognitionText?.observe(viewLifecycleOwner,
      Observer { text ->
        if (text.startsWith("VOSK model ready")) {
          isModelReady = true
          updateButtonStates(false)
          recognitionResultTextView.text = text
          Toast.makeText(context, "VOSK model ready", Toast.LENGTH_SHORT).show()
        } else if (text.startsWith("Recognition finished")) {
          updateButtonStates(false)
        } else {
          recognitionResultTextView.text = text
        }
    })

    if (voiceRemoconActivity == null) {
      Log.e(TAG_RCO, "Activity is not VoiceRemocon or null")
      Toast.makeText(context, "Error: Could not link to main activity", Toast.LENGTH_LONG).show()
      return
    }
    
    // Set up click listeners
    startRecognitionButton.setOnClickListener {
      Log.i(TAG_RCO, "Start Recognition button clicked.")
      voiceRemoconActivity?.onRecognise(true, audioLevelProgressBarRec, amplitudeTextViewRec)
      fromFile = false
      updateButtonStates(true)
    }

    stopRecognitionButton.setOnClickListener {
      Log.i(TAG_RCO, "Stop Recognition button clicked.")
      if (fromFile) {
        voiceRemoconActivity?.onRecogniseFromFile(false, audioLevelProgressBarRec, amplitudeTextViewRec) // Also stop file-based recognition if active
      } else {
        voiceRemoconActivity?.onRecognise(false, audioLevelProgressBarRec, amplitudeTextViewRec)
      }
      updateButtonStates(false)
    }

    startRecButton.setOnClickListener {
      Log.i(TAG_RCO, "Start Rec button clicked.")
      voiceRemoconActivity?.onRecogniseFromFile(true, audioLevelProgressBarRec, amplitudeTextViewRec)
      fromFile = true
      updateButtonStates(true)
    }
  }
  
  private fun updateButtonStates(isRecognizing: Boolean) {
    if (!isModelReady) {
      startRecognitionButton.isEnabled = false
      stopRecognitionButton.isEnabled = false
      startRecButton.isEnabled = false
    } else {
      startRecognitionButton.isEnabled = !isRecognizing
      stopRecognitionButton.isEnabled = isRecognizing
      startRecButton.isEnabled = !isRecognizing
    }
  }

  override fun onResume() {
    super.onResume()
    voiceRemoconActivity?.let { activity ->
      val isRecognising = activity.mediaRecogniseViewModel?.isRecognising() == true
      updateButtonStates(isRecognising)
      
      if (isRecognising) {
        activity.startUpdatingAmplitudeUI(audioLevelProgressBarRec, amplitudeTextViewRec) { activity.mediaRecogniseHandler.getMaxAmplitude() }
      }
    }
  }

  override fun onPause() {
    super.onPause()
    voiceRemoconActivity?.uiActionHandler?.removeCallbacks(voiceRemoconActivity!!.amplitudeUpdateRunnable)
  }
}
