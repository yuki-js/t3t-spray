# AOSP NFC解体新書 - NFCコントローラの制御メカニズム

## 目次
1. NFC コントローラとは
2. NCI (NFC Controller Interface) プロトコル
3. T3T 識別子の登録フロー
4. NFC コントローラのメモリ構造
5. ルーティングテーブル

## 1. NFC コントローラとは

### 1.1 物理的な構成
NFC コントローラは独立したハードウェアチップで、以下の機能を持ちます:

- **RF (Radio Frequency) 通信**: 13.56MHz での通信
- **プロトコル処理**: ISO/IEC 18092 (NFC-F), ISO14443 (A/B) 等
- **カードエミュレーション**: HCE の一部処理
- **内蔵メモリ**: ルーティングテーブル、識別子キャッシュ等

### 1.2 主要なベンダー
- **NXP Semiconductors**: PN544, PN547, PN548, PN553, PN557, PN80T 等
- **Broadcom**: BCM2079x, BCM43xx 系
- **Sony**: RC-S956, RC-S966 等
- **STMicroelectronics**: ST21NFC 系

## 2. NCI (NFC Controller Interface) プロトコル

### 2.1 NCI とは
NCI は、CPU (Application Processor) と NFC コントローラ間の標準通信プロトコルです。

```
┌──────────────┐       NCI Protocol       ┌──────────────┐
│              │◄─────────────────────────►│              │
│   CPU (AP)   │  (I2C, SPI, UART etc.)   │  NFC Chip    │
│              │                           │              │
└──────────────┘                           └──────────────┘
```

### 2.2 NCI メッセージフォーマット

```
┌────┬────┬────┬────────────────┐
│ MT │GID │OID │   Payload      │
└────┴────┴────┴────────────────┘
  1B   1B   1B    Variable
```

- **MT (Message Type)**: Data, Command, Response, Notification
- **GID (Group ID)**: コマンドグループ (Core, RF, NFCEE 等)
- **OID (Opcode ID)**: 具体的なコマンド

### 2.3 T3T 関連の NCI コマンド

#### NFA_CeRegisterFelicaSystemCodeOnDH
このコマンドは、System Code を DH (Device Host = CPU) にルーティングするために使用されます。

内部的には以下の NCI コマンドを発行:
- `NCI_MSG_RF_SET_LISTEN_MODE_ROUTING`
- Listen Mode Routing Configuration

## 3. T3T 識別子の登録フロー

### 3.1 完全な呼び出しチェーン

```java
// Java Layer (RegisteredT3tIdentifiersCache.java)
SystemCodeRoutingManager.configureRouting(List<T3tIdentifier>)
    ↓
NfcService.registerT3tIdentifier(systemCode, nfcid2, t3tPmm)
    ↓
DeviceHost.registerT3tIdentifier(byte[] t3tIdentifier)
    ↓
// Java Layer (NativeNfcManager.java)
NativeNfcManager.registerT3tIdentifier(byte[] t3tIdentifier)
    ↓
NativeNfcManager.doRegisterT3tIdentifier(byte[] t3tIdentifier)
```

```cpp
// C++ JNI Layer (NativeNfcManager.cpp)
static jint nfcManager_doRegisterT3tIdentifier(JNIEnv* e, jobject, jbyteArray t3tIdentifier)
    ↓
// C++ (RoutingManager.cpp)
int RoutingManager::registerT3tIdentifier(uint8_t* t3tId, uint8_t t3tIdLen)
    ↓
// NFC HAL
tNFA_STATUS NFA_CeRegisterFelicaSystemCodeOnDH(
    uint16_t systemCode,
    uint8_t nfcid2[NCI_RF_F_UID_LEN],
    uint8_t t3tPmm[NCI_T3T_PMM_LEN],
    tNFA_CONN_CBACK* p_conn_cback
)
    ↓
NCI コマンド送信
    ↓
NFC Controller に登録
```

### 3.2 T3T 識別子のフォーマット

T3T 識別子は以下の構造を持ちます:

```
┌───────────────┬──────────────────┬──────────────────┐
│ System Code   │     NFCID2       │      PMM         │
│   (2 bytes)   │   (8 bytes)      │   (8 bytes)      │
└───────────────┴──────────────────┴──────────────────┘
     Total: 18 bytes
```

- **System Code**: FeliCa のサービスコード (例: 0xFE00)
- **NFCID2**: IDm に相当 (8バイト)
- **PMM**: Manufacture Parameter (8バイト)

