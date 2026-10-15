@file:OptIn(ExperimentalMaterial3Api::class)

package app.clearfetch.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import app.clearfetch.shared.AnalysisState
import app.clearfetch.shared.AppSettings
import app.clearfetch.shared.DownloadRequest
import app.clearfetch.shared.DownloadTask
import app.clearfetch.shared.EnqueueResult
import app.clearfetch.shared.Format
import app.clearfetch.shared.Kind
import app.clearfetch.shared.MediaInfo
import app.clearfetch.shared.TaskState
import app.clearfetch.shared.YtDlpArgs
import java.awt.Desktop
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import javax.swing.JFileChooser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun main() = application {
    val model = remember { AppModel() }
    Window(onCloseRequest = ::exitApplication, title = Strings.appName) {
        val dark = androidx.compose.foundation.isSystemInDarkTheme()
        MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
            Surface(Modifier.fillMaxSize()) { App(model) }
        }
    }
}

@Composable
fun App(model: AppModel) {
    var tab by remember { mutableStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(Strings.tabDownload) })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(Strings.tabSettings) })
        }
        if (tab == 0) DownloadScreen(model) else SettingsScreen(model)
    }
}

@Composable
fun DownloadScreen(model: AppModel) {
    var url by remember { mutableStateOf("") }
    val analysis by model.analysis.state.collectAsState()
    val tasks by model.queue.tasks.collectAsState()
    var notice by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = url, onValueChange = { url = it }, singleLine = true,
                placeholder = { Text(Strings.urlHint) }, modifier = Modifier.weight(1f),
            )
            Button(onClick = { notice = null; model.analysis.analyze(url) }, enabled = analysis != AnalysisState.Loading) {
                Text(Strings.analyze)
            }
        }
        when (val a = analysis) {
            AnalysisState.Idle -> {}
            AnalysisState.Loading -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.size(20.dp)); Text(Strings.analyzing)
            }
            is AnalysisState.Failed -> Text(
                "${Strings.errorKind(a.kind)}: ${a.message}", color = MaterialTheme.colorScheme.error,
            )
            is AnalysisState.Ready -> OptionsCard(model, a.info) { notice = it }
        }
        notice?.let { Text(it, color = MaterialTheme.colorScheme.primary) }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(Strings.queueTitle, style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { model.queue.clearFinished() }) { Text(Strings.clearFinished) }
        }
        Text(Strings.resumeNote, style = MaterialTheme.typography.bodySmall)
        if (tasks.isEmpty()) {
            Text(Strings.queueEmpty)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(tasks.reversed(), key = { it.id }) { TaskCard(model, it) }
            }
        }
    }
}

@Composable
fun OptionsCard(model: AppModel, info: MediaInfo, onNotice: (String) -> Unit) {
    var kind by remember(info) { mutableStateOf(Kind.VIDEO) }
    var height by remember(info) { mutableStateOf<Int?>(null) }
    var container by remember(info) { mutableStateOf<String?>(null) }
    var audioFmt by remember(info) { mutableStateOf<String?>(null) }
    var fileName by remember(info) { mutableStateOf("") }
    var outDir by remember(info) { mutableStateOf(model.settings.outputDir) }
    val thumb = rememberThumbnail(info.thumbnailUrl)

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                thumb?.let { Image(it, null, Modifier.width(160.dp).height(90.dp)) }
                Column {
                    Text(info.title, style = MaterialTheme.typography.titleMedium)
                    val meta = listOfNotNull(info.uploader, info.durationSec?.let { Format.duration(it) }).joinToString(" · ")
                    if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.bodySmall)
                    if (info.isPlaylist) Text("재생목록은 첫 항목만 지원합니다.", style = MaterialTheme.typography.bodySmall)
                }
            }
            ChipRow {
                FilterChip(kind == Kind.VIDEO, { kind = Kind.VIDEO }, { Text(Strings.kindVideo) })
                FilterChip(kind == Kind.AUDIO, { kind = Kind.AUDIO }, { Text(Strings.kindAudio) })
            }
            if (kind == Kind.VIDEO) {
                Text(Strings.quality)
                ChipRow {
                    FilterChip(height == null, { height = null }, { Text(Strings.qualityBest) })
                    info.availableHeights().forEach { h ->
                        FilterChip(height == h, { height = h }, { Text("${h}p") })
                    }
                }
                Text(Strings.container)
                ChipRow {
                    FilterChip(container == null, { container = null }, { Text("자동") })
                    YtDlpArgs.allowedContainers.forEach { c ->
                        FilterChip(container == c, { container = c }, { Text(c) })
                    }
                }
            } else {
                Text(Strings.audioFormat)
                ChipRow {
                    FilterChip(audioFmt == null, { audioFmt = null }, { Text("원본") })
                    YtDlpArgs.allowedAudioFormats.forEach { f ->
                        FilterChip(audioFmt == f, { audioFmt = f }, { Text(f) })
                    }
                }
            }
            OutlinedTextField(
                value = fileName, onValueChange = { fileName = it }, singleLine = true,
                placeholder = { Text(Strings.fileNameHint) }, modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${Strings.saveTo}: $outDir", Modifier.weight(1f))
                OutlinedButton(onClick = { pickFolder(outDir)?.let { outDir = it } }) { Text(Strings.change) }
            }
            Button(onClick = {
                val r = DownloadRequest(
                    url = info.url, title = info.title, kind = kind,
                    maxHeight = if (kind == Kind.VIDEO) height else null,
                    container = if (kind == Kind.VIDEO) container else null,
                    audioFormat = if (kind == Kind.AUDIO) audioFmt else null,
                    outputDir = outDir, fileName = fileName.trim().ifEmpty { null },
                )
                onNotice(if (model.queue.enqueue(r) is EnqueueResult.Duplicate) Strings.duplicate else "")
            }) { Text(Strings.addToQueue) }
        }
    }
}

