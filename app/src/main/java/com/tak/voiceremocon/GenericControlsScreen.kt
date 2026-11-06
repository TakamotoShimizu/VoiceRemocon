package com.tak.voiceremocon

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tak.voiceremocon.ui.theme.VoiceRemoconTheme

private const val MAX_AMPLITUDE = 32767

@Immutable
data class GenericControlsUiState(
  val amplitude: Int = 0
)

internal class GenericControlsAmplitudeState {
  private val mutableAmplitude = mutableIntStateOf(0)
  val amplitude: State<Int>
    get() = mutableAmplitude
  private val handler = Handler(Looper.getMainLooper())
  private var updateRunnable: Runnable? = null

  fun update(amplitude: Int) {
    mutableAmplitude.intValue = amplitude.coerceIn(0, MAX_AMPLITUDE)
  }

  fun start(getAmplitude: () -> Int) {
    stopPolling()
    val runnable = object : Runnable {
      override fun run() {
        update(getAmplitude())
        handler.postDelayed(this, VoiceRemocon.UPDATE_INTERVAL_MS)
      }
    }
    updateRunnable = runnable
    handler.post(runnable)
  }

  fun stop() {
    stopPolling()
    update(0)
  }

  private fun stopPolling() {
    updateRunnable?.let(handler::removeCallbacks)
    updateRunnable = null
  }
}

@Composable
fun GenericControlsScreen(
  state: GenericControlsUiState,
  onRecord: () -> Unit,
  onStop: () -> Unit,
  onPlay: () -> Unit,
  onPause: () -> Unit,
  onStart: () -> Unit,
  onDelete: () -> Unit,
  modifier: Modifier = Modifier
) {
  Column(modifier = modifier.fillMaxSize()) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(start = 16.dp, top = 40.dp, end = 16.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically
    ) {
      Button(onClick = onRecord, modifier = Modifier.testTag("record_button")) {
        Text(stringResource(R.string.record_button_text))
      }
      Button(onClick = onStop, modifier = Modifier.testTag("stop_button")) {
        Text(stringResource(R.string.stop_button_text))
      }
      Button(onClick = onPlay, modifier = Modifier.testTag("play_button")) {
        Text(stringResource(R.string.playback_button_text))
      }
      Button(onClick = onPause, modifier = Modifier.testTag("pause_button")) {
        Text(stringResource(R.string.pause_button_text))
      }
      Button(
        onClick = onStart,
        modifier = Modifier.testTag("start_button"),
        colors = ButtonDefaults.buttonColors(containerColor = colorResource(R.color.vermilion))
      ) {
        Text(stringResource(R.string.start_button_text))
      }
    }

    Button(
      onClick = onDelete,
      modifier = Modifier
        .align(Alignment.CenterHorizontally)
        .padding(top = 16.dp)
        .testTag("delete_button")
    ) {
      Text(stringResource(R.string.delete_button_text))
    }

    LinearProgressIndicator(
      progress = state.amplitude.toFloat() / MAX_AMPLITUDE,
      modifier = Modifier
        .fillMaxWidth()
        .padding(start = 16.dp, top = 8.dp, end = 16.dp)
        .testTag("audio_level_progressbar")
    )

    Text(
      text = stringResource(R.string.amplitude, state.amplitude),
      modifier = Modifier
        .align(Alignment.CenterHorizontally)
        .padding(top = 8.dp)
        .testTag("amplitude_textview"),
      fontSize = 16.sp
    )
  }
}

@Preview(showBackground = true)
@Composable
private fun GenericControlsScreenPreview() {
  VoiceRemoconTheme {
    GenericControlsScreen(
      state = GenericControlsUiState(amplitude = 8600),
      onRecord = {},
      onStop = {},
      onPlay = {},
      onPause = {},
      onStart = {},
      onDelete = {}
    )
  }
}
