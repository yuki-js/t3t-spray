package app.aoki.yuki.t3tspray.config

import de.robv.android.xposed.XSharedPreferences

object XposedConfigLoader {

    fun load(): SprayConfig {
        val prefs = XSharedPreferences(MODULE_PACKAGE, ConfigRepository.PREFS_NAME)
        prefs.makeWorldReadable()
        prefs.reload()
        val enabled = prefs.getBoolean("enabled", true)
        val idm = prefs.getString("idm", SprayConfig.DEFAULT_IDM) ?: SprayConfig.DEFAULT_IDM
        val systemCodes = prefs.getString("system_codes", null)
            ?.split(",")
            ?.mapNotNull { ConfigSanitizer.normalizeSystemCode(it) }
            ?.takeIf { it.isNotEmpty() }
            ?: SprayConfig.DEFAULT_SYSTEM_CODES

        return SprayConfig(
            enabled = enabled,
            idm = ConfigSanitizer.normalizeIdm(idm),
            systemCodes = systemCodes
        )
    }

    private const val MODULE_PACKAGE = "app.aoki.yuki.t3tspray"
}