@Composable
fun ChipRow(content: @Composable () -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

@Composable
fun TaskCard(model: AppModel, t: DownloadTask) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(t.request.title, style = MaterialTheme.typography.titleSmall, maxLines = 2)
            val total = t.totalBytes
            when (t.state) {
                TaskState.DOWNLOADING, TaskState.PAUSED, TaskState.QUEUED -> {
                    if (total != null && total > 0) {
                        LinearProgressIndicator(progress = { (t.downloadedBytes.toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    } else if (t.state == TaskState.DOWNLOADING) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    val parts = mutableListOf(Strings.state(t.state))
                    if (t.downloadedBytes > 0) {
                        parts += if (total != null) "${Format.bytes(t.downloadedBytes)} / ${Format.bytes(total)}" else Format.bytes(t.downloadedBytes)
                    }
                    t.speedBps?.let { parts += Format.speed(it) }
                    t.etaSec?.let { parts += "남은 시간 ${Format.duration(it)}" }
                    Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                }
                TaskState.COMPLETED -> {
                    Text("${Strings.state(t.state)} · ${t.filePath ?: ""}", style = MaterialTheme.typography.bodySmall)
                    if (t.fileMissing) Text(Strings.fileMissing, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                TaskState.FAILED -> Text(
                    "${Strings.state(t.state)} · ${t.errorKind?.let { Strings.errorKind(it) } ?: ""}: ${t.error ?: ""}",
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                )
                else -> Text(Strings.state(t.state), style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (t.state) {
                    TaskState.DOWNLOADING, TaskState.QUEUED -> {
                        TextButton({ model.queue.pause(t.id) }) { Text(Strings.pause) }
                        TextButton({ model.queue.cancel(t.id) }) { Text(Strings.cancel) }
                    }
                    TaskState.PAUSED -> {
                        TextButton({ model.queue.resume(t.id) }) { Text(Strings.resume) }
                        TextButton({ model.queue.cancel(t.id) }) { Text(Strings.cancel) }
                    }
                    TaskState.FAILED, TaskState.CANCELED -> {
                        TextButton({ model.queue.retry(t.id) }) { Text(Strings.retry) }
                        TextButton({ model.queue.remove(t.id) }) { Text(Strings.remove) }
                    }
                    TaskState.COMPLETED -> {
                        if (!t.fileMissing) {
                            TextButton({ t.filePath?.let { openFile(File(it)) } }) { Text(Strings.open) }
                            TextButton({ t.filePath?.let { openFile(File(it).parentFile) } }) { Text(Strings.openFolder) }
                        }
                        TextButton({ model.queue.remove(t.id) }) { Text(Strings.remove) }
                    }
                    else -> {}
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(model: AppModel) {
    val s = model.settings
    var yt by remember { mutableStateOf(s.ytDlpPath) }
    var ff by remember { mutableStateOf(s.ffmpegDir ?: "") }
    var out by remember { mutableStateOf(s.outputDir) }
    var saved by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(Strings.settingsHelp, style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(yt, { yt = it; saved = false }, label = { Text(Strings.settingsYtDlp) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(ff, { ff = it; saved = false }, label = { Text(Strings.settingsFfmpeg) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(out, { out = it; saved = false }, label = { Text(Strings.settingsOutput) }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { pickFolder(out)?.let { out = it; saved = false } }) { Text(Strings.change) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = {
                model.updateSettings(AppSettings(outputDir = out.trim(), ytDlpPath = yt.trim().ifEmpty { "yt-dlp" }, ffmpegDir = ff.trim().ifEmpty { null }))
                saved = true
            }) { Text(Strings.settingsSave) }
            if (saved) Text(Strings.settingsSaved)
        }
        Spacer(Modifier.height(1.dp))
    }
}

private fun pickFolder(current: String): String? {
    val c = JFileChooser(File(current).takeIf { it.exists() })
    c.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
    return if (c.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) c.selectedFile.path else null
}

private fun openFile(f: File?) {
    try {
        if (f != null && f.exists() && Desktop.isDesktopSupported()) Desktop.getDesktop().open(f)
    } catch (e: Exception) { /* 열기 실패는 무시: 기본 앱이 없을 수 있음 */ }
}

@Composable
private fun rememberThumbnail(url: String?): ImageBitmap? {
    val state = produceState<ImageBitmap?>(null, url) {
        value = if (url != null && url.startsWith("https://")) {
            withContext(Dispatchers.IO) {
                try {
                    val conn = URL(url).openConnection() as HttpURLConnection
                    conn.connectTimeout = 5000; conn.readTimeout = 5000
                    val bytes = conn.inputStream.use { it.readBytes().takeIf { b -> b.size < 5_000_000 } }
                    bytes?.let { org.jetbrains.skia.Image.makeFromEncoded(it).toComposeImageBitmap() }
                } catch (e: Exception) { null }
            }
        } else null
    }
    return state.value
}
