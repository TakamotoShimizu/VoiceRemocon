package com.tak.voiceremocon

import android.Manifest.permission.RECORD_AUDIO
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.ProgressBar // Keep for type definition
import android.widget.TextView // Keep for type definition
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

class VoiceRemocon : AppCompatActivity() {

  companion object {
    const val PLAYBACK_START_DELAY_MS_CONSTANT = 100L
    const val REQUEST_RECORD_AUDIO_PERMISSION = 200
    const val UPDATE_INTERVAL_MS = 100L
    const val FIXED_RECORDING_FILENAME = "current_recording.wav"
    const val LOG_TAG = "AudioRecordTest"
  }

  private var permissionToRecordAccepted = false
  private var permissions: Array<String> = arrayOf(RECORD_AUDIO)
  private lateinit var audioManager: AudioManager
  lateinit var mediaRecordHandler: MediaRecordHandler
  lateinit var mediaPlayHandler: MediaPlayHandler
  lateinit var mediaRecogniseHandler: MediaRecogniseHandler // Added
  var mediaPlayViewModel: MediaPlayViewModel? = null
  var mediaRecordViewModel: MediaRecordViewModel? = null
  var mediaRecogniseViewModel: MediaRecogniseViewModel? = null // Added

  private lateinit var viewPager: ViewPager2
  private lateinit var tabLayout: TabLayout

  val uiActionHandler = Handler(Looper.getMainLooper())
  lateinit var amplitudeUpdateRunnable: Runnable
  var isRecording = false

