package app.aoki.yuki.t3tspray.config

import java.util.Locale

object ConfigSanitizer {
    fun normalizeSystemCode(raw: String): String? {
        val normalized = raw.trim().takeLast(4).uppercase(Locale.ROOT)
        return if (normalized.matches(Regex("[0-9A-F]{4}"))) normalized else null
    }

    fun normalizeIdm(raw: String): String {
        val cleaned = raw.trim().uppercase(Locale.ROOT).replace("[^0-9A-F]".toRegex(), "")
        return cleaned.padEnd(16, '0').take(16)
    }
}
