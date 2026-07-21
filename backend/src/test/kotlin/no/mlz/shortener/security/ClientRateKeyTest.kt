package no.mlz.shortener.security

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ClientRateKeyTest {

    @Test
    fun `ipv4 addresses key on the full address`() {
        assertEquals("203.0.113.7", ClientRateKey.of("203.0.113.7"))
    }

    @Test
    fun `ipv6 addresses in the same 64 collapse to one key`() {
        val a = ClientRateKey.of("2001:db8:abcd:1234::1")
        val b = ClientRateKey.of("2001:db8:abcd:1234:ffff:ffff:ffff:ffff")

        assertEquals(a, b)
        assertTrue(a.endsWith("/64"))
    }

    @Test
    fun `ipv6 addresses in different 64s get distinct keys`() {
        assertNotEquals(
            ClientRateKey.of("2001:db8:abcd:1111::1"),
            ClientRateKey.of("2001:db8:abcd:2222::1"),
        )
    }

    @Test
    fun `bracketed ipv6 is handled`() {
        assertEquals(
            ClientRateKey.of("2001:db8:abcd:1234::9"),
            ClientRateKey.of("[2001:db8:abcd:1234::9]"),
        )
    }

    @Test
    fun `a non-ip host is returned unchanged`() {
        assertEquals("unknown", ClientRateKey.of("unknown"))
    }
}
