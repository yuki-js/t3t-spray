# AOSP NFC解体新書 - JNI とネイティブコード分析

## 目次
1. JNI の基礎
2. NFC JNI の構造
3. メモリ管理の詳細
4. 型変換とマーシャリング
5. スレッドモデル
6. セキュリティ上の脆弱性

## 1. JNI の基礎

### 1.1 JNI とは

JNI (Java Native Interface) は、Java コードと C/C++ コードを接続するための仕組みです。

**主な用途**:
- パフォーマンスが重要な処理
- ハードウェアへの直接アクセス
- 既存の C/C++ ライブラリの利用

### 1.2 NFC における JNI の役割

```
┌──────────────────┐
│   Java Layer     │  NfcService, NativeNfcManager
│   (Framework)    │
└────────┬─────────┘
         │ JNI
         │
┌────────▼─────────┐
│  Native Layer    │  NativeNfcManager.cpp, RoutingManager.cpp
│   (C++)          │
└────────┬─────────┘
         │
┌────────▼─────────┐
│  NFC HAL         │  libnfc-nci.so
│  (libnfc-nci)    │
└────────┬─────────┘
         │
┌────────▼─────────┐
│  NFC Controller  │  Hardware
└──────────────────┘
```

## 2. NFC JNI の構造

### 2.1 JNI メソッドテーブル

```cpp
// NativeNfcManager.cpp
static JNINativeMethod gMethods[] = {
    {"initializeNativeStructure", "()Z",
     (void*)nfcManager_initNativeStruc},
    
    {"doInitialize", "()Z",
     (void*)nfcManager_doInitialize},
    
    {"doDeinitialize", "()Z",
     (void*)nfcManager_doDeinitialize},
    
    {"doRegisterT3tIdentifier", "([B)I",
     (void*)nfcManager_doRegisterT3tIdentifier},
    
    {"doDeregisterT3tIdentifier", "(I)V",
     (void*)nfcManager_doDeregisterT3tIdentifier},
    
    {"getLfT3tMax", "()I",
     (void*)nfcManager_getLfT3tMax},
    
    {"doEnableDiscovery", "(IZZZZZ)V",
     (void*)nfcManager_doEnableDiscovery},
    
    // ... 多数のメソッド
};
```

### 2.2 JNI ライブラリの登録

```cpp
// JNI_OnLoad 関数 (ライブラリロード時に呼ばれる)
jint JNI_OnLoad(JavaVM* jvm, void* reserved) {
    JNIEnv* e = NULL;
    
    if (jvm->GetEnv((void**)&e, JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }
    
    // NativeNfcManager クラスを探す
    jclass clazz = e->FindClass(
        "com/android/nfc/dhimpl/NativeNfcManager");
    if (clazz == NULL) {
        return JNI_ERR;
    }
    
    // メソッドを登録
    if (e->RegisterNatives(clazz, gMethods, 
                           sizeof(gMethods) / sizeof(gMethods[0])) != JNI_OK) {
        return JNI_ERR;
    }
    
    return JNI_VERSION_1_6;
}
```

### 2.3 メソッドシグネチャの読み方

| シグネチャ | 意味 |
|-----------|------|
| `()V` | 引数なし、戻り値 void |
| `()Z` | 引数なし、戻り値 boolean |
| `()I` | 引数なし、戻り値 int |
| `([B)I` | 引数 byte[]、戻り値 int |
| `(I)V` | 引数 int、戻り値 void |
| `(Ljava/lang/String;)V` | 引数 String、戻り値 void |
| `([I[B)Z` | 引数 int[], byte[]、戻り値 boolean |

## 3. メモリ管理の詳細

### 3.1 ローカル参照とグローバル参照

```cpp
// ローカル参照 (関数の終了時に自動的に解放)
jclass clazz = e->FindClass("com/android/nfc/SomeClass");
// ... 使用 ...
// 関数終了時に自動解放

// グローバル参照 (明示的に解放するまで保持)
jclass globalClazz = (jclass)e->NewGlobalRef(clazz);
// ... 長期間保持 ...
e->DeleteGlobalRef(globalClazz);  // 明示的に解放
```

### 3.2 配列のアクセス

#### 3.2.1 GetByteArrayElements (古い方法)

```cpp
// メモリリークの危険がある方法
jbyte* bytes = e->GetByteArrayElements(jarray, NULL);
// ... 使用 ...
e->ReleaseByteArrayElements(jarray, bytes, 0);  // 必ず呼ぶ必要
```

**問題点**:
- `Release~` を呼び忘れるとメモリリーク
- 例外が発生した場合、リークする可能性

#### 3.2.2 ScopedByteArrayRO (推奨)

```cpp
// RAII パターンで安全
{
    ScopedByteArrayRO bytes(e, jarray);
    uint8_t* buf = const_cast<uint8_t*>(
        reinterpret_cast<const uint8_t*>(&bytes[0]));
    size_t len = bytes.size();
    
    // ... 使用 ...
    
}  // スコープを抜けると自動的に解放
```

