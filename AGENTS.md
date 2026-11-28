# AGENTS.md — OpenClaw Nasdaq Swing Bot

Guidance for AI coding agents working in this repository. This is a Java bot
(Maven `release=25`, built with JDK 25) that polls a Nasdaq index, detects
confirmed swing highs/lows with a fractal rule, narrates each event with a local
Ollama model, emails an alert, and persists state across restarts.

## Build & test

```bash
# From repo root (Maven 3.9+; on this machine `mvn` may need the full path
# ~/.hermes/tools/apache-maven-3.9.6/bin/mvn, or `mise` provides 3.10.2):
mvn -q -DskipTests package      # fat jar -> target/nasdaq-swing-bot-0.1.0.jar
mvn test                        # JUnit 5 suite (SwingDetector, FinnhubClient, DemoPipeline)
```

Main class: `com.openclaw.bot.Main`. The jar is self-contained (shade).
Spotless enforces Google Java Format plus a `/* (C)$YEAR */` header — run
`mvn spotless:apply` if formatting fails. `MAVEN_OPTS="--enable-native-access=ALL-UNNAMED"`
is only required when executing DJL-based classes (sentiment CLI/poller), not for
`package`/`test`.

## Run

```bash
# Offline demo (no keys, no real email): replay + loopback SMTP sink
java -jar target/nasdaq-swing-bot-0.1.0.jar --demo \
  --replay data/replay.csv --ollama-url http://127.0.0.1:11435

# Live (Nasdaq public API + local Ollama)
java -jar target/nasdaq-swing-bot-0.1.0.jar \
  --ollama-url http://127.0.0.1:11435 --symbol COMP

# Single tick then exit (cron / smoke test)
java -jar target/nasdaq-swing-bot-0.1.0.jar --once
```

- Config precedence: `--flag` > env > default (see `BotConfig`). Env vars:
  `SMTP_HOST/PORT/USERNAME/PASSWORD`, `MAIL_TO`, `MAIL_FROM`, `FINNHUB_API_KEY`,
  `OLLAMA_BASE_URL`, `OLLAMA_MODEL`, `BOT_STATE`, `BOT_CAPTURE_DIR`.
- Providers: `--provider NASDAQ|YAHOO|FINNHUB|CSV` (default NASDAQ). FINNHUB
  requires `--finnhub-key` or `FINNHUB_API_KEY`; CSV uses `--replay FILE`.
- `--demo` delivers to a loopback SMTP sink (port 2525) and appends to
  `capture/debug-smtp.log`; without SMTP credentials the bot writes RFC-822
  `.eml` files under `capture/` — it never drops an alert silently.
- State lives in `state/bot-state.json` (last extremes, last-alert fingerprint,
  tick counters). Do not commit it to the repo.
- Replay CSV formats: `epochMillis,price` or `yyyy-MM-dd'T'HH:mm:ss,price` per
  line; `#` comments are ignored (see `data/replay.csv`).

## Architecture map

```
openclaw/plugin/handlers.py   # OpenClaw plugin: start/stop/status/alert_tail
openclaw/skill/SKILL.md       # operator runbook
src/main/java/com/openclaw/bot/
  Main.java                   # entry point (flags -> BotConfig -> wiring)
  config/BotConfig.java       # flag > env > default
  market/                     # MarketDataProvider: NasdaqApiClient (default, no key),
                              #   YahooFinanceClient, FinnhubClient (key; COMP->^IXIC,
                              #   NDX->^NDX), CsvReplayProvider
  swing/                      # SwingDetector (fractal, confirm=2, minDelta=0.05%), SwingEvent
  ai/                         # AiNarrator -> OllamaClient (POST /api/generate)
  sentiment/                  # standalone subsystem (see below): CLI, poller, FinBERT,
                              #   FinGPT cross-check, Ollama deep, aggregator, seen-store
  email/                      # SmtpEmailSender (Jakarta Mail), FileEmailSender (.eml),
                              #   DebugSmtpSink (loopback 2525)
  state/StateStore.java       # JSON persistence
  bot/NasdaqBot.java          # tick loop: fetch -> detect -> narrate -> email -> persist
docs/TECHNICAL.md             # detailed design
docs/model-benchmarks.md      # Ollama model measurements & selection
docs/spring.md                # Spring Boot integration proposal (NOT in pom.xml)
docs/spring-pooling.md        # async job-queue variant of that proposal
files/                        # model tooling: convert.py (FinBERT export), Modelfile*
                              #   (FinGPT GGUF for the fingpt-sentiment cross-check model);
                              #   gen_replay.py (synthetic replay CSV generator)
```

## Sentiment subpackage (`sentiment/`)

A **standalone subsystem** — not called by the bot's tick loop (`NasdaqBot`
only mentions "sentiment" inside the narrative prompt). Three entry points:

| Entry point | Class | Purpose |
|---|---|---|
| CLI | `SentimentCli` | Analyze one headline/file/batch; human or `--json` output |
| Scheduled poller | `NewsPoller` | Fetch Finnhub news → ingest → rolling per-symbol score |
| Self-test | `SeenStoreTest` | Offline verification of `SeenStore` persistence |

Backends:

