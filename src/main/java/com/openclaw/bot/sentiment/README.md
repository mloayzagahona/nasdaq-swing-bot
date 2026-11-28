# Sentiment Analysis Package — `com.openclaw.bot.sentiment`

This package provides the sentiment-analysis engine for the OpenClaw Nasdaq bot. It runs as a **standalone subsystem** — it is *not* called by the bot's tick loop (`NasdaqBot` only mentions "sentiment" inside its narrative prompt). Three entry points:

| Entry point | Class | Purpose |
|-------------|-------|---------|
| CLI | `SentimentCli` | Analyze one headline, a file, or a batch (`--json` output for jq). |
| Scheduled poller | `NewsPoller` | Long-running loop: fetch Finnhub news → ingest → rolling per-symbol score. |
| Self-test | `SeenStoreTest` | Offline verification of `SeenStore` persistence. |

---

## 1. Architecture & Data Flow

```
                     ┌─────────────────────────────┐
                     │  FinnhubNewsClient (fetch)   │
                     │  NewsPoller (driver)         │
                     └──────────────┬──────────────┘
                                    ▼
   ┌──────────────────────────────────────────────────────────────┐
   │              SentimentAggregator.ingest(symbol, articles, cfg)│
   │  1. SeenStore.alreadySeen? → skip (dedupe)                   │
   │  2. FinBertSentiment.analyze(headline)  → BULLISH/BEARISH/   │
   │     NEUTRAL (+ score, confidence)                            │
   │  3. [optional] FinGptSentiment.analyze → cross-check          │
   │     disagreement = opposite sides ONLY (positive/negative,    │
   │                   negative/positive)                          │
   │  4. shouldEscalate =                                          │
   │       (deepEnabled && (forceDeep || conf < minConfidence))    │
   │       || (useCrossCheck && disagree)                          │
   │  5. [if escalate && DeepBudget allows]                        │
   │     OllamaSentimentAnalyzer.analyze (deep JSON)               │
   │  6. SeenStore.save(); update rolling score (time-decayed)     │
   └──────────────────────────────────────────────────────────────┘
```

- **Fast pass (FinBERT):** local DJL model — primary, low-latency signal.
- **Cross-check (FinGPT):** optional classifier; only *opposite-side* disagreement escalates (neutral never forces).
- **Deep analysis (Ollama):** LLM returning structured JSON (`sentiment`, `score`, `confidence`, `entities`, `key_drivers`, `caveats`). Empty/blank content triggers one retry with a larger `num_predict` (2048 → 8192) before failing.
- **Fallback:** if deep analysis fails, the pipeline returns the FinBERT result with `ollama_error` recorded — an alert is never dropped silently.
- **Orchestration:** `SentimentAggregator` maintains a **time-decayed weighted-average score** (60-min half-life, 24h window) per symbol, and gates LLM calls with a `DeepBudget`.

---

## 2. Class Responsibilities

| Class | Role | Notable internals |
|-------|------|-------------------|
| `SentimentCli` | CLI entry: single/file/batch; human or `--json` output | `RunResult` record carries `finbert`, `ollama`, `crossCheck`, `source`, `ollamaError`; resolves `--num-ctx` (≥2048); exits `2` on missing input/usage errors |
| `FinBertSentiment` | Local DJL FinBERT classifier | Lazy **synchronized** model/translator load (`ModelZoo.loadModel`, PyTorch engine, `file://` model dir); `SentimentResult(sentiment, score, confidence)`; score = ±confidence by label direction; also exposes `analyzeBatch(List)` sharing one predictor |
| `OllamaSentimentAnalyzer` | Deep LLM analysis | `DeepAnalysis` + `Entity` records; default system prompt; `think=false`, `format=json`, temperature 0.1, `num_ctx` auto (64k tag → 65536 else 8192) or override; HTTP timeout 120s; retry once on blank content |
| `FinGptSentiment` | Cross-check classifier | `temperature=0.0`, `num_predict=8`, output normalized by substring match (positive/negative/neutral); confidence fixed at 1.0 |
| `SentimentAggregator` | Orchestration + rolling score | `IngestConfig` record with `defaults()`; `DeepBudget` (max deep calls per ingest); `score(symbol)` time-decayed |
| `SeenStore` | Persistent dedupe (**package-private**) | JSON `{symbol: {max_id, max_datetime}}`; tolerant of missing/corrupt file |
| `FinnhubNewsClient` | `GET /company-news` fetch | `FinNewsArticle` record; header `X-Finnhub-Token`; timeout 20s |
| `NewsPoller` | Scheduled driver | Fixed watchlist `NVDA, MSFT, AAPL, AMZN, META, GOOGL, TSLA`; 5-min poll; 2-day lookback; requires `FINNHUB_API_KEY` |

