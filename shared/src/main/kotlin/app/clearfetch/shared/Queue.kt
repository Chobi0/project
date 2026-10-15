package app.clearfetch.shared

import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

sealed interface EnqueueResult {
    data class Added(val task: DownloadTask) : EnqueueResult
    data class Duplicate(val existingId: String) : EnqueueResult
}

/**
 * 다운로드 대기열.
 * - 동시 실행 수 제한, 중복 작업 방지
 * - 일시정지 = 현재 실행을 중단하고 부분 파일을 남김 → 재개 시 엔진이 이어받기를 시도
 *   (서버가 Range를 지원하지 않으면 처음부터 다시 받을 수 있음)
 * - 취소 = 중단 + 엔진의 cleanup으로 부분 파일 삭제
 * - 상태는 [HistoryStore]에 저장되며, 앱 재시작 시 진행 중이던 작업은 PAUSED로 복원된다.
 */
class DownloadQueue(
    private val engine: DownloadEngine,
    private val scope: CoroutineScope,
    private val maxConcurrent: Int = 2,
    private val store: HistoryStore? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val idGen: () -> String = { UUID.randomUUID().toString() },
) {
    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks: StateFlow<List<DownloadTask>> = _tasks

    private val lock = Any()
    private val jobs = HashMap<String, Job>()

    fun restore() {
        val loaded = store?.load().orEmpty().map {
            if (it.state == TaskState.QUEUED || it.state == TaskState.ANALYZING || it.state == TaskState.DOWNLOADING) {
                it.copy(state = TaskState.PAUSED, speedBps = null, etaSec = null)
            } else it
        }
        _tasks.value = loaded
    }

    fun enqueue(request: DownloadRequest): EnqueueResult {
        val result = synchronized(lock) {
            val dup = _tasks.value.firstOrNull { it.state.isOpen && it.request.sameTarget(request) }
            if (dup != null) {
                EnqueueResult.Duplicate(dup.id)
            } else {
                val t = DownloadTask(id = idGen(), request = request, createdAt = clock())
                _tasks.update { it + t }
                EnqueueResult.Added(t)
            }
        }
        if (result is EnqueueResult.Added) {
            persist()
            schedule()
        }
        return result
    }

    fun pause(id: String) {
        val job = synchronized(lock) {
            val t = find(id) ?: return
            if (t.state != TaskState.QUEUED && t.state != TaskState.DOWNLOADING) return
            update(id) { it.copy(state = TaskState.PAUSED, speedBps = null, etaSec = null) }
            jobs[id]
        }
        job?.cancel()
        persist()
    }

    fun resume(id: String) {
        synchronized(lock) {
            if (find(id)?.state != TaskState.PAUSED) return
            update(id) { it.copy(state = TaskState.QUEUED) }
        }
        persist()
        schedule()
    }

    fun cancel(id: String) {
        val (job, req) = synchronized(lock) {
            val t = find(id) ?: return
            if (!t.state.isOpen) return
            update(id) { it.copy(state = TaskState.CANCELED, speedBps = null, etaSec = null) }
            jobs[id] to t.request
        }
        job?.cancel()
        persist()
        // 프로세스가 완전히 끝난 뒤에 부분 파일을 지운다.
        scope.launch {
            job?.join()
            try { engine.cleanup(id, req) } catch (e: Exception) { }
        }
        schedule()
    }

    fun retry(id: String) {
        synchronized(lock) {
            val t = find(id) ?: return
            if (t.state != TaskState.FAILED && t.state != TaskState.CANCELED) return
            update(id) {
                it.copy(
                    state = TaskState.QUEUED, error = null, errorKind = null,
                    downloadedBytes = 0, totalBytes = null, speedBps = null, etaSec = null,
                )
            }
        }
        persist()
        schedule()
    }

    /** 완료/실패/취소된 작업을 이력에서 제거한다(파일은 삭제하지 않는다). */
    fun remove(id: String) {
        synchronized(lock) {
            val t = find(id) ?: return
            if (t.state.isOpen) return
            _tasks.update { list -> list.filterNot { it.id == id } }
        }
        persist()
    }

    fun clearFinished() {
        _tasks.update { list -> list.filter { it.state.isOpen } }
        persist()
    }

    /** 완료된 파일이 삭제/이동되었는지 확인해 상태를 갱신한다. */
    fun refreshFileStatus(exists: (String) -> Boolean) {
        _tasks.update { list ->
            list.map { t ->
                val p = t.filePath
                if (t.state == TaskState.COMPLETED && p != null) t.copy(fileMissing = !exists(p)) else t
            }
        }
        persist()
    }

    private fun find(id: String): DownloadTask? = _tasks.value.firstOrNull { it.id == id }

    private fun update(id: String, f: (DownloadTask) -> DownloadTask) {
        _tasks.update { list -> list.map { if (it.id == id) f(it) else it } }
    }

    private fun persist() {
        try { store?.save(_tasks.value) } catch (e: Exception) { }
    }

    private fun schedule() {
        synchronized(lock) {
            var free = maxConcurrent - _tasks.value.count { it.state == TaskState.DOWNLOADING }
            for (t in _tasks.value.filter { it.state == TaskState.QUEUED }.sortedBy { it.createdAt }) {
                if (free <= 0) break
                launchTask(t.id)
                free--
            }
        }
    }

    /** lock을 잡은 상태에서 호출한다. */
    private fun launchTask(id: String) {
        update(id) { it.copy(state = TaskState.DOWNLOADING, error = null, errorKind = null) }
        val req = find(id)!!.request
        val previous = jobs[id] // 방금 일시정지된 이전 실행이 아직 끝나지 않았을 수 있다.
        lateinit var self: Job
        self = scope.launch(start = CoroutineStart.LAZY) {
            try {
                previous?.join()
                val path = engine.download(id, req) { p ->
                    update(id) { t ->
                        if (t.state == TaskState.DOWNLOADING) {
                            t.copy(
                                downloadedBytes = p.downloadedBytes,
                                totalBytes = p.totalBytes ?: t.totalBytes,
                                speedBps = p.speedBps,
                                etaSec = p.etaSec,
                            )
                        } else t
                    }
                }
                update(id) {
                    if (it.state == TaskState.DOWNLOADING) {
                        it.copy(state = TaskState.COMPLETED, filePath = path, speedBps = null, etaSec = null)
                    } else it
                }
                try { engine.cleanup(id, req) } catch (e: Exception) { }
            } catch (e: CancellationException) {
                throw e // 상태는 pause/cancel을 호출한 쪽에서 이미 바꿨다.
            } catch (e: DownloadException) {
                fail(id, e.kind, e.message)
            } catch (e: Exception) {
                fail(id, ErrorKind.UNKNOWN, e.message)
            } finally {
                synchronized(lock) { if (jobs[id] === self) jobs.remove(id) }
                persist()
                schedule()
            }
        }
        jobs[id] = self
        self.start()
    }

    private fun fail(id: String, kind: ErrorKind, message: String?) {
        update(id) {
            if (it.state == TaskState.DOWNLOADING) {
                it.copy(state = TaskState.FAILED, error = message ?: "알 수 없는 오류", errorKind = kind, speedBps = null, etaSec = null)
            } else it
        }
    }
}
