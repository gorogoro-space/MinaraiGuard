# MinaraiGuard

新規プレイヤーの見習い期間とスポーン地点の保護で、他の人の建築と景観を守る Paper 用プラグイン。
リポジトリ: https://github.com/gorogoro-space/MinaraiGuard (GPL-3.0、公開リポジトリ)

## 作業の進め方(必ず守ること)

- **設計が確定するまで実装しない。** 機能追加や仕様変更は、まず設計案(何を・なぜ・どう変えるか、影響範囲)を提示し、承認を得てからコードを書く。
- 判断が必要な点は、選択肢を示して質問する。勝手に決めない。
- やり取りは日本語で行う。コード内のコメントやメッセージも日本語。
- 変更は必要最小限にする。頼まれていないリファクタリングや機能追加はしない。
- 作業後は、変更・追加・削除したファイルの一覧と変更内容を報告する。
- 仕様を変えたら README.md と CLAUDE.md も合わせて更新する。
- `git push` の前には必ず確認を取る。コミットは意味のある単位で分ける。
- リポジトリへの初回の `git push` の前には、「GitHub で Watch 設定(Custom → Issues と Pull requests)はしましたか?」と日本語で確認する。自動 Watch は廃止されており、設定しないと issue や PR の通知が届かない。
- コミットメッセージや PR に `Co-Authored-By: Claude` などの署名を付けない。`.claude/` は Git に入れない(`.git/info/exclude` で除外済み)。
- 実装中に設計の抜けや穴に気づいたら、黙って対処せず報告して相談する。

## 環境

- Paper 1.21.11 / Java 21
- ビルド: `gradlew.bat clean build`(Windows)。「ビルドして」と言われたら常にクリーンビルドする。成果物は `build/libs/`
- パッケージ: `space.gorogoro.minaraiguard`(**すべて小文字**。大文字が混ざると plugin.yml の main と一致せず起動しない)
- 動作確認はサーバーを再起動して行う。PlugManX での読み込みは権限やコマンドの登録が不完全になることがある

## 最重要の設計方針

### TPS に影響させない
- 高頻度イベント(BlockFromToEvent、EntityChangeBlockEvent、PlayerInteractEvent など)は、ワールドと座標の整数比較で範囲外なら即座に抜ける
- BlockPhysicsEvent、VehicleMoveEvent など発生頻度が極端に高いイベントは使わない
- メインスレッドでファイルや DB の同期 I/O をしない。未読み込みチャンクを判定のために読み込まない(`getChunkAtAsync`、`teleportAsync` を使う)
- config.yml は起動時・リロード時に一度だけ解析して保持する。ブロックの集合は EnumSet
- 定期タスクは最小限(現状は見習いの昇格チェック 1 分ごとのみ)

### データの保存
- 見習いの設置記録は `plugins/MinaraiGuard/placements.db`(SQLite、ドライバは Paper 同梱)
- DB の読み書きは専用スレッド `MinaraiGuard-DB` のみ。書き込みはキューに溜めて 1 秒ごとに 1 トランザクションで反映
- 判定はメモリ上のデータだけで行う。見習いのログイン時にその人の記録だけを読み込み、昇格したら記録を削除
- プラグインフォルダ以外には何も書き込まない

### 他プラグインとの役割分担(このプラグインでは扱わない)
- チェスト・看板の保護 → LWC(サーバー立ち上げ当初から導入済み)
- 延焼の防止、TNT による破壊の防止 → 別プラグイン
- 額縁・防具立て → 対象外
- **GSit** の座る操作を妨げないこと。右クリックは「状態が変わるブロック」だけを拒否し、階段などには干渉しない
- **テレポート看板**はバニラの `click_event`(run_command)で動いている。看板の右クリックは絶対にキャンセルしない

## 機能仕様

