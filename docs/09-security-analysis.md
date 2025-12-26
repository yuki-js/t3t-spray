# AOSP NFC解体新書 - セキュリティ分析と脆弱性評価

## 目次
1. 脅威モデル
2. 発見された脆弱性
3. CVSS スコアリング
4. 緩和策
5. Xposed モジュールのセキュリティ
6. 責任ある開示

## 1. 脅威モデル

### 1.1 攻撃シナリオ

#### シナリオ 1: System Code スプレー攻撃
**攻撃者**: ルート化デバイスの所有者 (Xposed 使用)
**目的**: ワイルドカード Polling の乗っ取り

```
1. Well-known SC (0x0003, 0xFE00 等) を大量登録
2. ワイルドカード (0xFFFF) Polling に応答
3. Reader に任意の IDm を返す
4. 他のカードやサービスを妨害
```

**影響**:
- 正規の HCE-F サービスが動作不能
- eSE の応答を上書き
- フィッシング攻撃の可能性

#### シナリオ 2: IDm 偽装
**攻撃者**: ルート化デバイスの所有者
**目的**: 他のカードの IDm を偽装

```
1. 既知の IDm (例: 交通系 IC カード) を取得
2. Xposed で同じ IDm を登録
3. Reader が偽の IDm を読み取る
```

**影響**:
- ID 詐称
- アクセス制御の回避 (限定的)

#### シナリオ 3: DoS (Denial of Service) 攻撃
**攻撃者**: ルート化デバイスの所有者
**目的**: NFC 機能の妨害

```
1. T3T Identifier スロットを全て占有
2. 正規のサービスが登録できなくなる
```

**影響**:
- NFC 機能の停止
- システムの不安定化

### 1.2 攻撃面 (Attack Surface)

```
┌─────────────────────────────────────────────┐
│  Attack Surface                             │
├─────────────────────────────────────────────┤
│ 1. JNI 境界                                 │
│    - バッファオーバーフロー                 │
│    - 型変換エラー                           │
│                                             │
│ 2. ネイティブコード                         │
│    - メモリ管理エラー                       │
│    - レースコンディション                   │
│                                             │
│ 3. Xposed フック                            │
│    - 任意のパラメータ注入                   │
│    - アクセス制御の回避                     │
│                                             │
│ 4. NFC コントローラ                         │
│    - ルーティングテーブル操作               │
│    - ファームウェアの脆弱性 (対象外)        │
└─────────────────────────────────────────────┘
```

## 2. 発見された脆弱性

### 2.1 CVE候補-1: バッファオーバーリード (Low Severity)

**場所**: `RoutingManager::registerT3tIdentifier()`

```cpp
int RoutingManager::registerT3tIdentifier(uint8_t* t3tId, uint8_t t3tIdLen) {
    // 長さチェックは存在するが...
    if (t3tIdLen != (2 + NCI_RF_F_UID_LEN + NCI_T3T_PMM_LEN)) {
        return NFA_HANDLE_INVALID;
    }
    
    // 問題: t3tId が NULL の可能性をチェックしていない
    uint16_t systemCode = (((int)t3tId[0] << 8) | ((int)t3tId[1]));
    
    // 問題: バッファ境界の再チェックなし
    memcpy(nfcid2, t3tId + 2, NCI_RF_F_UID_LEN);  
    memcpy(t3tPmm, t3tId + 10, NCI_T3T_PMM_LEN);
}
```

**脆弱性の詳細**:
1. **NULL ポインタデリファレンス**: `t3tId == NULL` のチェックなし
2. **TOCTOU (Time-of-Check-Time-of-Use)**: 長さチェック後、バッファが変更される可能性 (現実的には低い)

**エクスプロイト**:
```kotlin
// Xposed フックから NULL を送信
XposedHelpers.callMethod(
    nativeManager,
    "doRegisterT3tIdentifier",
    null as ByteArray?  // Crash!
)
```

**影響**:
- アプリケーションクラッシュ (com.android.nfc プロセス)
- NFC 機能の一時的停止
- DoS

