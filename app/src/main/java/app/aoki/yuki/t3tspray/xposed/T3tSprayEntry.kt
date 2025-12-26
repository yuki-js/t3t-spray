package app.aoki.yuki.t3tspray.xposed

import app.aoki.yuki.t3tspray.config.ConfigSanitizer
import app.aoki.yuki.t3tspray.config.SprayConfig
import app.aoki.yuki.t3tspray.config.XposedConfigLoader
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.Locale

class T3tSprayEntry : IXposedHookLoadPackage, IXposedHookZygoteInit {

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam?) {
        XposedBridge.log("T3tSpray: zygote initialized")
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != NFC_PACKAGE) return

        val config = XposedConfigLoader.load()
        if (!config.enabled) {
            XposedBridge.log("T3tSpray: disabled by configuration")
            return
        }

        val cacheClass = XposedHelpers.findClassIfExists(CACHE_CLASS, lpparam.classLoader)
        if (cacheClass == null) {
            XposedBridge.log("T3tSpray: could not find RegisteredT3tIdentifiersCache")
            return
        }

        XposedBridge.hookAllConstructors(cacheClass, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                runCatching { sprayIdentifiers(param.thisObject, config) }
                    .onFailure { XposedBridge.log("T3tSpray: spray failed - ${it.message}") }
            }
        })
    }

    private fun sprayIdentifiers(target: Any, config: SprayConfig) {
        val registerMethod = runCatching {
            XposedHelpers.findMethodBestMatch(
                target.javaClass,
                "registerT3tIdentifier",
                String::class.java,
                String::class.java
            )
        }.onFailure {
            XposedBridge.log("T3tSpray: registerT3tIdentifier method missing - ${it.message}")
        }.getOrNull() ?: return

        config.systemCodes.forEachIndexed { index, rawSystemCode ->
            val systemCode = ConfigSanitizer.normalizeSystemCode(rawSystemCode)
                ?: run {
                    XposedBridge.log("T3tSpray: skip invalid SC=$rawSystemCode")
                    return@forEachIndexed
                }
            val idm = deriveIdm(config.idm, index)
            runCatching {
                registerMethod.invoke(target, idm, systemCode)
                XposedBridge.log("T3tSpray: registered SC=$systemCode with IDm=$idm")
            }.onFailure {
                XposedBridge.log("T3tSpray: failed to register $systemCode - ${it.message}")
            }
        }
    }

    private fun deriveIdm(base: String, offset: Int): String {
        val cleaned = base.trim().uppercase(Locale.ROOT).replace("[^0-9A-F]".toRegex(), "")
        val padded = cleaned.padEnd(16, '0').take(16)
        val suffix = offset.coerceAtLeast(0).and(BYTE_MASK).toString(16)
            .uppercase(Locale.ROOT)
            .padStart(2, '0')
        return padded.take(14) + suffix
    }

    companion object {
        private const val NFC_PACKAGE = "com.android.nfc"
        private const val CACHE_CLASS = "com.android.nfc.cardemulation.RegisteredT3tIdentifiersCache"
        private const val BYTE_MASK = 0xFF
    }
}
