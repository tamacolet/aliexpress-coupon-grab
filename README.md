# アリエク券取り

Android 12（API 31）以降向けの、AliExpress プロモコード自動適用アプリです。Kotlin / Jetpack Compose Material 3 製で、JST の秒指定と 1〜60 分の繰り返し（既定 5 分）に対応します。

## ビルドとインストール

JDK 17 または 23 で次を実行します。

```sh
./gradlew testDebugUnitTest assembleDebug
adb install --user 0 -r app/build/outputs/apk/debug/app-debug.apk
```

## 初期設定

アプリの「実行に必要な設定」から、**ユーザー補助・正確なアラーム・通知**を許可してください。ユーザー補助を adb で有効にする場合は、既存サービスを消さずに追加します。

```sh
adb shell 'SVC=com.tamacolet.coupongrab/com.tamacolet.coupongrab.automation.CouponAccessibilityService; OLD=$(settings --user 0 get secure enabled_accessibility_services); [ "$OLD" = null ] && OLD=; case ":$OLD:" in *":$SVC:"*) ;; *) settings --user 0 put secure enabled_accessibility_services "${OLD:+$OLD:}$SVC" ;; esac; settings --user 0 put secure accessibility_enabled 1'
adb shell pm grant --user 0 com.tamacolet.coupongrab android.permission.POST_NOTIFICATIONS
```

正確なアラームは設定画面で許可します。端末の再起動後と、アプリを入れ直した後は、有効な予定を自動再登録します。

**Android 17（Pixel 7a で確認）では `adb install -r` で入れ直した時と、`am force-stop` で止めた時に、ユーザー補助の設定が消えます。** 入れ直した後は上のコマンドでもう一度有効にしてください。

## 実行と状態

- コードは 1 行に 1 つ、ASCII 英数字 3〜32 文字です。状態は「待ち・取れた・使用済み・失敗」です。
- 時刻指定では失敗も終端です。繰り返しでは待機と失敗だけを再試行し、適用済み・使用済みは再実行しません。全コードが終端になると予定を停止します。
- 時刻指定では 60 秒前に foreground service が起動し、カートとコード入力を準備します。繰り返しと NAS 延期は指定時刻ちょうどに起動します。補正した時刻の直前は単調時計で待機し、押す直前と直後の補正時刻を記録します。最後約20msまでは中断できる待機を使い、最後約20msだけ単調時計を読み続けます。
- 実行要求は ID ごとに保存して直列処理し、時刻指定を繰り返しより優先します。同じ時刻の時刻指定同士も順番に処理するため、完全な同時適用は保証しません。
- 時刻は複数 SNTP サンプルの最小 RTT を採用し、失敗時は HTTP Date へ切り替えます。両方とも失敗した場合は警告通知を出し、補正なしの端末時計を使います。
- 履歴はミリ秒 timestamp 付きで最新 100 件を保持します。予定・状態は、アプリ内 JSON ファイルへアトミックに保存します。

## NAS との調整

端末側の `busy` は、時計測定・画面準備・適用・後片付けの間 `true` です。NAS 側の busy 状態とは別に保持します。

```sh
# NAS busy（untilMs は epoch ミリ秒）
adb shell am broadcast --user 0 -a com.tamacolet.coupongrab.NAS_BUSY -n com.tamacolet.coupongrab/.NasReceiver --el untilMs 1893456000000
# NAS free
adb shell am broadcast --user 0 -a com.tamacolet.coupongrab.NAS_FREE -n com.tamacolet.coupongrab/.NasReceiver
# 停止中のServiceでも取得できる、DUMP権限で保護した状態窓口
adb shell content call --user 0 --uri content://com.tamacolet.coupongrab.status --method status
```

出力は `coupongrab busy=true|false nextExactAtMs=epoch|0 nasBusyUntilMs=epoch|0` です。NAS busy 中の繰り返し予定は 30 秒ごとに延期し、NAS が空くまで延期を続けます。次の時刻指定まで3分以内の場合も繰り返しを30秒延期し、60秒前の準備を優先します。時刻指定は NAS busy より優先します。実行中は `adb shell dumpsys activity --user 0 service com.tamacolet.coupongrab/.GrabService` でも同じ行を返します。停止中は上の状態窓口を使います。

## 安全上の制約とフォールバック

**「今支払います」「注文」「支払」を含むボタン、および place order / pay now / payment 系 ID は絶対に押しません。** クリック対象の祖先と配下も検査し、危険なら処理を中止します。購入手続き画面の「プロモコード」行は押せる印（clickable）を持たないため、その文字の中心を位置で押します。位置で押す前に、その文字・祖先に加え、押す位置に重なる全ての部品（押せる部品は配下まで）を同じ禁止語で検査します。実際に押すのは、必要時のカートの全選択、「購入手続きへ」、「プロモコード」行、「適用」（再試行を含む）、「承認」、小窓を閉じるボタン、「中止する」です。これらは全て同じ PaymentSafety の検査を通します。画面を戻す操作、入力欄の選択と文字入力、行の表示・スクロールも行います。支払いは実行しません。

AliExpress の画面構造が変わった場合は失敗として終了します。画面は「今使っている窓」ではなく、重なり順で一番上のアプリの窓を読みます（「会計手続きを中止しますか」の窓はフォーカスを持たず、キーボードが出る瞬間は今使っている窓が取れないため）。無効と出たコードは「適用」が押せない状態になるので、押し直しの前に入力欄を空にしてから入れ直します。ロック画面の表示・キーガード解除を待ってからカートを開きますが、端末によっては認証や手動解除が必要です。AliExpress が前面にある間の画面維持には、20 分で必ず切れる従来型の画面 WakeLock を使います（Activity のフラグと PARTIAL_WAKE_LOCK だけでは維持できません）。正確なアラーム未許可時は不正確なアラームへフォールバックせず、設定画面で未許可を表示します。

画面の読み方で、7a の実機で分かったこと:

- 窓の控え（キャッシュ）が古いまま残り、消えた窓しか見えないことがあるので、読めない時は控えを消して（`clearCache()`）読み直します。
- 支払い方法の欄が長いと、プロモコード行が画面の下にはみ出して支払い欄と重なります。行を画面の中へ出し、位置が止まってから押します。
- 読み込み途中にプロモコード行を押すと、小窓ではなくウェブの「工事中」画面（`AEBuildingActivity`、promocode_v3.html）が開くことがあります。戻って最大3回押し直し、後片付けでもこの画面なら先に戻ります。
- 入力欄の×ボタンは押した後で遅れて効き、入れた文字まで消すので使いません。空にしてから入れます。
- 割引の大きい券を上書きする時は「このクーポン/コードを本当に適用してよろしいですか?」が出るので「承認」を押します（注文はしません）。
- 失敗の後はキーボードが残り、「戻る」の1回目はキーボードを閉じるだけのことがあるので、後片付けでは「戻る」をカートに着くまで最大3回くり返します。


登録には省電力中にも起動できる `setAlarmClock` を使うため、端末に目覚ましの印が出ます。1回の実行は最長20分、5分以上遅れた予約は失敗で終了します。SNTPの名前解決は取り消せますが、HTTP側の端末標準の名前解決が停止すると計測の目安12秒を超える場合があります。実機での画面操作・精度は親が確認します。