**CVSS 3.1 スコア**: 3.3 (Low)
```
CVSS:3.1/AV:L/AC:L/PR:H/UI:N/S:U/C:N/I:N/A:L
- AV:L (Local): ローカルアクセスが必要
- AC:L (Low): 特別な条件不要
- PR:H (High): ルート権限が必要
- UI:N (None): ユーザー操作不要
- S:U (Unchanged): スコープ変化なし
- C:N (None): 機密性への影響なし
- I:N (None): 完全性への影響なし
- A:L (Low): 可用性への影響は低い
```

**修正案**:
```cpp
int RoutingManager::registerT3tIdentifier(uint8_t* t3tId, uint8_t t3tIdLen) {
    // NULL チェック追加
    if (t3tId == nullptr) {
        LOG(ERROR) << "NULL pointer";
        return NFA_HANDLE_INVALID;
    }
    
    // 長さチェック
    if (t3tIdLen != (2 + NCI_RF_F_UID_LEN + NCI_T3T_PMM_LEN)) {
        LOG(ERROR) << "Invalid length: " << t3tIdLen;
        return NFA_HANDLE_INVALID;
    }
    
    // ... 以下同じ
}
```

### 2.2 CVE候補-2: 整数オーバーフロー (Low Severity)

**場所**: `RoutingManager::registerT3tIdentifier()`

```cpp
// 問題のあるコード
uint16_t systemCode = (((int)t3tId[0] << 8) | ((int)t3tId[1] << 0));

// t3tId が signed char* の場合
// t3tId[0] = 0xFF → (int)0xFF = -1 (符号拡張)
//           → -1 << 8 = -256
//           → 不正な systemCode
```

**エクスプロイト**:
```kotlin
val t3tId = byteArrayOf(
    0xFF.toByte(), 0xFF.toByte(),  // SC = 0xFFFF のつもりが...
    // ... NFCID2, PMM
)
// 実際には負の値として解釈される可能性
```

**影響**:
- 不正な System Code の登録
- ルーティングテーブルの破損 (可能性)
- 予期しない動作

**CVSS 3.1 スコア**: 2.0 (Low)
```
CVSS:3.1/AV:L/AC:H/PR:H/UI:N/S:U/C:N/I:L/A:N
- AC:H (High): signed/unsigned の実装に依存
- I:L (Low): 完全性への影響は低い
```

**修正案**:
```cpp
// uint8_t へのキャスト
を確実に
uint16_t systemCode = (((uint16_t)(uint8_t)t3tId[0] << 8) | 
                       ((uint16_t)(uint8_t)t3tId[1]));
```

### 2.3 CVE候補-3: レースコンディション (Medium Severity)

**場所**: `NativeNfcManager::registerT3tIdentifier()`

```java
// Java 側
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

// 問題: mT3tIdentifiers へのアクセスが他の場所で保護されていない可能性
```

**影響**:
- データ構造の破損
- Handle のリーク
- Use-After-Free (間接的)

**CVSS 3.1 スコア**: 4.0 (Medium)
```
CVSS:3.1/AV:L/AC:H/PR:H/UI:N/S:U/C:N/I:L/A:L
- AC:H: 特定のタイミングが必要
- I:L: 完全性への影響は低い
- A:L: 可用性への影響は低い
```

**注記**: 現在の AOSP 実装では適切に synchronized されているため、実際の脆弱性ではない。

## 3. CVSS スコアリング

### 3.1 スコアリングの前提

すべての脆弱性は以下の前提条件があります:

1. **ルート化デバイス**: PR:H (High Privileges Required)
2. **LSPosed/Xposed**: 特権的なフレームワーク
3. **ローカル攻撃**: AV:L (Local Attack Vector)

### 3.2 総合リスク評価

| 脆弱性 | CVSS | 深刻度 | 実現可能性 | 総合リスク |
|--------|------|--------|-----------|-----------|
| CVE候補-1 | 3.3 | Low | Medium | Low |
| CVE候補-2 | 2.0 | Low | Low | Low |
| CVE候補-3 | 4.0 | Medium | Low | Low |

