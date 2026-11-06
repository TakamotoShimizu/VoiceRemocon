package com.tak.voiceremocon

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.StorageService
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

private const val TAG_MRH = "MediaRecogniseHandler"

// Sealed class to represent different recognition events
sealed class RecognitionEvent {
  data class Partial(val text: String) : RecognitionEvent()
  data class Final(val text: String) : RecognitionEvent()
  data class Error(val message: String) : RecognitionEvent()
}

class MediaRecogniseHandler(private val context: Context) {

  private var audioRecord: AudioRecord? = null
  private var job: Job? = null
  private var isRecognition = false
  private var isRecognising = false
  private val audioBuffer = ByteArray(BUFFER_SIZE)
  private var readResult: Int = 0
  private var maxAmplitude = 0
  private var resetAmplitude = false
  private var recogniseOnValue = AtomicInteger(RECOG_ON_LEVEL)
  private var recogniseOffValue = AtomicInteger(RECOG_OFF_LEVEL)
  private var levelState = LEVEL_STATE_LOW
  private var recStartTime: Long = 0

  // VOSK related members
  private var voskModel: Model? = null
  private var modelInitialized = false
  private var errorMessage: String = ""

  // File recording members
  private var outputFilePath: String? = null
  private var fileOutputStream: FileOutputStream? = null
  private var totalAudioLen: Long = 0

  // Flow to emit recognition events
  private val _recognitionEvents = MutableSharedFlow<RecognitionEvent>(replay = 1)
  val recognitionEvents = _recognitionEvents.asSharedFlow()

  companion object {
    private const val AUDIO_SOURCE = MediaRecorder.AudioSource.VOICE_COMMUNICATION
    private const val SAMPLE_RATE_IN_HZ = 44100
    private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    private const val BUFFER_SIZE = 4096 
    private const val BYTES_PER_SAMPLE = 2
    private const val VOSK_MODEL_ASSET_PATH = "vosk-model-small-ja-0.22"

    private const val RECOG_ON_LEVEL = 3000
    private const val RECOG_OFF_LEVEL = 800

    private const val LEVEL_STATE_LOW: Int = 0
    private const val LEVEL_STATE_CONT_RECOGNISE: Int = 1
    private const val LEVEL_STATE_WAIT_RECOGNISE: Int = 2
    private const val LEVEL_STATE_WAIT_RECFINISH: Int = 3
  }

  init {
    Log.d(TAG_MRH, "MediaRecogniseHandler initialized")
    LibVosk.setLogLevel(LogLevel.INFO)
  }

  fun initializeVoskModel(): Boolean {
    if (modelInitialized && voskModel != null) {
      Log.d(TAG_MRH, "VOSK model already initialized.")
      return true
    }
    Log.d(TAG_MRH, "Initializing VOSK model from assets path: $VOSK_MODEL_ASSET_PATH")
    try {
      StorageService.unpack(context, VOSK_MODEL_ASSET_PATH, "model",
        { model ->
          this.voskModel = model
          this.modelInitialized = true
          _recognitionEvents.tryEmit(RecognitionEvent.Final("VOSK model ready"))
          Log.d(TAG_MRH, "VOSK model unpacked and loaded successfully.")
        },
        { exception ->
          val errorMsg = "Failed to extract the model: ${exception.message}"
          setErrorState(errorMsg)
          _recognitionEvents.tryEmit(RecognitionEvent.Error(errorMsg))
          this.modelInitialized = false
        }
      )
      return true
    } catch (e: Exception) {
      val errorMsg = "Unexpected error on VOSK model initialization: ${e.message}"
      setErrorState(errorMsg)
      _recognitionEvents.tryEmit(RecognitionEvent.Error(errorMsg))
      Log.e(TAG_MRH, errorMsg, e)
    }
    this.modelInitialized = false
    return false
  }

  private fun setErrorState(message: String) {
    errorMessage = message
    Log.e(TAG_MRH, "Error State: $message")
  }