- `FinBertSentiment` — local DJL FinBERT classifier (PyTorch engine, custom
  WordPiece tokenizer from `vocab.txt`). Lazy synchronized model load; one
  predictor per call. Fast pass. Also exposes `analyzeBatch(List)` sharing one
  predictor.
- `FinGptSentiment` — optional cross-check classifier over Ollama
  (`fingpt-sentiment` model; temperature 0, `num_predict=8`, confidence fixed at
  1.0).
- `OllamaSentimentAnalyzer` — optional "deep" analysis via Ollama `/api/chat`
  with a structured-JSON prompt (`format=json`, `think=false`,
  `num_predict=2048`). Blank content triggers one retry with a larger budget
  (8192) before failing; empty content is an error, never "neutral".

Orchestration: `SentimentAggregator` dedupes via `SeenStore`, scores through
FinBERT, optionally cross-checks with FinGPT (only *opposite-side* disagreement
escalates), escalates to deep Ollama under a `DeepBudget`, and keeps a
time-decayed per-symbol score. `FinnhubNewsClient` fetches
`GET /company-news` (header `X-Finnhub-Token`).

Run the CLI (needs `--enable-native-access=ALL-UNNAMED` for DJL):

```bash
MAVEN_OPTS="--enable-native-access=ALL-UNNAMED" mvn -q compile exec:java \
  -Dexec.mainClass=com.openclaw.bot.sentiment.SentimentCli \
  -Dexec.args="--text 'Fed signals rate cut, markets rally' --deep --json"
```

CLI flags: `--text|--file|--batch`, `--deep` (escalate to Ollama only when
FinBERT confidence < `--min-confidence`, default 0.65), `--force-deep` (always
escalate), `--cross-check`/`--voting` (FinGPT cross-check), `--num-ctx N`
(override context window, ≥ 2048), `--verbose`, `--json`, `--model` (default
`qwen3.5:9b`), `--base-url` (default `http://localhost:11434`), `--model-path`
(default `~/models/finbert`).

Pitfalls for this package:

- **The planning docs (`README.md`, `addon.md`, `notes.md`) describe a
  `--concurrency N` parallel batch refactor that is NOT implemented.** Batch is
  sequential; `--concurrency` is not a flag in the code. Don't document or rely
  on it.
- **`FinBertSentiment` reloads the model per headline in batch mode**: batch
  constructs and closes a fresh instance per line. Use the shared-predictor
  `analyzeBatch(List)` if optimizing.
- The package's Ollama default (**11434 / qwen3.5:9b**) differs from the bot's
  dedicated server (**11435 / gemma4-e4b-64k:latest**). Don't conflate them.
- `--json` output includes raw model output; keep it as-is for jq pipelines.

## Critical conventions & pitfalls

- **Ollama model default is `gemma4-e4b-64k:latest`** (set in `BotConfig`;
  env override `OLLAMA_MODEL`). Fallback: `gemma3:4b`. The code-level server
  default is `http://localhost:11434`; on this machine the dedicated instance
  runs on `127.0.0.1:11435` (env `OLLAMA_HOST=127.0.0.1:11435`) and is passed
  to the bot via `--ollama-url http://127.0.0.1:11435`. Server env:
  `OLLAMA_NUM_GPU=0 OLLAMA_MODELS=/usr/share/ollama/.ollama/models
  OLLAMA_HOST=127.0.0.1:11435 ollama serve`.
- **Thinking models return empty `response`** when `num_predict` is small:
  gemma4-e4b-64k and qwen3.5:4b spend the whole budget on hidden
  chain-of-thought. `OllamaClient` sends `"think": false` to fix this. If you
  touch the client, keep that flag, and keep `num_predict` >= ~500 for the
  narrative.
- **No credentials in code or README.** SMTP password is env-only
  (`SMTP_PASSWORD`); never add real API keys to the repo.
- Narrative prompt lives in `NasdaqBot.buildPrompt`; keep it concise
  (~150 tokens) and instruct "no markdown" — some models (mimo-v2.6) emit
  `**bold**` anyway.
- Keep the code free of `sun.misc.Unsafe`-style deprecation warnings where
  possible (they are only warnings; build stays green).
- Tests must not require the network or a live Ollama server (`DemoPipelineTest`
  uses a stub narrator + replay data).

<!-- CODEGRAPH_START -->
## CodeGraph

In repositories indexed by CodeGraph (a `.codegraph/` directory exists at the repo root), reach for it BEFORE grep/find or reading files when you need to understand or locate code:

- **MCP tool** (when available): `codegraph_codegraph_explore` answers most code questions in one call — the relevant symbols' verbatim source plus the call paths between them, including dynamic-dispatch hops grep can't follow. Name a file or symbol in the query to read its current line-numbered source. If it's listed but deferred, load it by name via tool search.
- **Shell** (always works): `codegraph explore "<symbol names or question>"` prints the same output.

If there is no `.codegraph/` directory, skip CodeGraph entirely — indexing is the user's decision.

OpenCode puts the MCP server's key in front of each tool's name, so it lists this tool as `codegraph_codegraph_explore` where other agents list `codegraph_explore`; if the server is registered under another key, that key replaces the first `codegraph_`.
<!-- CODEGRAPH_END -->
