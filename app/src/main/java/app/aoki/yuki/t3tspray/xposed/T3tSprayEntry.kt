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
import java.util.concurrent.atomic.AtomicBoolean

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

        hookNativeSpray(lpparam, config)
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

    private fun hookNativeSpray(
        lpparam: XC_LoadPackage.LoadPackageParam,
        config: SprayConfig
    ) {
        val nativeClass =
            XposedHelpers.findClassIfExists(NATIVE_MANAGER_CLASS, lpparam.classLoader) ?: run {
                XposedBridge.log("T3tSpray: NativeNfcManager not found")
                return
            }

        XposedHelpers.findAndHookMethod(
            nativeClass,
            "registerT3tIdentifier",
            ByteArray::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!nativeSprayed.compareAndSet(false, true)) return
                    val target = param.thisObject ?: return
                    val doRegister = runCatching {
                        XposedHelpers.findMethodBestMatch(
                            target.javaClass,
                            "doRegisterT3tIdentifier",
                            ByteArray::class.java
                        )
                    }.onFailure {
                        XposedBridge.log("T3tSpray: doRegisterT3tIdentifier missing - ${it.message}")
                    }.getOrNull() ?: return

                    config.systemCodes.forEachIndexed { index, raw ->
                        val systemCode = ConfigSanitizer.normalizeSystemCode(raw)
                        if (systemCode == null) {
                            XposedBridge.log("T3tSpray: skip invalid SC=$raw")
                            return@forEachIndexed
                        }
                        val idm = deriveIdm(config.idm, index)
                        val t3t = buildT3tIdentifier(systemCode, idm, DEFAULT_PMM)
                        runCatching {
                            doRegister.invoke(target, t3t)
                            XposedBridge.log("T3tSpray(native): registered SC=$systemCode IDm=$idm")
                        }.onFailure {
                            XposedBridge.log("T3tSpray(native): failed SC=$systemCode - ${it.message}")
                        }
                    }
                }
            }
        )
    }

    private fun buildT3tIdentifier(systemCode: String, idm: String, pmm: String): ByteArray {
        val scBytes = hexToBytes(systemCode.padStart(4, '0')).takeLast(2).toByteArray()
        val idmBytes = hexToBytes(idm.padEnd(16, '0')).take(8).toByteArray()
        val pmmBytes = hexToBytes(pmm.padEnd(16, '0')).take(8).toByteArray()
        return ByteArray(scBytes.size + idmBytes.size + pmmBytes.size).also { out ->
            System.arraycopy(scBytes, 0, out, 0, scBytes.size)
            System.arraycopy(idmBytes, 0, out, scBytes.size, idmBytes.size)
            System.arraycopy(pmmBytes, 0, out, scBytes.size + idmBytes.size, pmmBytes.size)
        }
    }

    private fun hexToBytes(raw: String): List<Byte> {
        val cleaned = raw.trim().uppercase(Locale.ROOT).replace(HEX_CLEAN_REGEX, "")
        return cleaned.chunked(2).mapNotNull { chunk ->
            runCatching { chunk.toInt(16).toByte() }.getOrNull()
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
        private const val NATIVE_MANAGER_CLASS = "com.android.nfc.dhimpl.NativeNfcManager"
        private const val DEFAULT_PMM = "0000000000000000"
        private val HEX_CLEAN_REGEX = "[^0-9A-F]".toRegex()
        private val nativeSprayed = AtomicBoolean(false)
    }
}
