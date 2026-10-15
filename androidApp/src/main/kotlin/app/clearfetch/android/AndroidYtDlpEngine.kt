package app.clearfetch.android

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import app.clearfetch.shared.DownloadEngine
import app.clearfetch.shared.DownloadException
import app.clearfetch.shared.DownloadRequest
import app.clearfetch.shared.ErrorKind
import app.clearfetch.shared.FileNames
import app.clearfetch.shared.MediaInfo
import app.clearfetch.shared.Opt
import app.clearfetch.shared.Progress
import app.clearfetch.shared.YtDlpArgs
import app.clearfetch.shared.YtDlpParser
import app.clearfetch.shared.requireValidUrl
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Android용 엔진: youtubedl-android(yt-dlp + ffmpeg 포장 라이브러리)를 사용한다.
 *
 * 주의: 이 파일은 라이브러리 API를 문서/기억에 의존해 작성했고 이 개발 환경에서 컴파일하지 못했다.
 * 시그니처가 다르면 CI 빌드 오류로 드러나므로 그에 맞춰 수정해야 한다 (docs/BUILD.md "알려진 위험").
 *
 * 저장 방식: 작업별 앱 전용 폴더(cacheDir/dl/<taskId>)에 받은 뒤 완료 시 MediaStore.Downloads의
 * Download/ClearFetch 로 복사한다. MediaStore가 이름 충돌을 자동으로 피해 준다.
 */
class AndroidYtDlpEngine(private val context: Context) : DownloadEngine {

    private fun YoutubeDLRequest.addOpt(o: Opt) {
        if (o.value == null) addOption(o.key) else addOption(o.key, o.value)
    }

    private suspend fun awaitReady() {
        try {
            ClearFetchApp.engineReady.await()
        } catch (e: Exception) {
            throw DownloadException(ErrorKind.ENGINE_MISSING, "다운로드 엔진 초기화 실패: ${e.message}", e)
        }
    }

    /** 코루틴 취소 시 yt-dlp 프로세스를 종료하며 블로킹 호출을 실행한다. */
    private suspend fun <T> withProcess(block: (processId: String) -> T): T = withContext(Dispatchers.IO) {
        val pid = UUID.randomUUID().toString()
        coroutineScope {
            val watcher = launch {
                try {
                    awaitCancellation()
                } finally {
                    try { YoutubeDL.getInstance().destroyProcessById(pid) } catch (e: Exception) { }
                }
            }
            try {
                block(pid)
            } catch (e: Exception) {
                ensureActive() // 취소로 인한 프로세스 종료라면 CancellationException으로 바꾼다.
                throw DownloadException(
                    YtDlpParser.classifyError(e.message.orEmpty()),
                    YtDlpParser.summarizeError(e.message.orEmpty()),
                    e,
                )
            } finally {
                watcher.cancel()
            }
        }
    }

    override suspend fun analyze(url: String): MediaInfo {
        val safe = requireValidUrl(url)
        awaitReady()
        val req = YoutubeDLRequest(safe)
        YtDlpArgs.infoOptions().forEach(req::addOpt)
        val resp = withProcess { pid -> YoutubeDL.getInstance().execute(req, pid, null) }
        return YtDlpParser.parseInfo(safe, resp.out)
    }

    override suspend fun download(taskId: String, request: DownloadRequest, onProgress: (Progress) -> Unit): String {
        val safe = requireValidUrl(request.url)
        awaitReady()
        val dir = File(context.cacheDir, "dl/$taskId").apply { mkdirs() }
        val stem = request.fileName?.let { FileNames.sanitize(it) }
        val opts = YtDlpArgs.downloadOptions(request.copy(outputDir = dir.path), tempDir = null, ffmpegDir = null, fileStem = stem)
        val req = YoutubeDLRequest(safe)
        opts.forEach(req::addOpt)

        val resp = withProcess { pid ->
            YoutubeDL.getInstance().execute(req, pid) { _, _, line ->
                YtDlpParser.parseProgress(line)?.let(onProgress)
            }
        }
        val produced = resp.out.lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() }
            ?.let(::File)?.takeIf { it.isFile }
            ?: dir.listFiles()?.firstOrNull { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }
            ?: throw DownloadException(ErrorKind.UNKNOWN, "다운로드된 파일을 찾을 수 없습니다.")
        val uri = withContext(Dispatchers.IO) { exportToDownloads(produced) }
        return uri
    }

    override fun cleanup(taskId: String, request: DownloadRequest) {
        File(context.cacheDir, "dl/$taskId").deleteRecursively()
    }

    private fun exportToDownloads(src: File): String {
        val resolver = context.contentResolver
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(src.extension.lowercase())
            ?: "application/octet-stream"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, src.name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/ClearFetch")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw DownloadException(ErrorKind.DISK, "다운로드 폴더에 파일을 만들 수 없습니다.")
        try {
            val out = resolver.openOutputStream(uri)
                ?: throw DownloadException(ErrorKind.DISK, "출력 스트림을 열 수 없습니다.")
            out.use { o -> src.inputStream().use { it.copyTo(o) } }
            val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            if (e is DownloadException) throw e
            throw DownloadException(ErrorKind.DISK, "파일 저장 실패: ${e.message}", e)
        }
        return uri.toString()
    }
}