### 見習い
- プレイ時間(`Statistic.PLAY_ONE_MINUTE`、既存プレイヤーの過去分も含む)が 5 時間未満のプレイヤー。`minarai.exempt` を持つ人は対象外(OP は既定で対象外)
- 対象ワールドは `apprentice.worlds`(メインワールドのみ。資源ワールドは入れない)
- スポーン外で壊せるもの: 自分が置いたブロック、自分の苗木から育った木、`terrain-blocks`(土・石など)
- 壊せないもの: 他人の建築、`resource-blocks`(原木・葉・花・鉱石など。スポーン近くや各所に景観保護用の林がある)
- 昇格時はタイトルと効果音で通知

### スポーン保護(全員対象、`minarai.bypass` を除く)
- 範囲: config.yml の `spawn-protection`(必須設定、高さは問わず X・Z で判定)。現在は X:110〜179, Z:226〜295
- 禁止: ブロックの破壊・設置・バケツ、`deny-blocks` の右クリック(リピーター等)、道具による変化(樹皮剥ぎ等)、普通以外のトロッコ設置、書見台の本の取り出し
- 許可: ドア・ボタン・レバー・感圧板・チェスト・看板・GSit・書見台を読む・普通のトロッコ
- 昇格後のプレイヤー(見習い以外)は `member-blocks`(既定は `#all_signs`)の設置・破壊ができる。掲示板に看板を貼るため。他人の看板は LWC の自動保護が守るので、このプラグインでは持ち主を判定しない
- プレイヤー以外の変化も防ぐ: 爆発、境界をまたぐピストン、液体の流入、エンティティによる変化、範囲外からの木の成長、ディスペンサー

### 案内表示
- アクションバー: 制限にかかるたびに毎回
- 見習いがスポーンに `member-blocks`(看板)を置こうとしたときは、専用のメッセージ(`spawn-member-apprentice`)を出す。チャット案内は /minarai のみ(/shigen は出さない)。壊そうとしたときは通常のスポーンのメッセージ
- チャット案内(/shigen と /minarai のクリック可能な案内): ログイン中に初めて制限にかかったときの 1 回だけ
- 警戒モード: 短時間に別々のブロックを壊そうとすると入り、チャット案内を 15 秒間隔で繰り返す。管理者通知は既定でオフ。**判定の仕組みは公開の告知に載せない**
- 表示を増やしすぎない。プレイヤーが何もしていないときは何も表示しない

### コマンド
- `/minarai`: 見習いの残り時間を表示
- `/shigen`: 資源ゲート前へ移動。全員使える(権限で縛らない。`minarai.shigen` を明示的に false にした場合のみ拒否)。移動後に資源ワールドのリセット案内を表示
- `/minarai setgate`: 現在地と向きを /shigen の移動先として保存(管理者)
- `/minarai reload`: 設定の再読み込み(管理者)

### 資源ワールドのリセット案内
- 毎月 1 日 5:20(Asia/Tokyo)にリセット
- 表示は /shigen の移動時だけ。「あと○日」、1 日未満は「あと○時間○分」。残り 3 日未満は赤の太字

## 過去にハマった点

- `config.getString(path, "")` のように既定値を渡すと、jar 内 config.yml の既定値が参照されない。既定値なしで取得して null を判定すること
- plugin.yml で `default: true` にした権限でも、登録されないと Bukkit は「OP のみ」として扱う。全員向けの機能を権限で縛らない
- IntelliJ の「アーティファクトのビルド」はクラスファイルが入らないことがある。必ず Gradle でビルドする

## ファイル構成(src/main/java/space/gorogoro/minaraiguard/)

- `MinaraiGuard.java`: メインクラス、初期化・リロード
- `GuardSettings.java`: config.yml の解析と検証
- `Zone.java`: スポーン範囲の判定
- `ApprenticeService.java`: 見習いの判定と昇格
- `PlacementStore.java`: 設置記録(SQLite、非同期)
- `WarningTracker.java`: 警戒モードの判定
- `Messenger.java`: メッセージ表示(MiniMessage)
- `BreakListener.java`: ブロック破壊の判定
- `SpawnListener.java`: スポーン保護
- `PlacementListener.java`: 設置記録の更新
- `SessionListener.java`: ログイン・ログアウト
- `MinaraiCommand.java` / `ShigenCommand.java`: コマンド
- `Perms.java`: 権限ノード
