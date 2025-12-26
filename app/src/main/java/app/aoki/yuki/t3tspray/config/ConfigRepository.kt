package app.aoki.yuki.t3tspray.config

import android.content.Context
import android.content.SharedPreferences
import java.io.File

class ConfigRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val prefsFile = File(context.applicationInfo.dataDir, "shared_prefs/$PREFS_NAME.xml")

    fun load(): SprayConfig {
        val enabled = prefs.getBoolean(KEY_ENABLED, true)
        val idm = prefs.getString(KEY_IDM, SprayConfig.DEFAULT_IDM) ?: SprayConfig.DEFAULT_IDM
        val storedCodes = prefs.getString(KEY_SYSTEM_CODES, null)
            ?.split(",")
            ?.mapNotNull { ConfigSanitizer.normalizeSystemCode(it) }
            ?.takeIf { it.isNotEmpty() }
            ?: SprayConfig.DEFAULT_SYSTEM_CODES
        return SprayConfig(
            enabled = enabled,
            idm = ConfigSanitizer.normalizeIdm(idm),
            systemCodes = storedCodes
        )
    }

    fun save(config: SprayConfig) {
        val sanitizedCodes = config.systemCodes.mapNotNull { ConfigSanitizer.normalizeSystemCode(it) }
            .takeIf { it.isNotEmpty() }
            ?: SprayConfig.DEFAULT_SYSTEM_CODES

        prefs.edit()
            .putBoolean(KEY_ENABLED, config.enabled)
            .putString(KEY_IDM, ConfigSanitizer.normalizeIdm(config.idm))
            .putString(KEY_SYSTEM_CODES, sanitizedCodes.joinToString(","))
            .commit()
        prefsFile.setReadable(true, false)
    }

    companion object {
        const val PREFS_NAME = "t3t_spray_prefs"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_IDM = "idm"
        private const val KEY_SYSTEM_CODES = "system_codes"
    }
}