  @SuppressLint("MissingPermission")
  fun startRecogniseFromStream() {
    if (isRecognition) {
      Log.w(TAG_MRH, "Recognition is already in progress.")
      return
    }
    if (!modelInitialized || voskModel == null) {
      _recognitionEvents.tryEmit(RecognitionEvent.Error("VOSK model is not initialized."))
      return
    }
    isRecognition = true
    var lowLevelStart: LocalDateTime = LocalDateTime.now()
    var currentTime: LocalDateTime

    try {
      audioRecord = AudioRecord(AUDIO_SOURCE, SAMPLE_RATE_IN_HZ, CHANNEL_CONFIG, AUDIO_FORMAT, BUFFER_SIZE)
    } catch (e: IllegalArgumentException) {
      _recognitionEvents.tryEmit(RecognitionEvent.Error("Failed to create AudioRecord: " + e.message))
      isRecognition = false
      return
    }

    if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
      _recognitionEvents.tryEmit(RecognitionEvent.Error("AudioRecord not initialized."))
      releaseAudioRecord()
      isRecognition = false
      return
    }
    
    var recognizer: Recognizer
    try {
      recognizer = Recognizer(voskModel!!, SAMPLE_RATE_IN_HZ.toFloat())
    } catch (e: Exception) {
      _recognitionEvents.tryEmit(RecognitionEvent.Error("Failed to create recognizer: " + e.message))
      isRecognition = false
      releaseAudioRecord()
      return
    }

    audioRecord?.startRecording()

