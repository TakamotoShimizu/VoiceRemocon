# VoiceRemocon クラス図

README の仕様と現在の Android 実装に基づくクラス図です。音声リモコン画面は `RemoconFragment`、認識エンジンと画面ホストは `VoiceRemocon` が担当します。

```mermaid
classDiagram
    class VoiceRemocon {
        <<AppCompatActivity>>
        +isRecording Boolean
        +onRecord(start, progressBar, textView)
        +onPlay(start)
        +onRecognise(start, progressBar, textView)
        +onRecogniseFromFile(start, progressBar, textView)
        +deleteAllRecordings()
        +startUpdatingAmplitudeUI(...)
    }

    class RemoconFragment {
        <<Fragment>>
        -modelReady Boolean
        -recognizing Boolean
        -amplitudeUpdatesStarted Boolean
        -amplitudeUpdater Runnable
        +onCreateView()
        +onViewCreated()
        +onResume()
        +onPause()
        -startAmplitudeUpdates()
        -stopAmplitudeUpdates(reset)
        -showRecognizedCommand(text)
        -updateControls()
        -showSettingsDialog()
        -sendCommand()
        -commandFromSpeech(text)
        -sendTcpCommand(host, port, command)
    }

    class CommandListFragment {
        <<Fragment>>
        -loading Boolean
        +onCreateView()
        +onViewCreated()
        -createContentView()
        -loadCommandList()
        -requestCommandList(host, port)
        -parseCommandList(json)
        -optStringIgnoreCase(key)
        -addEntry(text)
    }

    class GenericControlsFragment {
        <<Fragment>>
        +録音・再生・停止・一時停止
        +録音ファイル削除
    }

    class RecognitionFragment {
        <<Fragment>>
        +音声認識開始・停止
        +録音ファイルから認識
        +認識結果表示
    }

    class LearningFragment {
        <<Fragment>>
        -learningComplete Boolean
        -recognizing Boolean
        -busy Boolean
        -amplitudeUpdatesStarted Boolean
        -amplitudeUpdater Runnable
        +onCreateView()
        +onViewCreated()
        +onPause()
        +onDestroyView()
        -createContentView()
        -startLearning()
        -startVoiceRecognition()
        -storeLearnedCode()
        -updateControls()
        -startAmplitudeUpdates()
        -stopAmplitudeUpdates(reset)
        -configuredEndpoint()
        -showSettingsDialog()
        -waitForLearning(host, port)
        -exchange(host, port, command)
    }

    class VoiceRemoconPageAdapter {
        <<VoiceRemocon内のFragmentStateAdapter>>
        +getItemCount()
        +createFragment(position)
    }

    class ViewPagerAdapter {
        <<トップレベルFragmentStateAdapter>>
        +getItemCount()
        +createFragment(position)
    }

    class MediaRecordHandler {
        +startRecording(path)
        +stopRecording()
        +getMaxAmplitude()
        +releaseResources()
    }

    class MediaPlayHandler {
        +startPlaying(path)
        +requestStopPlaying()
        +pausePlaying()
        +release()
    }

    class MediaRecogniseHandler {
        +initializeVoskModel()
        +startRecogniseFromStream()
        +stopRecognitionStream()
        +startRecordingForRecognition(path)
        +recognitionEvents
        +release()
    }

    class MediaRecordViewModel {
        +startRecording(path)
        +stopRecording()
    }

    class MediaPlayViewModel {
        +startPlaying(path)
        +stopPlaying()
        +pausePlaying()
        +isPlaying()
    }

    class MediaRecogniseViewModel {
        +recognitionText LiveData
        +startRecognition()
        +stopRecognition()
        +startRecordingForRecognition(path)
        +stopRecordingForRecognition()
    }

    class RecognitionEvent {
        <<sealed>>
        Partial
        Final
        Error
    }

    class RemoteCommand {
        <<RemoconFragment内データ型>>
        +wireText
        +parse(input)
    }

    class SendResult {
        <<interface>>
        Ack
        Nack
        Error
    }

    VoiceRemocon *-- VoiceRemoconPageAdapter : 内部クラス
    VoiceRemoconPageAdapter ..> RemoconFragment : position 0
    VoiceRemoconPageAdapter ..> GenericControlsFragment : position 1
    VoiceRemoconPageAdapter ..> RecognitionFragment : position 2
    VoiceRemoconPageAdapter ..> LearningFragment : position 3
    VoiceRemoconPageAdapter ..> CommandListFragment : position 4

    ViewPagerAdapter ..> RemoconFragment : position 0
    ViewPagerAdapter ..> GenericControlsFragment : position 1
    ViewPagerAdapter ..> RecognitionFragment : position 2
    ViewPagerAdapter ..> LearningFragment : position 3
    ViewPagerAdapter ..> CommandListFragment : position 4

    RemoconFragment ..> VoiceRemocon : 音声認識と音量状態を利用
    GenericControlsFragment ..> VoiceRemocon : 操作を委譲
    RecognitionFragment ..> VoiceRemocon : 操作と状態を参照
    LearningFragment ..> VoiceRemocon : 音声認識操作と入力振幅を利用
    CommandListFragment ..> SharedPreferences : 接続先ホスト・ポートを取得
    CommandListFragment ..> JSONObject : JSON形式の一覧を解析

    VoiceRemocon *-- MediaRecordHandler
    VoiceRemocon *-- MediaPlayHandler
    VoiceRemocon *-- MediaRecogniseHandler
    VoiceRemocon *-- MediaRecordViewModel
    VoiceRemocon *-- MediaPlayViewModel
    VoiceRemocon *-- MediaRecogniseViewModel

    MediaPlayHandler --> MediaRecordHandler : 録音データを参照
    MediaRecordViewModel --> MediaRecordHandler
    MediaPlayViewModel --> MediaPlayHandler
    MediaRecogniseViewModel --> MediaRecogniseHandler
    MediaRecogniseHandler ..> RecognitionEvent : 認識イベントを発行
    MediaRecogniseViewModel ..> RecognitionEvent : イベントをLiveDataへ反映
    RecognitionFragment ..> MediaRecogniseViewModel : 結果を監視
    RemoconFragment ..> MediaRecogniseViewModel : 音声認識結果を監視
    LearningFragment ..> MediaRecogniseViewModel : 認識結果監視・認識開始停止
    LearningFragment ..> MediaRecogniseHandler : 入力振幅を50ms間隔で取得
    RemoconFragment ..> RemoteCommand : 形式を検証
    RemoconFragment ..> SendResult : ACK/NACK/通信エラーを表示
```

## 補足

`VoiceRemocon` 内部とトップレベルの2つの Pager Adapter が存在します。Activity は Pager Adapter を使って5画面を表示します。`RemoconFragment` と `LearningFragment` は同じ SharedPreferences の接続先設定（ホスト・ポート）を参照します。学習画面はTCPで `startLearning` の `WaitForLearning` / `LearningComplete` / `LearningTimedOut` を受け取り、音声認識後に `storeCode` を送信します。
