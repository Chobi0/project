package app.clearfetch.shared

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class FileNamesTest {
    @Test fun removesPathAndForbiddenChars() {
        val s = FileNames.sanitize("../../etc/pa:ss*wd?")
        assertFalse(s.contains("/") || s.contains(":") || s.contains("*") || s.contains("?"))
    }

    @Test fun handlesEmptyDotsAndReservedNames() {
        assertEquals("download", FileNames.sanitize("   "))
        assertEquals("download", FileNames.sanitize("..."))
        assertEquals("_CON", FileNames.sanitize("CON"))
        assertEquals("_nul.txt", FileNames.sanitize("nul.txt"))
    }

    @Test fun truncatesLongNames() {
        assertEquals(120, FileNames.sanitize("a".repeat(500)).length)
    }

    @Test fun keepsKoreanNames() = assertEquals("영상 제목", FileNames.sanitize("영상 제목"))

    @Test fun uniqueStemAvoidsConflicts() {
        val dir = Files.createTempDirectory("cf").toFile()
        try {
            assertEquals("a", FileNames.uniqueStem(dir, "a"))
            dir.resolve("a.mp4").writeText("x")
            assertEquals("a (1)", FileNames.uniqueStem(dir, "a"))
            dir.resolve("a (1).mkv").writeText("x")
            assertEquals("a (2)", FileNames.uniqueStem(dir, "a"))
        } finally {
            dir.deleteRecursively()
        }
    }
}
