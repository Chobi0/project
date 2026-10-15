package app.clearfetch.desktop

/** 데스크톱 UI 문자열 모음. 다국어 확장 시 이 객체를 로케일별 구현으로 교체한다. */
object Strings {
    const val appName = "ClearFetch"
    const val tabDownload = "다운로드"
    const val tabSettings = "설정"
    const val urlHint = "영상 또는 오디오 주소(URL)를 붙여넣으세요"
    const val analyze = "분석"
    const val analyzing = "분석 중…"
    const val kindVideo = "영상"
    const val kindAudio = "오디오"
    const val quality = "화질"
    const val qualityBest = "최고 화질"
    const val container = "컨테이너"
    const val audioFormat = "오디오 형식"
    const val fileNameHint = "파일 이름(선택, 확장자 제외)"
    const val saveTo = "저장 위치"
    const val change = "변경"
    const val addToQueue = "대기열에 추가"
    const val duplicate = "이미 대기열에 있는 같은 작업입니다."
    const val queueTitle = "다운로드 목록"
    const val queueEmpty = "아직 다운로드한 항목이 없습니다. 위에 주소를 붙여넣고 분석해 보세요."
    const val pause = "일시정지"
    const val resume = "재개"
    const val cancel = "취소"
    const val retry = "재시도"
    const val open = "열기"
    const val openFolder = "폴더 열기"
    const val remove = "목록에서 제거"
    const val clearFinished = "완료 항목 지우기"
    const val fileMissing = "파일이 삭제되었거나 이동되었습니다"
    const val resumeNote = "참고: 이어받기는 서버가 지원할 때만 가능하며, 지원하지 않으면 처음부터 다시 받습니다."
    const val settingsYtDlp = "yt-dlp 실행 파일 경로"
    const val settingsFfmpeg = "ffmpeg 폴더 경로(선택)"
    const val settingsOutput = "기본 저장 폴더"
    const val settingsSave = "저장"
    const val settingsSaved = "저장했습니다."
    const val settingsHelp = "yt-dlp와 ffmpeg는 앱에 포함되어 있지 않습니다. PATH에 있거나 여기에 경로를 지정하세요. (docs/BUILD.md 참고)"

    fun state(s: app.clearfetch.shared.TaskState) = when (s) {
        app.clearfetch.shared.TaskState.QUEUED -> "대기 중"
        app.clearfetch.shared.TaskState.ANALYZING -> "분석 중"
        app.clearfetch.shared.TaskState.DOWNLOADING -> "다운로드 중"
        app.clearfetch.shared.TaskState.PAUSED -> "일시정지"
        app.clearfetch.shared.TaskState.COMPLETED -> "완료"
        app.clearfetch.shared.TaskState.FAILED -> "실패"
        app.clearfetch.shared.TaskState.CANCELED -> "취소됨"
    }

    fun errorKind(k: app.clearfetch.shared.ErrorKind) = when (k) {
        app.clearfetch.shared.ErrorKind.NETWORK -> "네트워크 오류"
        app.clearfetch.shared.ErrorKind.UNSUPPORTED_URL -> "지원하지 않는 주소"
        app.clearfetch.shared.ErrorKind.AUTH_REQUIRED -> "로그인/권한 필요"
        app.clearfetch.shared.ErrorKind.ENGINE_MISSING -> "엔진(yt-dlp) 없음"
        app.clearfetch.shared.ErrorKind.DISK -> "저장 오류"
        app.clearfetch.shared.ErrorKind.UNKNOWN -> "알 수 없는 오류"
    }
}
