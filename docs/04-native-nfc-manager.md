# AOSP NFC解体新書 - NativeNfcManager とネイティブ層

## 目次
1. NativeNfcManager の役割
2. JNI メソッドの詳細
3. ネイティブメソッドの実装
4. メモリ管理と安全性
5. デバッグ方法

## 1. NativeNfcManager の役割

### 1.1 概要
`NativeNfcManager` は Java 層と C++ ネイティブ層の橋渡しをする重要なクラスです。

**場所**: 
- Java: `com.android.nfc.dhimpl.NativeNfcManager`
- C++ JNI: `apps-nfc/nci/jni/NativeNfcManager.cpp`

### 1.2 主要な責務

1. **JNI メソッドの提供**
   - Java から C++ ネイティブコードを呼び出すインターフェース
   
2. **NFC HAL との通信**
   - libnfc-nci (NFC HAL) の関数を呼び出し
   
3. **コールバックの管理**
   - ネイティブ層からのイベントを Java 層に通知

## 2. JNI メソッドの詳細

### 2.1 T3T 関連のメソッド (Java)

```java
package com.android.nfc.dhimpl;

public class NativeNfcManager implements DeviceHost {
    
    // T3T 識別子の登録 (ネイティブメソッド)
    public native int doRegisterT3tIdentifier(byte[] t3tIdentifier);
    
    // T3T 識別子の登録 (ラッパー)
    public void registerT3tIdentifier(byte[] t3tIdentifier) {
        synchronized (NativeNfcManager.class) {
            int handle = doRegisterT3tIdentifier(t3tIdentifier);
            if (handle != 0xFFFF) {
                mT3tIdentifiers.put(HexFormat.of().formatHex(t3tIdentifier),
                                    Integer.valueOf(handle));
            }
        }
    }
    
    // T3T 識別子の解除 (ネイティブメソッド)
    public native void doDeregisterT3tIdentifier(int handle);
    
    // T3T 識別子の解除 (ラッパー)
    public void deregisterT3tIdentifier(byte[] t3tIdentifier) {
        synchronized (NativeNfcManager.class) {
            String identifier = HexFormat.of().formatHex(t3tIdentifier);
            Integer handle = mT3tIdentifiers.get(identifier);
            if (handle != null) {
                doDeregisterT3tIdentifier(handle.intValue());
                mT3tIdentifiers.remove(identifier);
            }
        }
    }
    
    // T3T 識別子の最大数を取得
    public int getLfT3tMax() {
        return doGetLfT3tMax();
    }
    
    private native int doGetLfT3tMax();
}
```

### 2.2 メソッドシグネチャ

JNI メソッドのシグネチャは厳密に定義されています:

```cpp
// NativeNfcManager.cpp 内の JNINativeMethod テーブル
static JNINativeMethod gMethods[] = {
    // ...
    {"doRegisterT3tIdentifier", "([B)I",
     (void*)nfcManager_doRegisterT3tIdentifier},

    {"doDeregisterT3tIdentifier", "(I)V",
     (void*)nfcManager_doDeregisterT3tIdentifier},

    {"getLfT3tMax", "()I", 
     (void*)nfcManager_getLfT3tMax},
    // ...
};
```

**シグネチャ解説**:
- `([B)I`: バイト配列を受け取り、int を返す
- `(I)V`: int を受け取り、void を返す
- `()I`: 引数なし、int を返す

## 3. ネイティブメソッドの実装

### 3.1 nfcManager_doRegisterT3tIdentifier

```cpp
/*******************************************************************************
**
** Function:        nfcManager_doRegisterT3tIdentifier
**
** Description:     Registers LF_T3T_IDENTIFIER for NFC-F.
**                  e: JVM environment.
**                  o: Java object.
**                  t3tIdentifier: LF_T3T_IDENTIFIER value (10 or 18 bytes)
**
** Returns:         Handle retrieve from RoutingManager.
**
*******************************************************************************/
static jint nfcManager_doRegisterT3tIdentifier(
    JNIEnv* e, jobject, jbyteArray t3tIdentifier) {
    
    LOG(DEBUG) << StringPrintf("%s: enter", __func__);

    // Java バイト配列を C++ で読み取り可能に
    ScopedByteArrayRO bytes(e, t3tIdentifier);
    uint8_t* buf = const_cast<uint8_t*>(
        reinterpret_cast<const uint8_t*>(&bytes[0]));
    size_t bufLen = bytes.size();

    // RoutingManager に委譲
    int handle = RoutingManager::getInstance()
                    .registerT3tIdentifier(buf, bufLen);

    LOG(DEBUG) << StringPrintf("%s: handle=%d", __func__, handle);
    
    // 登録成功時、ルーティングをコミット
    if (handle != NFA_HANDLE_INVALID) {
        RoutingManager::getInstance().commitRouting();
    }
    
    LOG(DEBUG) << StringPrintf("%s: exit", __func__);
    return handle;
}
```

