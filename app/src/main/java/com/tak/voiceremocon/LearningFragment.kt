package com.tak.voiceremocon

import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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

private const val LEARNING_PREFS = "remocon_settings"
private const val LEARNING_HOST = "host"
private const val LEARNING_PORT = "port"
private const val LEARNING_TIMEOUT_SECONDS = 60

class LearningFragment : Fragment() {
  private lateinit var messageText: TextView
  private lateinit var resultText: TextView
  private lateinit var functionNameInput: EditText
  private lateinit var formatInput: EditText
  private lateinit var levelBar: ProgressBar
  private lateinit var startButton: Button
  private lateinit var retryButton: Button
  private lateinit var okButton: Button
  private var learningComplete = false
  private var recognizing = false
  private var busy = false
  private var amplitudeUpdatesStarted = false
  private val amplitudeHandler = Handler(Looper.getMainLooper())
  private val amplitudeUpdater = object : Runnable {
    override fun run() {
      if (!amplitudeUpdatesStarted) return
      val host = activity as? VoiceRemocon ?: return
      levelBar.progress = host.mediaRecogniseHandler.getMaxAmplitude()
      amplitudeHandler.postDelayed(this, 50L)
    }
  }

  /** 学習画面のView階層を生成します。 */
  override fun onCreateView(
    inflater: LayoutInflater,
    container: ViewGroup?,
    savedInstanceState: Bundle?
  ): View = createContentView()

