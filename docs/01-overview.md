# AOSP NFC解体新書 - 概要とアーキテクチャ

## 目次
1. Android NFC スタックの全体像
2. AOSP NFC の構成要素
3. Android 15 における変更点
4. HCE-F (Host Card Emulation - FeliCa) の位置づけ

## 1. Android NFC スタックの全体像

Android の NFC スタックは以下の階層で構成されています:

```
┌─────────────────────────────────────┐
│     アプリケーション層                │
│  (NFC API, HCE-F アプリ等)           │
└─────────────────────────────────────┘
              ↕
┌─────────────────────────────────────┐
│   Android Framework (Java)          │
│  - NfcService                        │
│  - CardEmulationManager             │
│  - RegisteredT3tIdentifiersCache    │
│  - SystemCodeRoutingManager         │
└─────────────────────────────────────┘
              ↕
┌─────────────────────────────────────┐
│      JNI Layer (C++)                │
│  - NativeNfcManager.cpp             │
│  - RoutingManager.cpp               │
│  - NfcTag.cpp                       │
└─────────────────────────────────────┘
              ↕
┌─────────────────────────────────────┐
│   NFC HAL / libnfc-nci              │
│  (NCI Protocol Implementation)      │
└─────────────────────────────────────┘
              ↕
┌─────────────────────────────────────┐
│      NFC Controller (Hardware)      │
│  - NXP PN5xx, Broadcom等            │
└─────────────────────────────────────┘
```

## 2. AOSP NFC の構成要素

### 2.1 パッケージ構造 (Android 15)

Android 15 では NFC コードは以下の場所に配置されています:

```
packages/apps/Nfc/
├── src/com/android/nfc/
│   ├── NfcService.java              # メインサービス
│   ├── DeviceHost.java              # ネイティブ層とのインターフェース
│   ├── cardemulation/
│   │   ├── RegisteredT3tIdentifiersCache.java   # T3T識別子キャッシュ
│   │   ├── SystemCodeRoutingManager.java        # システムコードルーティング
│   │   └── CardEmulationManager.java            # カードエミュレーション管理
│   └── ...
├── nci/
│   ├── src/com/android/nfc/dhimpl/
│   │   └── NativeNfcManager.java    # ネイティブメソッドのラッパー
│   └── jni/
│       ├── NativeNfcManager.cpp     # JNI実装
│       ├── RoutingManager.cpp       # ルーティング管理
│       └── ...
└── ...
```

### 2.2 Android 16 での変更

Android 16 (main branch) では、NFC コードは以下に移動:

```
packages/modules/Nfc/NfcNci/
```

この変更により、NFC が Android のモジュール化プロジェクトの一部となりました。

## 3. Android 15 における重要な変更点

### 3.1 パッケージの場所
- **Android 14以前**: `packages/apps/Nfc`
- **Android 15**: `packages/apps/Nfc` (まだここ)
- **Android 16+**: `packages/modules/Nfc/NfcNci`

### 3.2 クラス名とパッケージ名は不変
重要な点として、以下のクラスは場所が変わってもクラス名とパッケージ名は変わりません:

- `com.android.nfc.dhimpl.NativeNfcManager` ✓ (Android 15で存在)
- `com.android.nfc.cardemulation.RegisteredT3tIdentifiersCache` ✓
- `com.android.nfc.cardemulation.SystemCodeRoutingManager` ✓

## 4. HCE-F (Host Card Emulation - FeliCa) の位置づけ

### 4.1 HCE-F の特徴

HCE-F は通常の HCE-A/B とは大きく異なる特徴を持ちます:

1. **NFCコントローラレベルでの応答**
   - HCE-A/B: SELECT コマンドは CPU に届き、アプリが応答
   - HCE-F: SENSF_REQ (Polling) は NFC コントローラのみで応答

2. **事前登録の必要性**
   - System Code と IDm を事前に NFC コントローラに登録必須
   - 登録は CPU (Android) から行うが、応答はコントローラ単独

3. **ワイルドカード (FFFF) の挙動**
   - SENSF_REQ で System Code = FFFF (ワイルドカード) が送信された場合
   - 若い System Code 番号を持つ IDm が優先される
   - eSE が登録した SC が優先されることが多い

### 4.2 制御フロー

```
アプリ起動
    ↓
HCE-F サービス登録
    ↓
CardEmulationManager
    ↓
RegisteredT3tIdentifiersCache
    ↓
SystemCodeRoutingManager.configureRouting()
    ↓
NfcService.registerT3tIdentifier()
    ↓
DeviceHost (interface)
    ↓
NativeNfcManager.registerT3tIdentifier() [Java]
    ↓
NativeNfcManager.doRegisterT3tIdentifier() [Java native method]
    ↓
nfcManager_doRegisterT3tIdentifier() [C++ JNI]
    ↓
RoutingManager.registerT3tIdentifier() [C++]
    ↓
NFA_CeRegisterFelicaSystemCodeOnDH() [NFC HAL]
    ↓
NFC Controller に登録
```

## 5. セキュリティとルート化の関係

### 5.1 通常の制限
- 通常のアプリは `NfcService` API を通じてのみ T3T 識別子を登録可能
- システムアプリまたは特権が必要

### 5.2 ルート化環境での可能性
- LSPosed/Xposed を使用して `com.android.nfc` プロセスをフック
- `RegisteredT3tIdentifiersCache` や `NativeNfcManager` のメソッドをフック
- 任意の System Code / IDm を NFC コントローラに登録可能
- ワイルドカード応答の制御が可能

## 6. 次のステップ

次の章では、以下について詳細に解説します:

1. NFC コントローラの制御メカニズム
2. RegisteredT3tIdentifiersCache の実装詳細
3. NativeNfcManager とネイティブ層の詳細
4. RoutingManager の実装
5. セキュリティ分析

---

**分析対象ブランチ**: android15-release  
**分析日時**: 2025-12-26  
**リポジトリ**: platform/packages/apps/Nfc