### `SentimentCli` JSON output schema

```
{
  "headline": "…",
  "source": "finbert" | "both",
  "ollama_error": "…",            // only when deep failed
  "cross_check": { "used": bool, "sentiment": "…", "confidence": 1.0,
                   "finbert_sentiment": "…", "agreement": bool },
  "finbert":  { "sentiment": "BULLISH|BEARISH|NEUTRAL", "score": d, "confidence": d },
  "ollama":   { "used": bool, "sentiment": "…", "score": d, "confidence": d,
                 "entities": [{"ticker","name"}], "key_drivers": [..], "caveats": [..] }
}
```

---

## 3. Prerequisites

- **Java 21+** and **Maven 3.9+** (if `mvn` missing, use `~/.hermes/tools/apache-maven-3.9.6/bin/mvn`).
- **DJL / FinBERT** requires `--enable-native-access=ALL-UNNAMED` → pass via `MAVEN_OPTS` (in every template).
- For anything reaching **Ollama**, a server must run. Package default is `http://localhost:11434` (stock port). Models: `gemma4-e4b-64k:latest` (deep) and `fingpt-sentiment` (cross-check).
- Offline-safe: `mvn test`, `SeenStoreTest`, fast-pass CLI runs.

---

## 4. Running the CLI

### 4.1 General template

```bash
MAVEN_OPTS="--enable-native-access=ALL-UNNAMED" \
mvn -q compile exec:java \
  -Dexec.mainClass=com.openclaw.bot.sentiment.SentimentCli \
  -Dorg.slf4j.simpleLogger.defaultLogLevel=info \
  -Dexec.args="<ARGS>"
```

### 4.2 Examples

```bash
# Single headline (fast pass)
MAVEN_OPTS="--enable-native-access=ALL-UNNAMED" mvn -q compile exec:java \
  -Dexec.mainClass=com.openclaw.bot.sentiment.SentimentCli \
  -Dorg.slf4j.simpleLogger.defaultLogLevel=info \
  -Dexec.args="--text 'Tesla beats Q3 delivery estimates by 12%, but supply chain issues loom.'"

# Force deep + JSON + cross-check (reference)
MAVEN_OPTS="--enable-native-access=ALL-UNNAMED" mvn -q compile exec:java \
  -Dexec.mainClass=com.openclaw.bot.sentiment.SentimentCli \
  -Dorg.slf4j.simpleLogger.defaultLogLevel=info \
  -Dexec.args="--text 'Tesla beats Q3 delivery estimates by 12%, but supply chain issues loom.' --force-deep --num-ctx 65536 --cross-check --json"

# File / batch
mvn -q compile exec:java -Dexec.mainClass=com.openclaw.bot.sentiment.SentimentCli \
  -Dexec.args="--file headlines.txt --deep --json"
mvn -q compile exec:java -Dexec.mainClass=com.openclaw.bot.sentiment.SentimentCli \
  -Dexec.args="--batch headlines.txt --deep --json --verbose"

# Plain java -cp (after build)
java -cp target/classes:$(cat cp.txt) com.openclaw.bot.sentiment.SentimentCli \
  --text "Fed signals rate cut, markets rally"
```

---

## 5. CLI Flags

