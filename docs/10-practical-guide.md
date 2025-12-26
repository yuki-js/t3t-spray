# AOSP NFC解体新書 - 実践ガイドと今後の展望

## 目次
1. 実践的な実装ガイド
2. デバッグとトラブルシューティング
3. パフォーマンス最適化
4. Android バージョン間の違い
5. 今後の展望
6. 参考資料

## 1. 実践的な実装ガイド

### 1.1 Xposed モジュールの実装

#### 基本構造

```kotlin
class T3tSprayEntry : IXposedHookLoadPackage {
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "com.android.nfc") return
        
        // 1. 設定を読み込み
        val config = loadConfig()
        if (!config.enabled) return
        
        // 2. RegisteredT3tIdentifiersCache をフック
        hookRegisteredT3tIdentifiersCache(lpparam)
        
        // 3. NativeNfcManager をフック
        hookNativeNfcManager(lpparam)
    }
}
```

#### RegisteredT3tIdentifiersCache のフック

```kotlin
fun hookRegisteredT3tIdentifiersCache(lpparam: XC_LoadPackage.LoadPackageParam) {
    val cacheClass = XposedHelpers.findClass(
        "com.android.nfc.cardemulation.RegisteredT3tIdentifiersCache",
        lpparam.classLoader
    )
    
    // コンストラクタをフック
    XposedBridge.hookAllConstructors(cacheClass, object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            XposedBridge.log("T3tSpray: Cache created")
            
            // System Code をスプレー
            spraySystemCodes(param.thisObject)
        }
    })
}

fun spraySystemCodes(cacheInstance: Any) {
    val wellKnownSCs = listOf("0003", "FE00", "FE0F")
    val config = loadConfig()
    
    wellKnownSCs.forEachIndexed { index, sc ->
        try {
            val idm = deriveIdm(config.baseIdm, index)
            val pmm = "0000000000000000"
            
            // NfcService 経由で登録
            registerViaCache(cacheInstance, sc, idm, pmm)
            
            XposedBridge.log("T3tSpray: Registered SC=$sc IDm=$idm")
        } catch (e: Exception) {
            XposedBridge.log("T3tSpray: Failed SC=$sc: ${e.message}")
        }
    }
}
```

#### NativeNfcManager のフック

```kotlin
fun hookNativeNfcManager(lpparam: XC_LoadPackage.LoadPackageParam) {
    val nativeClass = XposedHelpers.findClass(
        "com.android.nfc.dhimpl.NativeNfcManager",
        lpparam.classLoader
    ) ?: run {
        XposedBridge.log("T3tSpray: NativeNfcManager not found")
        return
    }
    
    // registerT3tIdentifier をフック
    XposedHelpers.findAndHookMethod(
        nativeClass,
        "registerT3tIdentifier",
        ByteArray::class.java,
        object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                // 追加の SC を登録
                val additionalSCs = getAdditionalSystemCodes()
                
                additionalSCs.forEach { sc ->
                    val t3tId = buildT3tIdentifier(sc.systemCode, sc.idm, sc.pmm)
                    
                    runCatching {
                        XposedHelpers.callMethod(
                            param.thisObject,
                            "doRegisterT3tIdentifier",
                            t3tId
                        )
                    }.onSuccess { handle ->
                        XposedBridge.log("T3tSpray: Native registered handle=$handle")
                    }
                }
            }
        }
    )
}
```

### 1.2 デバッグログの実装

```kotlin
object DebugLogger {
    fun logVerbose(tag: String, message: String) {
        if (BuildConfig.DEBUG) {
            XposedBridge.log("$tag[VERBOSE]: $message")
        }
    }
    
    fun logInfo(tag: String, message: String) {
        XposedBridge.log("$tag[INFO]: $message")
    }
    
    fun logError(tag: String, message: String, throwable: Throwable? = null) {
        XposedBridge.log("$tag[ERROR]: $message")
        throwable?.let {
            XposedBridge.log("$tag[ERROR]: ${it.stackTraceToString()}")
        }
    }
    
    fun dumpState(tag: String, state: Map<String, Any>) {
        logInfo(tag, "=== State Dump ===")
        state.forEach { (key, value) ->
            logInfo(tag, "  $key = $value")
        }
        logInfo(tag, "==================")
    }
}
```

### 1.3 Toast 通知の実装

