# t3t-spray

LSPosed/Xposed モジュールとして HCE-F (Type3) の well-known System Code をスプレーし、SENSF_REQ のワイルドカードでも任意の IDm を返すための最小構成の Kotlin Android プロジェクトです。

## 構成
- `app` : アプリ本体と Xposed エントリポイント (`app.aoki.yuki.t3tspray.xposed.T3tSprayEntry`)
- Activity (`MainActivity`) で設定を保存し、LSPosed から再読み込みできるようにしています。
- 既定で System Code `0003`, `FE00`, `FE0F` を登録し、IDm は設定画面で上書きできます。

## ビルド
Android Studio または `./gradlew assembleDebug` でビルドできます。開発環境に Android SDK/NDK を用意してください。

## 使い方
1. モジュールをインストールし、LSPosed で有効化します（ターゲットは `com.android.nfc`）。
2. アプリを起動して有効化スイッチと IDm/System Code を設定し、保存します。
3. LSPosed でモジュールを再読み込み/再起動すると、NFC コントローラへスプレーされます。
