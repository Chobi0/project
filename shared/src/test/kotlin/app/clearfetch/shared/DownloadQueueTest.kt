package app.clearfetch.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

private class FakeEngine : DownloadEngine {
    val gates = mutableMapOf<String, CompletableDeferred<String>>()
    val cleaned = mutableListOf<String>()
    val started = mutableListOf<String>()
    var failNext: DownloadException? = null

    override suspend fun analyze(url: String): MediaInfo = error("unused")

    override suspend fun download(taskId: String, request: DownloadRequest, onProgress: (Progress) -> Unit): String {
        started += taskId
        failNext?.let { failNext = null; throw it }
        val gate = gates.getOrPut(taskId) { CompletableDeferred() }
        onProgress(Progress(50, 100, 10, 5))
        return gate.await()
    }

    override fun cleanup(taskId: String, request: DownloadRequest) { cleaned += taskId }
}

private class MemStore(var data: List<DownloadTask> = emptyList()) : HistoryStore {
    override fun load() = data
    override fun save(tasks: List<DownloadTask>) { data = tasks }
}

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadQueueTest {
    private fun req(url: String = "https://example.com/a") =
        DownloadRequest(url, "t", Kind.VIDEO, outputDir = "/out")

    private fun newQueue(engine: FakeEngine, scope: kotlinx.coroutines.CoroutineScope, max: Int = 2, store: HistoryStore? = null): DownloadQueue {
        var n = 0
        return DownloadQueue(engine, scope, max, store, clock = { 1L }, idGen = { "t${++n}" })
    }

    private fun DownloadQueue.state(id: String) = tasks.value.first { it.id == id }.state

    @Test fun completesAndStoresPath() = runTest {
        val engine = FakeEngine()
        val q = newQueue(engine, backgroundScope)
        q.enqueue(req())
        runCurrent()
        assertEquals(TaskState.DOWNLOADING, q.state("t1"))
        assertEquals(50L, q.tasks.value[0].downloadedBytes)
        assertEquals(100L, q.tasks.value[0].totalBytes)
        engine.gates["t1"]!!.complete("/out/a.mp4")
        runCurrent()
        assertEquals(TaskState.COMPLETED, q.state("t1"))
        assertEquals("/out/a.mp4", q.tasks.value[0].filePath)
        assertTrue("t1" in engine.cleaned)
    }

    @Test fun rejectsDuplicatesWhileOpenButAllowsAfterFinish() = runTest {
        val engine = FakeEngine()
        val q = newQueue(engine, backgroundScope)
        assertIs<EnqueueResult.Added>(q.enqueue(req()))
        val dup = q.enqueue(req())
        assertIs<EnqueueResult.Duplicate>(dup)
        assertEquals("t1", dup.existingId)
        runCurrent()
        engine.gates["t1"]!!.complete("/out/a.mp4")
        runCurrent()
        assertIs<EnqueueResult.Added>(q.enqueue(req()))
    }

    @Test fun respectsConcurrencyLimit() = runTest {
        val engine = FakeEngine()
        val q = newQueue(engine, backgroundScope, max = 1)
        q.enqueue(req("https://example.com/1"))
        q.enqueue(req("https://example.com/2"))
        runCurrent()
        assertEquals(TaskState.DOWNLOADING, q.state("t1"))
        assertEquals(TaskState.QUEUED, q.state("t2"))
        engine.gates["t1"]!!.complete("/out/1.mp4")
        runCurrent()
        assertEquals(TaskState.COMPLETED, q.state("t1"))
        assertEquals(TaskState.DOWNLOADING, q.state("t2"))
    }

    @Test fun pauseKeepsPartialFilesAndResumeContinues() = runTest {
        val engine = FakeEngine()
        val q = newQueue(engine, backgroundScope)
        q.enqueue(req())
        runCurrent()
        q.pause("t1")
        runCurrent()
        assertEquals(TaskState.PAUSED, q.state("t1"))
        assertTrue(engine.cleaned.isEmpty(), "일시정지는 부분 파일을 지우면 안 된다")
        q.resume("t1")
        runCurrent()
        assertEquals(TaskState.DOWNLOADING, q.state("t1"))
        assertEquals(2, engine.started.size)
        engine.gates["t1"]!!.complete("/out/a.mp4")
        runCurrent()
        assertEquals(TaskState.COMPLETED, q.state("t1"))
    }

    @Test fun cancelCleansUp() = runTest {
        val engine = FakeEngine()
        val q = newQueue(engine, backgroundScope)
        q.enqueue(req())
        runCurrent()
        q.cancel("t1")
        runCurrent()
        assertEquals(TaskState.CANCELED, q.state("t1"))
        assertEquals(listOf("t1"), engine.cleaned)
    }

    @Test fun failureThenRetry() = runTest {
        val engine = FakeEngine()
        engine.failNext = DownloadException(ErrorKind.NETWORK, "timeout")
        val q = newQueue(engine, backgroundScope)
        q.enqueue(req())
        runCurrent()
        assertEquals(TaskState.FAILED, q.state("t1"))
        assertEquals(ErrorKind.NETWORK, q.tasks.value[0].errorKind)
        assertEquals("timeout", q.tasks.value[0].error)
        q.retry("t1")
        runCurrent()
        assertEquals(TaskState.DOWNLOADING, q.state("t1"))
        assertNull(q.tasks.value[0].error)
        engine.gates["t1"]!!.complete("/out/a.mp4")
        runCurrent()
        assertEquals(TaskState.COMPLETED, q.state("t1"))
    }

    @Test fun restoreTurnsInterruptedTasksIntoPaused() = runTest {
        val store = MemStore(
            listOf(
                DownloadTask("a", req(), TaskState.DOWNLOADING, createdAt = 1),
                DownloadTask("b", req("https://example.com/b"), TaskState.COMPLETED, filePath = "/x", createdAt = 2),
                DownloadTask("c", req("https://example.com/c"), TaskState.FAILED, createdAt = 3),
            ),
        )
        val q = newQueue(FakeEngine(), backgroundScope, store = store)
        q.restore()
        assertEquals(TaskState.PAUSED, q.state("a"))
        assertEquals(TaskState.COMPLETED, q.state("b"))
        assertEquals(TaskState.FAILED, q.state("c"))
    }

    @Test fun refreshFileStatusMarksMissingFiles() = runTest {
        val engine = FakeEngine()
        val q = newQueue(engine, backgroundScope)
        q.enqueue(req())
        runCurrent()
        engine.gates["t1"]!!.complete("/out/a.mp4")
        runCurrent()
        q.refreshFileStatus { false }
        assertTrue(q.tasks.value[0].fileMissing)
        q.refreshFileStatus { true }
        assertTrue(!q.tasks.value[0].fileMissing)
    }

    @Test fun removeOnlyFinishedTasks() = runTest {
        val engine = FakeEngine()
        val q = newQueue(engine, backgroundScope)
        q.enqueue(req())
        runCurrent()
        q.remove("t1") // 진행 중이므로 무시
        assertEquals(1, q.tasks.value.size)
        engine.gates["t1"]!!.complete("/out/a.mp4")
        runCurrent()
        q.remove("t1")
        assertEquals(0, q.tasks.value.size)
    }
}
