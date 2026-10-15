# 참고 자료 분석: SealX Downloader 2.3.3

이 문서는 사용자가 제공한 파일의 **구조 수준** 분석입니다. 디컴파일이나 코드 열람은 하지 않았고,
기존 앱의 코드·리소스·아이콘·문자열·디자인은 이 프로젝트에 사용하지 않았습니다.
각 항목은 **[확인]**(파일에서 직접 확인) 또는 **[추정]**(구성으로 미루어 짐작)으로 구분합니다.

## 분석한 파일

`com.hkapps.sealdownloader.apk`(base), `config.armeabi_v7a.apk`, `config.en.apk`, `config.mdpi.apk`, `manifest.json`, `icon.png`.
모두 읽을 수 있었습니다. (중복 업로드된 `_1` 파일은 크기가 동일한 사본이었습니다.)

## 확인된 사실

- **[확인]** XAPK 매니페스트: 패키지 `com.hkapps.sealdownloader`, 버전 2.3.3(코드 44), minSdk 25, targetSdk 36, 분할 APK 4개(base, ARMv7 ABI, 영어, mdpi), 전체 약 111MB.
- **[확인]** base APK는 항목 약 2,578개, `classes*.dex` 7개(합계 약 59MB).
- **[확인]** 네이티브 라이브러리(ARMv7 분할)에 `libpython`, `libffmpeg`, `libffprobe`, `libcronet` 등이 있음.
- **[확인]** 포함된 AndroidX 계열: Room, DataStore, Biometric, Media, Navigation, WebKit 등.
- **[확인] 광고·추적 스택이 매우 큼**
  - AppLovin MAX 중개 구성과 다수의 광고 네트워크 어댑터(Google, Meta Audience Network, Chartboost, Unity Ads, Pangle, Vungle, InMobi, Yandex, Ogury, Verve, Maticoo 등)에 대응하는 검증 파일과 에셋.
  - 광고용 HTML/JS 에셋(MRAID, OM SDK, 엔드카드 템플릿 등)과 Meta Audience Network dex.
  - Firebase Analytics, FCM, Google 광고 ID·Privacy Sandbox 광고 API·설치 리퍼러 관련 항목, UMP(동의) SDK 속성.
  - 권한: `AD_ID`, `ACCESS_ADSERVICES_*`, 설치 리퍼러, `com.amazon.privacypass.ATTEST` 등.
- **[확인]** 앱 기능용 권한: 인터넷, 저장소 읽기/쓰기, 포그라운드 서비스(미디어 재생), 알림, 배터리 최적화 제외 요청, `WRITE_SETTINGS`, 생체 인증, 진동, WAKE_LOCK.

## 추정 (코드를 보지 않았으므로 확정 아님)

- **[추정]** 다운로드 엔진은 Python 위에서 yt-dlp를 실행하고 ffmpeg로 병합·변환하는 구조 (Python/ffmpeg 네이티브 라이브러리 번들로부터).
- **[추정]** Room은 다운로드 이력/설정, Biometric은 앱 잠금, Media는 내장 재생 또는 재생 알림에 쓰일 가능성.
- **[추정]** `WRITE_SETTINGS`와 배터리 최적화 제외의 용도는 알 수 없음. 새 앱에는 필요하지 않아 넣지 않음.

## 확인할 수 없었던 것

- 화면 구성과 사용자 작업 흐름, 실제 기능 목록(디컴파일 없이는 알 수 없음).
- 광고가 어떤 조건에서 표시되는지, 광고 시청이 기능 제한과 연동되는지.

## 새 앱에서 독립적으로 재구현하는 기능 요구사항

일반적인 yt-dlp 기반 다운로더에 공통되는 기능과 프로젝트 요구사항에서 도출했습니다.

| 요구사항 | 근거 | 새 앱 상태 |
|---|---|---|
| URL 분석(제목·썸네일·길이·형식) | 일반 기능 | 구현(미검증) |
| 영상/오디오, 화질·컨테이너·오디오 형식 선택 | 일반 기능 | 구현(미검증) |
| 대기열, 진행률, 일시정지/재개/취소/재시도 | 요구사항 | 구현, 단위 테스트 작성(미실행) |
| 이력 저장·복원 | Room 존재(추정) | 구현(JSON 파일), 테스트 일부 |
| 광고·추적 없음 | 요구사항 | 소스 수준 검사 통과, 빌드 후 검사는 CI 예정 |
| 앱 잠금(생체 인증) | Biometric 존재(추정) | 미구현 |
| 백그라운드 다운로드 서비스·알림 | 포그라운드 서비스 권한 존재 | 미구현 |
