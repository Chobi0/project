# ClearFetch

광고도 추적도 없는 오픈소스 미디어 다운로더. Android와 Windows를 지원하는 것이 목표이며,
다운로드 엔진은 [yt-dlp](https://github.com/yt-dlp/yt-dlp)를 사용합니다.

> **상태: 초기 소스. 아직 한 번도 빌드·실행되지 않았습니다.**
> 소스와 CI는 작성되었지만 이 코드는 컴파일을 검증할 수 없는 환경에서 만들어졌습니다.
> GitHub Actions의 첫 실행 결과로 오류를 고쳐 나가야 합니다. 자세한 내용은 [docs/BUILD.md](docs/BUILD.md).

사용자는 본인이 다운로드할 권리가 있는 콘텐츠만 받아야 하며, 각 사이트의 이용약관과 저작권법을 지켜야 합니다.
이 앱은 DRM이나 로그인 제한을 우회하지 않습니다.

## 기능 현황

| 기능 | Android | Windows |
|---|---|---|
| URL 검증·분석(제목, 썸네일, 길이, 형식) | 작성됨(미검증) | 작성됨(미검증) |
| 영상/오디오, 화질·컨테이너·오디오 형식 선택 | 작성됨(미검증) | 작성됨(미검증) |
| 대기열·진행률·속도·남은 시간 | 작성됨(미검증) | 작성됨(미검증) |
| 일시정지·재개·취소·재시도 | 작성됨(미검증) | 작성됨(미검증) |
| 이력 저장·재시작 복원, 파일 존재 확인 | 작성됨(미검증) | 작성됨(미검증) |
| 저장 위치 | 다운로드/ClearFetch 고정 | 폴더 선택 가능 |
| 파일 열기 / 폴더 열기 | 열기만 | 둘 다 |
| 백그라운드 서비스·알림 | **미구현** | 해당 없음 |
| yt-dlp 업데이트 | **미구현** | 사용자가 직접 |
| 앱 잠금, 다크 모드 | 다크 모드만(시스템 따름) | 다크 모드만(시스템 따름) |
| 다국어 | 한국어만 | 한국어만(문자열 분리됨) |

핵심 로직(`shared`)은 단위 테스트가 작성되어 있으나 **아직 실행되지 않았습니다**.

## 구조

[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) 참고. `shared/`(공통), `androidApp/`, `desktopApp/`.

## 빌드

[docs/BUILD.md](docs/BUILD.md) 참고. 산출물은 GitHub Actions의 아티팩트로 내려받습니다
(`clearfetch-android-debug-apk`, `clearfetch-windows-app`).

## 문서

- [참고 자료 분석](docs/REFERENCE_ANALYSIS.md) · [광고 제외 감사](docs/AD_FREE_AUDIT.md) · [서드파티 라이선스](docs/THIRD_PARTY_LICENSES.md)

## 라이선스

GPL-3.0. [LICENSE](LICENSE), [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
