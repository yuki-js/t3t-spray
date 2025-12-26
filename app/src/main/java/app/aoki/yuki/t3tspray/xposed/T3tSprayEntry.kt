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
        XposedBridge.log("T3tSpray: ========================================")
        XposedBridge.log("T3tSpray: Zygote initialized")
        XposedBridge.log("T3tSpray: Module version: 0.2.0")
        XposedBridge.log("T3tSpray: ========================================")
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != NFC_PACKAGE) return

        XposedBridge.log("T3tSpray: ========================================")
        XposedBridge.log("T3tSpray: Loading into package: ${lpparam.packageName}")
        XposedBridge.log("T3tSpray: Process name: ${lpparam.processName}")
        XposedBridge.log("T3tSpray: ========================================")

        val config = XposedConfigLoader.load()
        XposedBridge.log("T3tSpray: Config loaded - enabled=${config.enabled}, idm=${config.idm}, systemCodes=${config.systemCodes}")
        
        if (!config.enabled) {
            XposedBridge.log("T3tSpray: Module is DISABLED by configuration")
            return
        }

        XposedBridge.log("T3tSpray: Searching for class: $CACHE_CLASS")
        val cacheClass = XposedHelpers.findClassIfExists(CACHE_CLASS, lpparam.classLoader)
        if (cacheClass == null) {
            XposedBridge.log("T3tSpray: ERROR - Could not find RegisteredT3tIdentifiersCache")
            XposedBridge.log("T3tSpray: ClassLoader: ${lpparam.classLoader}")
            return
        }
        XposedBridge.log("T3tSpray: SUCCESS - Found RegisteredT3tIdentifiersCache")

        XposedBridge.log("T3tSpray: Hooking RegisteredT3tIdentifiersCache constructor...")
        XposedBridge.hookAllConstructors(cacheClass, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                XposedBridge.log("T3tSpray: RegisteredT3tIdentifiersCache constructor called!")
                XposedBridge.log("T3tSpray: Instance: ${param.thisObject}")
                runCatching { 
                    sprayIdentifiers(param.thisObject, config) 
                }.onSuccess {
                    XposedBridge.log("T3tSpray: Spray identifiers completed successfully")
                }.onFailure { 
                    XposedBridge.log("T3tSpray: ERROR - Spray failed: ${it.message}")
                    XposedBridge.log("T3tSpray: Stack trace: ${it.stackTraceToString()}")
                }
            }
        })
        XposedBridge.log("T3tSpray: RegisteredT3tIdentifiersCache hook installed")

        hookNativeSpray(lpparam, config)
        XposedBridge.log("T3tSpray: ========================================")
        XposedBridge.log("T3tSpray: Module initialization complete")
        XposedBridge.log("T3tSpray: ========================================")
    }

    private fun sprayIdentifiers(target: Any, config: SprayConfig) {
        XposedBridge.log("T3tSpray: [sprayIdentifiers] Starting spray...")
        XposedBridge.log("T3tSpray: [sprayIdentifiers] Target class: ${target.javaClass.name}")
        XposedBridge.log("T3tSpray: [sprayIdentifiers] System codes to register: ${config.systemCodes}")
        
        val registerMethod = runCatching {
            XposedHelpers.findMethodBestMatch(
                target.javaClass,
                "registerT3tIdentifier",
                String::class.java,
                String::class.java
            )
        }.onSuccess { method ->
            XposedBridge.log("T3tSpray: [sprayIdentifiers] Found registerT3tIdentifier method: $method")
        }.onFailure { e ->
            XposedBridge.log("T3tSpray: [sprayIdentifiers] ERROR - registerT3tIdentifier method missing: ${e.message}")
            XposedBridge.log("T3tSpray: [sprayIdentifiers] Stack trace: ${e.stackTraceToString()}")
        }.getOrNull() ?: return

        config.systemCodes.forEachIndexed { index, rawSystemCode ->
            XposedBridge.log("T3tSpray: [sprayIdentifiers] Processing SC #$index: $rawSystemCode")
            
            val systemCode = ConfigSanitizer.normalizeSystemCode(rawSystemCode)
                ?: run {
                    XposedBridge.log("T3tSpray: [sprayIdentifiers] SKIP - Invalid SC=$rawSystemCode")
                    return@forEachIndexed
                }
            
            val idm = deriveIdm(config.idm, index)
            XposedBridge.log("T3tSpray: [sprayIdentifiers] Normalized SC=$systemCode, derived IDm=$idm")
            
            runCatching {
                registerMethod.invoke(target, idm, systemCode)
                XposedBridge.log("T3tSpray: [sprayIdentifiers] ✓ SUCCESS - Registered SC=$systemCode with IDm=$idm")
            }.onFailure { e ->
                XposedBridge.log("T3tSpray: [sprayIdentifiers] ✗ FAILED - SC=$systemCode: ${e.message}")
                XposedBridge.log("T3tSpray: [sprayIdentifiers] Stack trace: ${e.stackTraceToString()}")
            }
        }
        
        XposedBridge.log("T3tSpray: [sprayIdentifiers] Spray complete!")
    }

    private fun hookNativeSpray(
        lpparam: XC_LoadPackage.LoadPackageParam,
        config: SprayConfig
    ) {
        XposedBridge.log("T3tSpray: [hookNativeSpray] Starting native hook setup...")
        XposedBridge.log("T3tSpray: [hookNativeSpray] Searching for class: $NATIVE_MANAGER_CLASS")
        
        val nativeClass =
            XposedHelpers.findClassIfExists(NATIVE_MANAGER_CLASS, lpparam.classLoader) ?: run {
                XposedBridge.log("T3tSpray: [hookNativeSpray] ERROR - NativeNfcManager not found at $NATIVE_MANAGER_CLASS")
                XposedBridge.log("T3tSpray: [hookNativeSpray] This is expected on Android 15 as the class is loaded dynamically")
                XposedBridge.log("T3tSpray: [hookNativeSpray] Native spray will be attempted when NFC initializes")
                // Don't return - the class may be loaded later
                return
            }

        XposedBridge.log("T3tSpray: [hookNativeSpray] SUCCESS - Found NativeNfcManager class")
        XposedBridge.log("T3tSpray: [hookNativeSpray] Class: $nativeClass")

        try {
            XposedHelpers.findAndHookMethod(
                nativeClass,
                "registerT3tIdentifier",
                ByteArray::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val t3tId = param.args[0] as? ByteArray
                        if (t3tId != null) {
                            val hex = t3tId.joinToString("") { "%02X".format(it) }
                            XposedBridge.log("T3tSpray: [hookNativeSpray] BEFORE registerT3tIdentifier - t3tId=$hex (${t3tId.size} bytes)")
                        }
                    }
                    
                    override fun afterHookedMethod(param: MethodHookParam) {
                        XposedBridge.log("T3tSpray: [hookNativeSpray] AFTER registerT3tIdentifier called")
                        
                        if (!nativeSprayed.compareAndSet(false, true)) {
                            XposedBridge.log("T3tSpray: [hookNativeSpray] Already sprayed, skipping")
                            return
                        }
                        
                        XposedBridge.log("T3tSpray: [hookNativeSpray] First time - starting native spray...")
                        
                        val target = param.thisObject ?: run {
                            XposedBridge.log("T3tSpray: [hookNativeSpray] ERROR - thisObject is null")
                            return
                        }
                        
                        XposedBridge.log("T3tSpray: [hookNativeSpray] Target instance: $target")
                        XposedBridge.log("T3tSpray: [hookNativeSpray] Searching for doRegisterT3tIdentifier method...")
                        
                        val doRegister = runCatching {
                            XposedHelpers.findMethodBestMatch(
                                target.javaClass,
                                "doRegisterT3tIdentifier",
                                ByteArray::class.java
                            )
                        }.onSuccess { method ->
                            XposedBridge.log("T3tSpray: [hookNativeSpray] Found doRegisterT3tIdentifier: $method")
                        }.onFailure { e ->
                            XposedBridge.log("T3tSpray: [hookNativeSpray] ERROR - doRegisterT3tIdentifier missing: ${e.message}")
                            XposedBridge.log("T3tSpray: [hookNativeSpray] Stack trace: ${e.stackTraceToString()}")
                        }.getOrNull() ?: return

                        XposedBridge.log("T3tSpray: [hookNativeSpray] Registering ${config.systemCodes.size} system codes...")
                        
                        config.systemCodes.forEachIndexed { index, raw ->
                            XposedBridge.log("T3tSpray: [hookNativeSpray] Processing native SC #$index: $raw")
                            
                            val systemCode = ConfigSanitizer.normalizeSystemCode(raw)
                            if (systemCode == null) {
                                XposedBridge.log("T3tSpray: [hookNativeSpray] SKIP - Invalid SC=$raw")
                                return@forEachIndexed
                            }
                            
                            val idm = deriveIdm(config.idm, index)
                            val t3t = buildT3tIdentifier(systemCode, idm, DEFAULT_PMM)
                            val hex = t3t.joinToString("") { "%02X".format(it) }
                            XposedBridge.log("T3tSpray: [hookNativeSpray] Built T3T identifier: $hex (${t3t.size} bytes)")
                            XposedBridge.log("T3tSpray: [hookNativeSpray] SC=$systemCode, IDm=$idm, PMM=$DEFAULT_PMM")
                            
                            runCatching {
                                val result = doRegister.invoke(target, t3t)
                                XposedBridge.log("T3tSpray: [hookNativeSpray] ✓ SUCCESS - Registered native SC=$systemCode IDm=$idm, handle=$result")
                            }.onFailure { e ->
                                XposedBridge.log("T3tSpray: [hookNativeSpray] ✗ FAILED - SC=$systemCode: ${e.message}")
                                XposedBridge.log("T3tSpray: [hookNativeSpray] Stack trace: ${e.stackTraceToString()}")
                            }
                        }
                        
                        XposedBridge.log("T3tSpray: [hookNativeSpray] Native spray complete!")
                    }
                }
            )
            XposedBridge.log("T3tSpray: [hookNativeSpray] Hook installed successfully")
        } catch (e: Throwable) {
            XposedBridge.log("T3tSpray: [hookNativeSpray] ERROR - Failed to hook registerT3tIdentifier: ${e.message}")
            XposedBridge.log("T3tSpray: [hookNativeSpray] Stack trace: ${e.stackTraceToString()}")
        }
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
