package app.aoki.yuki.t3tspray.config

import de.robv.android.xposed.XSharedPreferences
import java.util.Locale

object XposedConfigLoader {

    fun load(): SprayConfig {
        val prefs = XSharedPreferences(MODULE_PACKAGE, ConfigRepository.PREFS_NAME)
        prefs.makeWorldReadable()
        prefs.reload()
        val enabled = prefs.getBoolean("enabled", true)
        val idm = prefs.getString("idm", SprayConfig.DEFAULT_IDM) ?: SprayConfig.DEFAULT_IDM
        val systemCodes = prefs.getString("system_codes", null)
            ?.split(",")
            ?.mapNotNull { normalizeSystemCode(it) }
            ?.takeIf { it.isNotEmpty() }
            ?: SprayConfig.DEFAULT_SYSTEM_CODES

        return SprayConfig(
            enabled = enabled,
            idm = normalizeIdm(idm),
            systemCodes = systemCodes
        )
    }

    private fun normalizeSystemCode(raw: String): String? {
        val normalized = raw.trim().takeLast(4).uppercase(Locale.ROOT)
        return if (normalized.matches(Regex("[0-9A-Fa-f]{4}"))) normalized else null
    }

    private fun normalizeIdm(raw: String): String {
        val cleaned = raw.trim().uppercase(Locale.ROOT).replace("[^0-9A-F]".toRegex(), "")
        return cleaned.padEnd(16, '0').take(16)
    }

    private const val MODULE_PACKAGE = "app.aoki.yuki.t3tspray"
}
