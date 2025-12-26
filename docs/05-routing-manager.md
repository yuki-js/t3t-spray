# AOSP NFC解体新書 - RoutingManager C++ 実装詳細

## 目次
1. RoutingManager の役割
2. クラス構造とメンバー変数
3. T3T 登録処理の詳細
4. SCBR (System Code Based Routing)
5. コールバックメカニズム
6. ルーティングテーブルのコミット

## 1. RoutingManager の役割

### 1.1 概要
`RoutingManager` は C++ で実装された、NFC ルーティングテーブルを管理するシングルトンクラスです。

**場所**: `apps-nfc/nci/jni/RoutingManager.cpp` および `RoutingManager.h`

### 1.2 主要な責務

1. **T3T 識別子の登録/解除**
   - NFA (NFC Adaptation) API を呼び出し
   - ハンドル管理

2. **ルーティングテーブルの構成**
   - AID (Application ID) routing
   - Technology routing
   - Protocol routing
   - System Code routing

3. **コールバック処理**
   - NFA からのイベントを処理
   - 同期イベントの管理

## 2. クラス構造とメンバー変数

### 2.1 ヘッダーファイル (RoutingManager.h)

```cpp
class RoutingManager {
public:
    static RoutingManager& getInstance();
    
    bool initialize(nfc_jni_native_data* native);
    
    // T3T 関連メソッド
    int registerT3tIdentifier(uint8_t* t3tId, uint8_t t3tIdLen);
    void deregisterT3tIdentifier(int handle);
    
    bool commitRouting();
    
    // その他のルーティングメソッド
    // ...

private:
    RoutingManager();
    ~RoutingManager();
    
    // メンバー変数
    nfc_jni_native_data* mNativeData;
    
    // T3T 関連
    tNFA_HANDLE mNfcFOnDhHandle;
    
    // ルーティング設定
    int mDefaultOffHostRoute;
    int mDefaultFelicaRoute;
    int mDefaultEe;
    int mDefaultSysCodeRoute;
    uint16_t mDefaultSysCode;
    
    // SCBR サポート
    bool mIsScbrSupported;
    
    // 同期イベント
    SyncEvent mRoutingEvent;
    SyncEvent mEeRegisterEvent;
    SyncEvent mEeInfoEvent;
    
    // EE (Execution Environment) 情報
    tNFA_EE_INFO mEeInfo[MAX_NUM_EE];
    bool mReceivedEeInfo;
    
    // コールバック
    static void nfaEeCallback(tNFA_EE_EVT event, tNFA_EE_CBACK_DATA* eventData);
    static void nfcFCeCallback(uint8_t event, tNFA_CONN_EVT_DATA* eventData);
};
```

### 2.2 シングルトンパターン

```cpp
RoutingManager& RoutingManager::getInstance() {
    static RoutingManager manager;
    return manager;
}
```

スレッドセーフなシングルトン (C++11 以降)。

### 2.3 重要な定数

```cpp
static const int MAX_NUM_EE = 5;
static const uint8_t SYS_CODE_PWR_STATE_HOST = 0x01;
static const uint16_t DEFAULT_SYS_CODE = 0xFEFE;
static const uint8_t AID_ROUTE_QUAL_PREFIX = 0x10;
```

## 3. T3T 登録処理の詳細

### 3.1 registerT3tIdentifier の完全実装

```cpp
int RoutingManager::registerT3tIdentifier(uint8_t* t3tId, uint8_t t3tIdLen) {
    static const char fn[] = "RoutingManager::registerT3tIdentifier";

    LOG(DEBUG) << fn << ": Start to register NFC-F system on DH";

    // ステップ 1: 長さ検証
    // 期待される長さ: 2 (System Code) + 8 (NFCID2) + 8 (PMM) = 18 bytes
    if (t3tIdLen != (2 + NCI_RF_F_UID_LEN + NCI_T3T_PMM_LEN)) {
        LOG(ERROR) << fn << ": Invalid length of T3T Identifier";
        return NFA_HANDLE_INVALID;
    }

    // ステップ 2: ハンドルの初期化
    mNfcFOnDhHandle = NFA_HANDLE_INVALID;

    // ステップ 3: バッファからデータを抽出
    uint16_t systemCode;
    uint8_t nfcid2[NCI_RF_F_UID_LEN];    // 8 bytes
    uint8_t t3tPmm[NCI_T3T_PMM_LEN];     // 8 bytes

    // System Code (Big Endian)
    systemCode = (((int)t3tId[0] << 8) | ((int)t3tId[1] << 0));
    
    // NFCID2 (IDm)
    memcpy(nfcid2, t3tId + 2, NCI_RF_F_UID_LEN);
    
    // PMM
    memcpy(t3tPmm, t3tId + 10, NCI_T3T_PMM_LEN);

    // ステップ 4: DH (Device Host) に Felica System Code を登録
    {
        SyncEventGuard guard(mRoutingEvent);
        tNFA_STATUS nfaStat = NFA_CeRegisterFelicaSystemCodeOnDH(
            systemCode, nfcid2, t3tPmm, nfcFCeCallback);
        
        if (nfaStat == NFA_STATUS_OK) {
            mRoutingEvent.wait();  // コールバック待ち
        } else {
            LOG(ERROR) << fn << ": Fail to register NFC-F system on DH";
            return NFA_HANDLE_INVALID;
        }
    }

    LOG(DEBUG) << fn << ": Succeed to register NFC-F system on DH";

    // ステップ 5: SCBR (System Code Based Routing) のサポートチェック
    if (mIsScbrSupported) {
        SyncEventGuard guard(mRoutingEvent);
        tNFA_STATUS nfaStat = NFA_EeAddSystemCodeRouting(
            systemCode, NCI_DH_ID, SYS_CODE_PWR_STATE_HOST);
        
        if (nfaStat == NFA_STATUS_OK) {
            mRoutingEvent.wait();
        }
        
        if ((nfaStat != NFA_STATUS_OK) || 
            (mCbEventData.status != NFA_STATUS_OK)) {
            LOG(ERROR) << StringPrintf("%s: Fail to register system code on DH", fn);
            return NFA_HANDLE_INVALID;
        }
    }

    // ステップ 6: ハンドルを返す
    return mNfcFOnDhHandle;
}
```

