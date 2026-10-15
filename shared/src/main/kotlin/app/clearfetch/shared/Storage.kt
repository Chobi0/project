package app.clearfetch.shared

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

interface HistoryStore {
    fun load(): List<DownloadTask>
    fun save(tasks: List<DownloadTask>)
}

private val storeJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

private fun writeAtomically(file: File, text: String) {
    file.absoluteFile.parentFile?.mkdirs()
    val tmp = File(file.path + ".tmp")
    tmp.writeText(text, Charsets.UTF_8)
    Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
}

class JsonFileHistoryStore(private val file: File) : HistoryStore {
    private val ser = ListSerializer(DownloadTask.serializer())

    override fun load(): List<DownloadTask> = try {
        if (!file.exists()) emptyList() else storeJson.decodeFromString(ser, file.readText(Charsets.UTF_8))
    } catch (e: Exception) {
        emptyList() // 손상된 이력 파일 때문에 앱이 시작되지 않는 일을 막는다.
    }

    override fun save(tasks: List<DownloadTask>) = writeAtomically(file, storeJson.encodeToString(ser, tasks))
}

class JsonFileSettingsStore(private val file: File) {
    fun load(default: AppSettings): AppSettings = try {
        if (!file.exists()) default
        else storeJson.decodeFromString(AppSettings.serializer(), file.readText(Charsets.UTF_8))
    } catch (e: Exception) {
        default
    }

    fun save(settings: AppSettings) =
        writeAtomically(file, storeJson.encodeToString(AppSettings.serializer(), settings))
}
