# AOSP NFC解体新書 - RegisteredT3tIdentifiersCache 詳細分析

## 目次
1. クラスの役割と責務
2. データ構造
3. 主要メソッドの詳細
4. フォアグラウンドサービスの処理
5. ルーティング更新のフロー

## 1. クラスの役割と責務

### 1.1 概要
`RegisteredT3tIdentifiersCache` は、HCE-F サービスが登録した T3T 識別子を管理するキャッシュクラスです。

**場所**: `com.android.nfc.cardemulation.RegisteredT3tIdentifiersCache`

### 1.2 主要な責務

1. **T3T 識別子のキャッシュ管理**
   - フォアグラウンドサービスの T3T 識別子を保持
   - NFCID2 からサービス情報を解決

2. **ルーティング管理との連携**
   - `SystemCodeRoutingManager` を使用してルーティング設定
   - NFC コントローラへの登録/解除を調整

3. **サービスライフサイクル管理**
   - フォアグラウンドサービスの変更検出
   - NFC 有効/無効の状態管理
   - ユーザー切り替えの処理

## 2. データ構造

### 2.1 内部クラス: T3tIdentifier

```java
final class T3tIdentifier {
    public final String systemCode;  // System Code (例: "FE00")
    public final String nfcid2;      // NFCID2 / IDm (8バイト)
    public final String t3tPmm;      // PMM (8バイト)

    T3tIdentifier(String systemCode, String nfcid2, String t3tPmm) {
        this.systemCode = systemCode;
        this.nfcid2 = nfcid2;
        this.t3tPmm = t3tPmm;
    }

    @Override
    public boolean equals(Object o) {
        // systemCode と nfcid2 で比較 (大文字小文字を区別しない)
        T3tIdentifier that = (T3tIdentifier) o;
        return systemCode.equalsIgnoreCase(that.systemCode) &&
               nfcid2.equalsIgnoreCase(that.nfcid2);
    }
}
```

### 2.2 メンバー変数

```java
// ユーザーごとの NFC-F サービス情報
final Map<Integer, List<NfcFServiceInfo>> mUserNfcFServiceInfo;

// フォアグラウンド T3T 識別子キャッシュ
final HashMap<String, NfcFServiceInfo> mForegroundT3tIdentifiersCache;

// 有効化されたフォアグラウンドサービス
ComponentName mEnabledForegroundService;
int mEnabledForegroundServiceUserId;

// SystemCodeRoutingManager への参照
final SystemCodeRoutingManager mRoutingManager;

// NFC 有効状態
boolean mNfcEnabled;
```

## 3. 主要メソッドの詳細

### 3.1 コンストラクタ

```java
public RegisteredT3tIdentifiersCache(Context context) {
    this(context, new SystemCodeRoutingManager());
}

@VisibleForTesting
RegisteredT3tIdentifiersCache(Context context, 
                               SystemCodeRoutingManager routingManager) {
    Log.d(TAG, "RegisteredT3tIdentifiersCache");
    mContext = context;
    mRoutingManager = routingManager;
}
```

**Xposed フックポイント**: コンストラクタをフックして、インスタンス生成時に追加の T3T 識別子を注入可能。

### 3.2 resolveNfcid2 - NFCID2 の解決

```java
public NfcFServiceInfo resolveNfcid2(String nfcid2) {
    synchronized (mLock) {
        if (DBG) Log.d(TAG, "resolveNfcid2: resolving NFCID " + nfcid2);
        
        // フォアグラウンドキャッシュから検索
        NfcFServiceInfo resolveInfo = 
            mForegroundT3tIdentifiersCache.get(nfcid2);
        
        Log.d(TAG, "Resolved to: " + 
            (resolveInfo == null ? "null" : resolveInfo.toString()));
        
        return resolveInfo;
    }
}
```

このメソッドは、SENSF_RES で返された NFCID2 から対応するサービスを解決するために使用されます。

### 3.3 generateForegroundT3tIdentifiersCacheLocked

```java
void generateForegroundT3tIdentifiersCacheLocked() {
    if (DBG) Log.d(TAG, "generateForegroundT3tIdentifiersCacheLocked");
    
    mForegroundT3tIdentifiersCache.clear();
    
    if (mEnabledForegroundService != null) {
        // フォアグラウンドサービスの T3T 識別子を検索
        for (NfcFServiceInfo service : 
             mUserNfcFServiceInfo.get(mEnabledForegroundServiceUserId)) {
            
            if (mEnabledForegroundService.equals(service.getComponent())) {
                // "NULL" でない場合のみ登録
                if (!service.getSystemCode().equalsIgnoreCase("NULL") &&
                    !service.getNfcid2().equalsIgnoreCase("NULL")) {
                    mForegroundT3tIdentifiersCache.put(
                        service.getNfcid2(), service);
                }
                break;
            }
        }
    }
    
    // ルーティングを更新
    updateRoutingLocked(false);
}
```

**重要**: このメソッドがフォアグラウンドサービスの T3T 識別子をキャッシュに追加します。

### 3.4 updateRoutingLocked - ルーティング更新

