@file:OptIn(ExperimentalMaterial3Api::class)

package app.clearfetch.android

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.clearfetch.shared.AnalysisState
import app.clearfetch.shared.DownloadRequest
import app.clearfetch.shared.DownloadTask
import app.clearfetch.shared.EnqueueResult
import app.clearfetch.shared.ErrorKind
import app.clearfetch.shared.Format
import app.clearfetch.shared.Kind
import app.clearfetch.shared.MediaInfo
import app.clearfetch.shared.TaskState
import app.clearfetch.shared.YtDlpArgs
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val dark = isSystemInDarkTheme()
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) { Screen(vm) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.refreshFiles()
    }
}

private fun kindLabel(k: ErrorKind) = when (k) {
    ErrorKind.NETWORK -> "네트워크 오류"
    ErrorKind.UNSUPPORTED_URL -> "지원하지 않는 주소"
    ErrorKind.AUTH_REQUIRED -> "로그인/권한 필요"
    ErrorKind.ENGINE_MISSING -> "다운로드 엔진 오류"
    ErrorKind.DISK -> "저장 오류"
    ErrorKind.UNKNOWN -> "알 수 없는 오류"
}

private fun stateLabel(s: TaskState) = when (s) {
    TaskState.QUEUED -> "대기 중"
    TaskState.ANALYZING -> "분석 중"
    TaskState.DOWNLOADING -> "다운로드 중"
    TaskState.PAUSED -> "일시정지"
    TaskState.COMPLETED -> "완료"
    TaskState.FAILED -> "실패"
    TaskState.CANCELED -> "취소됨"
}

@Composable
private fun Screen(vm: MainViewModel) {
    var url by remember { mutableStateOf("") }
    val analysis by vm.analysis.state.collectAsState()
    val tasks by vm.queue.tasks.collectAsState()
    var notice by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().statusBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("ClearFetch", style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(
            value = url, onValueChange = { url = it }, singleLine = true,
            placeholder = { Text("영상 또는 오디오 주소(URL)를 붙여넣으세요") },
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { notice = null; vm.analysis.analyze(url) },
            enabled = analysis != AnalysisState.Loading,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("분석") }

        when (val a = analysis) {
            AnalysisState.Idle -> {}
            AnalysisState.Loading -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.size(20.dp)); Text("분석 중…")
            }
            is AnalysisState.Failed -> Text("${kindLabel(a.kind)}: ${a.message}", color = MaterialTheme.colorScheme.error)
            is AnalysisState.Ready -> Options(vm, a.info) { notice = it }
        }
        notice?.let { Text(it, color = MaterialTheme.colorScheme.primary) }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("다운로드 목록", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { vm.queue.clearFinished() }) { Text("완료 항목 지우기") }
        }
        Text(
            "저장 위치: 다운로드/ClearFetch · 이어받기는 서버가 지원할 때만 가능합니다. " +
                "현재 버전은 앱이 백그라운드로 가면 다운로드가 중단될 수 있습니다.",
            style = MaterialTheme.typography.bodySmall,
        )
        if (tasks.isEmpty()) {
            Text("아직 다운로드한 항목이 없습니다.")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(tasks.reversed(), key = { it.id }) { Task(vm, it) }
            }
        }
    }
}

