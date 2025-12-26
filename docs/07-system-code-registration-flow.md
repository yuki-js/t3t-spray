# AOSP NFC解体新書 - System Code 登録フロー詳細

## 目次
1. 登録フローの全体像
2. 各レイヤーでのデータ変換
3. 同期メカニズム
4. エラーハンドリング
5. ログとデバッグ
6. パフォーマンス考慮事項

## 1. 登録フローの全体像

### 1.1 レイヤー別のフロー図

```
┌─────────────────────────────────────────────────────────┐
│ Application Layer (HCE-F Service)                       │
│  - AndroidManifest.xml で SC 宣言                       │
│  - HostNfcFService 実装                                 │
└────────────────┬────────────────────────────────────────┘
                 │
                 ▼
┌─────────────────────────────────────────────────────────┐
│ Framework Layer (CardEmulationManager)                  │
│  - NfcFServiceInfo の解析                               │
│  - フォアグラウンドサービスの管理                        │
└────────────────┬────────────────────────────────────────┘
                 │
                 ▼
┌─────────────────────────────────────────────────────────┐
│ RegisteredT3tIdentifiersCache                           │
│  - T3T 識別子のキャッシング                             │
│  - generateForegroundT3tIdentifiersCacheLocked()        │
└────────────────┬────────────────────────────────────────┘
                 │
                 ▼
┌─────────────────────────────────────────────────────────┐
│ SystemCodeRoutingManager                                │
│  - configureRouting(List<T3tIdentifier>)                │
│  - 差分検出 (toBeAdded, toBeRemoved)                    │
└────────────────┬────────────────────────────────────────┘
                 │ For each T3tIdentifier
                 ▼
┌─────────────────────────────────────────────────────────┐
│ NfcService                                              │
│  - registerT3tIdentifier(String sc, String idm, pmm)    │
│  - String から byte[] に変換                            │
└────────────────┬────────────────────────────────────────┘
                 │
                 ▼
┌─────────────────────────────────────────────────────────┐
│ NativeNfcManager (Java)                                 │
│  - registerT3tIdentifier(byte[] t3tIdentifier)          │
│  - synchronized ブロックで保護                          │
│  - doRegisterT3tIdentifier() 呼び出し                   │
└────────────────┬────────────────────────────────────────┘
                 │ JNI Boundary
                 ▼
┌─────────────────────────────────────────────────────────┐
│ NativeNfcManager.cpp (JNI)                              │
│  - nfcManager_doRegisterT3tIdentifier()                 │
│  - Java byte[] → C++ uint8_t*                           │
└────────────────┬────────────────────────────────────────┘
                 │
                 ▼
┌─────────────────────────────────────────────────────────┐
│ RoutingManager.cpp                                      │
│  - registerT3tIdentifier(uint8_t*, len)                 │
│  - バッファから SC, IDm, PMM を抽出                      │
└────────────────┬────────────────────────────────────────┘
                 │
                 ▼
┌─────────────────────────────────────────────────────────┐
│ NFC HAL (libnfc-nci)                                    │
│  - NFA_CeRegisterFelicaSystemCodeOnDH()                 │
│  - NCI コマンド構築                                     │
└────────────────┬────────────────────────────────────────┘
                 │
                 ▼
┌─────────────────────────────────────────────────────────┐
│ NFC Controller (Hardware)                               │
│  - T3T Identifier Table に追加                          │
│  - SENSF_REQ 応答準備完了                               │
└─────────────────────────────────────────────────────────┘
```

## 2. 各レイヤーでのデータ変換

### 2.1 XML → NfcFServiceInfo

```xml
<!-- nfcf_service.xml -->
<system-code-filter android:name="FE00"/>
<nfcid2-filter android:name="02FE000000000000"/>
```

↓ パース

```java
NfcFServiceInfo serviceInfo = new NfcFServiceInfo(...);
// systemCode = "FE00"
// nfcid2 = "02FE000000000000"
// t3tPmm = "0000000000000000" (デフォルト)
```

### 2.2 NfcFServiceInfo → T3tIdentifier

```java
// RegisteredT3tIdentifiersCache.java
T3tIdentifier t3tId = new T3tIdentifier(
    service.getSystemCode(),  // "FE00"
    service.getNfcid2(),      // "02FE000000000000"
    service.getT3tPmm()       // "0000000000000000"
);
```

### 2.3 T3tIdentifier → String 引数

```java
// SystemCodeRoutingManager.java
NfcService.getInstance().registerT3tIdentifier(
    t3tIdentifier.systemCode,  // "FE00"
    t3tIdentifier.nfcid2,      // "02FE000000000000"
    t3tIdentifier.t3tPmm       // "0000000000000000"
);
```

