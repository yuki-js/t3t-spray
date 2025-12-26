package app.aoki.yuki.t3tspray.config

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.util.Locale

class ConfigRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val prefsFile = File(context.applicationInfo.dataDir, "shared_prefs/$PREFS_NAME.xml")

    fun load(): SprayConfig {
        val enabled = prefs.getBoolean(KEY_ENABLED, true)
        val idm = prefs.getString(KEY_IDM, SprayConfig.DEFAULT_IDM) ?: SprayConfig.DEFAULT_IDM
        val systemCodes = prefs.getString(KEY_SYSTEM_CODES, null)
            ?.split(",")
            ?.mapNotNull { normalizeSystemCode(it) }
            ?.takeIf { it.isNotEmpty() }
            ?: SprayConfig.DEFAULT_SYSTEM_CODES
        return SprayConfig(enabled = enabled, idm = normalizeIdm(idm), systemCodes = systemCodes)
    }

    fun save(config: SprayConfig) {
        prefs.edit()
            .putBoolean(KEY_ENABLED, config.enabled)
            .putString(KEY_IDM, normalizeIdm(config.idm))
            .putString(KEY_SYSTEM_CODES, config.systemCodes.joinToString(",") { normalizeSystemCode(it) ?: "" })
            .commit()
        prefsFile.setReadable(true, false)
    }

    private fun normalizeSystemCode(raw: String): String? {
        val normalized = raw.trim().takeLast(4).uppercase(Locale.ROOT)
        return if (normalized.matches(Regex("[0-9A-F]{4}"))) normalized else null
    }

    private fun normalizeIdm(raw: String): String {
        val cleaned = raw.trim().uppercase(Locale.ROOT).replace("[^0-9A-F]".toRegex(), "")
        return cleaned.padEnd(16, '0').take(16)
    }

    companion object {
        const val PREFS_NAME = "t3t_spray_prefs"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_IDM = "idm"
        private const val KEY_SYSTEM_CODES = "system_codes"
    }
}
