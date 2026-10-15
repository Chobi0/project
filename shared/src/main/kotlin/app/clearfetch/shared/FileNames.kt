package app.clearfetch.shared

import java.io.File

object FileNames {
    private val reserved = setOf(
        "CON", "PRN", "AUX", "NUL",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9",
    )
    private val forbidden = "\\/:*?\"<>|"

    /** 경로 구분자·Windows 금지 문자·제어 문자를 제거해 안전한 파일 이름(확장자 제외)으로 만든다. */
    fun sanitize(name: String): String {
        var s = name.map { if (it in forbidden || it.isISOControl()) '_' else it }.joinToString("")
        s = s.trim().trim('.').trim()
        if (s.length > 120) s = s.take(120).trim()
        if (s.isEmpty()) return "download"
        if (s.substringBefore('.').uppercase() in reserved) s = "_$s"
        return s
    }

    /** dir 안에 `stem.` 으로 시작하는 파일이 있으면 "stem (1)", "stem (2)"... 로 피한다. */
    fun uniqueStem(dir: File, stem: String): String {
        fun taken(candidate: String): Boolean =
            dir.listFiles()?.any { it.name.startsWith("$candidate.") } == true
        if (!taken(stem)) return stem
        var i = 1
        while (taken("$stem ($i)")) i++
        return "$stem ($i)"
    }
}
