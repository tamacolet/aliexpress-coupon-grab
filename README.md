# 券取り

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

正確なアラームは設定画面で許可します。端末の再起動後は有効な予定を自動再登録します。

## 実行と状態

- コードは 1 行に 1 つ、ASCII 英数字 3〜32 文字です。状態は「待ち・取れた・使用済み・失敗」です。
- 時刻指定では失敗も終端です。繰り返しでは待機と失敗だけを再試行し、適用済み・使用済みは再実行しません。全コードが終端になると予定を停止します。
- 時刻指定では 60 秒前に foreground service が起動し、カートとコード入力を準備します。繰り返しと NAS 延期は指定時刻ちょうどに起動します。補正した時刻の直前は単調時計で待機し、押す直前と直後の補正時刻を記録します。最後400msは短い間隔で単調時計を確認し、最後2msは待ち処理を挟みません。
- 実行要求は ID ごとに保存して直列処理し、時刻指定を繰り返しより優先します。同じ時刻の時刻指定同士も順番に処理するため、完全な同時適用は保証しません。
- 時刻は複数 SNTP サンプルの最小 RTT を採用し、失敗時は HTTP Date へ切り替えます。両方とも失敗した場合は警告通知を出し、補正なしの端末時計を使います。
- 履歴はミリ秒 timestamp 付きで最新 100 件を保持します。予定・状態・NAS 待機開始時刻は、アプリ内 JSON ファイルへアトミックに保存します。

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

出力は `coupongrab busy=true|false nextExactAtMs=epoch|0 nasBusyUntilMs=epoch|0` です。NAS busy 中の繰り返し予定は 30 秒ごとに延期し、最初の延期から最長 10 分で実行します。時刻指定は NAS busy より優先します。実行中は `adb shell dumpsys activity --user 0 service com.tamacolet.coupongrab/.GrabService` でも同じ行を返します。停止中は上の状態窓口を使います。

## 安全上の制約とフォールバック

**「今支払います」「注文」「支払」を含むボタン、および place order / pay now / payment 系 ID は絶対に押しません。** クリック対象の祖先と配下も検査し、危険なら処理を中止します。座標タップへの安全でないフォールバックはありません。会計後の戻る操作と「中止する」だけを行い、支払いは実行しません。

AliExpress の画面構造が変わった場合は失敗として終了します。ロック画面の表示・キーガード解除を待ってからカートを開きますが、端末によっては認証や手動解除が必要です。AliExpress が前面にある間の画面維持には、20 分で必ず切れる従来型の画面 WakeLock を使います（Activity のフラグと PARTIAL_WAKE_LOCK だけでは維持できません）。正確なアラーム未許可時は不正確なアラームへフォールバックせず、設定画面で未許可を表示します。

登録には省電力中にも起動できる `setAlarmClock` を使うため、端末に目覚ましの印が出ます。1回の実行は最長20分、5分以上遅れた予約は失敗で終了します。SNTPの名前解決は取り消せますが、HTTP側の端末標準の名前解決が停止すると計測の目安12秒を超える場合があります。実機での画面操作・精度は親が確認します。
