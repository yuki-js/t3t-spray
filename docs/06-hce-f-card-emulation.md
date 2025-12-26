# AOSP NFC解体新書 - HCE-F (Type 3) カードエミュレーション

## 目次
1. HCE-F の概要
2. HCE-A/B との違い
3. SENSF_REQ/RES プロトコル
4. アプリケーション実装
5. NFCコントローラでの処理
6. ワイルドカード (FFFF) の挙動

## 1. HCE-F の概要

### 1.1 HCE-F とは

HCE-F (Host Card Emulation - FeliCa) は、Android デバイスを FeliCa カードとしてエミュレートする機能です。

**規格**: 
- ISO/IEC 18092 (NFCIP-1)
- JIS X 6319-4 (FeliCa)

**主な用途**:
- 交通系 IC カード (Suica, PASMO 等)
- 電子マネー (Edy 等)
- 企業 ID カード

### 1.2 Android API

```java
// HCE-F サービスの宣言
public class MyHceFService extends HostNfcFService {
    
    @Override
    public byte[] processNfcFPacket(byte[] commandPacket, Bundle extras) {
        // コマンドを処理して応答を返す
        return responsePacket;
    }
    
    @Override
    public void onDeactivated(int reason) {
        // セッション終了時の処理
    }
}
```

```xml
<!-- AndroidManifest.xml -->
<service
    android:name=".MyHceFService"
    android:exported="true"
    android:permission="android.permission.BIND_NFC_SERVICE">
    <intent-filter>
        <action android:name="android.nfc.cardemulation.action.HOST_NFCF_SERVICE"/>
    </intent-filter>
    
    <meta-data
        android:name="android.nfc.cardemulation.host_nfcf_service"
        android:resource="@xml/nfcf_service"/>
</service>
```

```xml
<!-- res/xml/nfcf_service.xml -->
<host-nfcf-service xmlns:android="http://schemas.android.com/apk/res/android"
    android:description="@string/service_description">
    <system-code-filter android:name="4000"/>
    <system-code-filter android:name="FE00"/>
    <nfcid2-filter android:name="02FE000000000000"/>
</host-nfcf-service>
```

## 2. HCE-A/B との違い

### 2.1 処理レベルの違い

| 特性 | HCE-A/B (ISO-DEP) | HCE-F (NFC-F) |
|------|-------------------|---------------|
| Polling 応答 | CPU が処理 | **NFC コントローラが処理** |
| SELECT | CPU が処理 | N/A (FeliCa には SELECT なし) |
| APDU | CPU が処理 | CPU が処理 |
| 事前登録 | 不要 (AID で動的選択) | **必須 (SC + IDm)** |
| フォアグラウンド要件 | 緩い | **厳しい** |

### 2.2 シーケンスの違い

#### HCE-A/B の場合:
```
Reader → REQA/REQB (Polling)
            ↓ [NFC Controller]
Reader ← ATQA/ATQB
Reader → ANTICOLL
            ↓ [NFC Controller]
Reader ← UID
Reader → SELECT AID
            ↓ [CPU / App]
Reader ← SELECT Response
Reader → APDU
            ↓ [CPU / App]
Reader ← APDU Response
```

#### HCE-F の場合:
```
Reader → SENSF_REQ (Polling) SC=FE00
            ↓ [NFC Controller ONLY!]
Reader ← SENSF_RES (IDm=0x02FE000000000000)
Reader → Command (Packet with IDm)
            ↓ [CPU / App via processNfcFPacket]
Reader ← Response Packet
```

### 2.3 重要な違い: SENSF_REQ は CPU に届かない

```
┌──────────────┐
│   Reader     │
└──────┬───────┘
       │ SENSF_REQ (SC=FE00, IDm=FFFF...)
       ▼
┌──────────────┐
│   NFC Chip   │◄──── ここで応答が完結！
└──────┬───────┘
       │ SENSF_RES (IDm=0x02FE...)
       ▼
┌──────────────┐
│   Reader     │
└──────┬───────┘
       │ Command (IDm=0x02FE...)
       ▼
┌──────────────┐
│   NFC Chip   │
└──────┬───────┘
       │ (CPU に転送)
       ▼
┌──────────────┐
│  HCE-F App   │◄──── ここで初めて CPU が関与
└──────────────┘
```

## 3. SENSF_REQ/RES プロトコル

### 3.1 SENSF_REQ (Polling Request) フォーマット

```
┌────┬────┬─────────┬─────────┬─────────┐
│ Len│ Cmd│  SC     │  RC     │  TSN    │
├────┼────┼─────────┼─────────┼─────────┤
│ 1B │ 1B │ 2B      │ 1B      │ 1B      │
│ 06 │ 00 │ FFFF or │ 01      │ 00-0F   │
│    │    │ specific│         │         │
└────┴────┴─────────┴─────────┴─────────┘

Len: Length (固定 0x06)
Cmd: Command (0x00 = Polling)
SC:  System Code (0xFFFF = Wildcard, または特定の SC)
RC:  Request Code (通常 0x01)
TSN: Time Slot Number (0x00-0x0F)
```

### 3.2 SENSF_RES (Polling Response) フォーマット

```
┌────┬────┬─────────────────┬────────────────┐
│ Len│ Res│     IDm         │      PMM       │
├────┼────┼─────────────────┼────────────────┤
│ 1B │ 1B │  8B             │  8B            │
│ 12 │ 01 │ 02FE000000000000│ xxxxxxxxxxxxxxxx│
└────┴────┴─────────────────┴────────────────┘

Len: Length (固定 0x12 = 18 bytes)
Res: Response (0x01)
IDm: ID for Manufacture (製造ID、実質的にカードの一意識別子)
PMM: PMM (Manufacture Parameter)
```

