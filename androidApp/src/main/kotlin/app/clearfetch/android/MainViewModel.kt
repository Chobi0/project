package app.clearfetch.android

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import app.clearfetch.shared.AnalysisController
import app.clearfetch.shared.DownloadQueue
import app.clearfetch.shared.JsonFileHistoryStore
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val engine = AndroidYtDlpEngine(app)

    val analysis = AnalysisController(engine, scope)
    val queue = DownloadQueue(
        engine, scope, maxConcurrent = 2,
        store = JsonFileHistoryStore(File(app.filesDir, "history.json")),
    )

    init {
        queue.restore()
        refreshFiles()
    }

    /** MediaStore에 등록된 완료 파일이 아직 있는지 확인한다. */
    fun refreshFiles() {
        val resolver = getApplication<Application>().contentResolver
        queue.refreshFileStatus { path ->
            try {
                resolver.openFileDescriptor(Uri.parse(path), "r")?.use { true } ?: false
            } catch (e: Exception) {
                false
            }
        }
    }
}
