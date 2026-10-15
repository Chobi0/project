package app.clearfetch.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class UrlValidatorTest {
    private fun ok(s: String) = assertIs<UrlValidator.Result.Ok>(UrlValidator.validate(s), s)
    private fun bad(s: String) = assertIs<UrlValidator.Result.Invalid>(UrlValidator.validate(s), s)

    @Test fun acceptsNormalUrls() {
        ok("https://example.com/watch?v=abc")
        ok("  http://example.com/a.mp4  ")
    }

    @Test fun trimsInput() {
        assertEquals("https://example.com/x", (UrlValidator.validate(" https://example.com/x ") as UrlValidator.Result.Ok).url)
    }

    @Test fun rejectsEmptyAndGarbage() {
        bad("")
        bad("   ")
        bad("not a url")
        bad("https://exa mple.com")
    }

    @Test fun rejectsNonHttpSchemes() {
        bad("file:///etc/passwd")
        bad("ftp://example.com/a")
        bad("javascript:alert(1)")
    }

    @Test fun rejectsCredentialsInUrl() = bad("https://user:pw@example.com/")

    @Test fun rejectsLocalAndPrivateHosts() {
        bad("http://localhost/x")
        bad("http://foo.localhost/x")
        bad("http://printer.local/x")
        bad("http://127.0.0.1/x")
        bad("http://10.0.0.5/x")
        bad("http://172.16.3.1/x")
        bad("http://192.168.0.10/x")
        bad("http://169.254.169.254/latest/meta-data")
        bad("http://0.0.0.0/")
        bad("http://[::1]/x")
    }

    @Test fun allowsPublicIpAndNearMissRanges() {
        ok("http://8.8.8.8/x")
        ok("http://172.32.0.1/x")
        ok("http://192.169.0.1/x")
    }
}