@Composable
private fun Options(vm: MainViewModel, info: MediaInfo, onNotice: (String) -> Unit) {
    var kind by remember(info) { mutableStateOf(Kind.VIDEO) }
    var height by remember(info) { mutableStateOf<Int?>(null) }
    var container by remember(info) { mutableStateOf<String?>(null) }
    var audioFmt by remember(info) { mutableStateOf<String?>(null) }
    var fileName by remember(info) { mutableStateOf("") }
    val thumb = rememberThumbnail(info.thumbnailUrl)

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            thumb?.let { Image(it, null, Modifier.fillMaxWidth().height(180.dp)) }
            Text(info.title, style = MaterialTheme.typography.titleMedium)
            val meta = listOfNotNull(info.uploader, info.durationSec?.let { Format.duration(it) }).joinToString(" · ")
            if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.bodySmall)
            if (info.isPlaylist) Text("재생목록은 첫 항목만 지원합니다.", style = MaterialTheme.typography.bodySmall)

            Chips {
                FilterChip(kind == Kind.VIDEO, { kind = Kind.VIDEO }, { Text("영상") })
                FilterChip(kind == Kind.AUDIO, { kind = Kind.AUDIO }, { Text("오디오") })
            }
            if (kind == Kind.VIDEO) {
                Text("화질")
                Chips {
                    FilterChip(height == null, { height = null }, { Text("최고 화질") })
                    info.availableHeights().forEach { h -> FilterChip(height == h, { height = h }, { Text("${h}p") }) }
                }
                Text("컨테이너")
                Chips {
                    FilterChip(container == null, { container = null }, { Text("자동") })
                    YtDlpArgs.allowedContainers.forEach { c -> FilterChip(container == c, { container = c }, { Text(c) }) }
                }
            } else {
                Text("오디오 형식")
                Chips {
                    FilterChip(audioFmt == null, { audioFmt = null }, { Text("원본") })
                    YtDlpArgs.allowedAudioFormats.forEach { f -> FilterChip(audioFmt == f, { audioFmt = f }, { Text(f) }) }
                }
            }
            OutlinedTextField(
                value = fileName, onValueChange = { fileName = it }, singleLine = true,
                placeholder = { Text("파일 이름(선택, 확장자 제외)") }, modifier = Modifier.fillMaxWidth(),
            )
            Button(modifier = Modifier.fillMaxWidth(), onClick = {
                val r = DownloadRequest(
                    url = info.url, title = info.title, kind = kind,
                    maxHeight = if (kind == Kind.VIDEO) height else null,
                    container = if (kind == Kind.VIDEO) container else null,
                    audioFormat = if (kind == Kind.AUDIO) audioFmt else null,
                    outputDir = "Download/ClearFetch", // 표시용. 실제 저장은 MediaStore가 처리한다.
                    fileName = fileName.trim().ifEmpty { null },
                )
                onNotice(if (vm.queue.enqueue(r) is EnqueueResult.Duplicate) "이미 대기열에 있는 같은 작업입니다." else "")
            }) { Text("대기열에 추가") }
        }
    }
}

@Composable
private fun Chips(content: @Composable () -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

@Composable
private fun Task(vm: MainViewModel, t: DownloadTask) {
    val ctx = LocalContext.current
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
                    val parts = mutableListOf(stateLabel(t.state))
                    if (t.downloadedBytes > 0) {
                        parts += if (total != null) "${Format.bytes(t.downloadedBytes)} / ${Format.bytes(total)}" else Format.bytes(t.downloadedBytes)
                    }
                    t.speedBps?.let { parts += Format.speed(it) }
                    t.etaSec?.let { parts += "남은 시간 ${Format.duration(it)}" }
                    Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                }
                TaskState.COMPLETED -> {
                    Text(if (t.fileMissing) "파일이 삭제되었거나 이동되었습니다" else "완료", style = MaterialTheme.typography.bodySmall,
                        color = if (t.fileMissing) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                }
                TaskState.FAILED -> Text(
                    "실패 · ${t.errorKind?.let { kindLabel(it) } ?: ""}: ${t.error ?: ""}",
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                )
                else -> Text(stateLabel(t.state), style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                when (t.state) {
                    TaskState.DOWNLOADING, TaskState.QUEUED -> {
                        TextButton({ vm.queue.pause(t.id) }) { Text("일시정지") }
                        TextButton({ vm.queue.cancel(t.id) }) { Text("취소") }
                    }
                    TaskState.PAUSED -> {
                        TextButton({ vm.queue.resume(t.id) }) { Text("재개") }
                        TextButton({ vm.queue.cancel(t.id) }) { Text("취소") }
                    }
                    TaskState.FAILED, TaskState.CANCELED -> {
                        TextButton({ vm.queue.retry(t.id) }) { Text("재시도") }
                        TextButton({ vm.queue.remove(t.id) }) { Text("제거") }
                    }
                    TaskState.COMPLETED -> {
                        if (!t.fileMissing) TextButton({ t.filePath?.let { openUri(ctx, it) } }) { Text("열기") }
                        TextButton({ vm.queue.remove(t.id) }) { Text("제거") }
                    }
                    else -> {}
                }
            }
        }
    }
}

private fun openUri(ctx: Context, uriString: String) {
    val uri = Uri.parse(uriString)
    val mime = ctx.contentResolver.getType(uri)
        ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(MimeTypeMap.getFileExtensionFromUrl(uriString))
        ?: "*/*"
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        ctx.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(ctx, "이 파일을 열 수 있는 앱이 없습니다.", Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun rememberThumbnail(url: String?): ImageBitmap? {
    val state = produceState<ImageBitmap?>(null, url) {
        value = if (url != null && url.startsWith("https://")) {
            withContext(Dispatchers.IO) {
                try {
                    val conn = URL(url).openConnection() as HttpURLConnection
                    conn.connectTimeout = 5000; conn.readTimeout = 5000
                    conn.inputStream.use { BitmapFactory.decodeStream(it)?.asImageBitmap() }
                } catch (e: Exception) { null }
            }
        } else null
    }
    return state.value
}
