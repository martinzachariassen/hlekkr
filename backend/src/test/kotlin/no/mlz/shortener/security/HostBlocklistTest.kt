package no.mlz.shortener.security

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HostBlocklistTest {

    @Test
    fun `empty blocklist blocks nothing`() {
        val blocklist = HostBlocklist.empty()
        assertEquals(0, blocklist.size)
        assertFalse(blocklist.isBlocked("anything.example"))
    }

    @Test
    fun `blocks an exact domain and all its subdomains`() {
        val blocklist = HostBlocklist.of(listOf("evil.example"))
        assertTrue(blocklist.isBlocked("evil.example"))
        assertTrue(blocklist.isBlocked("www.evil.example"))
        assertTrue(blocklist.isBlocked("a.b.c.evil.example"))
    }

    @Test
    fun `does not block a sibling or a superstring domain`() {
        val blocklist = HostBlocklist.of(listOf("evil.example"))
        assertFalse(blocklist.isBlocked("good.example"))
        assertFalse(blocklist.isBlocked("notevil.example")) // suffix must break on a label boundary
        assertFalse(blocklist.isBlocked("evil.example.co")) // "evil.example" is not a suffix here
    }

    @Test
    fun `normalizes case, surrounding dots, and whitespace`() {
        val blocklist = HostBlocklist.of(listOf("  .EVIL.Example.  "))
        assertTrue(blocklist.isBlocked("evil.example"))
        assertTrue(blocklist.isBlocked("SUB.Evil.Example"))
    }

    @Test
    fun `ignores comments, blanks, and hosts-file IP prefixes`() {
        val blocklist = HostBlocklist.of(
            listOf(
                "# a comment",
                "",
                "   ",
                "0.0.0.0 ads.example   # inline comment",
                "127.0.0.1 tracker.example",
                "plain.example",
                "localhost",
            ),
        )
        assertEquals(3, blocklist.size)
        assertTrue(blocklist.isBlocked("ads.example"))
        assertTrue(blocklist.isBlocked("tracker.example"))
        assertTrue(blocklist.isBlocked("plain.example"))
        assertFalse(blocklist.isBlocked("localhost"))
    }

    @Test
    fun `from parses an inline comma-separated string`() {
        val blocklist = HostBlocklist.from("a.example, b.example ,, c.example", null)
        assertEquals(3, blocklist.size)
        assertTrue(blocklist.isBlocked("x.a.example"))
        assertTrue(blocklist.isBlocked("c.example"))
    }

    @Test
    fun `from tolerates a missing file`() {
        val blocklist = HostBlocklist.from("a.example", "/no/such/blocklist/file.txt")
        assertEquals(1, blocklist.size)
        assertTrue(blocklist.isBlocked("a.example"))
    }
}
