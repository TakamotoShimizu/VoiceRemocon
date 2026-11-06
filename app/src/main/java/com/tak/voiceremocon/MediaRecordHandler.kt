package com.tak.voiceremocon

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder // For AudioSource constant
import android.util.Log
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

private const val TAG_MR = "MediaRecordHandler"

class MediaRecordHandler(private val context: Context) {

  private var audioRecord: AudioRecord? = null
  private var job: Job? = null
  private var isRecording = false
  private var outputFilePath: String? = null
  private var fileOutputStream: FileOutputStream? = null

  private val audioBuffer = ByteArray(BUFFER_SIZE)
  private var readResult : Int = 0
  private var maxAmplitude = 0
  private var resetAmpiltude = false
  private var totalAudioLen: Long = 0 // In bytes
  private var totalDataLen: Long = 0  // In bytes, totalAudioLen + 36 (header without RIFF and WAVE size)

  companion object {
    private const val AUDIO_SOURCE = MediaRecorder.AudioSource.VOICE_COMMUNICATION
    private const val SAMPLE_RATE_IN_HZ = 44100
    private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    private val BUFFER_SIZE = AudioRecord.getMinBufferSize(SAMPLE_RATE_IN_HZ, CHANNEL_CONFIG, AUDIO_FORMAT)
    private const val BYTES_PER_SAMPLE = 2 // For 16-bit PCM

    @Throws(IOException::class)
    fun writeWavHeader(out: FileOutputStream, totalAudioLen: Long, totalDataLen: Long) {
      val channels = if (CHANNEL_CONFIG == AudioFormat.CHANNEL_IN_MONO) 1 else 2
      val byteRate = (SAMPLE_RATE_IN_HZ * channels * BYTES_PER_SAMPLE).toLong()
      val header = ByteArray(44)

      header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte(); header[2] =
        'F'.code.toByte(); header[3] = 'F'.code.toByte() // RIFF/WAVE header
      val chunkSize = totalDataLen + 36 // or totalAudioLen + 36
      header[4] = (chunkSize and 0xff).toByte()
      header[5] = (chunkSize shr 8 and 0xff).toByte()
      header[6] = (chunkSize shr 16 and 0xff).toByte()
      header[7] = (chunkSize shr 24 and 0xff).toByte()
      header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte(); header[10] =
        'V'.code.toByte(); header[11] = 'E'.code.toByte() // WAVE
      header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte(); header[14] =
        't'.code.toByte(); header[15] = ' '.code.toByte() // 'fmt ' chunk
      header[16] = 16; header[17] = 0; header[18] = 0; header[19] =
        0  // 4 bytes: size of 'fmt ' chunk
      header[20] = 1; header[21] = 0 // format = 1 (PCM)
      header[22] = channels.toByte(); header[23] = 0 // channels
      header[24] = (SAMPLE_RATE_IN_HZ and 0xff).toByte()
      header[25] = (SAMPLE_RATE_IN_HZ shr 8 and 0xff).toByte()
      header[26] = (SAMPLE_RATE_IN_HZ shr 16 and 0xff).toByte()
      header[27] = (SAMPLE_RATE_IN_HZ shr 24 and 0xff).toByte() // sample rate
      header[28] = (byteRate and 0xff).toByte()
      header[29] = (byteRate shr 8 and 0xff).toByte()
      header[30] = (byteRate shr 16 and 0xff).toByte()
      header[31] = (byteRate shr 24 and 0xff).toByte() // byte rate
      header[32] = (channels * BYTES_PER_SAMPLE).toByte(); header[33] = 0 // block align
      header[34] = (BYTES_PER_SAMPLE * 8).toByte(); header[35] = 0 // bits per sample
      header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte(); header[38] =
        't'.code.toByte(); header[39] = 'a'.code.toByte() // 'data' chunk
      header[40] = (totalAudioLen and 0xff).toByte()
      header[41] = (totalAudioLen shr 8 and 0xff).toByte()
      header[42] = (totalAudioLen shr 16 and 0xff).toByte()
      header[43] = (totalAudioLen shr 24 and 0xff).toByte() // data size

      out.write(header, 0, 44)
    }

    @Throws(IOException::class)
    suspend fun updateWavHeader(filePath: String, totalAudioLen: Long) {
      val channels = if (CHANNEL_CONFIG == AudioFormat.CHANNEL_IN_MONO) 1 else 2
      // totalDataLen is the totalAudioLen because 'data' chunk size is totalAudioLen
      // The RIFF chunk size is totalAudioLen + 36 (size of header from 'WAVE' onwards)
      val riffChunkSize = totalAudioLen + 36
      val dataChunkSize = totalAudioLen

      withContext(Dispatchers.IO) {
        RandomAccessFile(File(filePath), "rw").use { randomAccessFile ->
          randomAccessFile.seek(4) // RIFF chunk size
          randomAccessFile.writeInt(Integer.reverseBytes(riffChunkSize.toInt()))
          randomAccessFile.seek(40) // data sub-chunk size
          randomAccessFile.writeInt(Integer.reverseBytes(dataChunkSize.toInt()))
        }
      }
    }
  }