| Flag | Meaning | Default |
|------|---------|---------|
| `--text "<headline>"` | Analyze a single headline | — |
| `--file <path>` | Read headline text from a file | — |
| `--batch <path>` | Analyze a file of headlines (one per line; emits a JSON array) | — |
| `--deep` | Enable Ollama escalation (opt-in); escalates only when FinBERT confidence < threshold | off |
| `--force-deep` | Always escalate to Ollama | off |
| `--cross-check` / `--voting` | Run FinGPT cross-check; auto-escalate on **opposite-side** disagreement | off |
| `--json` | Machine-readable JSON on stdout (safe for `jq`) | off (human) |
| `--min-confidence <d>` | FinBERT confidence threshold | `0.65` |
| `--model <name>` | Ollama model for deep analysis | `gemma4-e4b-64k:latest` |
| `--fingpt-model <name>` | Ollama model for FinGPT cross-check | `fingpt-sentiment` |
| `--base-url <url>` | Ollama server URL | `http://localhost:11434` |
| `--model-path <dir>` | FinBERT model directory | `~/models/finbert` |
| `--num-ctx <n>` | Ollama context window override (≥ 2048) | auto by model tag |
| `--verbose` | Print Ollama fallback/skip warnings to stderr | off |

> **Context auto-selection:** `--num-ctx` wins; otherwise models tagged `64k` → `65536`, others → `8192`.
> ```bash
> # Auto: 64k for gemma4-e4b-64k, 8k for qwen3.5:9b
> mvn -q compile exec:java -Dexec.mainClass=com.openclaw.bot.sentiment.SentimentCli \
>   -Dexec.args="--text '...' --force-deep"
> # Force 64k on any model:
> mvn -q compile exec:java -Dexec.mainClass=com.openclaw.bot.sentiment.SentimentCli \
>   -Dexec.args="--text '...' --force-deep --num-ctx 65536"
> # Force 32k:
> mvn -q compile exec:java -Dexec.mainClass=com.openclaw.bot.sentiment.SentimentCli \
>   -Dexec.args="--text '...' --force-deep --num-ctx 32768"
> ```

---

## 6. Expected Output

Fast pass only:

```
FinBERT   : BULLISH (score=0.920, confidence=0.920)
(no escalation: confidence 0.920 >= 0.650)
```

With `--deep --force-deep`:

```
FinBERT   : BULLISH (score=0.920, confidence=0.920)
Escalated to Ollama (gemma4-e4b-64k:latest)
Ollama deep analysis:
  sentiment   : bullish (score=0.550, confidence=0.700)
  entities    : [Entity[ticker=TSLA, name=Tesla]]
  key drivers : [record Q3 deliveries beating estimates by 12%]
  caveats     : [supply chain issues may weigh on Q4 margins]
```

With `--json`, output follows the schema in §2 (single object, or JSON array in batch mode).

---

## 7. Development & Testing Guide

> All CLI scenarios use the §4.1 template (with your `<ARGS>`).

### 7.1 Scenario matrix — `SentimentCli`

#### A. Fast pass (no LLM, offline-capable)
| Scenario | `-Dexec.args="…"` | Expected |
|----------|--------------------|----------|
| Single headline, human | `--text 'Apple beats earnings estimates'` | FinBERT line; no escalation |
| Single headline, JSON | `--text 'Apple beats earnings estimates' --json` | `source:"finbert"`, `ollama.used:false` |
| Low confidence escalates | `--text 'Apple beats earnings estimates' --deep --min-confidence 0.99` | Escalates (0.920 < 0.99) |
| Above threshold, no escalation | `--text 'Apple beats earnings estimates' --deep --min-confidence 0.1` | `(no escalation: confidence …)` |

#### B. Deep analysis (requires Ollama)
| Scenario | Command | Expected |
|----------|---------|----------|
| Deep enable | `--text 'Fed signals rate cut, markets rally' --deep --json` | `ollama.used:true`, entities/drivers/caveats populated |
| Force-deep + cross-check (reference) | `--text 'Tesla beats Q3 delivery estimates by 12%, but supply chain issues loom.' --force-deep --num-ctx 65536 --cross-check` | Deep runs; `cross_check` populated; `num_ctx=65536` |
| Explicit context override | `--text '…' --force-deep --num-ctx 8192` | `num_ctx=8192` |
| Model / endpoint override | `--text '…' --force-deep --model qwen3.5:9b --base-url http://localhost:11434` | Uses qwen3.5:9b |
| Verbose | `--text '…' --deep --verbose` | Fallback warnings on stderr |

