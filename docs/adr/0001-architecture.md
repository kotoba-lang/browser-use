# ADR-0001: browser-use-clj — portable Clojure, Datomic-API-first browser agent

- Status: Accepted (2026-06-12)
- 関連: langchain-clj / langgraph-clj / comfyui-clj ADR-0001, kawasakijun ADR-0010

## 課題

browser-use 相当のブラウザ操作エージェントを、

1. **Clojure WASM で動く前提**(実ブラウザはホスト能力として注入)、
2. **Datomic API 前提**(操作履歴を EAV ファクトとして保持)

で実装したい。本家 browser-use は Python + Playwright 一体だが、本質は
**「ページを番号付き要素として LLM に見せ、アクションを tool として
与えるループ」**にある。

## 決定

### 1. ブラウザはホスト能力 (IBrowser protocol)

`browseruse.browser/IBrowser`(navigate!/click!/input-text!/scroll!/
back!/state)。実装はホストが注入: JVM なら Playwright/CDP、
ブラウザ内 WASM ならページ自身、デスクトップなら claude-in-chrome 系
MCP。テストは `mock-browser`(pure-data のサイトモデル: :nav リンク・
:nav-fn 計算遷移・入力値の保持)。

### 2. 本家 browser-use との対応

| upstream (browser-use) | browser-use-clj |
|---|---|
| DOM → indexed clickable elements 表現 | `browser/-state` + `state->prompt`(`[0]<a>Sign in</a>` 形式) |
| Controller / Registry (actions as tools) | `browseruse.actions`(langchain tool maps、合成で拡張) |
| Agent loop (observe → LLM → act, max_steps) | `browseruse.agent` — langgraph StateGraph(`:agent ⇄ :tools`、`:recursion-limit`) |
| `done` action | `actions/done-action`(ループ終端、:result に格納) |
| AgentHistoryList | 操作ログ datom(`:action/*`)+ langgraph checkpointer |
| Playwright / BrowserContext | 非スコープ — ホスト側の責務 |

### 3. 操作履歴は datom (ADR-0010 L1)

`:history-conn` を渡すと、実行された全アクションが
session entity + action entity(`:action/name :action/input
:action/result :action/url`)として記録される。
「エージェントが訪れた全 URL」「昨日の全 input_text」は Datalog クエリ。
チェックポイント(resume / human-in-the-loop)は langgraph-clj の
`:compile-opts {:checkpointer …}` をそのまま透過。

### 4. 積層

langchain-core ⟵ langgraph ⟵ browser-use の本家依存関係をそのまま再現:
deps は langgraph-clj のみ(langchain-clj が transitive に入る)。

## 非スコープ (v0.1)

- 実ブラウザドライバ(Playwright 相当)— ホスト注入
- スクリーンショット/vision 入力(content blocks はホスト実装の自由)
- マルチタブ・ファイルダウンロード・DOM diff 最適化
