package app.clearfetch.shared

import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

interface DownloadEngine {
    /** URL을 분석한다. 실패 시 [DownloadException]. 코루틴 취소 시 하위 프로세스도 종료되어야 한다. */
    suspend fun analyze(url: String): MediaInfo

    /** 다운로드하고 최종 파일 경로(또는 URI 문자열)를 반환한다. 코루틴 취소 시 중단하되 부분 파일은 남긴다. */
    suspend fun download(taskId: String, request: DownloadRequest, onProgress: (Progress) -> Unit): String

    /** 작업의 임시/부분 파일을 삭제한다. 취소 또는 완료 후 호출된다. */
    fun cleanup(taskId: String, request: DownloadRequest) {}
}

fun requireValidUrl(raw: String): String = when (val r = UrlValidator.validate(raw)) {
    is UrlValidator.Result.Ok -> r.url
    is UrlValidator.Result.Invalid -> throw DownloadException(ErrorKind.UNSUPPORTED_URL, r.reason)
}

/** 데스크톱(JVM)용: yt-dlp 실행 파일을 하위 프로세스로 호출한다. 셸은 사용하지 않는다. */
class ProcessYtDlpEngine(
    private val ytDlpPath: () -> String,
    private val ffmpegDir: () -> String?,
) : DownloadEngine {

    private class ProcResult(val exitCode: Int, val stdout: String, val stderr: String)

    override suspend fun analyze(url: String): MediaInfo {
        val safe = requireValidUrl(url)
        val cmd = YtDlpArgs.toCommand(ytDlpPath(), YtDlpArgs.infoOptions(), safe)
        val r = runProcess(cmd, null)
        if (r.exitCode != 0) {
            throw DownloadException(YtDlpParser.classifyError(r.stderr), YtDlpParser.summarizeError(r.stderr))
        }
        return YtDlpParser.parseInfo(safe, r.stdout)
    }

    override suspend fun download(taskId: String, request: DownloadRequest, onProgress: (Progress) -> Unit): String {
        val safe = requireValidUrl(request.url)
        val outDir = File(request.outputDir)
        withContext(Dispatchers.IO) {
            if (!outDir.isDirectory && !outDir.mkdirs()) {
                throw DownloadException(ErrorKind.DISK, "저장 폴더를 만들 수 없습니다: ${outDir.path}")
            }
        }
        val stem = request.fileName?.let { FileNames.uniqueStem(outDir, FileNames.sanitize(it)) }
        val opts = YtDlpArgs.downloadOptions(request, tempDir(taskId, outDir).path, ffmpegDir(), stem)
        val r = runProcess(YtDlpArgs.toCommand(ytDlpPath(), opts, safe)) { line ->
            YtDlpParser.parseProgress(line)?.let(onProgress)
        }
        if (r.exitCode != 0) {
            throw DownloadException(YtDlpParser.classifyError(r.stderr), YtDlpParser.summarizeError(r.stderr))
        }
        val path = r.stdout.lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() }
        if (path == null || !File(path).exists()) {
            throw DownloadException(ErrorKind.DISK, "같은 이름의 파일이 이미 있거나 출력 파일을 찾을 수 없습니다.")
        }
        return path
    }

    override fun cleanup(taskId: String, request: DownloadRequest) {
        tempDir(taskId, File(request.outputDir)).deleteRecursively()
    }

    private fun tempDir(taskId: String, outDir: File) = File(outDir, ".clearfetch-tmp/$taskId")

    private suspend fun runProcess(cmd: List<String>, onLine: ((String) -> Unit)?): ProcResult =
        withContext(Dispatchers.IO) {
            val pb = ProcessBuilder(cmd)
            pb.environment()["PYTHONIOENCODING"] = "utf-8"
            val proc = try {
                pb.start()
            } catch (e: IOException) {
                throw DownloadException(
                    ErrorKind.ENGINE_MISSING,
                    "yt-dlp를 실행할 수 없습니다. 설정에서 경로를 확인하세요. (${e.message})",
                    e,
                )
            }
            proc.outputStream.close() // 표준 입력을 닫아 yt-dlp가 입력을 기다리지 않게 한다.
            coroutineScope {
                // 코루틴이 취소되면 블로킹 읽기를 풀기 위해 프로세스를 강제 종료한다.
                val watcher = launch {
                    try {
                        awaitCancellation()
                    } finally {
                        if (proc.isAlive) proc.destroyForcibly()
                    }
                }
                try {
                    val err = async { proc.errorStream.bufferedReader(Charsets.UTF_8).readText() }
                    val out = StringBuilder()
                    proc.inputStream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                        if (!line.startsWith(YtDlpArgs.PROGRESS_PREFIX)) out.appendLine(line)
                        onLine?.invoke(line)
                    }
                    val code = proc.waitFor()
                    ProcResult(code, out.toString(), err.await())
                } finally {
                    watcher.cancel()
                }
            }
        }
}