### 2.4 String → byte[]

```java
// NfcService.java
public void registerT3tIdentifier(String systemCode, String nfcid2, String t3tPmm) {
    byte[] t3tIdentifier = new byte[2 + 8 + 8];  // 18 bytes
    
    // System Code (Big Endian)
    int sc = Integer.parseInt(systemCode, 16);
    t3tIdentifier[0] = (byte) ((sc >> 8) & 0xFF);
    t3tIdentifier[1] = (byte) (sc & 0xFF);
    
    // NFCID2
    byte[] idmBytes = hexStringToByteArray(nfcid2);
    System.arraycopy(idmBytes, 0, t3tIdentifier, 2, 8);
    
    // PMM
    byte[] pmmBytes = hexStringToByteArray(t3tPmm);
    System.arraycopy(pmmBytes, 0, t3tIdentifier, 10, 8);
    
    mDeviceHost.registerT3tIdentifier(t3tIdentifier);
}
```

### 2.5 Java byte[] → C++ uint8_t*

```cpp
// NativeNfcManager.cpp
static jint nfcManager_doRegisterT3tIdentifier(
    JNIEnv* e, jobject, jbyteArray t3tIdentifier) {
    
    ScopedByteArrayRO bytes(e, t3tIdentifier);
    uint8_t* buf = const_cast<uint8_t*>(
        reinterpret_cast<const uint8_t*>(&bytes[0]));
    size_t bufLen = bytes.size();
    
    // RoutingManager に渡す
    int handle = RoutingManager::getInstance()
                    .registerT3tIdentifier(buf, bufLen);
    return handle;
}
```

### 2.6 uint8_t* → NFA パラメータ

```cpp
// RoutingManager.cpp
int RoutingManager::registerT3tIdentifier(uint8_t* t3tId, uint8_t t3tIdLen) {
    uint16_t systemCode = (((int)t3tId[0] << 8) | ((int)t3tId[1] << 0));
    uint8_t nfcid2[8];
    uint8_t t3tPmm[8];
    
    memcpy(nfcid2, t3tId + 2, 8);
    memcpy(t3tPmm, t3tId + 10, 8);
    
    tNFA_STATUS nfaStat = NFA_CeRegisterFelicaSystemCodeOnDH(
        systemCode, nfcid2, t3tPmm, nfcFCeCallback);
    
    // ...
}
```

## 3. 同期メカニズム

### 3.1 Java レイヤーの同期

```java
// NativeNfcManager.java
public void registerT3tIdentifier(byte[] t3tIdentifier) {
    synchronized (NativeNfcManager.class) {
        int handle = doRegisterT3tIdentifier(t3tIdentifier);
        if (handle != 0xFFFF) {
            mT3tIdentifiers.put(
                HexFormat.of().formatHex(t3tIdentifier),
                Integer.valueOf(handle));
        }
    }
}
```

**目的**: 
- 複数スレッドからの同時呼び出しを防ぐ
- `mT3tIdentifiers` マップへのアクセスを保護

### 3.2 C++ レイヤーの同期

```cpp
// RoutingManager.cpp
int RoutingManager::registerT3tIdentifier(uint8_t* t3tId, uint8_t t3tIdLen) {
    // ...
    
    {
        SyncEventGuard guard(mRoutingEvent);
        tNFA_STATUS nfaStat = NFA_CeRegisterFelicaSystemCodeOnDH(
            systemCode, nfcid2, t3tPmm, nfcFCeCallback);
        
        if (nfaStat == NFA_STATUS_OK) {
            mRoutingEvent.wait();  // コールバックを待つ
        }
    }  // guard が自動的にロック解除
    
    // ...
}
```

**目的**:
- HAL からのコールバックを待機
- タイムアウトなしの無限待機

### 3.3 コールバックからの通知

```cpp
static void nfcFCeCallback(uint8_t event, tNFA_CONN_EVT_DATA* eventData) {
    RoutingManager& routingManager = RoutingManager::getInstance();

    switch (event) {
        case NFA_CE_REGISTERED_EVT:
            if (eventData->ce_registered.status == NFA_STATUS_OK) {
                routingManager.mNfcFOnDhHandle = 
                    eventData->ce_registered.handle;
            }
            
            {
                SyncEventGuard guard(routingManager.mRoutingEvent);
                routingManager.mRoutingEvent.notifyOne();  // 待機中を起こす
            }
            break;
    }
}
```

### 3.4 タイムシーケンス図

```
Thread 1 (Main)                  NFC HAL Thread
    |                                   |
    | registerT3tIdentifier()           |
    |-------------------------------->  |
    | mRoutingEvent.wait()              |
    |----> [Blocked]                    |
                                        |
                                        | NFA processing...
                                        |
                                        | nfcFCeCallback()
                                        |----------------------->
                                        | mRoutingEvent.notifyOne()
    |<----------------------------------|
    | [Unblocked]                       |
    | return handle                     |
    |                                   |
```