    job = CoroutineScope(Dispatchers.IO).launch {
      Log.d(TAG_MRH, "Starting recognition loop: $isRecognition")
      try {
        while (isRecognition) {
          readResult = audioRecord?.read(audioBuffer, 0, audioBuffer.size) ?: -1
          if (readResult > 0) {
            calculateMaxAmplitude(audioBuffer, readResult)
            if (levelState == LEVEL_STATE_LOW) {
              if (maxAmplitude >= recogniseOnValue.get()) {
                levelState = LEVEL_STATE_CONT_RECOGNISE
                Log.d(TAG_MRH, "level State changed from LOW: $levelState")
                executeRecognition(recognizer, audioBuffer, readResult)
              }
            } else if (levelState == LEVEL_STATE_CONT_RECOGNISE) {
              if (maxAmplitude < recogniseOffValue.get()) {
                lowLevelStart = LocalDateTime.now()
                levelState = LEVEL_STATE_WAIT_RECOGNISE
                Log.d(TAG_MRH, "level State changed from CONT: $levelState")
              }
              executeRecognition(recognizer, audioBuffer, readResult)
            } else if (levelState == LEVEL_STATE_WAIT_RECOGNISE) {
              currentTime = LocalDateTime.now()
              val secondsElapsed = Duration.between(lowLevelStart, currentTime).seconds
              if (secondsElapsed >= 1) {
                levelState = LEVEL_STATE_LOW
                Log.d(TAG_MRH, "level State changed from WAIT: $levelState")
              } else {
                executeRecognition(recognizer, audioBuffer, readResult)
              }
            }
          } else if (readResult < 0) {
            _recognitionEvents.tryEmit(RecognitionEvent.Error("Error reading audio data"))
            isRecognition = false
          }
        }
      } catch (e: Exception) {
        val errorMsg = "Exception in VOSK recognition loop: ${e.message}"
        _recognitionEvents.tryEmit(RecognitionEvent.Error(errorMsg))
        isRecognition = false
      } finally {
        releaseAudioRecord()
        try {
          val finalResultJson = recognizer.finalResult
          val text = JSONObject(finalResultJson).getString("text")
          if (text.isNotBlank()) {
            _recognitionEvents.tryEmit(RecognitionEvent.Final(text))
          }
        } catch (e: org.json.JSONException) {
          Log.e(TAG_MRH, "JSON parsing error on final result: ${e.message}")
        }
        recognizer.close()
        Log.d(TAG_MRH, "AudioRecord and VOSK Recognizer released after loop.")
      }
    }
  }

  fun stopRecognitionStream() {
    levelState = LEVEL_STATE_LOW
    if (isRecognition) {
      Log.d(TAG_MRH, "Stopping recognition stream explicitly.")
      isRecognition = false
    } else {
      Log.d(TAG_MRH, "Recognition stream not active or already stopping.")
    }
  }
  
  private fun executeRecognition(recognizer: Recognizer, buffer: ByteArray, readSize: Int) {
    if (recognizer.acceptWaveForm(buffer, readSize)) {
      val resultJson = recognizer.result
      val text = JSONObject(resultJson).getString("text")
      if (text.isNotBlank()) {
        _recognitionEvents.tryEmit(RecognitionEvent.Final(text))
      }
    } else {
      val partialResultJson = recognizer.partialResult
      val partialText = JSONObject(partialResultJson).getString("partial")
      if (partialText.isNotBlank()) {
        _recognitionEvents.tryEmit(RecognitionEvent.Partial(partialText))
      }
    }
  }

  @SuppressLint("MissingPermission")
  fun startRecordingForRecognition(fileName: String) {
    var lowLevelStart: LocalDateTime = LocalDateTime.now()
    var currentTime: LocalDateTime
    outputFilePath = fileName

    if (isRecognition) {
      Log.w(TAG_MRH, "Recognition/Recording process is already in progress.")
      return
    }

    if (isRecognising) {
      Log.e(TAG_MRH, "Recognising is already in started.")
      return
    }

    isRecognition = true
    levelState = LEVEL_STATE_LOW
    isRecognising = false

    try {
      audioRecord = AudioRecord(AUDIO_SOURCE, SAMPLE_RATE_IN_HZ, CHANNEL_CONFIG, AUDIO_FORMAT, BUFFER_SIZE)
    } catch (e: IllegalArgumentException) {
      _recognitionEvents.tryEmit(RecognitionEvent.Error("Failed to create AudioRecord: " + e.message))
      isRecognition = false
      return
    }

    if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
      _recognitionEvents.tryEmit(RecognitionEvent.Error("AudioRecord not initialized."))
      releaseAudioRecord()
      isRecognition = false
      return
    }
    
    audioRecord?.startRecording()

    job = CoroutineScope(Dispatchers.IO).launch {
      try {
        fileOutputStream = FileOutputStream(fileName)
        writeWavHeader(fileOutputStream!!, 0)
      } catch (e: IOException) {
        _recognitionEvents.tryEmit(RecognitionEvent.Error("Failed to open file for recording: " + e.message))
        stopRecordingForRecognition()
        return@launch
      }

      Log.d(TAG_MRH, "Listening for audio level to start recording...")
      totalAudioLen = 0

      while(isRecognition) {
        readResult = audioRecord?.read(audioBuffer, 0, audioBuffer.size) ?: -1
        if (readResult > 0) {
          calculateMaxAmplitude(audioBuffer, readResult)
          if (levelState == LEVEL_STATE_LOW) {
            if (maxAmplitude >= recogniseOnValue.get()) {
              levelState = LEVEL_STATE_CONT_RECOGNISE
              Log.d(TAG_MRH, "Threshold reached. Start recording: $levelState")
              recStartTime = System.currentTimeMillis()
              recordAndRecognise(fileName)
            }
          } else if (levelState == LEVEL_STATE_CONT_RECOGNISE) {
            if (maxAmplitude < recogniseOffValue.get()) {
              lowLevelStart = LocalDateTime.now()
              levelState = LEVEL_STATE_WAIT_RECOGNISE
              Log.d(TAG_MRH, "level State changed from CONT: $levelState")
            }
            recordAndRecognise(fileName)
          } else if (levelState == LEVEL_STATE_WAIT_RECOGNISE) {
            currentTime = LocalDateTime.now()
            val secondsElapsed = Duration.between(lowLevelStart, currentTime).seconds
            if (secondsElapsed >= 1) {
              levelState = LEVEL_STATE_WAIT_RECFINISH
              Log.d(TAG_MRH, "level State changed from WAIT: $levelState")
            } else {
              recordAndRecognise(fileName)
            }
          } else if (levelState == LEVEL_STATE_WAIT_RECFINISH) {
            if (!isRecognising) {
              Log.d(TAG_MRH, "Recognition finished then finish record loop")
              isRecognition = false
              levelState = LEVEL_STATE_LOW
              stopRecordingForRecognition()
              break
            }
          }
        }
      }
      Log.d(TAG_MRH, "Listening loop finished.")
    }
  }
  
  private fun recordAndRecognise(fileName: String) {
    // Write the first buffer that triggered the recording
    try {
      fileOutputStream?.write(audioBuffer, 0, readResult)
      fileOutputStream?.flush()
      totalAudioLen += readResult
    } catch (e: IOException) {
      Log.e(TAG_MRH, "Failed to write audio buffer to file: ${e.message}")
    }

    val currentTime = System.currentTimeMillis()

    if (currentTime - recStartTime > 500) {
      if (!isRecognising) {
        Log.d(TAG_MRH, "500msec reached start to recognise")
        isRecognising = true
        recogniseFromFile(fileName)
      }
    }
  }

  fun stopRecordingForRecognition() {
    isRecognition = false
    isRecognising = false
    job?.cancel()
    job = null

    releaseAudioRecord()

    // send stop recognition event to UI
    _recognitionEvents.tryEmit(RecognitionEvent.Final("Recognition finished"))

    try {
      fileOutputStream?.flush()
      fileOutputStream?.close()
    } catch (e: IOException) {
      Log.e(TAG_MRH, "Failed to close file output stream.", e)
    }
    fileOutputStream = null

    if (outputFilePath != null && totalAudioLen > 0) {
      try {
        updateWavHeader(outputFilePath!!, totalAudioLen)
      } catch (e: IOException) {
        Log.e(TAG_MRH, "Failed to update WAV header.", e)
      }
    }
  }
  
  private fun releaseAudioRecord() {
    if (audioRecord != null) {
      if (audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
        audioRecord?.stop()
      }
      audioRecord?.release()
      audioRecord = null
    }
  }

  private fun recogniseFromFile(filePath: String) {
    val localRecognizer = Recognizer(voskModel!!, SAMPLE_RATE_IN_HZ.toFloat())
    val fis: InputStream = FileInputStream(filePath)
    fis.skip(44) // Skip WAV header

    if (!modelInitialized || voskModel == null) {
      _recognitionEvents.tryEmit(RecognitionEvent.Error("VOSK model is not initialized."))
      return
    }
    
    CoroutineScope(Dispatchers.IO).launch {
      try {
        Log.d(TAG_MRH, "Starting recognition from file: $filePath")

        val buffer = ByteArray(BUFFER_SIZE)
        var nbytes: Int
        while (fis.read(buffer).also { nbytes = it } > 0) {
          if (localRecognizer.acceptWaveForm(buffer, nbytes)) {
            val resultJson = localRecognizer.result
            val text = JSONObject(resultJson).getString("text")
            if (text.isNotBlank()) {
              _recognitionEvents.tryEmit(RecognitionEvent.Final(text))
            }
          } else {
            val partialResultJson = localRecognizer.partialResult
            val partialText = JSONObject(partialResultJson).getString("partial")
            if (partialText.isNotBlank()) {
              _recognitionEvents.tryEmit(RecognitionEvent.Partial(partialText))
            }
          }
        }

        val finalResultJson = localRecognizer.finalResult
        val text = JSONObject(finalResultJson).getString("text")
        if (text.isNotBlank()) {
          _recognitionEvents.tryEmit(RecognitionEvent.Final(text))
          Log.d(TAG_MRH, "File recognition final result: $text")
        } else {
          _recognitionEvents.tryEmit(RecognitionEvent.Final("Recognition complete, no speech detected."))
          Log.d(TAG_MRH, "File recognition final how ever no speech detected")
        }
      } catch (e: Exception) {
        val errorMsg = "Failed to recognize from file: ${e.message}"
        _recognitionEvents.tryEmit(RecognitionEvent.Error(errorMsg))
        Log.e(TAG_MRH, errorMsg, e)
      } finally {
        fis.close()
        localRecognizer.close()
        isRecognising = false
        Log.d(TAG_MRH, "File recognition resources released.")
      }
    }
  }

  @Throws(IOException::class)
  private fun writeWavHeader(out: FileOutputStream, totalAudioLen: Long) {
    val channels = 1
    val byteRate = (SAMPLE_RATE_IN_HZ * channels * BYTES_PER_SAMPLE).toLong()
    val header = ByteArray(44)

    header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte(); header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
    val chunkSize = totalAudioLen + 36
    header[4] = (chunkSize and 0xff).toByte()
    header[5] = (chunkSize shr 8 and 0xff).toByte()
    header[6] = (chunkSize shr 16 and 0xff).toByte()
    header[7] = (chunkSize shr 24 and 0xff).toByte()
    header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte(); header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()
    header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte(); header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()
    header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0
    header[20] = 1; header[21] = 0
    header[22] = channels.toByte(); header[23] = 0
    val sampleRate = SAMPLE_RATE_IN_HZ.toLong()
    header[24] = (sampleRate and 0xff).toByte()
    header[25] = (sampleRate shr 8 and 0xff).toByte()
    header[26] = (sampleRate shr 16 and 0xff).toByte()
    header[27] = (sampleRate shr 24 and 0xff).toByte()
    header[28] = (byteRate and 0xff).toByte()
    header[29] = (byteRate shr 8 and 0xff).toByte()
    header[30] = (byteRate shr 16 and 0xff).toByte()
    header[31] = (byteRate shr 24 and 0xff).toByte()
    header[32] = (channels * BYTES_PER_SAMPLE).toByte(); header[33] = 0
    header[34] = (BYTES_PER_SAMPLE * 8).toByte(); header[35] = 0
    header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte(); header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()
    header[40] = (totalAudioLen and 0xff).toByte()
    header[41] = (totalAudioLen shr 8 and 0xff).toByte()
    header[42] = (totalAudioLen shr 16 and 0xff).toByte()
    header[43] = (totalAudioLen shr 24 and 0xff).toByte()

    out.write(header, 0, 44)
  }

  @Throws(IOException::class)
  private fun updateWavHeader(filePath: String, totalAudioLen: Long) {
    val riffChunkSize = totalAudioLen + 36
    RandomAccessFile(File(filePath), "rw").use { randomAccessFile ->
      randomAccessFile.seek(4) // RIFF chunk size
      randomAccessFile.writeInt(Integer.reverseBytes(riffChunkSize.toInt()))
      randomAccessFile.seek(40) // data sub-chunk size
      randomAccessFile.writeInt(Integer.reverseBytes(totalAudioLen.toInt()))
    }
  }
  
  fun release() {
    Log.d(TAG_MRH, "Releasing MediaRecogniseHandler resources...")
    stopRecordingForRecognition()
    if (voskModel != null) {
      voskModel?.close()
      voskModel = null
    }
    modelInitialized = false
    Log.d(TAG_MRH, "VOSK model and recognizer resources released.")
  }

  private fun calculateMaxAmplitude(audioData: ByteArray, readSize: Int) {
    for (i in 0 until readSize step BYTES_PER_SAMPLE * 2) {
      if (i + 1 < readSize) {
        val sampleShort = ByteBuffer.wrap(audioData, i, BYTES_PER_SAMPLE)
          .order(ByteOrder.LITTLE_ENDIAN)
          .short
        val currentAmplitude = abs(sampleShort.toInt())
        if (resetAmplitude) {
          maxAmplitude = currentAmplitude
          resetAmplitude = false
        } else if (currentAmplitude > maxAmplitude) {
          maxAmplitude = currentAmplitude
        }
      }
    }
  }
  
  fun getMaxAmplitude(): Int {
    resetAmplitude = true
    return maxAmplitude
  }

  fun setRecogniseOnValue(value: Int) {
    recogniseOnValue.set(value)
  }

  fun setRecogniseOffValue(value: Int) {
    recogniseOffValue.set(value)
  }

  fun isRecognising(): Boolean {
    return isRecognition
  }
}
