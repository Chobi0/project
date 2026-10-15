package app.clearfetch.shared

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/** URL 분석 상태를 관리한다. 새 분석을 시작하면 이전 분석(및 하위 프로세스)은 취소된다. */
class AnalysisController(
    private val engine: DownloadEngine,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<AnalysisState>(AnalysisState.Idle)
    val state: StateFlow<AnalysisState> = _state
    private var job: Job? = null

    fun analyze(raw: String) {
        job?.cancel()
        when (val v = UrlValidator.validate(raw)) {
            is UrlValidator.Result.Invalid -> {
                _state.value = AnalysisState.Failed(ErrorKind.UNSUPPORTED_URL, v.reason)
            }
            is UrlValidator.Result.Ok -> {
                _state.value = AnalysisState.Loading
                job = scope.launch {
                    try {
                        _state.value = AnalysisState.Ready(engine.analyze(v.url))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: DownloadException) {
                        _state.value = AnalysisState.Failed(e.kind, e.message ?: "분석 실패")
                    } catch (e: Exception) {
                        _state.value = AnalysisState.Failed(ErrorKind.UNKNOWN, e.message ?: "분석 실패")
                    }
                }
            }
        }
    }

    fun reset() {
        job?.cancel()
        _state.value = AnalysisState.Idle
    }
}