### 3.3 System Code の例

| System Code | 用途 |
|-------------|------|
| 0x0003 | Common (共通領域) |
| 0xFE00 | FeliCa Lite |
| 0xFE0F | FeliCa Plug |
| 0x8008 | Suica エリア |
| 0x957A | Edy |
| 0xFFFF | **ワイルドカード (全て)** |

## 4. アプリケーション実装

### 4.1 NfcFServiceInfo の構造

```java
public class NfcFServiceInfo {
    private String mSystemCode;   // 例: "FE00"
    private String mNfcid2;        // 例: "02FE000000000000"
    private String mT3tPmm;        // 例: "0000000000000000"
    private ComponentName mComponent;
    
    public String getSystemCode() { return mSystemCode; }
    public String getNfcid2() { return mNfcid2; }
    public String getT3tPmm() { return mT3tPmm; }
}
```

### 4.2 登録フロー

```
1. アプリ起動、フォアグラウンドに
    ↓
2. CardEmulationManager.getDefaultServiceForCategory(CATEGORY_OTHER)
    ↓
3. NfcFServiceInfo を取得 (Manifest から)
    ↓
4. RegisteredT3tIdentifiersCache に登録
    ↓
5. SystemCodeRoutingManager.configureRouting()
    ↓
6. NFC Controller に System Code + IDm を登録
    ↓
7. SENSF_REQ 受信時、Controller が自動応答可能に
```

## 5. NFCコントローラでの処理

### 5.1 コントローラ内部のテーブル

NFC コントローラは内部に T3T Identifier Table を持ちます:

```
┌────┬─────────┬──────────────────┬──────────────────┬─────┐
│ ID │   SC    │       IDm        │       PMM        │Route│
├────┼─────────┼──────────────────┼──────────────────┼─────┤
│ 0  │  0x0003 │ 0x01234567...    │ 0x00000000...    │ DH  │
│ 1  │  0xFE00 │ 0x02FE000000...  │ 0x00000000...    │ DH  │
│ 2  │  0xFE0F │ 0x02FE0F0000...  │ 0x00000000...    │ DH  │
│ 3  │  0x8008 │ 0x011234...      │ 0xFFFFFFFF...    │ UICC│
└────┴─────────┴──────────────────┴──────────────────┴─────┘

最大エントリ数: デバイス依存 (4-16, getLfT3tMax() で取得)
```

### 5.2 SENSF_REQ 受信時の処理

```
1. Reader から SENSF_REQ (SC=XXXX) を受信
    ↓
2. NFC Controller がテーブルを検索
    ↓
3. SC が一致するエントリを発見
    ↓
4. 対応する IDm と PMM で SENSF_RES を構築
    ↓
5. Reader に SENSF_RES を送信
    ↓
6. CPU には通知しない (Controller 単独で完結)
```

### 5.3 その後のコマンド処理

```
1. Reader から Command (IDm 含む) を受信
    ↓
2. NFC Controller が IDm をチェック
    ↓
3. Route フィールドを確認
    ↓
4. Route = DH の場合:
   - CPU に転送
   - HCE-F Service の processNfcFPacket() を呼び出し
    ↓
5. Route = UICC/eSE の場合:
   - Secure Element に転送
```

## 6. ワイルドカード (FFFF) の挙動

### 6.1 ワイルドカードとは

SC = 0xFFFF で Polling すると、「どの System Code でも良い」という意味になります。

```
SENSF_REQ: SC=FFFF, RC=01, TSN=00
           ↓
"すべての FeliCa カードよ、応答せよ"
```

### 6.2 優先順位のルール

複数の System Code が登録されている場合:

1. **小さい System Code が優先**
2. **SE (UICC/eSE) が DH より優先** (実装依存)

例:
```
テーブル:
- SC=0x0003, Route=DH
- SC=0x8008, Route=UICC
- SC=0xFE00, Route=DH

SENSF_REQ (SC=FFFF) → 0x0003 の IDm で応答
```

### 6.3 問題: eSE の優先

eSE が若い System Code (例: 0x0003) を登録していると:

```
- SC=0x0003, Route=eSE (クレジットカード等)
- SC=0xFE00, Route=DH  (HCE-F アプリ)

SENSF_REQ (SC=FFFF) → eSE の IDm で応答

結果: HCE-F アプリは応答できない！
```

### 6.4 対策: System Code のスプレー

若い System Code を大量に登録することで、ワイルドカードでも応答可能に:

```kotlin
val wellKnownSystemCodes = listOf(
    "0003",  // Common
    "FE00",  // FeliCa Lite
    "FE0F",  // FeliCa Plug
    // ... さらに追加
)

wellKnownSystemCodes.forEach { sc ->
    registerT3tIdentifier(sc, customIdm, pmm)
}
```

これにより:
- eSE より若い SC で登録
- ワイルドカード Polling に応答可能
- Reader に任意の IDm を返せる

## 7. セキュリティ上の考慮

### 7.1 通常の制限

通常のアプリは:
- Manifest で宣言した SC のみ登録可能
- フォアグラウンド時のみ有効
- システムが管理する IDm のみ使用可能

### 7.2 Xposed フックによる回避

LSPosed/Xposed を使用すると:
- 任意の SC を登録可能
- 任意の IDm を設定可能
- バックグラウンドでも動作可能 (実装次第)

### 7.3 悪用の可能性

- 他のカードの IDm を偽装
- ワイルドカード Polling の乗っ取り
- DoS 攻撃 (すべてのスロットを占有)

**重要**: この知識は教育目的です。悪用は法律で禁止されています。

---

**HCE-F は NFC コントローラとの密接な連携が必要な、複雑だが強力な機能です。**