### 3.3 RoutingManager::registerT3tIdentifier の実装詳細

```cpp
int RoutingManager::registerT3tIdentifier(uint8_t* t3tId, uint8_t t3tIdLen) {
  // 長さチェック: 2 (SC) + 8 (NFCID2) + 8 (PMM) = 18 bytes
  if (t3tIdLen != (2 + NCI_RF_F_UID_LEN + NCI_T3T_PMM_LEN)) {
    LOG(ERROR) << "Invalid length of T3T Identifier";
    return NFA_HANDLE_INVALID;
  }

  // バイト配列から各フィールドを抽出
  uint16_t systemCode = (((int)t3tId[0] << 8) | ((int)t3tId[1] << 0));
  uint8_t nfcid2[NCI_RF_F_UID_LEN];
  uint8_t t3tPmm[NCI_T3T_PMM_LEN];
  
  memcpy(nfcid2, t3tId + 2, NCI_RF_F_UID_LEN);
  memcpy(t3tPmm, t3tId + 10, NCI_T3T_PMM_LEN);

  // NFC HAL に登録
  tNFA_STATUS nfaStat = NFA_CeRegisterFelicaSystemCodeOnDH(
      systemCode, nfcid2, t3tPmm, nfcFCeCallback);
  
  // SCBR (System Code Based Routing) のサポートチェック
  if (mIsScbrSupported) {
    // System Code Routing Entry を追加
    nfaStat = NFA_EeAddSystemCodeRouting(systemCode, NCI_DH_ID,
                                         SYS_CODE_PWR_STATE_HOST);
  }
  
  return mNfcFOnDhHandle;
}
```

## 4. NFC コントローラのメモリ構造

### 4.1 Listen Mode Routing Table (LMRT)

NFC コントローラ内部には Listen Mode Routing Table があり、以下の情報を保持:

```
┌──────────────┬─────────────┬──────────────┬──────────┐
│   Protocol   │   Tech      │   Route      │  Power   │
├──────────────┼─────────────┼──────────────┼──────────┤
│   ISO-DEP    │   Type A    │   DH (CPU)   │  ON      │
│   NFC-F      │   Type F    │   DH (CPU)   │  ON      │
│   System Code│   0xFE00    │   DH (CPU)   │  ON      │
│   System Code│   0x0003    │   UICC       │  ON/OFF  │
└──────────────┴─────────────┴──────────────┴──────────┘
```

### 4.2 T3T Identifier Cache

コントローラは複数の T3T 識別子をキャッシュ可能:

```
Max T3T Identifiers: デバイス依存 (通常 4-16 個)
```

`getLfT3tMax()` メソッドで最大数を取得可能。

## 5. ルーティングテーブル

### 5.1 ルーティングの仕組み

SENSF_REQ (Polling) を受信した場合:

1. NFC コントローラが System Code をチェック
2. ルーティングテーブルに一致するエントリを検索
3. 一致した場合:
   - DH (CPU) ルート → CPU に通知 (HCE-F アプリが処理)
   - UICC/eSE ルート → SE に転送
4. 登録された NFCID2 を含む SENSF_RES を返送

### 5.2 優先順位

複数の System Code が登録されている場合:

1. **完全一致が最優先**
2. **ワイルドカード (0xFFFF) の場合**:
   - 小さい System Code 番号が優先
   - eSE の System Code が若い場合、eSE が優先される

### 5.3 コミットの重要性

ルーティングテーブルの変更は `commitRouting()` を呼ぶまで有効化されません:

```cpp
RoutingManager::getInstance().commitRouting();
```

これにより:
- NCI コマンド `NFA_EeUpdateNow()` が発行される
- コントローラのルーティングテーブルが更新される

## 6. セキュリティ上の考慮点

### 6.1 直接制御の制限
通常のアプリは NFC コントローラを直接制御できません:
- NCI コマンドは特権が必要
- NfcService がゲートキーパーとして機能

### 6.2 Xposed フックの可能性
LSPosed/Xposed を使用すると:
- `NativeNfcManager.doRegisterT3tIdentifier()` をフック可能
- `RoutingManager::registerT3tIdentifier()` に任意のデータを送信可能
- 結果として、NFC コントローラに任意の T3T 識別子を登録可能

---

**重要**: この知識は、NFC コントローラがどのように制御されているかを理解するためのものです。