  @SuppressLint("MissingPermission")
  fun startRecording(fileName: String) {
    if (isRecording) {
      Log.w(TAG_MR, "Recording is already in progress.")
      return
    }
    isRecording = true

    if (ActivityCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
      Log.e(TAG_MR, "RECORD_AUDIO permission not granted.")
      // Consider throwing an exception or returning a status
      return
    }

    try {
      audioRecord = AudioRecord(
        AUDIO_SOURCE,
        SAMPLE_RATE_IN_HZ,
        CHANNEL_CONFIG,
        AUDIO_FORMAT,
        BUFFER_SIZE
      )
    } catch (e: IllegalArgumentException) {
      Log.e(TAG_MR, "Failed to create AudioRecord instance", e)
      return
    }

    if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
      Log.e(TAG_MR, "AudioRecord not initialized.")
      audioRecord?.release()
      audioRecord = null
      return
    }

    outputFilePath = fileName
    totalAudioLen = 0
    totalDataLen = 0
    maxAmplitude = 0

    try {
      fileOutputStream = FileOutputStream(outputFilePath)
      writeWavHeader(fileOutputStream!!, 0, 0) // Write a placeholder header
    } catch (e: IOException) {
      Log.e(TAG_MR, "Failed to open file for recording: ${e.message}", e)
      releaseResources() // Call the public version
      return
    }

    Log.d(TAG_MR, "Starting recording with AudioRecord buffer size $BUFFER_SIZE")
    audioRecord?.startRecording()
    Log.d(TAG_MR, "Recording started with AudioRecord to $fileName")

    job = CoroutineScope(Dispatchers.IO).launch {
      try {
        while (isRecording) {
          readResult = audioRecord?.read(audioBuffer, 0, audioBuffer.size) ?: -1
          if (readResult > 0) {
            fileOutputStream?.write(audioBuffer, 0, readResult)
            calculateMaxAmplitude(audioBuffer, readResult)
            fileOutputStream?.flush()
            totalAudioLen += readResult
          } else if (readResult < 0) {
            Log.e(TAG_MR, "Error while reading audio data: $readResult")
            isRecording = false // Stop recording on error
          }
        }
      } catch (e: IOException) {
        Log.e(TAG_MR, "IOException during recording loop: ${e.message}", e)
      } finally {
        Log.i(TAG_MR, "Recording loop finished. Total audio length: $totalAudioLen bytes")
      }
    }
  }

  private fun calculateMaxAmplitude(audioData: ByteArray, readSize: Int) {
    // Assuming 16-bit PCM
    for (i in 0 until readSize step BYTES_PER_SAMPLE * 4) {
      if (i + 1 < readSize) {
        val sampleShort = ByteBuffer.wrap(audioData, i, BYTES_PER_SAMPLE)
                              .order(ByteOrder.LITTLE_ENDIAN)
                              .short
        val currentAmplitude = abs(sampleShort.toInt())
        if (resetAmpiltude) {
          maxAmplitude = currentAmplitude
          resetAmpiltude = false
        } else if (currentAmplitude > maxAmplitude) {
          maxAmplitude = currentAmplitude
        }
      }
    }
  }

  suspend fun stopRecording() {
    if (!isRecording && audioRecord == null) {
      Log.w(TAG_MR, "Recording was not started or already stopped.")
      return
    }
    isRecording = false // Signal the recording loop to stop

    try {
      job?.join() // Wait for the recording coroutine to finish
    } catch (e: InterruptedException) {
      Log.e(TAG_MR, "Recording job interruption: ${e.message}", e)
      Thread.currentThread().interrupt() // Restore interrupted status
    }
    job = null

    // Stop and release AudioRecord
    if (audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
      try {
        audioRecord?.stop()
      } catch (e: IllegalStateException) {
        Log.e(TAG_MR, "AudioRecord stop failed: ${e.message}", e)
      }
    }
    audioRecord?.release()
    audioRecord = null
    Log.d(TAG_MR, "AudioRecord stopped and released.")

    // Close file output stream
    try {
      fileOutputStream?.flush()
      fileOutputStream?.close()
    } catch (e: IOException) {
      Log.e(TAG_MR, "Failed to close file output stream: ${e.message}", e)
    }
    fileOutputStream = null

    // Update WAV header with correct file sizes
    if (outputFilePath != null && totalAudioLen > 0) {
      try {
        updateWavHeader(outputFilePath!!, totalAudioLen)
        Log.d(TAG_MR, "WAV header updated. Total audio length: $totalAudioLen bytes")
      } catch (e: IOException) {
        Log.e(TAG_MR, "Failed to update WAV header: ${e.message}", e)
      }
    } else {
      Log.w(TAG_MR, "No audio data recorded or output path is null, skipping WAV header update.")
      // If no data, consider deleting the empty/incomplete file
      outputFilePath?.let { File(it).delete() }
    }
    outputFilePath = null
  }

  fun releaseResources() { // Changed to public
    isRecording = false // Ensure loop stops
    audioRecord?.release()
    audioRecord = null
    try {
      fileOutputStream?.close()
    } catch (e: IOException) {
      Log.e(TAG_MR, "Error closing file stream on resource release: ${e.message}", e)
    }
    fileOutputStream = null
    job?.cancel() // Cancel the job if it's still active
    job = null
    Log.d(TAG_MR, "MediaRecordHandler resources released.") // Added log for confirmation
  }

  fun getMaxAmplitude(): Int {
    resetAmpiltude = true
    // Scale to 0-32767 if maxAmplitude was calculated from Short.MIN_VALUE to Short.MAX_VALUE
    // Since we used abs(short), maxAmplitude is already in the 0-32767 range.
    return maxAmplitude
  }

  fun isRecording(): Boolean {
    return isRecording
  }

  fun getTotalAudioLen(): Long {
    return totalAudioLen
  }
}
