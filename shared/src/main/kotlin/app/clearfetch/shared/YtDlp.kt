package app.clearfetch.shared

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/** yt-dlp 옵션 하나. value가 null이면 값 없는 플래그. */
data class Opt(val key: String, val value: String? = null)

object YtDlpArgs {
    const val PROGRESS_PREFIX = "PROG|"
    val allowedContainers = setOf("mp4", "mkv", "webm")
    val allowedAudioFormats = setOf("mp3", "m4a", "opus", "flac", "wav")

    /** yt-dlp 출력 템플릿에서 %는 특수 문자이므로 사용자 입력은 반드시 이스케이프한다. */
    fun escapeTemplate(s: String): String = s.replace("%", "%%")

    fun infoOptions(): List<Opt> = listOf(Opt("-J"), Opt("--no-playlist"), Opt("--no-warnings"))

    /**
     * @param tempDir 부분 다운로드 파일을 둘 폴더. null이면 지정하지 않는다(Android는 작업별 전용 폴더를 outputDir로 쓴다).
     * @param fileStem 확장자를 뺀 최종 파일 이름. null이면 "제목 [id].확장자".
     */
    fun downloadOptions(
        request: DownloadRequest,
        tempDir: String?,
        ffmpegDir: String?,
        fileStem: String?,
    ): List<Opt> {
        val o = mutableListOf(
            Opt("--no-playlist"),
            Opt("--no-warnings"),
            Opt("--quiet"),
            Opt("--progress"),
            Opt("--newline"),
            Opt(
                "--progress-template",
                "download:${PROGRESS_PREFIX}%(progress.downloaded_bytes)s|%(progress.total_bytes)s|" +
                    "%(progress.total_bytes_estimate)s|%(progress.speed)s|%(progress.eta)s",
            ),
            Opt("--continue"),
            Opt("--no-overwrites"),
            Opt("--windows-filenames"),
            Opt("--paths", "home:${request.outputDir}"),
        )
        if (tempDir != null) o += Opt("--paths", "temp:$tempDir")
        val template = if (fileStem != null) {
            "${escapeTemplate(fileStem)}.%(ext)s"
        } else {
            "%(title).150B [%(id)s].%(ext)s"
        }
        o += Opt("-o", template)
        o += Opt("--print", "after_move:filepath")
        if (!ffmpegDir.isNullOrBlank()) o += Opt("--ffmpeg-location", ffmpegDir)

        when (request.kind) {
            Kind.VIDEO -> {
                val h = request.maxHeight
                val selector = if (h != null) "bv*[height<=$h]+ba/b[height<=$h]" else "bv*+ba/b"
                o += Opt("-f", selector)
                request.container?.takeIf { it in allowedContainers }
                    ?.let { o += Opt("--merge-output-format", it) }
            }
            Kind.AUDIO -> {
                o += Opt("-f", "ba/b")
                o += Opt("-x")
                request.audioFormat?.takeIf { it in allowedAudioFormats }
                    ?.let { o += Opt("--audio-format", it) }
            }
        }
        return o
    }

    /** 셸을 거치지 않는 인자 배열. URL 앞에 `--`를 넣어 URL이 옵션으로 해석되는 일을 막는다. */
    fun toCommand(exe: String, opts: List<Opt>, url: String): List<String> =
        listOf(exe) + opts.flatMap { listOfNotNull(it.key, it.value) } + listOf("--", url)
}

object YtDlpParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parseInfo(url: String, text: String): MediaInfo {
        val root = try {
            json.parseToJsonElement(text.trim()).jsonObject
        } catch (e: Exception) {
            throw DownloadException(ErrorKind.UNKNOWN, "분석 결과를 해석할 수 없습니다.", e)
        }
        val formats = (root["formats"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonObject)?.let(::parseFormat) }
        return MediaInfo(
            url = url,
            title = root.str("title") ?: "(제목 없음)",
            uploader = root.str("uploader") ?: root.str("channel"),
            durationSec = root.long("duration"),
            thumbnailUrl = root.str("thumbnail"),
            formats = formats,
            isPlaylist = root.str("_type") == "playlist",
        )
    }

    private fun parseFormat(o: JsonObject): FormatOption? {
        val id = o.str("format_id") ?: return null
        val vcodec = o.str("vcodec")
        val acodec = o.str("acodec")
        // 코덱 정보가 아예 없는 형식(직접 링크 등)은 영상+오디오로 간주한다.
        val unknown = vcodec == null && acodec == null
        val hasVideo = unknown || (vcodec != null && vcodec != "none")
        val hasAudio = unknown || (acodec != null && acodec != "none")
        if (!hasVideo && !hasAudio) return null // 스토리보드 등
        return FormatOption(
            id = id,
            ext = o.str("ext"),
            resolution = o.str("resolution"),
            heightPx = o.long("height")?.toInt(),
            note = o.str("format_note"),
            hasVideo = hasVideo,
            hasAudio = hasAudio,
            sizeBytes = o.long("filesize") ?: o.long("filesize_approx"),
            bitrateKbps = (o.str("tbr") ?: o.str("abr"))?.toDoubleOrNull()?.toInt(),
        )
    }

    /** `PROG|다운로드|전체|전체추정|속도|ETA` 형식의 한 줄을 해석한다. 해당 형식이 아니면 null. */
    fun parseProgress(line: String): Progress? {
        if (!line.startsWith(YtDlpArgs.PROGRESS_PREFIX)) return null
        val p = line.removePrefix(YtDlpArgs.PROGRESS_PREFIX).split("|")
        if (p.size < 5) return null
        fun n(s: String) = s.trim().toDoubleOrNull()?.toLong()
        val downloaded = n(p[0]) ?: return null
        return Progress(downloaded, n(p[1]) ?: n(p[2]), n(p[3]), n(p[4]))
    }

    fun classifyError(stderr: String): ErrorKind {
        val s = stderr.lowercase()
        return when {
            "unsupported url" in s -> ErrorKind.UNSUPPORTED_URL
            "sign in" in s || "log in" in s || "login" in s || "private video" in s ||
                "members-only" in s || "confirm your age" in s -> ErrorKind.AUTH_REQUIRED
            "no space left" in s || "permission denied" in s -> ErrorKind.DISK
            "timed out" in s || "temporary failure" in s || "connection" in s ||
                "unable to download" in s || "http error 5" in s || "network" in s -> ErrorKind.NETWORK
            else -> ErrorKind.UNKNOWN
        }
    }

    fun summarizeError(stderr: String): String {
        val lines = stderr.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val line = lines.lastOrNull { it.startsWith("ERROR") } ?: lines.lastOrNull() ?: "알 수 없는 오류"
        return line.take(300)
    }

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.long(k: String): Long? = str(k)?.toDoubleOrNull()?.toLong()
}