**ScopedByteArrayRO の実装**:
```cpp
class ScopedByteArrayRO {
public:
    ScopedByteArrayRO(JNIEnv* env, jbyteArray array)
        : mEnv(env), mArray(array) {
        mBytes = mEnv->GetByteArrayElements(mArray, NULL);
        mSize = mEnv->GetArrayLength(mArray);
    }
    
    ~ScopedByteArrayRO() {
        mEnv->ReleaseByteArrayElements(mArray, mBytes, JNI_ABORT);
    }
    
    const jbyte& operator[](size_t n) const { return mBytes[n]; }
    size_t size() const { return mSize; }
    
private:
    JNIEnv* mEnv;
    jbyteArray mArray;
    jbyte* mBytes;
    jsize mSize;
};
```

### 3.3 文字列の変換

```cpp
// Java String → C++ std::string
jstring jstr = ...;
const char* chars = e->GetStringUTFChars(jstr, NULL);
std::string cppStr(chars);
e->ReleaseStringUTFChars(jstr, chars);

// C++ std::string → Java String
std::string cppStr = "Hello";
jstring jstr = e->NewStringUTF(cppStr.c_str());
```

## 4. 型変換とマーシャリング

### 4.1 プリミティブ型の変換

| Java 型 | C/C++ 型 | サイズ |
|---------|----------|--------|
| boolean | jboolean | 1 byte |
| byte | jbyte | 1 byte |
| char | jchar | 2 bytes |
| short | jshort | 2 bytes |
| int | jint | 4 bytes |
| long | jlong | 8 bytes |
| float | jfloat | 4 bytes |
| double | jdouble | 8 bytes |

### 4.2 byte[] ↔ uint8_t* の変換

```cpp
// Java → C++
static jint nfcManager_doRegisterT3tIdentifier(
    JNIEnv* e, jobject, jbyteArray t3tIdentifier) {
    
    // 方法 1: ScopedByteArrayRO (推奨)
    ScopedByteArrayRO bytes(e, t3tIdentifier);
    uint8_t* buf = const_cast<uint8_t*>(
        reinterpret_cast<const uint8_t*>(&bytes[0]));
    size_t bufLen = bytes.size();
    
    // 方法 2: GetByteArrayRegion (コピーが発生)
    jsize len = e->GetArrayLength(t3tIdentifier);
    uint8_t* buf = new uint8_t[len];
    e->GetByteArrayRegion(t3tIdentifier, 0, len, (jbyte*)buf);
    // ... 使用 ...
    delete[] buf;
}

// C++ → Java
jbyteArray createJavaByteArray(JNIEnv* e, uint8_t* data, size_t len) {
    jbyteArray result = e->NewByteArray(len);
    e->SetByteArrayRegion(result, 0, len, (jbyte*)data);
    return result;
}
```

### 4.3 構造体の変換

```cpp
// C++ 構造体
struct T3tIdentifier {
    uint16_t systemCode;
    uint8_t nfcid2[8];
    uint8_t pmm[8];
};

// バイト配列から構造体へ
void parseT3tIdentifier(uint8_t* buf, T3tIdentifier* out) {
    out->systemCode = (buf[0] << 8) | buf[1];
    memcpy(out->nfcid2, buf + 2, 8);
    memcpy(out->pmm, buf + 10, 8);
}

// 構造体からバイト配列へ
void serializeT3tIdentifier(const T3tIdentifier* t3tId, uint8_t* buf) {
    buf[0] = (t3tId->systemCode >> 8) & 0xFF;
    buf[1] = t3tId->systemCode & 0xFF;
    memcpy(buf + 2, t3tId->nfcid2, 8);
    memcpy(buf + 10, t3tId->pmm, 8);
}
```

## 5. スレッドモデル

### 5.1 JNI とスレッド

```cpp
// 各スレッドは独立した JNIEnv を持つ
static void workerThread(JavaVM* jvm) {
    JNIEnv* env;
    
    // スレッドを JVM にアタッチ
    jvm->AttachCurrentThread(&env, NULL);
    
    // JNI 操作
    jclass clazz = env->FindClass("com/android/nfc/SomeClass");
    // ...
    
    // デタッチ
    jvm->DetachCurrentThread();
}
```

### 5.2 NFC JNI のスレッド構造

```
Main Thread (Java)
    ↓ JNI 呼び出し
Native Thread (C++)
    ↓ HAL 呼び出し
NFC HAL Thread
    ↓ コールバック
Native Callback Thread
    ↓ JNI コールバック
Java Callback Thread
```

### 5.3 スレッド安全性

```cpp
// 同期の例
class RoutingManager {
private:
    SyncEvent mRoutingEvent;  // 同期オブジェクト
    
public:
    int registerT3tIdentifier(uint8_t* t3tId, uint8_t t3tIdLen) {
        // メインスレッドから呼ばれる
        {
            SyncEventGuard guard(mRoutingEvent);
            tNFA_STATUS status = NFA_CeRegisterFelicaSystemCodeOnDH(...);
            if (status == NFA_STATUS_OK) {
                mRoutingEvent.wait();  // コールバックを待つ
            }
        }
        // コールバックスレッドが notifyOne() を呼ぶまでブロック
    }
    
    static void callback(uint8_t event, tNFA_CONN_EVT_DATA* data) {
        // HAL スレッドから呼ばれる
        RoutingManager& mgr = RoutingManager::getInstance();
        {
            SyncEventGuard guard(mgr.mRoutingEvent);
            mgr.mRoutingEvent.notifyOne();  // 待機中を起こす
        }
    }
};
```

