# 아키텍처

## 구조

```
shared/       순수 Kotlin(JVM) 라이브러리 — 모델, 대기열, 파서, URL 검증, 저장소, 프로세스 엔진
androidApp/   Android 앱 (Compose + youtubedl-android 엔진 + MediaStore 저장)
desktopApp/   Windows 앱 (Compose Desktop + yt-dlp 프로세스 엔진)
```

`shared`는 UI와 플랫폼에 의존하지 않으며 `DownloadEngine` 인터페이스만 노출합니다.
Android는 `AndroidYtDlpEngine`, 데스크톱은 `ProcessYtDlpEngine`을 주입합니다.
대기열(`DownloadQueue`)과 분석 컨트롤러(`AnalysisController`)는 두 플랫폼이 그대로 공유합니다.

## 기술 스택 선택과 이유

선택: **Kotlin + Compose (Android는 Jetpack Compose, 데스크톱은 Compose Desktop) + JVM 공통 모듈**

- Android와 Windows 모두 JVM에서 돌아가므로, 공통 로직은 Kotlin Multiplatform 없이 평범한 JVM 모듈로 공유해도 충분합니다.
  KMP를 쓰면 `expect/actual`과 Android 타깃 설정이 늘어 빌드 복잡도만 커지고 이득이 작습니다.
- 다운로드 엔진은 어차피 플랫폼별 네이티브 연동(Android: 번들 라이브러리, Windows: 외부 실행 파일)이라 UI까지 통일해도 이득이 작습니다.
- 나중에 macOS/Linux는 `desktopApp`을 재사용해 확장할 수 있습니다(yt-dlp 경로만 다름).
  iOS 등을 추가하게 되면 그때 `shared`를 KMP로 전환하는 것이 자연스럽습니다.

포기한 대안
- **Flutter**: 엔진 연동이 플랫폼 채널로 다시 네이티브 코드를 요구해 공통화 이득이 작고, JVM 쪽 자산(코루틴, 테스트)을 못 씁니다.
- **Kotlin Multiplatform + Compose Multiplatform 전체 공유**: UI까지 공유 가능하지만 Android 저장소·서비스는 어차피 별도이고, 빌드 설정 위험이 큽니다.
  개발 환경에서 컴파일을 검증할 수 없는 상황이라 위험을 줄이는 쪽을 택했습니다.

## 다운로드 흐름

1. `AnalysisController.analyze(url)` → `UrlValidator`로 검증 → `engine.analyze` → `MediaInfo`
2. 사용자가 옵션 선택 → `DownloadRequest` → `DownloadQueue.enqueue` (중복이면 거절)
3. 큐가 동시 실행 수 한도 안에서 `engine.download` 실행, 진행률을 `StateFlow`로 갱신
4. 일시정지: 실행 중단 + 부분 파일 유지 / 재개: 같은 임시 폴더로 재실행(yt-dlp `--continue`)
5. 취소: 중단 후 `engine.cleanup`으로 임시 폴더 삭제
6. 상태는 전이마다 JSON 파일에 저장, 재시작 시 진행 중이던 작업은 `PAUSED`로 복원

## 보안 설계

- URL은 http/https만 허용, 인증 정보 포함·localhost·사설 IPv4·IPv6 리터럴 차단.
- yt-dlp는 셸 없이 인자 배열로 실행하고, URL 앞에 `--`를 둡니다. 컨테이너/오디오 형식은 허용 목록만 통과합니다.
- 사용자 지정 파일 이름은 경로 구분자·Windows 금지 문자·예약어를 제거하고, 출력 템플릿의 `%`를 이스케이프합니다.
- 인증·로그인 우회는 구현하지 않으며, 인증이 필요한 소스는 `AUTH_REQUIRED` 오류로 알립니다.
- 로그/오류 메시지에는 yt-dlp의 마지막 오류 한 줄만 노출하고 토큰·쿠키를 다루지 않습니다.

## 알려진 제한

- 도메인이 사설 IP로 풀리는 경우(DNS rebinding)와 리디렉션 대상은 앱이 검사하지 않습니다(yt-dlp가 처리).
- 이어받기는 서버가 HTTP Range를 지원할 때만 가능합니다. 아니면 처음부터 다시 받습니다.
- 같은 이름의 파일 충돌: 사용자가 이름을 지정하면 `이름 (1)`로 피합니다. 지정하지 않으면 yt-dlp `--no-overwrites`로 덮어쓰기를 막고, 이미 있으면 오류로 알립니다.
- `TaskState.ANALYZING`은 모델에 있지만 대기열에서는 쓰지 않습니다. 분석은 다운로드 전 별도 단계입니다.
- 재생목록은 첫 항목만 처리합니다(`--no-playlist`).
- Android는 minSdk 29 이상만 지원하며, 포그라운드 서비스·알림이 없어 앱이 백그라운드로 가면 다운로드가 중단될 수 있습니다.