**結論**: すべての脆弱性は Low リスク。ルート権限が必要なため、実用的な脅威は限定的。

## 4. 緩和策

### 4.1 コード側の改善

```cpp
// 1. 入力検証の強化
int RoutingManager::registerT3tIdentifier(uint8_t* t3tId, uint8_t t3tIdLen) {
    // NULL チェック
    if (t3tId == nullptr) {
        LOG(ERROR) << "NULL pointer";
        return NFA_HANDLE_INVALID;
    }
    
    // 長さチェック
    const size_t EXPECTED_LEN = 2 + NCI_RF_F_UID_LEN + NCI_T3T_PMM_LEN;
    if (t3tIdLen != EXPECTED_LEN) {
        LOG(ERROR) << "Invalid length: expected=" << EXPECTED_LEN 
                   << " actual=" << t3tIdLen;
        return NFA_HANDLE_INVALID;
    }
    
    // 安全な型変換
    uint16_t systemCode = (((uint16_t)(uint8_t)t3tId[0] << 8) | 
                           ((uint16_t)(uint8_t)t3tId[1]));
    
    // ... 以下同じ
}

// 2. ログの充実
LOG(DEBUG) << StringPrintf("registerT3tIdentifier: SC=0x%04X len=%d", 
                           systemCode, t3tIdLen);
```

### 4.2 システム側の保護

1. **SELinux ポリシー**:
   - `com.android.nfc` プロセスへのアクセス制限
   - Xposed モジュールの監査

2. **SafetyNet / Play Integrity**:
   - ルート化検出
   - Xposed 検出

3. **ログ監視**:
   - 異常な T3T 登録の検出
   - 大量の System Code 登録の警告

### 4.3 開発者向けベストプラクティス

```cpp
// RAII パターンの使用
{
    ScopedByteArrayRO bytes(e, jarray);
    // ... 使用 ...
}  // 自動解放

// スマートポインタの使用
std::unique_ptr<uint8_t[]> buffer(new uint8_t[len]);

// 境界チェックの徹底
if (offset + len > bufferSize) {
    return ERROR_BUFFER_TOO_SMALL;
}
```

## 5. Xposed モジュールのセキュリティ

### 5.1 責任ある使用

t3t-spray モジュールは教育目的であり、以下を守る必要があります:

1. **私的使用のみ**: 他人のデバイスには使用しない
2. **法令遵守**: 不正アクセス禁止法等に違反しない
3. **透明性**: ログを充実させ、何が起きているか明確に

### 5.2 推奨されるセーフガード

```kotlin
// 1. 設定画面での明示的な有効化
if (!config.enabled) {
    XposedBridge.log("T3tSpray: Disabled by user")
    return
}

// 2. 詳細なログ
XposedBridge.log("T3tSpray: Registering SC=$sc IDm=$idm")

// 3. エラーハンドリング
runCatching {
    registerT3tIdentifier(sc, idm, pmm)
}.onFailure { e ->
    XposedBridge.log("T3tSpray: Failed: ${e.message}")
}

// 4. 制限
val MAX_SYSTEM_CODES = 10  // 適切な上限
if (systemCodes.size > MAX_SYSTEM_CODES) {
    XposedBridge.log("T3tSpray: Too many system codes")
    return
}
```

## 6. 責任ある開示

### 6.1 発見された脆弱性の報告

もし実際の脆弱性を発見した場合:

1. **Google Security Team に報告**:
   - https://www.google.com/about/appsecurity/
   - security@android.com

2. **90日ルール**:
   - 報告から 90 日間は公表しない
   - ベンダーに修正の機会を与える

3. **CVE 番号の取得**:
   - MITRE または NVD に申請

### 6.2 倫理的考慮事項

1. **Do No Harm**: 脆弱性を悪用しない
2. **Responsible Disclosure**: 適切な手続きで報告
3. **Educational Purpose**: 知識の共有と向上

---

**セキュリティ分析は継続的なプロセスです。発見された脆弱性は適切に報告し、コミュニティ全体のセキュリティ向上に貢献しましょう。**