### 3.2 データ抽出の詳細

```
T3T Identifier Buffer Layout:
┌────────┬────────┬─────────────────────┬─────────────────────┐
│ Byte 0 │ Byte 1 │  Bytes 2-9          │  Bytes 10-17        │
│  SC[0] │ SC[1]  │  NFCID2 (8 bytes)   │  PMM (8 bytes)      │
└────────┴────────┴─────────────────────┴─────────────────────┘

例: System Code = 0xFE00, NFCID2 = 0x0102030405060708, PMM = all zeros
Buffer: FE 00 01 02 03 04 05 06 07 08 00 00 00 00 00 00 00 00
```

### 3.3 deregisterT3tIdentifier の実装

```cpp
void RoutingManager::deregisterT3tIdentifier(int handle) {
    static const char fn[] = "RoutingManager::deregisterT3tIdentifier";

    LOG(DEBUG) << StringPrintf("%s: Start to deregister NFC-F system on DH", fn);
    
    // ステップ 1: DH から Felica System Code を解除
    {
        SyncEventGuard guard(mRoutingEvent);
        tNFA_STATUS nfaStat = NFA_CeDeregisterFelicaSystemCodeOnDH(handle);
        
        if (nfaStat == NFA_STATUS_OK) {
            mRoutingEvent.wait();
            LOG(DEBUG) << StringPrintf(
                "%s: Succeeded in deregistering NFC-F system on DH", fn);
        } else {
            LOG(ERROR) << StringPrintf(
                "%s: Fail to deregister NFC-F system on DH", fn);
        }
    }
    
    // ステップ 2: SCBR ルートの削除
    if (mIsScbrSupported) {
        SyncEventGuard guard(mRoutingEvent);
        tNFA_STATUS nfaStat = NFA_EeRemoveSystemCodeRouting(
            NCI_DH_ID, /* systemCode - stored internally */);
        
        if (nfaStat == NFA_STATUS_OK) {
            mRoutingEvent.wait();
        }
    }
}
```

## 4. SCBR (System Code Based Routing)

### 4.1 SCBR とは

SCBR は、System Code に基づいてルーティング先を決定する機能です。

**従来**: Technology ベースのルーティング (NFC-F 全体を DH または SE にルーティング)
**SCBR**: System Code ごとに異なるルーティング先を設定可能

### 4.2 SCBR のサポートチェック

```cpp
// 初期化時に SCBR サポートを確認
void RoutingManager::initialize(nfc_jni_native_data* native) {
    // ...
    
    // EE (Execution Environment) 情報を取得
    // mIsScbrSupported は EE の能力に基づいて設定
    
    if (mIsScbrSupported) {
        LOG(DEBUG) << "SCBR is supported";
    } else {
        LOG(DEBUG) << "SCBR is not supported, using legacy routing";
    }
}
```

### 4.3 System Code Routing の追加

```cpp
// SCBR を使用して System Code をルーティング
tNFA_STATUS nfaStat = NFA_EeAddSystemCodeRouting(
    systemCode,              // 例: 0xFE00
    NCI_DH_ID,               // ルート先: Device Host (0x00)
    SYS_CODE_PWR_STATE_HOST  // 電源状態: 0x01 (ON のみ)
);
```

**電源状態の値**:
- `0x01`: Screen ON のみ
- `0x02`: Screen OFF UNLOCKED
- `0x04`: Screen OFF LOCKED
- `0x08`: Screen ON LOCKED
- `0x10`: Screen OFF UNLOCKED (バッテリー駆動)

## 5. コールバックメカニズム

### 5.1 NFC-F Card Emulation コールバック

