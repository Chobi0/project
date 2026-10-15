# 빌드 방법

## 현재 검증 상태 (중요)

이 프로젝트의 소스는 **컴파일/테스트를 실행할 수 없는 환경에서 작성**되었습니다(Gradle, Android SDK, 인터넷 없음).
따라서 아래는 모두 **아직 한 번도 빌드되지 않은** 상태이며, 첫 CI 실행에서 컴파일 오류가 나올 수 있습니다.

| 항목 | 상태 |
|---|---|
| 소스·문서·워크플로 작성 | 완료 |
| `scripts/ad_audit.sh` 실행 | 완료 (소스 검사 통과, 양성 대조군 탐지 확인) |
| `shared` 컴파일 / 단위 테스트 | **미실행** |
| Android APK 빌드 | **미실행** |
| Windows 앱 빌드 | **미실행** |
| GitHub Actions | **미실행** |

## 준비물

JDK 17, Gradle 8.9(또는 아래 wrapper 생성), Android Studio(Android 빌드 시), Windows(데스크톱 앱 빌드·실행 시).

## Gradle wrapper

`gradle-wrapper.jar`는 바이너리라 포함하지 않았습니다. 처음 한 번 생성하세요.

```
gradle wrapper --gradle-version 8.9
git add gradlew gradlew.bat gradle/wrapper
```

CI는 wrapper 없이 `gradle/actions/setup-gradle`의 Gradle 8.9를 직접 사용합니다.

## 로컬 빌드

```
gradle :shared:test                       # 단위 테스트
gradle :androidApp:assembleDebug          # androidApp/build/outputs/apk/debug/
gradle :desktopApp:run                    # 데스크톱 앱 실행
gradle :desktopApp:createDistributable    # desktopApp/build/compose/binaries/main/app/
scripts/ad_audit.sh .                     # 광고/추적 의존성 검사
```

## 데스크톱 앱의 yt-dlp / ffmpeg

앱에 포함하지 않습니다(재배포 라이선스 조건을 피하기 위해). 별도로 설치하고 PATH에 두거나 앱의 설정 탭에서 경로를 지정하세요.
예: `winget install yt-dlp.yt-dlp` / `winget install Gyan.FFmpeg` (패키지 ID는 설치 전에 `winget search`로 확인하세요).

## 알려진 위험 (첫 빌드에서 확인할 것)

1. **youtubedl-android 좌표·버전·API**: `gradle/libs.versions.toml`의 `io.github.junkfood02.youtubedl-android:*:0.17.2`는
   기억에 의존한 값이라 Maven Central에 실제로 있는지 확인이 필요합니다. 패키지 이름(`com.yausername.*`)과
   `execute(request, processId, callback)` 시그니처도 다를 수 있습니다. 라이브러리의 라이선스(GPL 여부)와 ffmpeg 빌드 조건도 확인하세요.
2. **Compose 관련 실험적 API**: `FilterChip` 등에 `@OptIn`을 붙였지만 버전에 따라 경고/오류가 날 수 있습니다.
3. **AGP 8.5.2 + Kotlin 2.0.21 + compileSdk 35** 조합은 일반적이지만 실제 빌드로 확인되지 않았습니다.
4. **MSI 패키징**은 설정만 있고 CI는 설치 불필요한 `createDistributable`만 실행합니다.

## 릴리스 서명 (미구성)

`.github/workflows/release.yml`은 안내용 뼈대입니다. 서명 키는 저장소에 커밋하지 말고
GitHub Secrets(`ANDROID_KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`)로만 주입하세요.
