package no.mlz.shortener.service

import no.mlz.shortener.security.HostBlocklist
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class UrlValidatorTest {

    private val validator = UrlValidator(baseUrl = "https://sho.rt")

    @ParameterizedTest
    @ValueSource(
        strings = [
            "https://example.com",
            "http://example.com/path?q=1#frag",
            "https://sub.example.co.uk/a/b/c",
            "https://example.com:8443/x",
            "https://192.0.2.1/valid-public-ip", // TEST-NET-1, not private
        ],
    )
    fun `accepts valid public http and https urls`(url: String) {
        assertEquals(url, validator.validate(url))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "javascript:alert(1)",
            "data:text/html,<script>alert(1)</script>",
            "file:///etc/passwd",
            "ftp://example.com/file",
            "mailto:someone@example.com",
        ],
    )
    fun `rejects non-http schemes`(url: String) {
        assertThrows(InvalidTargetUrlException::class.java) { validator.validate(url) }
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "http://127.0.0.1/admin",
            "http://127.0.0.1:8080/",
            "http://169.254.169.254/latest/meta-data", // cloud metadata endpoint
            "http://10.0.0.5/internal",
            "http://172.16.0.1/",
            "http://192.168.1.1/router",
            "http://0.0.0.0/",
            "http://[::1]/",
            "http://[fe80::1]/",
            "http://[fc00::1]/",
        ],
    )
    fun `rejects loopback link-local and private targets`(url: String) {
        assertThrows(InvalidTargetUrlException::class.java) { validator.validate(url) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["http://localhost/", "http://localhost:8080/x", "http://api.localhost/"])
    fun `rejects localhost hostnames`(url: String) {
        assertThrows(InvalidTargetUrlException::class.java) { validator.validate(url) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["https://sho.rt/abc", "https://sho.rt/", "http://sho.rt/loop"])
    fun `rejects self-referential targets`(url: String) {
        assertThrows(InvalidTargetUrlException::class.java) { validator.validate(url) }
    }

    @Test
    fun `rejects a target on the configured blocklist, including subdomains`() {
        val guarded = UrlValidator(
            baseUrl = "https://sho.rt",
            blocklist = HostBlocklist.of(listOf("blocked.example")),
        )
        assertThrows(InvalidTargetUrlException::class.java) {
            guarded.validate("https://blocked.example/x")
        }
        assertThrows(InvalidTargetUrlException::class.java) {
            guarded.validate("https://cdn.blocked.example/y")
        }
        assertEquals("https://allowed.example/z", guarded.validate("https://allowed.example/z"))
    }

    @Test
    fun `rejects urls with embedded credentials`() {
        assertThrows(InvalidTargetUrlException::class.java) {
            validator.validate("http://user:pass@127.0.0.1/")
        }
    }

    @Test
    fun `rejects relative urls`() {
        assertThrows(InvalidTargetUrlException::class.java) { validator.validate("/just/a/path") }
    }

    @Test
    fun `rejects a sql injection payload as an inert non-url`() {
        assertThrows(InvalidTargetUrlException::class.java) {
            validator.validate("'; DROP TABLE links; --")
        }
    }

    @Test
    fun `rejects urls longer than the maximum`() {
        val tooLong = "https://example.com/" + "a".repeat(UrlValidator.MAX_LENGTH)
        assertThrows(InvalidTargetUrlException::class.java) { validator.validate(tooLong) }
    }

    @Test
    fun `rejects blank input`() {
        assertThrows(InvalidTargetUrlException::class.java) { validator.validate("   ") }
    }
}
