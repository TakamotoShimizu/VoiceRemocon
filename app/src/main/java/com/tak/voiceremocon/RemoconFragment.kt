package com.tak.voiceremocon

import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

private const val TAG_REMOCON = "RemoconFragment"
private const val PREFS_REMOCON = "remocon_settings"
private const val PREF_HOST = "host"
private const val PREF_PORT = "port"

class RemoconFragment : Fragment() {
  private lateinit var startButton: Button
  private lateinit var stopButton: Button
  private lateinit var quitButton: Button
  private lateinit var settingsButton: Button
  private lateinit var sendButton: Button
  private lateinit var resultText: TextView
  private lateinit var statusText: TextView
  private lateinit var progressBar: ProgressBar
  private lateinit var amplitudeText: TextView
  private lateinit var commandInput: EditText
  private var modelReady = false
  private var recognizing = false
  private var amplitudeUpdatesStarted = false
  private var lastAmplitudeTextUpdateAt = 0L
  private val amplitudeHandler = Handler(Looper.getMainLooper())
  private val amplitudeUpdater = object : Runnable {
    override fun run() {
      if (!amplitudeUpdatesStarted) return
      val host = activity as? VoiceRemocon ?: return
      val amplitude = host.mediaRecogniseHandler.getMaxAmplitude()
      progressBar.progress = amplitude
      val now = SystemClock.elapsedRealtime()
      if (now - lastAmplitudeTextUpdateAt >= 1000L) {
        amplitudeText.text = getString(R.string.amplitude, amplitude)
        lastAmplitudeTextUpdateAt = now
      }
      amplitudeHandler.postDelayed(this, 50L)
    }
  }

  /** Remocon画面のXMLレイアウトを生成します。 */
  override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View =
    inflater.inflate(R.layout.fragment_remocon, container, false)