  /** 各UI操作を設定し、音声認識結果を画面状態へ反映します。 */
  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)
    val host = activity as? VoiceRemocon ?: run {
      messageText.text = "VoiceRemocon activity is unavailable."
      return
    }

    host.mediaRecogniseViewModel?.recognitionText?.observe(viewLifecycleOwner) { value ->
      when {
        value.startsWith("Partial: ") -> resultText.text = value.removePrefix("Partial: ")
        value.startsWith("Final: ") -> {
          resultText.text = value.removePrefix("Final: ").trim()
          recognizing = false
          stopAmplitudeUpdates()
          updateControls()
        }
        value.startsWith("Error: ") -> {
          messageText.text = value.removePrefix("Error: ")
          recognizing = false
          stopAmplitudeUpdates()
          updateControls()
        }
        value.startsWith("Recognition finished") -> {
          recognizing = false
          stopAmplitudeUpdates()
          updateControls()
        }
        else -> if (recognizing && value.isNotBlank()) {
          resultText.text = value.trim()
          recognizing = false
          stopAmplitudeUpdates()
          updateControls()
        }
      }
    }

    startButton.setOnClickListener { startLearning() }
    retryButton.setOnClickListener { startVoiceRecognition() }
    okButton.setOnClickListener { storeLearnedCode() }
    view.findViewWithTag<Button>("learning_settings").setOnClickListener { showSettingsDialog() }
    functionNameInput.addTextChangedListener(SimpleTextWatcher { updateControls() })
    formatInput.addTextChangedListener(SimpleTextWatcher { updateControls() })
    updateControls()
  }

  /** 画面が非表示になる際に、音量メーターの定期更新を止めます。 */
  override fun onPause() {
    stopAmplitudeUpdates(reset = false)
    super.onPause()
  }

  /** View破棄時に音声認識と音量更新を停止します。 */
  override fun onDestroyView() {
    (activity as? VoiceRemocon)?.mediaRecogniseViewModel?.stopRecognition()
    recognizing = false
    stopAmplitudeUpdates()
    super.onDestroyView()
  }

  /** 学習メッセージ、認識結果、入力欄、各操作ボタンを配置します。 */
  private fun createContentView(): View {
    val context = requireContext()
    val padding = (20 * resources.displayMetrics.density).toInt()
    val column = LinearLayout(context).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(padding, padding, padding, padding)
    }
    messageText = TextView(context).apply { text = "学習を開始してください。" }
    resultText = TextView(context).apply { text = "" }
    levelBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
      max = 32767
    }
    formatInput = EditText(context).apply {
      hint = "Format: SONY or OTHERS"
      setText("SONY")
      isSingleLine = true
      inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
    }
    functionNameInput = EditText(context).apply {
      hint = "登録する機能名"
      isSingleLine = true
    }
    startButton = Button(context).apply { text = "Start" }
    retryButton = Button(context).apply { text = "Re-Try" }
    okButton = Button(context).apply { text = "OK" }
    val settingsButton = Button(context).apply {
      text = "⋮"
      tag = "learning_settings"
    }
    listOf(
      messageText, levelBar, resultText,
      formatInput, functionNameInput,
      startButton, retryButton, okButton, settingsButton
    ).forEach(column::addView)
    return column
  }

  /** 接続先へ学習開始要求を送り、学習完了またはタイムアウトを待ちます。 */
  private fun startLearning() {
    if (busy) return
    val (host, port) = configuredEndpoint() ?: run {
      showSettingsDialog()
      return
    }
    busy = true
    learningComplete = false
    messageText.text = "学習させるリモコンのボタンを押してください。"
    resultText.text = ""
    updateControls()
    viewLifecycleOwner.lifecycleScope.launch {
      val response = withContext(Dispatchers.IO) { waitForLearning(host, port) }
      busy = false
      when (response) {
        "LearningComplete" -> {
          learningComplete = true
          messageText.text = "対応するコマンドを話してください。"
          startVoiceRecognition()
        }
        "LearningTimedOut" -> messageText.text = "学習がタイムアウトしました。もう一度開始してください。"
        else -> messageText.text = "学習に失敗しました: $response"
      }
      updateControls()
    }
  }

  /** 学習完了後の音声認識を開始し、入力音量の表示を更新します。 */
  private fun startVoiceRecognition() {
    if (!learningComplete || busy || recognizing) return
    val host = activity as? VoiceRemocon ?: return
    resultText.text = ""
    messageText.text = "対応するコマンドを話してください。"
    recognizing = true
    host.onRecognise(true, null, null)
    startAmplitudeUpdates()
    updateControls()
  }

  /** 入力された形式と機能名で学習コードを登録し、応答を表示します。 */
  private fun storeLearnedCode() {
    if (!learningComplete || busy) return
    val format = formatInput.text.toString().trim().uppercase()
    val name = functionNameInput.text.toString().trim()
    if (format !in setOf("SONY", "OTHERS") || name.isBlank()) {
      messageText.text = "SONYまたはOTHERSと、登録する機能名を入力してください。"
      return
    }
    val endpoint = configuredEndpoint() ?: run {
      showSettingsDialog()
      return
    }
    busy = true
    messageText.text = "登録しています…"
    updateControls()
    viewLifecycleOwner.lifecycleScope.launch {
      val response = withContext(Dispatchers.IO) {
        exchange(endpoint.first, endpoint.second, "storeCode $format $name")
      }
      busy = false
      if (response.equals("Acknowledge", true) || response.equals("ACK", true)) {
        messageText.text = "登録が完了しました。"
        learningComplete = false
      } else {
        messageText.text = "登録に失敗しました: $response"
      }
      updateControls()
    }
  }

  /** 学習状態や入力内容に応じて各操作部品の有効状態を更新します。 */
  private fun updateControls() {
    if (!::startButton.isInitialized) return
    startButton.isEnabled = !busy && !learningComplete
    retryButton.isEnabled = !busy && learningComplete && !recognizing
    okButton.isEnabled = !busy && learningComplete && !recognizing &&
      functionNameInput.text.isNotBlank()
    formatInput.isEnabled = !busy
    functionNameInput.isEnabled = !busy
  }

  /** 50ミリ秒間隔の音量メーター更新を開始します。 */
  private fun startAmplitudeUpdates() {
    if (amplitudeUpdatesStarted) return
    amplitudeUpdatesStarted = true
    amplitudeHandler.post(amplitudeUpdater)
  }

  /** 音量メーター更新を停止し、必要なら値をゼロに戻します。 */
  private fun stopAmplitudeUpdates(reset: Boolean = true) {
    if (!amplitudeUpdatesStarted) return
    amplitudeHandler.removeCallbacks(amplitudeUpdater)
    amplitudeUpdatesStarted = false
    if (reset && ::levelBar.isInitialized) levelBar.progress = 0
  }

  /** SharedPreferencesから有効なホストとポートを取得します。 */
  private fun configuredEndpoint(): Pair<String, Int>? {
    val prefs = requireContext().getSharedPreferences(LEARNING_PREFS, Context.MODE_PRIVATE)
    val host = prefs.getString(LEARNING_HOST, "").orEmpty()
    val port = prefs.getInt(LEARNING_PORT, 0)
    return if (host.isNotBlank() && port in 1..65535) host to port else null
  }

  /** 学習リモコン部のホストとポートを編集・保存するダイアログを表示します。 */
  private fun showSettingsDialog() {
    val context = requireContext()
    val prefs = context.getSharedPreferences(LEARNING_PREFS, Context.MODE_PRIVATE)
    val hostInput = EditText(context).apply {
      hint = "IP address"
      inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
      setText(prefs.getString(LEARNING_HOST, "").orEmpty())
      isSingleLine = true
    }
    val portInput = EditText(context).apply {
      hint = "Port"
      inputType = InputType.TYPE_CLASS_NUMBER
      setText(prefs.getInt(LEARNING_PORT, 0).takeIf { it > 0 }?.toString().orEmpty())
      isSingleLine = true
    }
    val form = LinearLayout(context).apply {
      orientation = LinearLayout.VERTICAL
      addView(hostInput)
      addView(portInput)
      val padding = (16 * resources.displayMetrics.density).toInt()
      setPadding(padding, 0, padding, 0)
    }
    AlertDialog.Builder(context)
      .setTitle("学習リモコン部の接続設定")
      .setView(form)
      .setNegativeButton(android.R.string.cancel, null)
      .setPositiveButton(android.R.string.ok) { _, _ ->
        val host = hostInput.text.toString().trim()
        val port = portInput.text.toString().toIntOrNull()
        if (host.isBlank() || port == null || port !in 1..65535) {
          Toast.makeText(context, "IPアドレスと1〜65535のポートを入力してください。", Toast.LENGTH_SHORT).show()
        } else {
          prefs.edit().putString(LEARNING_HOST, host).putInt(LEARNING_PORT, port).apply()
          messageText.text = "接続設定を保存しました。"
        }
      }
      .show()
  }

  /** startLearningを送信し、WaitForLearning後の学習完了応答を待ちます。 */
  private fun waitForLearning(host: String, port: Int): String = try {
    Socket().use { socket ->
      socket.connect(InetSocketAddress(host, port), 3000)
      socket.soTimeout = 65_000
      val writer = socket.getOutputStream().bufferedWriter(Charsets.UTF_8)
      val reader = socket.getInputStream().bufferedReader(Charsets.UTF_8)
      writer.write("startLearning $LEARNING_TIMEOUT_SECONDS")
      writer.newLine()
      writer.flush()
      val waiting = reader.readLine() ?: return "Empty response"
      if (!waiting.equals("WaitForLearning", true)) return waiting
      reader.readLine() ?: "Empty response"
    }
  } catch (error: Exception) {
    error.message ?: error.javaClass.simpleName
  }

  /** 指定コマンドをTCP送信し、1行の応答を返します。 */
  private fun exchange(host: String, port: Int, command: String): String = try {
    Socket().use { socket ->
      socket.connect(InetSocketAddress(host, port), 3000)
      socket.soTimeout = 5000
      socket.getOutputStream().bufferedWriter(Charsets.UTF_8).use { writer ->
        writer.write(command)
        writer.newLine()
        writer.flush()
      }
      socket.getInputStream().bufferedReader(Charsets.UTF_8).readLine() ?: "Empty response"
    }
  } catch (error: Exception) {
    error.message ?: error.javaClass.simpleName
  }

  private class SimpleTextWatcher(private val changed: () -> Unit) : android.text.TextWatcher {
    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = changed()
    override fun afterTextChanged(s: android.text.Editable?) = Unit
  }
}