**重要なポイント**:
1. `ScopedByteArrayRO`: RAII パターンで Java 配列を安全に読み取り
2. `RoutingManager` への委譲
3. `commitRouting()` の呼び出し (即座に反映)

### 3.2 nfcManager_doDeregisterT3tIdentifier

```cpp
/*******************************************************************************
**
** Function:        nfcManager_doDeregisterT3tIdentifier
**
** Description:     Deregisters LF_T3T_IDENTIFIER for NFC-F.
**                  e: JVM environment.
**                  o: Java object.
**                  handle: Handle retrieve from libnfc-nci.
**
** Returns:         None
**
*******************************************************************************/
static void nfcManager_doDeregisterT3tIdentifier(
    JNIEnv*, jobject, jint handle) {
    
    LOG(DEBUG) << StringPrintf("%s: enter; handle=%d", __func__, handle);

    // RoutingManager に委譲
    RoutingManager::getInstance().deregisterT3tIdentifier(handle);

    LOG(DEBUG) << StringPrintf("%s: exit", __func__);
}
```

### 3.3 nfcManager_getLfT3tMax

```cpp
/*******************************************************************************
**
** Function:        nfcManager_getLfT3tMax
**
** Description:     Get the maximum supported T3T identifiers.
**
** Returns:         Maximum number
**
*******************************************************************************/
static jint nfcManager_getLfT3tMax(JNIEnv*, jobject) {
    LOG(DEBUG) << __func__;
    
    // sLfT3tMax は初期化時に設定される
    return sLfT3tMax;
}
```

`sLfT3tMax` は NFC コントローラの能力に応じて設定されます (通常 4-16)。

## 4. メモリ管理と安全性

### 4.1 JNI のメモリ管理

JNI では、Java と C++ 間のメモリ管理が重要です:

```cpp
// 正しい例: ScopedByteArrayRO を使用
ScopedByteArrayRO bytes(e, t3tIdentifier);
uint8_t* buf = const_cast<uint8_t*>(
    reinterpret_cast<const uint8_t*>(&bytes[0]));
// bytes がスコープを抜けると自動的に解放
```

```cpp
// 間違った例 (メモリリーク):
jbyte* bytes = e->GetByteArrayElements(t3tIdentifier, NULL);
uint8_t* buf = reinterpret_cast<uint8_t*>(bytes);
// !! e->ReleaseByteArrayElements() を呼び忘れるとリーク !!
```

### 4.2 スレッド安全性

```java
// Java 側: synchronized で保護
synchronized (NativeNfcManager.class) {
    int handle = doRegisterT3tIdentifier(t3tIdentifier);
    mT3tIdentifiers.put(identifier, handle);
}
```

```cpp
// C++ 側: SyncEventGuard でイベント同期
SyncEventGuard guard(mRoutingEvent);
tNFA_STATUS nfaStat = NFA_CeRegisterFelicaSystemCodeOnDH(...);
if (nfaStat == NFA_STATUS_OK) {
    mRoutingEvent.wait();  // コールバック待ち
}
```

### 4.3 エラーハンドリング

```cpp
// バッファ長のチェック
if (t3tIdLen != (2 + NCI_RF_F_UID_LEN + NCI_T3T_PMM_LEN)) {
    LOG(ERROR) << fn << ": Invalid length of T3T Identifier";
    return NFA_HANDLE_INVALID;  // エラー値を返す
}

// NFA 関数の戻り値チェック
tNFA_STATUS nfaStat = NFA_CeRegisterFelicaSystemCodeOnDH(...);
if (nfaStat != NFA_STATUS_OK) {
    LOG(ERROR) << fn << ": Fail to register NFC-F system on DH";
    return NFA_HANDLE_INVALID;
}
```

## 5. デバッグ方法

### 5.1 ログ出力