  /**
   * 画面のViewを初期化し、音声認識の状態監視と各操作ボタンの処理を設定します。
   */
  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)
    startButton = view.findViewById(R.id.start_remocon_button)
    stopButton = view.findViewById(R.id.stop_remocon_button)
    quitButton = view.findViewById(R.id.quit_remocon_button)
    settingsButton = view.findViewById(R.id.remocon_settings_button)
    sendButton = view.findViewById(R.id.remocon_send_button)
    resultText = view.findViewById(R.id.remocon_result_text)
    statusText = view.findViewById(R.id.remocon_status_text)
    progressBar = view.findViewById(R.id.remocon_audio_level)
    amplitudeText = view.findViewById(R.id.remocon_amplitude_text)
    commandInput = view.findViewById(R.id.remocon_command_input)

    val host = activity as? VoiceRemocon
    if (host == null) {
      statusText.setText(R.string.remocon_activity_error)
      return
    }

    host.mediaRecogniseViewModel?.recognitionText?.observe(viewLifecycleOwner) { value ->
      when {
        value.startsWith("VOSK model ready") -> {
          modelReady = true
          statusText.setText(R.string.remocon_ready)
          updateControls()
        }
        value.startsWith("Partial: ") -> {
          resultText.text = value.removePrefix("Partial: ")
          statusText.setText(R.string.remocon_listening)
        }
        value.startsWith("Final: ") -> {
          val recognized = value.removePrefix("Final: ").trim()
          resultText.text = recognized
          commandInput.setText(commandFromSpeech(recognized))
          recognizing = false
          stopAmplitudeUpdates()
          statusText.setText(R.string.remocon_result_received)
          updateControls()
        }
        value.startsWith("Error: ") -> {
          statusText.text = value.removePrefix("Error: ")
          recognizing = false
          stopAmplitudeUpdates()
          updateControls()
        }
        value.startsWith("Recognition finished") -> {
          recognizing = false
          stopAmplitudeUpdates()
          statusText.setText(R.string.remocon_ready)
          updateControls()
        }
        else -> if (recognizing && value.isNotBlank()) {
          // Final events are published as raw text by MediaRecogniseViewModel.
          showRecognizedCommand(value.trim())
        }
      }
    }

    startButton.setOnClickListener {
      if (!modelReady) {
        statusText.setText(R.string.remocon_model_loading)
        return@setOnClickListener
      }
      host.onRecognise(true, null, null)
      startAmplitudeUpdates()
      recognizing = true
      resultText.setText(R.string.remocon_listening)
      statusText.setText(R.string.remocon_listening)
      updateControls()
    }
    stopButton.setOnClickListener {
      host.onRecognise(false, null, null)
      stopAmplitudeUpdates()
      recognizing = false
      statusText.setText(R.string.remocon_ready)
      updateControls()
    }
    settingsButton.setOnClickListener { showSettingsDialog() }
    sendButton.setOnClickListener { sendCommand() }
    quitButton.setOnClickListener { quitRemote() }
    commandInput.addTextChangedListener(object : TextWatcher {
      override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
      override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = updateControls()
      override fun afterTextChanged(s: Editable?) = Unit
    })

    modelReady = host.mediaRecogniseViewModel?.recognitionText?.value
      ?.startsWith("VOSK model ready") == true
    statusText.setText(
      if (modelReady) R.string.remocon_ready else R.string.remocon_model_loading
    )
    updateControls()
  }

  /** 画面復帰時に認識状態と操作ボタンの有効状態を同期します。 */
  override fun onResume() {
    super.onResume()
    val host = activity as? VoiceRemocon ?: return
    recognizing = host.mediaRecogniseViewModel?.isRecognising() == true
    if (recognizing) {
      startAmplitudeUpdates()
    }
    updateControls()
  }

  /** 画面が見えなくなるとき、振幅更新を停止してView更新を避けます。 */
  override fun onPause() {
    if (amplitudeUpdatesStarted) {
      stopAmplitudeUpdates(reset = false)
    }
    super.onPause()
  }

  /** 認識中の最大振幅を定期取得し、進捗バーと振幅表示へ反映します。 */
  private fun startAmplitudeUpdates() {
    if (amplitudeUpdatesStarted) return
    amplitudeUpdatesStarted = true
    lastAmplitudeTextUpdateAt = 0L
    amplitudeHandler.post(amplitudeUpdater)
  }

  /** 振幅の定期更新を停止し、必要に応じて表示を初期値へ戻します。 */
  private fun stopAmplitudeUpdates(reset: Boolean = true) {
    if (!amplitudeUpdatesStarted) return
    amplitudeHandler.removeCallbacks(amplitudeUpdater)
    amplitudeUpdatesStarted = false
    if (reset) {
      progressBar.progress = 0
      amplitudeText.setText(R.string.amplitude_zero)
    }
  }

  /** 認識結果を画面に表示し、入力欄を送信形式へ変換します。 */
  private fun showRecognizedCommand(text: String) {
    resultText.text = text
    commandInput.setText(commandFromSpeech(text))
    statusText.setText(R.string.remocon_result_received)
    recognizing = false
    stopAmplitudeUpdates()
    updateControls()
  }

  /** 入力コマンドの妥当性と認識状態から各ボタンの有効状態を更新します。 */
  private fun updateControls() {
    if (!::startButton.isInitialized) return
    val valid = RemoteCommand.parse(commandInput.text.toString()) != null
    startButton.isEnabled = modelReady && !recognizing
    stopButton.isEnabled = modelReady && recognizing
    sendButton.isEnabled = valid && !recognizing
    quitButton.isEnabled = !recognizing
  }

  /** リモコンの接続先ホストとポートを編集するダイアログを表示します。 */
  private fun showSettingsDialog() {
    val prefs = requireContext().getSharedPreferences(PREFS_REMOCON, Context.MODE_PRIVATE)
    val hostField = EditText(requireContext()).apply {
      hint = getString(R.string.remocon_host_hint)
      inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
      setText(prefs.getString(PREF_HOST, "").orEmpty())
      isSingleLine = true
    }
    val portField = EditText(requireContext()).apply {
      hint = getString(R.string.remocon_port_hint)
      inputType = InputType.TYPE_CLASS_NUMBER
      setText(prefs.getInt(PREF_PORT, 0).takeIf { it > 0 }?.toString().orEmpty())
      isSingleLine = true
    }
    val form = LinearLayout(requireContext()).apply {
      orientation = LinearLayout.VERTICAL
      val padding = (20 * resources.displayMetrics.density).toInt()
      setPadding(padding, padding / 2, padding, 0)
      addView(hostField)
      addView(portField)
    }
    AlertDialog.Builder(requireContext())
      .setTitle(R.string.remocon_settings)
      .setMessage(R.string.remocon_settings_message)
      .setView(form)
      .setNegativeButton(android.R.string.cancel, null)
      .setPositiveButton(android.R.string.ok) { _, _ ->
        val address = hostField.text.toString().trim()
        val port = portField.text.toString().toIntOrNull()
        if (address.isBlank() || port == null || port !in 1..65535) {
          Toast.makeText(requireContext(), R.string.remocon_invalid_settings, Toast.LENGTH_SHORT).show()
        } else {
          prefs.edit().putString(PREF_HOST, address).putInt(PREF_PORT, port).apply()
          statusText.setText(R.string.remocon_ready)
        }
      }
      .show()
  }

  /** 入力コマンドと保存済み接続先を検証し、TCP送信結果を画面に反映します。 */
  private fun sendCommand() {
    val command = RemoteCommand.parse(commandInput.text.toString())
    if (command == null) {
      Toast.makeText(requireContext(), R.string.remocon_invalid_command, Toast.LENGTH_SHORT).show()
      return
    }
    val prefs = requireContext().getSharedPreferences(PREFS_REMOCON, Context.MODE_PRIVATE)
    val address = prefs.getString(PREF_HOST, "").orEmpty()
    val port = prefs.getInt(PREF_PORT, 0)
    if (address.isBlank() || port !in 1..65535) {
      showSettingsDialog()
      return
    }
    sendButton.isEnabled = false
    statusText.setText(R.string.remocon_sending)
    viewLifecycleOwner.lifecycleScope.launch {
      val response = withContext(Dispatchers.IO) { sendTcpCommand(address, port, command.wireText) }
      if (!isAdded) return@launch
      when (response) {
        is SendResult.Ack -> statusText.setText(R.string.remocon_ack)
        is SendResult.Nack -> statusText.text = getString(R.string.remocon_nack, response.message)
        is SendResult.Error -> statusText.text = getString(R.string.remocon_network_error, response.message)
      }
      updateControls()
    }
  }

  /** 学習リモコン部へquitコマンドを送り、ACKを画面に表示します。 */
  private fun quitRemote() {
    val prefs = requireContext().getSharedPreferences(PREFS_REMOCON, Context.MODE_PRIVATE)
    val address = prefs.getString(PREF_HOST, "").orEmpty()
    val port = prefs.getInt(PREF_PORT, 0)
    if (address.isBlank() || port !in 1..65535) {
      showSettingsDialog()
      return
    }
    quitButton.isEnabled = false
    statusText.setText(R.string.remocon_quitting)
    viewLifecycleOwner.lifecycleScope.launch {
      val response = withContext(Dispatchers.IO) { sendTcpCommand(address, port, "quit") }
      if (!isAdded) return@launch
      when (response) {
        is SendResult.Ack -> statusText.setText(R.string.remocon_quit_ack)
        is SendResult.Nack -> statusText.text = getString(R.string.remocon_nack, response.message)
        is SendResult.Error -> statusText.text = getString(R.string.remocon_network_error, response.message)
      }
      updateControls()
    }
  }

  /** 音声認識結果を送信コマンド形式に整えます。 */
  private fun commandFromSpeech(text: String): String {
    val phrase = text.trim()
    if (phrase.startsWith("send ", ignoreCase = true)) return phrase
    if (phrase.startsWith("SONY ", ignoreCase = true) ||
      phrase.startsWith("OTHERS ", ignoreCase = true)
    ) return "send $phrase"
    return ""
  }

  /** IOスレッドからリモコンへコマンドを送り、ACK/NACKまたは通信エラーを返します。 */
  private fun sendTcpCommand(host: String, port: Int, command: String): SendResult =
    try {
      Socket().use { socket ->
        socket.connect(InetSocketAddress(host, port), 3000)
        socket.soTimeout = 3000
        socket.getOutputStream().bufferedWriter(Charsets.UTF_8).use { writer ->
          writer.write(command)
          writer.newLine()
          writer.flush()
        }
        val response = socket.getInputStream().bufferedReader(Charsets.UTF_8).readLine()
        when {
          response == null -> SendResult.Error("Empty response")
          response.trim().lowercase() == "ack" ||
            response.trim().lowercase() == "acknowledge" -> SendResult.Ack
          response.trim().lowercase().startsWith("nack") ||
            response.trim().lowercase().startsWith("notack") -> SendResult.Nack(response)
          else -> SendResult.Error("Unexpected response: $response")
        }
      }
    } catch (error: Exception) {
      Log.e(TAG_REMOCON, "Command send failed", error)
      SendResult.Error(error.message ?: error.javaClass.simpleName)
    }

  private data class RemoteCommand(val wireText: String) {
    companion object {
      /** 入力文字列を検証し、機器へ送信する正規化済みコマンドを作成します。 */
      fun parse(input: String): RemoteCommand? {
        val parts = input.trim().split(Regex("\\s+"))
        if (parts.size != 5 || !parts[0].equals("send", ignoreCase = true)) return null
        if (!parts[1].equals("SONY", ignoreCase = true) &&
          !parts[1].equals("OTHERS", ignoreCase = true)
        ) return null
        val repeat = parts[3].toIntOrNull() ?: return null
        val gap = parts[4].toIntOrNull() ?: return null
        if (repeat !in 1..20 || gap !in 20..2000 || parts[2].isBlank()) return null
        return RemoteCommand("send ${parts[1].uppercase()} ${parts[2]} $repeat $gap")
      }
    }
  }

  private sealed interface SendResult {
    data object Ack : SendResult
    data class Nack(val message: String) : SendResult
    data class Error(val message: String) : SendResult
  }
}
