package no.mlz.shortener.security

import java.security.SecureRandom

// Random base62, not base62-of-autoincrement: sequential codes are enumerable (walk /1, /2, /3
// and scrape every link). These carry no ordering or relationship to the row id. Uniqueness is
// enforced at insert time, with bounded retry that fails closed.
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