```java
void updateRoutingLocked(boolean force) {
    if (DBG) Log.d(TAG, "updateRoutingLocked");
    
    if (!mNfcEnabled) {
        Log.d(TAG, "Not updating routing table because NFC is off.");
        return;
    }

    List<T3tIdentifier> t3tIdentifiers = new ArrayList<T3tIdentifier>();

    // 強制更新の場合、空のテーブルを送信して全エントリを解除
    if (force) {
        mRoutingManager.configureRouting(t3tIdentifiers);
    }
    
    // フォアグラウンドサービスの T3T 識別子を登録
    Iterator<Map.Entry<String, NfcFServiceInfo>> it = 
        mForegroundT3tIdentifiersCache.entrySet().iterator();
    
    while (it.hasNext()) {
        Map.Entry<String, NfcFServiceInfo> entry = it.next();
        t3tIdentifiers.add(new T3tIdentifier(
            entry.getValue().getSystemCode(), 
            entry.getValue().getNfcid2(), 
            entry.getValue().getT3tPmm()));
    }
    
    // SystemCodeRoutingManager 経由でルーティング設定
    mRoutingManager.configureRouting(t3tIdentifiers);
}
```

このメソッドは、実際に NFC コントローラへの登録を開始するトリガーです。

## 4. フォアグラウンドサービスの処理

### 4.1 フォアグラウンドサービス変更の通知

```java
public void onEnabledForegroundNfcFServiceChanged(
        int userId, ComponentName component) {
    if (DBG) Log.d(TAG, "Enabled foreground service changed.");
    
    synchronized (mLock) {
        if (component != null) {
            // 同じサービスが既に有効な場合は何もしない
            if (mEnabledForegroundService != null
                    && mEnabledForegroundServiceUserId == userId) {
                return;
            }
            mEnabledForegroundService = component;
            mEnabledForegroundServiceUserId = userId;
        } else {
            // サービス無効化
            if (mEnabledForegroundService == null) {
                return;
            }
            mEnabledForegroundService = null;
            mEnabledForegroundServiceUserId = -1;
        }
        
        // キャッシュとルーティングを再生成
        generateForegroundT3tIdentifiersCacheLocked();
    }
}
```

### 4.2 フォアグラウンドの定義

Android の HCE-F では、「フォアグラウンド」は以下を意味します:
- アクティビティが画面に表示されている
- `CardEmulationManager.enableService()` が呼ばれている

## 5. ルーティング更新のフロー

### 5.1 完全なフロー

```
1. HCE-F アプリがフォアグラウンドに
    ↓
2. CardEmulationManager.enableService() 呼び出し
    ↓
3. RegisteredT3tIdentifiersCache.onEnabledForegroundNfcFServiceChanged()
    ↓
4. generateForegroundT3tIdentifiersCacheLocked()
    ↓
5. updateRoutingLocked()
    ↓
6. SystemCodeRoutingManager.configureRouting()
    ↓
7. NfcService.registerT3tIdentifier() (各識別子ごと)
    ↓
8. NativeNfcManager.registerT3tIdentifier()
    ↓
9. NativeNfcManager.doRegisterT3tIdentifier() [native]
    ↓
10. RoutingManager::registerT3tIdentifier() [C++]
    ↓
11. NFA_CeRegisterFelicaSystemCodeOnDH() [HAL]
    ↓
12. NFC Controller に登録完了
```

### 5.2 NFC 無効化時の処理

```java
public void onNfcDisabled() {
    synchronized (mLock) {
        mNfcEnabled = false;
        mForegroundT3tIdentifiersCache.clear();
        mEnabledForegroundService = null;
        mEnabledForegroundServiceUserId = -1;
    }
    // ルーティングテーブルをクリア
    mRoutingManager.onNfccRoutingTableCleared();
}
```

## 6. Xposed フックの戦略

### 6.1 フックポイント候補

1. **コンストラクタ**
   ```java
   XposedBridge.hookAllConstructors(
       RegisteredT3tIdentifiersCache.class, hookCallback);
   ```

2. **updateRoutingLocked**
   - 任意の T3T 識別子を追加可能
   - ルーティング更新前にリストを改変

3. **generateForegroundT3tIdentifiersCacheLocked**
   - キャッシュ生成後にエントリを追加

### 6.2 実装例 (概念)

```kotlin
// updateRoutingLocked の後にフック
XposedHelpers.findAndHookMethod(
    cacheClass,
    "updateRoutingLocked",
    Boolean::class.javaPrimitiveType,
    object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            // mForegroundT3tIdentifiersCache に強制追加
            val cache = XposedHelpers.getObjectField(
                param.thisObject, "mForegroundT3tIdentifiersCache")
            // ... 追加処理
        }
    }
)
```

## 7. セキュリティ上の注意

### 7.1 通常の保護メカニズム
- System アプリのみが `onEnabledForegroundNfcFServiceChanged()` を呼び出し可能
- 通常のアプリは NfcFServiceInfo を登録できない

### 7.2 Xposed による回避
- プロセスフックにより、これらの制限を回避可能
- 任意の T3T 識別子を NFC コントローラに登録可能

---

**このクラスは HCE-F の心臓部です。理解することで、NFC-F カードエミュレーションの完全な制御が可能になります。**