```kotlin
object ToastHelper {
    private var handler: Handler? = null
    
    fun init(context: Context) {
        handler = Handler(context.mainLooper)
    }
    
    fun show(context: Context, message: String, duration: Int = Toast.LENGTH_SHORT) {
        handler?.post {
            Toast.makeText(context, message, duration).show()
        }
    }
}

// フック内での使用
override fun afterHookedMethod(param: MethodHookParam) {
    val context = getContextFromParam(param)
    ToastHelper.show(context, "T3tSpray: Registered SC=FE00")
}
```

## 2. デバッグとトラブルシューティング

### 2.1 一般的な問題と解決策

#### 問題 1: モジュールが動作しない

```bash
# 1. LSPosed でモジュールが有効か確認
adb shell pm list packages | grep t3tspray

# 2. スコープに com.android.nfc が含まれているか確認
# LSPosed Manager → t3t-spray → Scope

# 3. ログを確認
adb logcat | grep T3tSpray
```

#### 問題 2: NativeNfcManager not found

```kotlin
// Android 15 と 16 の違いに対応
val nativeClass = XposedHelpers.findClassIfExists(
    "com.android.nfc.dhimpl.NativeNfcManager",
    lpparam.classLoader
) ?: XposedHelpers.findClassIfExists(
    "com.android.nfc.NativeNfcManager",  // 古いパス
    lpparam.classLoader
)

if (nativeClass == null) {
    XposedBridge.log("T3tSpray: Could not find NativeNfcManager")
    // フォールバック処理
}
```

#### 問題 3: 登録が失敗する

```kotlin
// エラーハンドリングを充実
runCatching {
    val handle = XposedHelpers.callMethod(
        nativeManager,
        "doRegisterT3tIdentifier",
        t3tIdentifier
    ) as Int
    
    if (handle == -1 || handle == 0xFFFF) {
        DebugLogger.logError(TAG, "Invalid handle: $handle")
        // Handle が無効な場合の処理
    } else {
        DebugLogger.logInfo(TAG, "Success: handle=$handle")
    }
}.onFailure { e ->
    DebugLogger.logError(TAG, "Exception during registration", e)
}
```

### 2.2 ログ分析

```bash
# T3tSpray 関連のログのみ
adb logcat | grep T3tSpray

# NFC 全般のログ
adb logcat | grep -E "NfcService|NativeNfcManager|RoutingManager"

# エラーのみ
adb logcat *:E | grep -E "NFC|T3tSpray"

# 詳細なフィルタ
adb logcat | grep -E "registerT3tIdentifier|T3tIdentifier|SENSF"

# ログをファイルに保存
adb logcat -d > nfc_log.txt
```

### 2.3 動作確認

```bash
# NFC が有効か確認
adb shell settings get secure nfc_on

# NFC プロセスが動作中か確認
adb shell ps | grep com.android.nfc

# NFC プロセスを再起動
adb shell am force-stop com.android.nfc
adb shell am start-foreground-service \
    com.android.nfc/.NfcService
```

## 3. パフォーマンス最適化

### 3.1 登録の最適化

```kotlin
// 非効率な実装
fun registerSystemCodes(codes: List<String>) {
    codes.forEach { sc ->
        registerT3tIdentifier(sc, idm, pmm)
        commitRouting()  // 毎回コミット (遅い!)
    }
}

// 最適化された実装
fun registerSystemCodes(codes: List<String>) {
    codes.forEach { sc ->
        registerT3tIdentifier(sc, idm, pmm)
        // コミットは最後に一度だけ
    }
    commitRouting()  // 1回のみ
}
```

### 3.2 キャッシング

```kotlin
object T3tIdentifierCache {
    private val registeredHandles = mutableMapOf<String, Int>()
    
    fun isRegistered(sc: String, idm: String): Boolean {
        val key = "$sc:$idm"
        return registeredHandles.containsKey(key)
    }
    
    fun register(sc: String, idm: String, handle: Int) {
        val key = "$sc:$idm"
        registeredHandles[key] = handle
    }
    
    fun clear() {
        registeredHandles.clear()
    }
}

// 使用例
fun registerIfNeeded(sc: String, idm: String, pmm: String) {
    if (T3tIdentifierCache.isRegistered(sc, idm)) {
        DebugLogger.logVerbose(TAG, "Already registered: $sc")
        return
    }
    
    val handle = doRegister(sc, idm, pmm)
    T3tIdentifierCache.register(sc, idm, handle)
}
```