#### C. Cross-check & voting
| Scenario | Command | Expected |
|----------|---------|----------|
| Cross-check enabled | `--text '…' --cross-check --json` | `cross_check.used:true`, agreement flag |
| Voting alias | `--text '…' --voting --json` | Same as `--cross-check` |
| Opposite-side disagreement | `--text '…' --cross-check` (no `--deep`) | Auto-escalates |
| Neutral disagreement | `--text '…' --cross-check` | No escalation (neutral never forces) |

#### D. File & batch
| Scenario | Command | Expected |
|----------|---------|----------|
| File input | `--file news.txt --deep --json` | Single JSON object |
| Batch JSON array | `--batch headlines.txt --deep --json --verbose` | JSON array; per-line `error` on failure |
| Blank lines | prepare `headlines.txt` with blanks | Blank lines filtered |

#### E. Error & boundary paths
| Scenario | Command | Expected |
|----------|---------|----------|
| No text/file/batch | *(empty args)* | Usage printed, exit code `2` |
| Missing input file | `--file does-not-exist.txt` | "Cannot read file" + usage, exit `2` |
| Missing batch file | `--batch does-not-exist.txt` | "Cannot read batch file", exit `2` |
| Invalid `--num-ctx` | `--text '…' --num-ctx 100` | "Invalid --num-ctx value", usage, exit `2` |
| Ollama down (deep) | `--text '…' --force-deep` (server stopped) | Falls back to FinBERT, exit `0`, `ollama_error` set |
| Empty LLM content | `--text '…' --force-deep` (stubbed empty response) | Retry with 8192 budget; still empty → `OllamaException` → FinBERT fallback |

### 7.2 `SeenStore` self-test (offline)

```bash
MAVEN_OPTS="--enable-native-access=ALL-UNNAMED" \
mvn -q compile exec:java \
  -Dexec.mainClass=com.openclaw.bot.sentiment.SeenStoreTest
```

Expected: `All SeenStore tests passed.` (load/save round-trip, idempotency, missing-file, corrupt-file).

### 7.3 `NewsPoller` (live integration)

```bash
FINNHUB_API_KEY=<your-key> \
MAVEN_OPTS="--enable-native-access=ALL-UNNAMED" \
mvn -q compile exec:java \
  -Dexec.mainClass=com.openclaw.bot.sentiment.NewsPoller
```

Expected: rolling per-symbol line `NVDA: new=… deep=… skipped=… disagreements=… score=…` every 5 min; exits `2` without `FINNHUB_API_KEY`.

### 7.4 Automated unit suite

```bash
mvn test
```

(JUnit 5 suite — offline-safe.)

### 7.5 Verification checklist

- [ ] `mvn test` green; `SeenStoreTest` passes standalone.
- [ ] `SentimentCli --text '<headline>' --json` returns `source:"finbert"` when no escalation.
- [ ] Force-deep with Ollama up → populated `ollama` object; with Ollama down → `ollama_error` + FinBERT fallback.
- [ ] `--cross-check` sets `cross_check.used:true`; opposite-sided disagreement escalates; neutral doesn't.
- [ ] Batch mode emits a well-formed JSON array (clean `jq` pipe).
- [ ] Invalid CLI inputs exit `2` with usage on stderr.
- [ ] `NewsPoller` starts (or exits `2` without key) and prints per-symbol scores.

---

## 8. Known Pitfalls & Conventions

- **Port/model:** the package defaults to Ollama on **11434** with `gemma4-e4b-64k:latest` (deep) and `fingpt-sentiment` (cross-check). The bot's dedicated server is on **11435** — do not conflate.
- **Batch inefficiency:** the CLI's `runBatch` constructs a fresh `FinBertSentiment` per line (model reload per headline). `FinBertSentiment.analyzeBatch(List)` exists and shares one predictor — use it if optimizing batch throughput.
- **`think=false` + retry:** required for thinking models (empty-content risk). Keep `num_predict >= 500` for narratives.
- Do **not** rely on `--concurrency N` (documented in planning docs but NOT implemented — batch is sequential).
- No credentials in this repo: `FINNHUB_API_KEY` / `SMTP_PASSWORD` are environment-only.