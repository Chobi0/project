package app.clearfetch.shared

import java.net.URI

/**
 * 사용자가 입력한 URL을 엔진에 넘기기 전에 검사한다.
 * - http/https만 허용 (file://, ftp:// 등 차단)
 * - 주소에 인증 정보(user:pass@) 포함 금지
 * - localhost, 사설 IPv4, IPv6 리터럴 직접 입력 차단
 *
 * 한계: 도메인이 DNS 조회 후 사설 IP로 풀리는 경우(DNS rebinding)는 여기서 검사하지 않는다.
 * docs/ARCHITECTURE.md의 "알려진 제한" 참고.
 */
object UrlValidator {
    sealed interface Result {
        data class Ok(val url: String) : Result
        data class Invalid(val reason: String) : Result
    }

    fun validate(raw: String): Result {
        val s = raw.trim()
        if (s.isEmpty()) return Result.Invalid("URL이 비어 있습니다.")
        if (s.any { it.isISOControl() || it.isWhitespace() }) {
            return Result.Invalid("URL에 공백이나 제어 문자를 넣을 수 없습니다.")
        }
        val uri = try {
            URI(s)
        } catch (e: Exception) {
            return Result.Invalid("URL 형식이 올바르지 않습니다.")
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            return Result.Invalid("http 또는 https 주소만 지원합니다.")
        }
        if (uri.userInfo != null) return Result.Invalid("주소에 인증 정보를 넣을 수 없습니다.")
        val host = uri.host ?: return Result.Invalid("호스트를 찾을 수 없습니다.")
        if (isBlockedHost(host.lowercase())) {
            return Result.Invalid("로컬 또는 사설 네트워크 주소는 허용되지 않습니다.")
        }
        return Result.Ok(s)
    }

    private fun isBlockedHost(host: String): Boolean {
        if (host == "localhost" || host.endsWith(".localhost") ||
            host.endsWith(".local") || host.endsWith(".internal")
        ) return true
        if (host.startsWith("[")) return true // IPv6 리터럴은 일괄 차단
        val parts = host.split(".")
        if (parts.size == 4 && parts.all { p -> p.isNotEmpty() && p.all { it.isDigit() } }) {
            val n = parts.map { it.toIntOrNull() ?: return true }
            if (n.any { it !in 0..255 }) return true
            val (a, b) = n[0] to n[1]
            return a == 0 || a == 10 || a == 127 ||
                (a == 169 && b == 254) ||
                (a == 172 && b in 16..31) ||
                (a == 192 && b == 168) ||
                (a == 100 && b in 64..127)
        }
        return false
    }
}
