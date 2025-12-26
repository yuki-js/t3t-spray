package app.aoki.yuki.t3tspray.config

import java.util.Locale

object ConfigSanitizer {
    fun normalizeSystemCode(raw: String): String? {
        val cleaned = raw.trim().uppercase(Locale.ROOT).replace("[^0-9A-F]".toRegex(), "")
        if (cleaned.isEmpty() || cleaned.length > 4) return null
        return cleaned.padStart(4, '0')
    }

    fun normalizeIdm(raw: String): String {
        val cleaned = raw.trim().uppercase(Locale.ROOT).replace("[^0-9A-F]".toRegex(), "")
        return cleaned.padEnd(16, '0').take(16)
    }
}