  override fun onRequestPermissionsResult(
    requestCode: Int,
    permissions: Array<String>,
    grantResults: IntArray
  ) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    permissionToRecordAccepted = requestCode == REQUEST_RECORD_AUDIO_PERMISSION &&
      grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
    if (!permissionToRecordAccepted) {
      Toast.makeText(this, "Recording permission denied.", Toast.LENGTH_SHORT).show()
    }
  }

  fun startUpdatingAmplitudeUI(progressBar: ProgressBar?, textView: TextView?, getAmplitude: () -> Int) {
    amplitudeUpdateRunnable = Runnable {
        val maxAmplitude = getAmplitude()
        progressBar?.progress = maxAmplitude
        textView?.text = getString(R.string.amplitude, maxAmplitude)
        uiActionHandler.postDelayed(amplitudeUpdateRunnable, UPDATE_INTERVAL_MS)
    }
    uiActionHandler.post(amplitudeUpdateRunnable)
  }

  fun stopUpdatingAmplitudeUI(progressBar: ProgressBar?, textView: TextView?) {
     uiActionHandler.removeCallbacks(amplitudeUpdateRunnable)
     progressBar?.progress = 0
     textView?.text = "Amplitude: 0"
  }

  fun onRecord(start: Boolean, progressBar: ProgressBar?, textView: TextView?) {
    val recordingFilePath = externalCacheDir?.absolutePath?.let { "$it/$FIXED_RECORDING_FILENAME" }
    if (recordingFilePath == null) {
        Toast.makeText(this, "External cache directory not available.", Toast.LENGTH_SHORT).show()
        return
    }

    if (start) {
      if (isRecording) {
        Log.d(LOG_TAG, "Recording is already in progress. Ignoring new start request.")
        return
      }
      Log.d(LOG_TAG, "Start Recording to file: $recordingFilePath (Overwrite)")
      isRecording = true
      mediaRecordViewModel?.startRecording(recordingFilePath)
      if (progressBar != null && textView != null) {
        startUpdatingAmplitudeUI(progressBar, textView) { mediaRecordHandler.getMaxAmplitude() }
      }
    } else {
      if (!isRecording) {
        Log.d(LOG_TAG, "Recording is not in progress. Ignoring stop request.")
        return
      }
      Log.d(LOG_TAG, "Stop Recording for $FIXED_RECORDING_FILENAME")
      mediaRecordViewModel?.stopRecording()
      isRecording = false
      if (progressBar != null && textView != null) {
        stopUpdatingAmplitudeUI(progressBar, textView)
      }
    }
  }

  fun onPlay(start: Boolean) {
    val filePath = externalCacheDir?.absolutePath?.let { "$it/$FIXED_RECORDING_FILENAME" }
    if (filePath == null) {
        Toast.makeText(this, "External cache directory not available.", Toast.LENGTH_SHORT).show()
        return
    }

    val recordingFile = File(filePath)
    if (!recordingFile.exists() && start) {
      Toast.makeText(this, "Recording file ($FIXED_RECORDING_FILENAME) not found.", Toast.LENGTH_SHORT).show()
      return
    }
    if (start) {
      mediaPlayViewModel?.startPlaying(filePath)
    } else {
      mediaPlayViewModel?.stopPlaying()
    }
  }

  override fun onResume() {
    super.onResume()
    audioManager = applicationContext.getSystemService("audio") as AudioManager
  }

  override fun onPause() {
    super.onPause()
  }

  override fun onDestroy() {
    super.onDestroy()
    mediaPlayHandler.release()
    mediaRecordHandler.releaseResources()
    mediaRecogniseHandler.release()
    uiActionHandler.removeCallbacksAndMessages(null)
  }

  fun deleteAllRecordings() {
    // Implementation as before
  }

  fun onRecognise(start: Boolean, progressBar: ProgressBar?, textView: TextView?) {
    if (start) {
      mediaRecogniseViewModel?.startRecognition()
      if (progressBar != null && textView != null) {
        startUpdatingAmplitudeUI(progressBar, textView) { mediaRecogniseHandler.getMaxAmplitude() }
      }
    } else {
      mediaRecogniseViewModel?.stopRecognition()
      if (progressBar != null && textView != null) {
        stopUpdatingAmplitudeUI(progressBar, textView)
      }
    }
  }
  
  fun onRecogniseFromFile(start: Boolean, progressBar: ProgressBar?, textView: TextView?) {
    val recordingFilePath = externalCacheDir?.absolutePath?.let { "$it/$FIXED_RECORDING_FILENAME" }
    if (recordingFilePath == null) {
      Toast.makeText(this, "External cache directory not available.", Toast.LENGTH_SHORT).show()
      return
    }

    if (start) {
      mediaRecogniseViewModel?.startRecordingForRecognition(recordingFilePath)
      if (progressBar != null && textView != null) {
        startUpdatingAmplitudeUI(progressBar, textView) { mediaRecogniseHandler.getMaxAmplitude() }
      }
    } else {
      mediaRecogniseViewModel?.stopRecordingForRecognition()
      if (progressBar != null && textView != null) {
        stopUpdatingAmplitudeUI(progressBar, textView)
      }
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContentView(R.layout.activity_main)

    ActivityCompat.requestPermissions(this, permissions, REQUEST_RECORD_AUDIO_PERMISSION)

    viewPager = findViewById(R.id.view_pager)
    tabLayout = findViewById(R.id.tab_layout)

    val adapter = ViewPagerAdapter(this)
    viewPager.adapter = adapter

    TabLayoutMediator(tabLayout, viewPager) { tab, position ->
      tab.text = when (position) {
        0 -> "Remocon"
        1 -> "Generic"
        2 -> "Recognition"
        3 -> "Learning"
        else -> null
      }
    }.attach()

    // Initialize Handlers
    mediaRecordHandler = MediaRecordHandler(this)
    mediaPlayHandler = MediaPlayHandler(mediaRecordHandler)
    mediaRecogniseHandler = MediaRecogniseHandler(this)

    // Initialize ViewModels
    mediaRecordViewModel = MediaRecordViewModel(mediaRecordHandler)
    mediaPlayViewModel = MediaPlayViewModel(mediaPlayHandler)
    mediaRecogniseViewModel = MediaRecogniseViewModel(mediaRecogniseHandler)

    Log.d(LOG_TAG, "All handlers and ViewModels initialized")
  }

  private class ViewPagerAdapter(fa: FragmentActivity) : FragmentStateAdapter(fa) {
    override fun getItemCount(): Int = 4

    override fun createFragment(position: Int): Fragment {
      return when (position) {
        0 -> RemoconFragment()
        1 -> GenericControlsFragment()
        2 -> RecognitionFragment()
        3 -> LearningFragment()
        else -> throw IllegalStateException("Invalid position: $position")
      }
    }
  }

  // --- ViewModels ---
  class MediaPlayViewModel(private val mediaPlayHandler: MediaPlayHandler) : ViewModel() {
    fun startPlaying(fileNamePath: String) { viewModelScope.launch { mediaPlayHandler.startPlaying(fileNamePath) } }
    fun stopPlaying() { mediaPlayHandler.requestStopPlaying() }
    fun isPlaying(): Boolean { return mediaPlayHandler.isPlaying() }
    fun pausePlaying() { mediaPlayHandler.pausePlaying() }
  }

  class MediaRecordViewModel(private val mediaRecordHandler: MediaRecordHandler) : ViewModel() {
    fun startRecording(fileNamePath: String) {
      viewModelScope.launch(Dispatchers.IO) { mediaRecordHandler.startRecording(fileNamePath) }
    }
    fun stopRecording() {
      viewModelScope.launch(Dispatchers.IO) { mediaRecordHandler.stopRecording() }
    }
  }

  class MediaRecogniseViewModel(private val mediaRecogniseHandler: MediaRecogniseHandler) : ViewModel() {
    private val _recognitionText = MutableLiveData<String>()
    val recognitionText: LiveData<String> = _recognitionText

    init {
      // Launch a background coroutine to initialize the Vosk model without blocking the UI
      viewModelScope.launch(Dispatchers.IO) {
        Log.d(LOG_TAG, "VOSK model initialization started in background...")
        mediaRecogniseHandler.initializeVoskModel()
      }

      // Launch a coroutine to collect recognition events
      viewModelScope.launch {
        mediaRecogniseHandler.recognitionEvents.collect {
          when (it) {
            is RecognitionEvent.Partial -> {
              _recognitionText.postValue("Partial: ${it.text}")
              Log.d(LOG_TAG, "Partial result: ${it.text}")
            }
            is RecognitionEvent.Final -> {
              _recognitionText.postValue(it.text)
              Log.d(LOG_TAG, "Final result: ${it.text}")
            }
            is RecognitionEvent.Error -> {
              _recognitionText.postValue("Error: ${it.message}")
            }
          }
        }
      }
    }

    fun startRecognition() {
      Log.d(LOG_TAG, "MediaRecogniseViewModel: startRecognition requested")
      viewModelScope.launch(Dispatchers.IO) {
        mediaRecogniseHandler.startRecogniseFromStream()
      }
    }

    fun stopRecognition() {
      Log.d(LOG_TAG, "MediaRecogniseViewModel: stopRecognition requested")
      mediaRecogniseHandler.stopRecognitionStream()
    }

    fun startRecordingForRecognition(fileNamePath: String) {
      Log.d(LOG_TAG, "MediaRecogniseViewModel: startRecordingForRecognition requested")
      viewModelScope.launch(Dispatchers.IO) {
        mediaRecogniseHandler.startRecordingForRecognition(fileNamePath)
      }
    }
    
    fun stopRecordingForRecognition() {
        mediaRecogniseHandler.stopRecordingForRecognition()
    }

    fun isRecognising(): Boolean {
      return mediaRecogniseHandler.isRecognising()
    }
  }
}