## 4. Android バージョン間の違い

### 4.1 パッケージの場所

| Android Version | NFC Package Location |
|----------------|----------------------|
| Android 13- | `packages/apps/Nfc` |
| Android 14-15 | `packages/apps/Nfc` |
| Android 16+ | `packages/modules/Nfc/NfcNci` |

### 4.2 クラス名の変更

| Class | Android 13-15 | Android 16+ |
|-------|--------------|-------------|
| NativeNfcManager | ✓ 存在 | ✓ 存在 (同じパッケージ) |
| RegisteredT3tIdentifiersCache | ✓ 存在 | ✓ 存在 |

**結論**: クラス名とパッケージ名は変わっていないため、t3t-spray は Android 15 と 16 の両方で動作するはず。

### 4.3 互換性のある実装

```kotlin
fun findNativeNfcManager(lpparam: XC_LoadPackage.LoadPackageParam): Class<*>? {
    return XposedHelpers.findClassIfExists(
        "com.android.nfc.dhimpl.NativeNfcManager",
        lpparam.classLoader
    ) ?: XposedHelpers.findClassIfExists(
        "com.android.nfc.NativeNfcManager",
        lpparam.classLoader
    ) ?: run {
        DebugLogger.logError(TAG, "NativeNfcManager not found")
        null
    }
}
```

## 5. 今後の展望

### 5.1 機能拡張

1. **動的 System Code 管理**
   - UI から System Code を追加/削除
   - ライブリロード

2. **PMM のカスタマイズ**
   - デバイスごとに異なる PMM を設定
   - ランダム PMM 生成

3. **ログの可視化**
   - アプリ内でログを表示
   - 登録状況のダッシュボード

4. **プロファイル機能**
   - 複数の設定を保存
   - シーンに応じて切り替え

### 5.2 研究課題

1. **SCBR の詳細分析**
   - System Code Based Routing のベンダー実装の違い
   - パフォーマンスへの影響

2. **eSE との相互作用**
   - eSE が登録した SC との競合
   - 優先順位の制御

3. **セキュリティ強化**
   - 悪意のある使用の検出
   - 適切な監査ログ

## 6. 参考資料

### 6.1 公式ドキュメント

1. **Android Developers - NFC**
   - https://developer.android.com/guide/topics/connectivity/nfc

2. **Host-based Card Emulation**
   - https://developer.android.com/guide/topics/connectivity/nfc/hce

3. **NFC Forum Specifications**
   - https://nfc-forum.org/specifications/

### 6.2 AOSP ソースコード

1. **packages/apps/Nfc**
   - https://android.googlesource.com/platform/packages/apps/Nfc/

2. **packages/modules/Nfc**
   - https://android.googlesource.com/platform/packages/modules/Nfc/

3. **system/nfc (NFC HAL)**
   - https://android.googlesource.com/platform/system/nfc/

### 6.3 関連プロジェクト

1. **libnfc-nci**
   - NFC Controller Interface の実装

2. **NXP NFC Driver**
   - ハードウェアドライバ

3. **LSPosed**
   - https://github.com/LSPosed/LSPosed

### 6.4 技術仕様

1. **ISO/IEC 18092** (NFC-F)
   - NFCIP-1: Near Field Communication Interface and Protocol

2. **JIS X 6319-4** (FeliCa)
   - 非接触型 IC カード

3. **NCI Specification**
   - NFC Controller Interface

### 6.5 セキュリティ研究

1. **NFC Security**
   - OWASP Mobile Security Testing Guide

2. **CVE Database**
   - https://cve.mitre.org/

3. **Android Security Bulletins**
   - https://source.android.com/security/bulletin

---

**この解体新書が、AOSP NFC の理解と t3t-spray モジュールの開発に役立つことを願っています。継続的な学習と責任ある使用を心がけましょう。**

## 謝辞

- **AOSP Team**: 素晴らしいオープンソース実装
- **LSPosed Team**: 強力な Xposed フレームワーク
- **NFC Forum**: 標準化への貢献
- **セキュリティ研究者**: 脆弱性の発見と責任ある開示

---

**作成日**: 2025-12-26  
**分析対象**: AOSP android15-release  
**分析者**: GitHub Copilot + Human Review
