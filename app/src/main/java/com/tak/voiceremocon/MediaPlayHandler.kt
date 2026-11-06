package com.tak.voiceremocon

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.io.IOException
import java.io.FileNotFoundException

private const val TAG_MH = "MediaPlayHandler"

class MediaPlayHandler(private val mediaRecordHandler: MediaRecordHandler) {

  private var audioTrack: AudioTrack? = null
  private var playbackJob: Job? = null
  private var isActuallyPlaying: Boolean = false
  private var stream: FileInputStream? = null

  // WAV Header properties
  private var sampleRateInHz: Int = 0
  private var channelConfig: Int = AudioFormat.CHANNEL_OUT_MONO
  private var audioEncoding: Int = AudioFormat.ENCODING_PCM_16BIT
  // private var pcmDataSize: Long = 0 // Not strictly needed as a member if stream reads till EOF for data
  private var dataOffset: Long = 0 // Offset to the actual PCM data in the WAV file

  private fun parseWavHeader(fis: FileInputStream): Boolean {
    var bytesRecord: Int = 0
    // wait for audio recording actually start
    if (mediaRecordHandler.isRecording()) {
      while (bytesRecord <= 0) {
        bytesRecord = mediaRecordHandler.getTotalAudioLen().toInt()
      }
    }

    try {
      val header = ByteArray(44) // Standard WAV header size for initial read
      var bytesRead = fis.read(header, 0, 12) // Read RIFF, FileSize, WAVE ID
      if (bytesRead < 12) {
        Log.e(TAG_MH, "Could not read initial 12 bytes of WAV header")
        return false
      }

      if (String(header, 0, 4) != "RIFF" || String(header, 8, 4) != "WAVE") {
        Log.e(TAG_MH, "Not a valid WAV file (RIFF/WAVE check failed)")
        return false
      }

      // Search for 'fmt ' and 'data' chunks
      var fmtChunkFound = false
      var dataChunkFound = false

      val chunkHeader = ByteArray(8) // For Chunk ID (4 bytes) and Chunk Size (4 bytes)

      while (fis.available() > 0) {
        bytesRead = fis.read(chunkHeader, 0, 8)
        if (bytesRead < 8) {
          Log.e(TAG_MH, "Reached EOF or error while reading chunk header.")
          break
        }

        val chunkId = String(chunkHeader, 0, 4)
        val chunkSize = ((chunkHeader[7].toInt() and 0xFF) shl 24) or
                        ((chunkHeader[6].toInt() and 0xFF) shl 16) or
                        ((chunkHeader[5].toInt() and 0xFF) shl 8) or
                        (chunkHeader[4].toInt() and 0xFF)

        if (chunkId == "fmt ") {
          fmtChunkFound = true
          val fmtChunkData = ByteArray(chunkSize.toInt())
          if (fis.read(fmtChunkData, 0, chunkSize.toInt()) != chunkSize.toInt()) {
            Log.e(TAG_MH, "Could not read 'fmt ' chunk data.")
            return false
          }

          val audioFormatCode = ((fmtChunkData[1].toInt() and 0xFF) shl 8) or (fmtChunkData[0].toInt() and 0xFF)
          if (audioFormatCode != 1) { // PCM = 1
            Log.e(TAG_MH, "Unsupported WAV audio format (not PCM): $audioFormatCode")
            return false
          }

          val numChannels = ((fmtChunkData[3].toInt() and 0xFF) shl 8) or (fmtChunkData[2].toInt() and 0xFF)
          channelConfig = if (numChannels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO

          sampleRateInHz = ((fmtChunkData[7].toInt() and 0xFF) shl 24) or
                           ((fmtChunkData[6].toInt() and 0xFF) shl 16) or
                           ((fmtChunkData[5].toInt() and 0xFF) shl 8) or
                           (fmtChunkData[4].toInt() and 0xFF)

          val bitsPerSample = ((fmtChunkData[15].toInt() and 0xFF) shl 8) or (fmtChunkData[14].toInt() and 0xFF)
          audioEncoding = when (bitsPerSample) {
            8 -> AudioFormat.ENCODING_PCM_8BIT
            16 -> AudioFormat.ENCODING_PCM_16BIT
            else -> {
              Log.e(TAG_MH, "Unsupported WAV bit depth: $bitsPerSample")
              return false
            }
          }
        } else if (chunkId == "data") {
          dataChunkFound = true
          // pcmDataSize = chunkSize // Size of the data chunk
          dataOffset = fis.channel.position() // Current position is the start of PCM data
          // We don't need to read the data chunk itself here, just note its position and size.
          // The playback loop will read from dataOffset up to pcmDataSize or EOF.
          Log.i(TAG_MH, "Found 'data' chunk. Size: $chunkSize, Offset: $dataOffset")
          break // Found data chunk, header parsing is mostly done for playback parameters
        } else {
          // Skip other chunks
          if (fis.skip(chunkSize.toLong()).toInt() != chunkSize) {
            Log.e(TAG_MH, "Error skipping chunk: $chunkId")
            return false
          }
        }
      }

      if (!fmtChunkFound) {
        Log.e(TAG_MH, "'fmt ' chunk not found in WAV file.")
        return false
      }
      if (!dataChunkFound) {
        Log.e(TAG_MH, "'data' chunk not found in WAV file.")
        return false
      }

      Log.i(TAG_MH, "WAV Header Parsed: SR=$sampleRateInHz, Channels=${if(channelConfig == AudioFormat.CHANNEL_OUT_MONO) 1 else 2}, Encoding=$audioEncoding, DataOffset=$dataOffset")
      return true

    } catch (e: IOException) {
      Log.e(TAG_MH, "IOException during WAV header parsing: ${e.message}", e)
      return false
    }
  }

  suspend fun startPlaying(fileName: String) {
    if (isActuallyPlaying) {
      Log.w(TAG_MH, "Playback is already in progress.")
      return
    }
    stopPlayingInternally(false) // Release any existing resources

    withContext(Dispatchers.Default) {
      var tempStream: FileInputStream? = null
      try {
        tempStream = FileInputStream(fileName)
        if (!parseWavHeader(tempStream)) {
          Log.e(TAG_MH, "Failed to parse WAV header from $fileName.")
          return@withContext
        }
        // parseWavHeader sets dataOffset. We need to re-open/seek to that offset for actual playback.
        tempStream.close() // Close stream used for parsing
        
        stream = FileInputStream(fileName) // Re-open for data reading
        if (stream!!.skip(dataOffset) != dataOffset) {
          Log.e(TAG_MH, "Could not skip to data offset ($dataOffset) in WAV file.")
          return@withContext
        }

        val minBufferSize = AudioTrack.getMinBufferSize(sampleRateInHz, channelConfig, audioEncoding)
        if (minBufferSize == AudioTrack.ERROR_BAD_VALUE || minBufferSize == AudioTrack.ERROR) {
          Log.e(TAG_MH, "Invalid parameters for AudioTrack or unable to get min buffer size. SR:$sampleRateInHz, ChCfg:$channelConfig, Enc:$audioEncoding")
          return@withContext
        }
        val actualBufferSize = if (minBufferSize <= 0) 4096 else minBufferSize
        Log.d(TAG_MH, "Using actual buffer size: $actualBufferSize")

        audioTrack = AudioTrack.Builder()
          .setAudioAttributes(
            AudioAttributes.Builder()
              .setUsage(AudioAttributes.USAGE_MEDIA)
              .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
              .build()
          )
          .setAudioFormat(
            AudioFormat.Builder()
              .setEncoding(audioEncoding)
              .setSampleRate(sampleRateInHz)
              .setChannelMask(channelConfig)
              .build()
          )
          .setBufferSizeInBytes(actualBufferSize)
          .setTransferMode(AudioTrack.MODE_STREAM)
          .build()

        if (audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
          Log.e(TAG_MH, "AudioTrack initialization failed. State: ${audioTrack?.state}")
          return@withContext // Cleanup will be done in finally block
        }

        isActuallyPlaying = true
        audioTrack?.play()
        Log.i(TAG_MH, "AudioTrack playback started for $fileName.")

        playbackJob = CoroutineScope(Dispatchers.Default).launch {
          val buffer = ByteArray(actualBufferSize)
          var bytesRead: Int
          var bytesRecord: Int = 0
          var tmpRecord: Int = 0
          var tmpBytesRead: Int = 0
          try {
            while (isActive && isActuallyPlaying) {
              if (mediaRecordHandler.isRecording()) {
                // wait for recording actually write record file
                while (bytesRecord <= tmpRecord) {
                  bytesRecord = mediaRecordHandler.getTotalAudioLen().toInt()
                }
                tmpBytesRead = bytesRecord - tmpRecord
                //Log.d(TAG_MH, "Actually written: $tmpBytesRead")
                tmpRecord = bytesRecord
                bytesRead = stream?.read(buffer, 0, tmpBytesRead) ?: -1
              } else {
                bytesRead = stream?.read(buffer, 0, buffer.size) ?: -1
              }
              //Log.d(TAG_MH, "Read from file. bytesRead: $bytesRead")
              if (bytesRead <= 0) {
                Log.i(TAG_MH, "End of stream or read error. bytes read: $bytesRead")
                bytesRead = -1
                break
              }

              val bytesWritten = audioTrack?.write(buffer, 0, bytesRead)
              if (bytesWritten != null && bytesWritten < 0) {
                Log.e(TAG_MH, "Error writing to AudioTrack: $bytesWritten")
                break
              }
            }
          } catch (e: Exception) {
            Log.e(TAG_MH, "Error during playback loop: ${e.message}", e)
          } finally {
            Log.d(TAG_MH, "Playback loop finished. isActive: $isActive, isActuallyPlaying: $isActuallyPlaying")
            // This internal stop will handle UI update for isActuallyPlaying and resource cleanup
            stopPlayingInternally(false) // Called from within the job, so don't wait for job
          }
        }

      } catch (e: FileNotFoundException) {
        Log.e(TAG_MH, "File not found: $fileName", e)
        isActuallyPlaying = false
      } catch (e: IOException) {
        Log.e(TAG_MH, "IOException during AudioTrack setup: ${e.message}", e)
        isActuallyPlaying = false
      } catch (e: IllegalStateException) {
        Log.e(TAG_MH, "IllegalStateException during AudioTrack setup: ${e.message}", e)
        isActuallyPlaying = false
      } catch(e: Exception) {
        Log.e(TAG_MH, "Unexpected error in startPlaying for $fileName: ${e.message}", e)
        isActuallyPlaying = false
      } finally {
        // Ensure cleanup if startPlaying fails before job is launched or if any exception occurs here
        if (!isActuallyPlaying || playbackJob == null || playbackJob?.isActive == false) {
          tempStream?.close() // Close the temporary stream if it was opened
          stopPlayingInternally(false) // General cleanup if not already handled by job
        }
      }
    }
  }

  fun requestStopPlaying() {
    Log.d(TAG_MH, "requestStopPlaying called. Current state: isActuallyPlaying=$isActuallyPlaying, Job active=${playbackJob?.isActive}")
    if (!isActuallyPlaying && (playbackJob == null || playbackJob?.isCompleted == true || playbackJob?.isCancelled == true) ) {
      Log.w(TAG_MH, "Playback not active or job already finished.")
      // Attempt cleanup just in case, though stopPlayingInternally in job's finally should handle it
      CoroutineScope(Dispatchers.Default).launch { stopPlayingInternally(false) }
      return
    }
    isActuallyPlaying = false // Signal the loop to stop
    playbackJob?.cancel() // Cancel the job. Its finally block calls stopPlayingInternally.
    // If job is null or already done, explicit cleanup might be needed if something went wrong.
    if (playbackJob == null || playbackJob?.isActive == false) {
        CoroutineScope(Dispatchers.Default).launch { stopPlayingInternally(false) }
    }
  }

  private fun stopPlayingInternally(waitForJob: Boolean) {
    Log.d(TAG_MH, "stopPlayingInternally called. waitForJob: $waitForJob. AudioTrack PlayState: ${audioTrack?.playState}")
    
    // If this is called from the job's finally block, waitForJob should be false.
    // If called externally (e.g. from requestStopPlaying before job cancellation), it might be true.
    // However, playbackJob?.cancel() is now preferred, and this function is more of a cleanup utility.

    isActuallyPlaying = false // Crucial state update

    // Job cancellation logic is now primarily in requestStopPlaying
    // If waitForJob is true here, it implies an external call wanting to ensure job finishes.
    // This part is tricky. For now, assume job is cancelled by caller if needed.

    audioTrack?.let {
      try {
        if (it.playState == AudioTrack.PLAYSTATE_PLAYING) {
          it.pause() // Pause before stop can be safer
        }
        // Checking state before stop might be needed if stop() is called multiple times
        if (it.playState != AudioTrack.PLAYSTATE_STOPPED) {
           it.stop()
        } else {
          // Do nothing if already stopped
        }
      } catch (e: IllegalStateException) {
        Log.e(TAG_MH, "IllegalStateException on audioTrack.stop/pause: ${e.message}")
      } finally {
        it.release() // Always release
        Log.i(TAG_MH, "AudioTrack released.")
      }
    }
    audioTrack = null

    try {
      stream?.close()
      Log.i(TAG_MH, "FileInputStream closed.")
    } catch (e: IOException) {
      Log.e(TAG_MH, "IOException on stream.close(): ${e.message}")
    }
    stream = null
    
    // Reset WAV header properties for next playback
    sampleRateInHz = 0
    channelConfig = AudioFormat.CHANNEL_OUT_MONO
    audioEncoding = AudioFormat.ENCODING_PCM_16BIT
    dataOffset = 0
    Log.d(TAG_MH, "stopPlayingInternally finished.")
  }

  fun isPlaying(): Boolean {
    return isActuallyPlaying && audioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING
  }

  fun pausePlaying() {
    if (isActuallyPlaying && audioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING) {
      try {
        audioTrack?.pause()
        // Consider if isActuallyPlaying should be set to false on pause, or use a separate isPaused state.
        Log.i(TAG_MH, "AudioTrack paused.")
      } catch (e: IllegalStateException) {
         Log.e(TAG_MH, "IllegalStateException on audioTrack.pause(): ${e.message}")
      }
    }
  }

  fun release() {
    Log.d(TAG_MH, "MediaPlayHandler release() called.")
    requestStopPlaying() // This will trigger cleanup via stopPlayingInternally
  }
}