## 6. セキュリティ上の脆弱性

### 6.1 バッファオーバーフロー

#### 脆弱性例 1: 境界チェック不足

```cpp
// 脆弱なコード
int RoutingManager::registerT3tIdentifier(uint8_t* t3tId, uint8_t t3tIdLen) {
    uint8_t nfcid2[8];
    
    // 問題: t3tIdLen のチェック不足
    memcpy(nfcid2, t3tId + 2, 8);  // バッファオーバーリード
    
    // もし t3tIdLen < 10 の場合、範囲外メモリを読む
}

// 修正版
int RoutingManager::registerT3tIdentifier(uint8_t* t3tId, uint8_t t3tIdLen) {
    if (t3tIdLen < 10) {
        LOG(ERROR) << "Buffer too small: " << t3tIdLen;
        return NFA_HANDLE_INVALID;
    }
    
    uint8_t nfcid2[8];
    memcpy(nfcid2, t3tId + 2, 8);
}
```

**CVSS スコア**: 3.3 (Low)
- **攻撃ベクター**: Xposed フックから任意サイズのバッファを送信
- **影響**: メモリ情報漏洩、クラッシュ
- **緩和要因**: ルート権限が必要、ローカル攻撃のみ

#### 脆弱性例 2: 整数オーバーフロー

```cpp
// 脆弱なコード
uint16_t systemCode = (((int)t3tId[0] << 8) | ((int)t3tId[1] << 0));

// 問題: t3tId が signed char* の場合
// t3tId[0] = 0xFF → (int)0xFF = (int)-1 (符号拡張)
//           → -1 << 8 = -256
//           → 予期しない値

// 修正版
uint16_t systemCode = (((uint16_t)(uint8_t)t3tId[0] << 8) | 
                       ((uint16_t)(uint8_t)t3tId[1]));
```

**CVSS スコア**: 2.0 (Low)
- **影響**: 不正な System Code の登録
- **緩和要因**: 実害は限定的

### 6.2 Use After Free

```cpp
// 脆弱なコード
static jclass gNfcManagerClass = NULL;

void nfcManager_doSomething(JNIEnv* e, jobject o) {
    if (gNfcManagerClass == NULL) {
        gNfcManagerClass = e->FindClass("com/android/nfc/dhimpl/NativeNfcManager");
    }
    // 問題: ローカル参照を保存
    // GC でクラスがアンロードされる可能性
}

// 修正版
static jclass gNfcManagerClass = NULL;

void nfcManager_initialize(JNIEnv* e, jobject o) {
    jclass clazz = e->FindClass("com/android/nfc/dhimpl/NativeNfcManager");
    gNfcManagerClass = (jclass)e->NewGlobalRef(clazz);  // グローバル参照
    e->DeleteLocalRef(clazz);
}

void nfcManager_cleanup(JNIEnv* e, jobject o) {
    if (gNfcManagerClass != NULL) {
        e->DeleteGlobalRef(gNfcManagerClass);
        gNfcManagerClass = NULL;
    }
}
```

### 6.3 レースコンディション

```cpp
// 脆弱なコード
static tNFA_HANDLE sNfcFOnDhHandle = NFA_HANDLE_INVALID;

// Thread 1
int registerT3tIdentifier(...) {
    NFA_CeRegisterFelicaSystemCodeOnDH(...);
    // コールバックを待つ
}

// Thread 2 (callback)
void callback(uint8_t event, tNFA_CONN_EVT_DATA* data) {
    sNfcFOnDhHandle = data->ce_registered.handle;  // 競合!
}

// 修正版: メンバー変数 + ロック
class RoutingManager {
private:
    tNFA_HANDLE mNfcFOnDhHandle;
    SyncEvent mRoutingEvent;
    
public:
    int registerT3tIdentifier(...) {
        SyncEventGuard guard(mRoutingEvent);
        NFA_CeRegisterFelicaSystemCodeOnDH(...);
        mRoutingEvent.wait();
        return mNfcFOnDhHandle;
    }
    
    static void callback(...) {
        RoutingManager& mgr = getInstance();
        SyncEventGuard guard(mgr.mRoutingEvent);
        mgr.mNfcFOnDhHandle = ...;
        mgr.mRoutingEvent.notifyOne();
    }
};
```

**CVSS スコア**: 4.0 (Medium)
- **影響**: データ破損、予期しない動作
- **緩和要因**: 現在の実装では適切に同期されている

---

**JNI は強力ですが、メモリ管理やスレッド安全性に注意が必要です。RAII パターンや適切な同期を使用することで、安全なコードを書くことができます。**
