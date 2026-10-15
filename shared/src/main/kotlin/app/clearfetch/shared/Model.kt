package app.clearfetch.shared

import kotlinx.serialization.Serializable

@Serializable
enum class TaskState { QUEUED, ANALYZING, DOWNLOADING, PAUSED, COMPLETED, FAILED, CANCELED }

/** 아직 끝나지 않은(완료/실패/취소가 아닌) 상태인지 여부. */
val TaskState.isOpen: Boolean
    get() = this == TaskState.QUEUED || this == TaskState.ANALYZING ||
        this == TaskState.DOWNLOADING || this == TaskState.PAUSED

@Serializable
enum class Kind { VIDEO, AUDIO }

@Serializable
enum class ErrorKind { NETWORK, UNSUPPORTED_URL, AUTH_REQUIRED, ENGINE_MISSING, DISK, UNKNOWN }

class DownloadException(
    val kind: ErrorKind,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

@Serializable
data class FormatOption(
    val id: String,
    val ext: String?,
    val resolution: String?,
    val heightPx: Int?,
    val note: String?,
    val hasVideo: Boolean,
    val hasAudio: Boolean,
    val sizeBytes: Long?,
    val bitrateKbps: Int?,
)

@Serializable
data class MediaInfo(
    val url: String,
    val title: String,
    val uploader: String?,
    val durationSec: Long?,
    val thumbnailUrl: String?,
    val formats: List<FormatOption>,
    val isPlaylist: Boolean = false,
) {
    /** 선택 가능한 영상 높이(내림차순). */
    fun availableHeights(): List<Int> =
        formats.filter { it.hasVideo }.mapNotNull { it.heightPx }.distinct().sortedDescending()
}

@Serializable
data class DownloadRequest(
    val url: String,
    val title: String,
    val kind: Kind,
    val maxHeight: Int? = null,
    val container: String? = null,
    val audioFormat: String? = null,
    val outputDir: String,
    val fileName: String? = null,
) {
    /** 중복 작업 판정용: 같은 대상·같은 옵션이면 같은 작업으로 본다. */
    fun sameTarget(o: DownloadRequest): Boolean =
        url == o.url && kind == o.kind && maxHeight == o.maxHeight &&
            container == o.container && audioFormat == o.audioFormat && outputDir == o.outputDir
}

data class Progress(
    val downloadedBytes: Long,
    val totalBytes: Long?,
    val speedBps: Long?,
    val etaSec: Long?,
)

@Serializable
data class DownloadTask(
    val id: String,
    val request: DownloadRequest,
    val state: TaskState = TaskState.QUEUED,
    val downloadedBytes: Long = 0,
    val totalBytes: Long? = null,
    val speedBps: Long? = null,
    val etaSec: Long? = null,
    val filePath: String? = null,
    val fileMissing: Boolean = false,
    val error: String? = null,
    val errorKind: ErrorKind? = null,
    val createdAt: Long,
)

@Serializable
data class AppSettings(
    val outputDir: String,
    val ytDlpPath: String = "yt-dlp",
    val ffmpegDir: String? = null,
)

sealed interface AnalysisState {
    data object Idle : AnalysisState
    data object Loading : AnalysisState
    data class Ready(val info: MediaInfo) : AnalysisState
    data class Failed(val kind: ErrorKind, val message: String) : AnalysisState
}
