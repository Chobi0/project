#!/usr/bin/env bash
# 광고/광고 추적 관련 의존성·권한이 소스와 (있다면) 병합된 매니페스트에 없는지 검사한다.
# 사용법: scripts/ad_audit.sh [저장소 루트]   → 발견되면 종료 코드 1
set -u
ROOT="${1:-.}"
PATTERN='applovin|admob|gms\.ads|play-services-ads|permission\.AD_ID|ACCESS_ADSERVICES|privacysandbox\.ads|audience_network|facebook\.ads|unity3d\.ads|unityads|chartboost|pangle|bytedance\.sdk|vungle|inmobi|ironsource|mbridge|mintegral|yandex\.mobileads|ogury|maticoo|appsflyer|adjust\.sdk|firebase-analytics|firebase-crashlytics|user-messaging-platform|install_referrer|installreferrer'

fail=0
echo "== 광고/추적 의존성 검사 =="

mapfile -t files < <(
  find "$ROOT" \( -path '*/build' -o -path '*/.gradle' -o -path '*/.git' -o -path '*/node_modules' \) -prune -o \
    -type f \( -name '*.gradle' -o -name '*.gradle.kts' -o -name '*.toml' -o -name 'AndroidManifest.xml' -o -name 'proguard*.pro' \) -print
)
echo "검사 대상 소스 파일: ${#files[@]}개"
for f in "${files[@]}"; do
  if grep -nEi "$PATTERN" "$f" >/tmp/ad_hits.txt 2>/dev/null; then
    echo "[발견] $f"; sed 's/^/    /' /tmp/ad_hits.txt; fail=1
  fi
done

# 빌드 후 병합된 매니페스트(있을 때만)
mapfile -t merged < <(find "$ROOT" -path '*/build/*' -name 'AndroidManifest.xml' \( -path '*merged_manifest*' -o -path '*packaged_manifests*' \) 2>/dev/null)
echo "병합된 매니페스트: ${#merged[@]}개"
for f in "${merged[@]}"; do
  if grep -nEi "$PATTERN" "$f" >/tmp/ad_hits.txt 2>/dev/null; then
    echo "[발견-병합] $f"; sed 's/^/    /' /tmp/ad_hits.txt; fail=1
  else
    echo "[깨끗함] $f"
    echo "  선언된 권한:"; grep -o 'uses-permission[^>]*android:name="[^"]*"' "$f" | sed -E 's/.*android:name="([^"]*)"/    \1/' | sort -u
  fi
done

if [ "$fail" -eq 0 ]; then echo "결과: 광고/추적 관련 항목 없음"; else echo "결과: 광고/추적 관련 항목 발견 — 위 목록 확인"; fi
exit $fail
