package app.clearfetch.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class YtDlpTest {
    private val sampleInfo = """
    {
      "title": "테스트 영상", "uploader": "채널A", "duration": 125.0,
      "thumbnail": "https://example.com/t.jpg", "_type": "video",
      "formats": [
        {"format_id":"sb0","ext":"mhtml","vcodec":"none","acodec":"none"},
        {"format_id":"140","ext":"m4a","vcodec":"none","acodec":"mp4a.40.2","abr":129.5,"filesize":2000000},
        {"format_id":"137","ext":"mp4","vcodec":"avc1","acodec":"none","height":1080,"resolution":"1920x1080","tbr":4000,"filesize_approx":50000000},
        {"format_id":"136","ext":"mp4","vcodec":"avc1","acodec":"none","height":720,"resolution":"1280x720"},
        {"format_id":"18","ext":"mp4","vcodec":"avc1","acodec":"mp4a","height":360}
      ]
    }
    """.trimIndent()

    @Test fun parsesInfo() {
        val info = YtDlpParser.parseInfo("https://example.com/v", sampleInfo)
        assertEquals("테스트 영상", info.title)
        assertEquals("채널A", info.uploader)
        assertEquals(125L, info.durationSec)
        assertEquals(4, info.formats.size) // 스토리보드 제외
        assertEquals(listOf(1080, 720, 360), info.availableHeights())
        val audio = info.formats.first { it.id == "140" }
        assertTrue(audio.hasAudio && !audio.hasVideo)
        assertEquals(2_000_000L, audio.sizeBytes)
        assertEquals(50_000_000L, info.formats.first { it.id == "137" }.sizeBytes)
    }

    @Test fun formatsWithoutCodecInfoAreTreatedAsPlayable() {
        val info = YtDlpParser.parseInfo("u", """{"title":"x","formats":[{"format_id":"0","ext":"mp4"}]}""")
        assertEquals(1, info.formats.size)
        assertTrue(info.formats[0].hasVideo && info.formats[0].hasAudio)
    }

    @Test fun invalidJsonThrows() {
        val e = runCatching { YtDlpParser.parseInfo("u", "not json") }.exceptionOrNull()
        assertTrue(e is DownloadException)
    }

    @Test fun parsesProgress() {
        val p = YtDlpParser.parseProgress("PROG|1048576|NA|2097152|524288.5|4")
        assertNotNull(p)
        assertEquals(1_048_576L, p.downloadedBytes)
        assertEquals(2_097_152L, p.totalBytes) // total_bytes가 NA면 추정치 사용
        assertEquals(524_288L, p.speedBps)
        assertEquals(4L, p.etaSec)
    }

    @Test fun progressHandlesNaAndForeignLines() {
        val p = YtDlpParser.parseProgress("PROG|100|NA|NA|NA|NA")
        assertNotNull(p)
        assertNull(p.totalBytes); assertNull(p.speedBps); assertNull(p.etaSec)
        assertNull(YtDlpParser.parseProgress("[download] 50% of 10MiB"))
        assertNull(YtDlpParser.parseProgress("PROG|NA|NA|NA|NA|NA"))
    }

    @Test fun classifiesErrors() {
        assertEquals(ErrorKind.UNSUPPORTED_URL, YtDlpParser.classifyError("ERROR: Unsupported URL: https://x"))
        assertEquals(ErrorKind.AUTH_REQUIRED, YtDlpParser.classifyError("ERROR: Sign in to confirm"))
        assertEquals(ErrorKind.AUTH_REQUIRED, YtDlpParser.classifyError("ERROR: Private video"))
        assertEquals(ErrorKind.NETWORK, YtDlpParser.classifyError("ERROR: Unable to download webpage: timed out"))
        assertEquals(ErrorKind.DISK, YtDlpParser.classifyError("OSError: No space left on device"))
        assertEquals(ErrorKind.UNKNOWN, YtDlpParser.classifyError("something odd"))
    }

    private fun req(kind: Kind, h: Int? = null, container: String? = null, audio: String? = null, name: String? = null) =
        DownloadRequest("https://example.com/v", "t", kind, h, container, audio, "/out", name)

    private fun cmd(r: DownloadRequest, stem: String?) =
        YtDlpArgs.toCommand("yt-dlp", YtDlpArgs.downloadOptions(r, "/out/.tmp", null, stem), r.url)

    @Test fun urlComesAfterDoubleDashAndIsLast() {
        val c = cmd(req(Kind.VIDEO), null)
        assertEquals("--", c[c.size - 2])
        assertEquals("https://example.com/v", c.last())
        assertEquals("yt-dlp", c.first())
    }

    @Test fun videoHeightAndContainer() {
        val c = cmd(req(Kind.VIDEO, h = 720, container = "mp4"), null)
        assertEquals("bv*[height<=720]+ba/b[height<=720]", c[c.indexOf("-f") + 1])
        assertEquals("mp4", c[c.indexOf("--merge-output-format") + 1])
    }

    @Test fun unknownContainerAndAudioFormatAreIgnored() {
        assertTrue("--merge-output-format" !in cmd(req(Kind.VIDEO, container = "exe"), null))
        assertTrue("--audio-format" !in cmd(req(Kind.AUDIO, audio = "evil"), null))
    }

    @Test fun audioOptions() {
        val c = cmd(req(Kind.AUDIO, audio = "mp3"), null)
        assertTrue("-x" in c)
        assertEquals("mp3", c[c.indexOf("--audio-format") + 1])
    }

    @Test fun fileNamePercentIsEscaped() {
        val c = cmd(req(Kind.VIDEO), "100% %(title)s")
        assertEquals("100%% %%(title)s.%(ext)s", c[c.indexOf("-o") + 1])
    }

    @Test fun homeAndTempPaths() {
        val c = cmd(req(Kind.VIDEO), null)
        assertTrue("home:/out" in c)
        assertTrue("temp:/out/.tmp" in c)
        val noTemp = YtDlpArgs.toCommand("y", YtDlpArgs.downloadOptions(req(Kind.VIDEO), null, null, null), "https://e.com")
        assertTrue(noTemp.none { it.startsWith("temp:") })
    }
}
