package app.clearfetch.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.clearfetch.shared.AnalysisController
import app.clearfetch.shared.AppSettings
import app.clearfetch.shared.DownloadQueue
import app.clearfetch.shared.JsonFileHistoryStore
import app.clearfetch.shared.JsonFileSettingsStore
import app.clearfetch.shared.ProcessYtDlpEngine
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class AppModel {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val dataDir = File(System.getProperty("user.home"), ".clearfetch").apply { mkdirs() }
    private val settingsStore = JsonFileSettingsStore(File(dataDir, "settings.json"))

    var settings by mutableStateOf(
        settingsStore.load(
            AppSettings(outputDir = File(System.getProperty("user.home"), "Downloads/ClearFetch").path),
        ),
    )
        private set

    private val engine = ProcessYtDlpEngine(
        ytDlpPath = { settings.ytDlpPath },
        ffmpegDir = { settings.ffmpegDir },
    )

    val analysis = AnalysisController(engine, scope)
    val queue = DownloadQueue(engine, scope, maxConcurrent = 2, store = JsonFileHistoryStore(File(dataDir, "history.json")))

    init {
        queue.restore()
        queue.refreshFileStatus { File(it).exists() }
    }

    fun updateSettings(s: AppSettings) {
        settings = s
        settingsStore.save(s)
    }
}