```cpp
// C++ 側のログ
LOG(DEBUG) << StringPrintf("%s: handle=%d", __func__, handle);
LOG(ERROR) << StringPrintf("%s: Failed with status=%d", __func__, status);
```

ログは `logcat` で確認可能:
```bash
adb logcat | grep -E "NativeNfcManager|RoutingManager"
```

### 5.2 Java 側のデバッグ

```java
Log.d(TAG, "registerT3tIdentifier: " + 
      HexFormat.of().formatHex(t3tIdentifier));
```

### 5.3 Xposed でのデバッグ

```kotlin
XposedHelpers.findAndHookMethod(
    NativeNfcManager::class.java,
    "doRegisterT3tIdentifier",
    ByteArray::class.java,
    object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            val t3tId = param.args[0] as ByteArray
            XposedBridge.log("T3tSpray: Registering: ${t3tId.toHex()}")
        }
        override fun afterHookedMethod(param: MethodHookParam) {
            val handle = param.result as Int
            XposedBridge.log("T3tSpray: Handle: $handle")
        }
    }
)
```

## 6. 潜在的な問題と対策

### 6.1 バッファオーバーフロー (CVE 候補)

```cpp
// 潜在的な問題:
memcpy(nfcid2, t3tId + 2, NCI_RF_F_UID_LEN);  // 境界チェックなし

// 改善案:
if (bufLen < 2 + NCI_RF_F_UID_LEN + NCI_T3T_PMM_LEN) {
    LOG(ERROR) << "Buffer too small";
    return NFA_HANDLE_INVALID;
}
memcpy(nfcid2, t3tId + 2, NCI_RF_F_UID_LEN);
```

**リスク評価**: CVSS 3.0-4.0 (Low-Medium)
- 入力は Java 層から来るため、ある程度検証済み
- しかし、Xposed フックで任意のサイズを送信可能

### 6.2 整数オーバーフロー

```cpp
// System Code の解析
uint16_t systemCode = (((int)t3tId[0] << 8) | ((int)t3tId[1] << 0));

// 潜在的な問題: t3tId[0] が負の値の場合 (signed char の場合)
// 改善: uint8_t にキャスト
uint16_t systemCode = (((uint16_t)t3tId[0] << 8) | 
                       ((uint16_t)t3tId[1]));
```

### 6.3 レースコンディション

```java
// 潜在的な問題:
int handle = doRegisterT3tIdentifier(t3tIdentifier);
// ここで別スレッドが mT3tIdentifiers を変更可能
mT3tIdentifiers.put(identifier, handle);

// 対策: synchronized ブロック (既に実装済み)
synchronized (NativeNfcManager.class) {
    int handle = doRegisterT3tIdentifier(t3tIdentifier);
    mT3tIdentifiers.put(identifier, handle);
}
```

## 7. Xposed フックの実装詳細

### 7.1 ネイティブメソッドのフック

```kotlin
val nativeManagerClass = XposedHelpers.findClass(
    "com.android.nfc.dhimpl.NativeNfcManager",
    lpparam.classLoader
)

// doRegisterT3tIdentifier をフック
XposedHelpers.findAndHookMethod(
    nativeManagerClass,
    "doRegisterT3tIdentifier",
    ByteArray::class.java,
    object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            // オリジナルの呼び出しが完了した後
            val handle = param.result as Int
            
            if (handle != -1) {  // NFA_HANDLE_INVALID
                XposedBridge.log("T3tSpray: Successfully registered, handle=$handle")
            }
        }
    }
)
```

### 7.2 追加登録の実装

```kotlin
// registerT3tIdentifier (ラッパー) をフック
XposedHelpers.findAndHookMethod(
    nativeManagerClass,
    "registerT3tIdentifier",
    ByteArray::class.java,
    object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            // 元の登録が完了した後、追加の識別子を登録
            val additionalIdentifiers = getAdditionalIdentifiers()
            
            additionalIdentifiers.forEach { t3tId ->
                runCatching {
                    XposedHelpers.callMethod(
                        param.thisObject,
                        "doRegisterT3tIdentifier",
                        t3tId
                    )
                }.onSuccess { handle ->
                    XposedBridge.log("T3tSpray: Added SC=${t3tId.toHex()}, handle=$handle")
                }
            }
        }
    }
)
```

---

**NativeNfcManager は Java と C++ の境界に位置する重要なコンポーネントです。このレイヤーを理解することで、より深いレベルでの NFC 制御が可能になります。**