```cpp
static void nfcFCeCallback(uint8_t event, tNFA_CONN_EVT_DATA* eventData) {
    static const char fn[] = "RoutingManager::nfcFCeCallback";
    RoutingManager& routingManager = RoutingManager::getInstance();

    LOG(DEBUG) << StringPrintf("%s: event=0x%02X", fn, event);

    switch (event) {
        case NFA_CE_REGISTERED_EVT:
            LOG(DEBUG) << fn << ": NFA_CE_REGISTERED_EVT";
            
            if (eventData->ce_registered.status == NFA_STATUS_OK) {
                // ハンドルを保存
                routingManager.mNfcFOnDhHandle = 
                    eventData->ce_registered.handle;
            }
            
            // 同期イベントを通知
            {
                SyncEventGuard guard(routingManager.mRoutingEvent);
                routingManager.mRoutingEvent.notifyOne();
            }
            break;

        case NFA_CE_DEREGISTERED_EVT:
            LOG(DEBUG) << fn << ": NFA_CE_DEREGISTERED_EVT";
            
            routingManager.mNfcFOnDhHandle = NFA_HANDLE_INVALID;
            
            {
                SyncEventGuard guard(routingManager.mRoutingEvent);
                routingManager.mRoutingEvent.notifyOne();
            }
            break;

        case NFA_CE_ACTIVATED_EVT:
            LOG(DEBUG) << fn << ": NFA_CE_ACTIVATED_EVT";
            // Card Emulation がアクティブ化
            break;

        case NFA_CE_DEACTIVATED_EVT:
            LOG(DEBUG) << fn << ": NFA_CE_DEACTIVATED_EVT";
            // Card Emulation が非アクティブ化
            break;

        case NFA_CE_DATA_EVT:
            LOG(DEBUG) << fn << ": NFA_CE_DATA_EVT";
            // データ受信 (実際の通信はここで処理)
            break;

        default:
            LOG(ERROR) << StringPrintf("%s: unknown event=0x%02X", fn, event);
            break;
    }
}
```

### 5.2 SyncEvent の仕組み

```cpp
// SyncEvent.h より
class SyncEvent {
public:
    void wait() {
        // 条件変数で待機
        std::unique_lock<std::mutex> lock(mMutex);
        mCondVar.wait(lock);
    }
    
    void notifyOne() {
        // 待機中のスレッドを起こす
        std::lock_guard<std::mutex> lock(mMutex);
        mCondVar.notify_one();
    }
    
private:
    std::mutex mMutex;
    std::condition_variable mCondVar;
};

// 使用例
{
    SyncEventGuard guard(mRoutingEvent);  // RAII でロック
    tNFA_STATUS nfaStat = NFA_CeRegisterFelicaSystemCodeOnDH(...);
    if (nfaStat == NFA_STATUS_OK) {
        mRoutingEvent.wait();  // コールバックを待つ
    }
}  // guard がスコープを抜けると自動的にロック解除
```

## 6. ルーティングテーブルのコミット

### 6.1 commitRouting の実装

```cpp
bool RoutingManager::commitRouting() {
    static const char fn[] = "RoutingManager::commitRouting";
    
    LOG(DEBUG) << fn;
    
    {
        SyncEventGuard guard(mRoutingEvent);
        
        // ルーティングテーブルを更新
        tNFA_STATUS nfaStat = NFA_EeUpdateNow();
        
        if (nfaStat != NFA_STATUS_OK) {
            LOG(ERROR) << StringPrintf("%s: fail; error=0x%X", fn, nfaStat);
            return false;
        }
        
        // 更新完了を待つ
        mRoutingEvent.wait();
    }
    
    LOG(DEBUG) << fn << ": routing committed successfully";
    return true;
}
```

### 6.2 NFA_EeUpdateNow の役割

`NFA_EeUpdateNow()` は:
1. すべての pending なルーティング変更を収集
2. NCI コマンドを構築
3. NFC コントローラに送信
4. コントローラがルーティングテーブルを更新
5. コールバックで完了を通知

## 7. セキュリティ上の考慮点

### 7.1 バッファ境界チェック

```cpp
// 現在の実装
memcpy(nfcid2, t3tId + 2, NCI_RF_F_UID_LEN);

// 改善案: 境界チェックを追加
if (t3tIdLen < 2 + NCI_RF_F_UID_LEN) {
    LOG(ERROR) << "Buffer too small for NFCID2";
    return NFA_HANDLE_INVALID;
}
memcpy(nfcid2, t3tId + 2, NCI_RF_F_UID_LEN);
```

**潜在的な脆弱性**: バッファオーバーリード
**CVSS スコア**: 3.5 (Low)
- Xposed フックから任意サイズのバッファを送信可能
- メモリリークの可能性

### 7.2 整数オーバーフロー

```cpp
// System Code の解析
uint16_t systemCode = (((int)t3tId[0] << 8) | ((int)t3tId[1] << 0));

// 問題: t3tId が signed char の場合、負の値になる可能性
// 改善:
uint16_t systemCode = (((uint16_t)(uint8_t)t3tId[0] << 8) | 
                       ((uint16_t)(uint8_t)t3tId[1]));
```

---

**RoutingManager は NFC コントローラへの最終的なゲートウェイです。この実装を理解することで、NFC-F の完全な制御が可能になります。**