## 4. エラーハンドリング

### 4.1 各レイヤーでのエラーチェック

```java
// NfcService.java
public void registerT3tIdentifier(String sc, String idm, String pmm) {
    // パラメータ検証
    if (sc == null || sc.length() != 4) {
        Log.e(TAG, "Invalid system code: " + sc);
        return;
    }
    
    if (idm == null || idm.length() != 16) {
        Log.e(TAG, "Invalid NFCID2: " + idm);
        return;
    }
    
    // ...
}
```

```cpp
// RoutingManager.cpp
int RoutingManager::registerT3tIdentifier(uint8_t* t3tId, uint8_t t3tIdLen) {
    // 長さチェック
    if (t3tIdLen != 18) {
        LOG(ERROR) << "Invalid length: " << t3tIdLen;
        return NFA_HANDLE_INVALID;
    }
    
    // Null ポインタチェック
    if (t3tId == nullptr) {
        LOG(ERROR) << "Null pointer";
        return NFA_HANDLE_INVALID;
    }
    
    // HAL 呼び出しの結果チェック
    tNFA_STATUS nfaStat = NFA_CeRegisterFelicaSystemCodeOnDH(...);
    if (nfaStat != NFA_STATUS_OK) {
        LOG(ERROR) << "NFA_Ce failed: " << nfaStat;
        return NFA_HANDLE_INVALID;
    }
    
    // ...
}
```

### 4.2 エラー値の伝播

```
NFA_HANDLE_INVALID (0xFFFF)
    ↓
return -1 (C++ → Java)
    ↓
handle = -1 (Java)
    ↓
mT3tIdentifiers に追加しない
    ↓
登録失敗として処理
```

## 5. ログとデバッグ

### 5.1 各レイヤーのログ

```java
// Java レイヤー
Log.d(TAG, "registerT3tIdentifier: SC=" + systemCode + " IDm=" + nfcid2);
```

```cpp
// C++ レイヤー
LOG(DEBUG) << StringPrintf("%s: SC=0x%04X", __func__, systemCode);
LOG(DEBUG) << StringPrintf("%s: handle=%d", __func__, handle);
```

### 5.2 logcat でのフィルタリング

```bash
# NFC 関連のすべてのログ
adb logcat | grep -E "NfcService|NativeNfcManager|RoutingManager"

# T3T 登録関連のみ
adb logcat | grep -E "registerT3tIdentifier|T3tIdentifier"

# エラーのみ
adb logcat *:E | grep NFC
```

### 5.3 Xposed ログの追加

```kotlin
XposedHelpers.findAndHookMethod(
    nativeManagerClass,
    "doRegisterT3tIdentifier",
    ByteArray::class.java,
    object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            val t3tId = param.args[0] as ByteArray
            val hex = t3tId.joinToString("") { "%02X".format(it) }
            XposedBridge.log("T3tSpray[BEFORE]: $hex")
        }
        
        override fun afterHookedMethod(param: MethodHookParam) {
            val handle = param.result as Int
            XposedBridge.log("T3tSpray[AFTER]: handle=$handle")
        }
    }
)
```

## 6. パフォーマンス考慮事項

### 6.1 登録の遅延

各 T3T 識別子の登録には時間がかかります:

```
1 つの登録: 約 10-50ms
  - JNI 呼び出し: ~1ms
  - HAL 処理: ~5-20ms
  - NCI 通信: ~5-20ms
  - コントローラ更新: ~5-10ms
```

### 6.2 バッチ処理の検討

```java
// 現在の実装 (1つずつ登録)
for (T3tIdentifier t3tId : t3tIdentifiers) {
    NfcService.getInstance().registerT3tIdentifier(
        t3tId.systemCode, t3tId.nfcid2, t3tId.t3tPmm);
}

// 改善案: バッチ登録
// (ただし、現在の HAL API はサポートしていない)
```

### 6.3 commitRouting() のコスト

```cpp
bool RoutingManager::commitRouting() {
    // すべての pending な変更を一括でコミット
    tNFA_STATUS nfaStat = NFA_EeUpdateNow();
    // コスト: 約 50-200ms
}
```

**最適化**: 
- 複数の T3T 識別子を登録後、一度だけ `commitRouting()` を呼ぶ
- 現在の実装では各登録後に自動的に呼ばれる

---

**System Code 登録は複数のレイヤーを経由する複雑なプロセスですが、各レイヤーの役割を理解することで、デバッグや最適化が容易になります。**
