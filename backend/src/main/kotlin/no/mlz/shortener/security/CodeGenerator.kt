package no.mlz.shortener.security

import java.security.SecureRandom

/**
 * Generates random base62 short codes (§2.2).
 *
 * Sequential/base62-of-autoincrement schemes are rejected: they are enumerable, letting an
 * attacker walk /1, /2, /3 and scrape every link ever created. These codes carry no ordering
 * and no relationship to the row id. Uniqueness is enforced at insert time by the repository,
 * which retries with a fresh code on collision and fails closed after N attempts.
 */
class CodeGenerator(private val length: Int) {

    private val random = SecureRandom()

    fun newCode(): String {
        val chars = CharArray(length) { ALPHABET[random.nextInt(ALPHABET.length)] }
        return String(chars)
    }

    companion object {
        private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    }
}
