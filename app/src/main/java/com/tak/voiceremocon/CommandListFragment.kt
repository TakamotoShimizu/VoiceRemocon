package com.tak.voiceremocon

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket

private const val COMMAND_LIST_PREFS = "remocon_settings"
private const val COMMAND_LIST_HOST = "host"
private const val COMMAND_LIST_PORT = "port"

class CommandListFragment : Fragment() {
  private lateinit var statusText: TextView
  private lateinit var commandsContainer: LinearLayout
  private lateinit var listButton: Button
  private var loading = false

  override fun onCreateView(
    inflater: LayoutInflater,
    container: ViewGroup?,
    savedInstanceState: Bundle?
  ): View = createContentView()

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)
    listButton.setOnClickListener { loadCommandList() }
  }

  /** 一覧取得操作と結果表示に使う画面を生成します。 */
  private fun createContentView(): View {
    val context = requireContext()
    val padding = (20 * resources.displayMetrics.density).toInt()
    val root = LinearLayout(context).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(padding, padding, padding, padding)
    }
    listButton = Button(context).apply { text = "List" }
    statusText = TextView(context).apply {
      text = "Listを押すと登録済みコマンドを取得します。"
      setPadding(0, padding / 2, 0, padding / 2)
    }
    commandsContainer = LinearLayout(context).apply {
      orientation = LinearLayout.VERTICAL
    }
    val scroll = ScrollView(context).apply { addView(commandsContainer) }
    root.addView(listButton)
    root.addView(statusText)
    root.addView(scroll, LinearLayout.LayoutParams(
      ViewGroup.LayoutParams.MATCH_PARENT,
      0,
      1f
    ))
    return root
  }

  /** 学習リモコン部へ一覧要求を送り、JSON応答を画面に表示します。 */
  private fun loadCommandList() {
    if (loading) return
    val prefs = requireContext().getSharedPreferences(COMMAND_LIST_PREFS, Context.MODE_PRIVATE)
    val host = prefs.getString(COMMAND_LIST_HOST, "").orEmpty()
    val port = prefs.getInt(COMMAND_LIST_PORT, 0)
    if (host.isBlank() || port !in 1..65535) {
      statusText.text = "先にRemoconまたはLearning画面で接続先を設定してください。"
      return
    }

    loading = true
    listButton.isEnabled = false
    statusText.text = "コマンド一覧を取得しています…"
    commandsContainer.removeAllViews()
    viewLifecycleOwner.lifecycleScope.launch {
      val response = withContext(Dispatchers.IO) { requestCommandList(host, port) }
      loading = false
      listButton.isEnabled = true
      if (!isAdded) return@launch
      if (response.startsWith("ERROR:")) {
        statusText.text = "取得に失敗しました: ${response.removePrefix("ERROR:")}"
        return@launch
      }
      try {
        val entries = parseCommandList(response)
        statusText.text = "登録済みコマンド: ${entries.size}件"
        if (entries.isEmpty()) {
          addEntry("登録済みコマンドはありません。")
        } else {
          entries.forEach { (format, name) -> addEntry("$format    $name") }
        }
      } catch (error: Exception) {
        statusText.text = "JSON応答を読み取れませんでした: ${error.message ?: "形式エラー"}"
      }
    }
  }

  /** getCommandListコマンドをTCP送信し、1行の応答を返します。 */
  private fun requestCommandList(host: String, port: Int): String = try {
    Socket().use { socket ->
      socket.connect(InetSocketAddress(host, port), 3000)
      socket.soTimeout = 5000
      val writer = socket.getOutputStream().bufferedWriter(Charsets.UTF_8)
      writer.write("getCommandList")
      writer.newLine()
      writer.flush()
      socket.getInputStream().bufferedReader(Charsets.UTF_8).readLine()
        ?: "ERROR: 空の応答"
    }
  } catch (error: Exception) {
    "ERROR: ${error.message ?: error.javaClass.simpleName}"
  }

  /** JSON配列、commands配列、または単一オブジェクトから形式名と機能名を抽出します。 */
  private fun parseCommandList(json: String): List<Pair<String, String>> {
    val trimmed = json.trim()
    val records = when {
      trimmed.startsWith("[") -> JSONArray(trimmed)
      trimmed.startsWith("{") -> {
        val root = JSONObject(trimmed)
        val array = root.optJSONArray("commands")
          ?: root.optJSONArray("CommandList")
          ?: root.optJSONArray("commandList")
        array ?: JSONArray().put(root)
      }
      else -> throw IllegalArgumentException("JSON object or array expected")
    }
    return buildList {
      for (index in 0 until records.length()) {
        val record = records.optJSONObject(index) ?: continue
        val format = record.optStringIgnoreCase("Format")
        val name = record.optStringIgnoreCase("Name")
        if (format.isNotBlank() && name.isNotBlank()) add(format to name)
      }
    }
  }

  /** 大文字小文字の違いを許容してJSONオブジェクトの文字列値を取得します。 */
  private fun JSONObject.optStringIgnoreCase(key: String): String {
    val actualKey = keys().asSequence().firstOrNull { it.equals(key, ignoreCase = true) }
      ?: return ""
    return optString(actualKey).takeUnless { it == "null" }.orEmpty()
  }

  /** 1件分のコマンド情報を縦並びの行として追加します。 */
  private fun addEntry(text: String) {
    val padding = (12 * resources.displayMetrics.density).toInt()
    commandsContainer.addView(TextView(requireContext()).apply {
      this.text = text
      textSize = 16f
      setPadding(padding, padding, padding, padding)
    })
  }
}
