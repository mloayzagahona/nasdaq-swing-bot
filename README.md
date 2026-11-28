# OpenClaw Nasdaq Swing Bot

A **Java 25** proof-of-concept bot that polls a Nasdaq index, detects *confirmed swing highs/lows* with a fractal rule, narrates each event with a **local Ollama model**, emails an alert, and persists state across restarts. Fully self-contained (Maven shade fat jar); no cloud AI dependencies.

**Build:** JDK 25 / Maven `release=25`
**Main class:** `com.openclaw.bot.Main`
**Jar:** `target/nasdaq-swing-bot-0.1.0.jar` (self-contained)

---

## 1. Architecture Map

```
src/main/java/com/openclaw/bot/
  Main.java                   # entry point: arg parsing → wiring → bot.run()
  config/BotConfig.java       # flag > env > default; immutable builder
  market/                     # MarketDataProvider abstraction + 4 providers
    MarketDataProvider.java   #   interface { quote(); hasNext(); }
    Quote.java                #   record: symbol, last, prevClose, dayHigh/Low, ts, status
    Sample.java               #   record: millis, price
    NasdaqApiClient.java      #   live default (api.nasdaq.com, index assetclass)
    YahooFinanceClient.java   #   alternative (rate-limited on DC IPs)
    FinnhubClient.java        #   alternative (requires API key, COMP→^IXIC mapping)
    CsvReplayProvider.java    #   deterministic offline replay (millis,price CSV)
  swing/
    SwingDetector.java        # fractal rule: confirm bars left/right + minDeltaPct
    SwingEvent.java           # record (Type HIGH/LOW, price, atMillis, prev, deltaPct) + fingerprint
  ai/
    AiNarrator.java           # interface { narrate(prompt) }
    OllamaClient.java         # local Ollama /api/generate (think=false, num_predict=500)
  email/
    EmailSender.java          # interface { send(subject, body) }
    SmtpEmailSender.java      # real Jakarta Mail (STARTTLS/SSL by port)
    FileEmailSender.java      # offline RFC-822 .eml fallback (never drops alerts)
    DebugSmtpSink.java        # loopback SMTP sink for --demo
  state/StateStore.java       # JSON persistence (extremes, alert fingerprint, ticks)
  bot/NasdaqBot.java          # tick loop: fetch → detect → narrate → email → persist
  sentiment/                  # standalone sentiment subsystem (see §6)
```

### Core data flow (one tick)

```
MarketDataProvider.quote()
      │  Quote
      ▼
SwingDetector.accept(Sample)      → Optional<SwingEvent>
      │  when confirmed new extreme beats previous by ≥ minDeltaPct
      ▼
NasdaqBot.handleEvent(SwingEvent, Quote)
      │  buildPrompt() (concise, ~150 tokens, "no markdown")
      ▼
AiNarrator.narrate(prompt)        → narrative text (Ollama, local)
      ▼
EmailSender.send(subject, body)   → SMTP | loopback sink | .eml file
      │
      ▼
StateStore.recordExtremes / recordAlert(fingerprint) → persist to JSON
```

---

## 2. Swing Detection (the core rule)

`SwingDetector` implements a **fractal rule**:

- A bar is a swing **HIGH** if it is greater than the `confirm` bars before *and* `confirm` bars after it; symmetric for LOW.
- Default `confirm = 2` → a pivot needs `2*confirm+1 = 5` samples.
- A **trigger/alert** fires only when a confirmed extremum *strictly beats* the previously confirmed extreme of the same type by at least `minDeltaPct` (default 0.05%).
- Non-decisive confirmed extremes are remembered (not alerted) to suppress noise.
- State is **stateless across restarts** via `StateStore`: extremes are restored on boot so the bot never re-alerts on levels it already signaled.

**Tests:** `src/test/java/com/openclaw/bot/swing/SwingDetectorTest.java` — confirmed high/low detection, `minDelta` filtering of small new extremes, no duplicate event on peak-revisit below prior extreme.

---

## 3. Market Data Providers (`MarketDataProvider`)

| Provider | Default? | Source | Notes |
|----------|:-------:|-------|-------|
| `NasdaqApiClient` | ✅ | `api.nasdaq.com` (`GET /api/quote/{symbol}/info?assetclass=index`) | Robust number parsing (`"26,214.17"`, `"--"`→NaN). Index symbols (`COMP`, `^IXIC`). |
| `YahooFinanceClient` | — | Yahoo chart v8 API | Kept for whitelisted environments; Yahoo frequently rate-limits datacenter IPs → prefer Nasdaq. |
| `FinnhubClient` | — | `finnhub.io/api/v1/quote` | Requires API key (`FINNHUB_API_KEY`); maps `COMP`→`^IXIC`, `NDX`→`^NDX`. |
| `CsvReplayProvider` | — | CSV file | Deterministic offline demos/tests. Lines: `epochMillis,price` or `yyyy-MM-dd'T'HH:mm:ss,price`; `#` comments ignored. `hasNext()` false when exhausted. |

Choose via `--provider NASDAQ|YAHOO|FINNHUB|CSV` (default NASDAQ) or `--demo`/`--replay`.

---

## 4. AI Narration (OllamaClient)

- Calls the **local** Ollama server (`/api/generate`) — no cloud LLM.
- Sends `"think": false` — critical for gemma4/qwen3.5 family models that otherwise spend their whole `num_predict` budget on hidden chain-of-thought and return an empty `response`.
- `num_predict = 500`; temperature from config; empty response → `IOException`.
- Model default: `gemma4-e4b-64k:latest` (fallback `gemma3:4b`); server on `127.0.0.1:11435` (dedicated) or default `11434`.

---

## 5. Email Delivery & Fail-Safes

| Mode | When | Delivery |
|------|------|----------|
| `SmtpEmailSender` | `--smtp-host` set | Real Jakarta Mail; SSL on port 465, else STARTTLS; auth when user set |
| `DebugSmtpSink` | `--demo` | Loopback SMTP sink on 2525 + real SMTP client exercising the actual delivery path; logs to `capture/debug-smtp.log` |
| `FileEmailSender` | no SMTP, not demo | Writes RFC-822 `.eml` files to `capture/` — **the bot never drops an alert silently** |

---

## 6. Sentiment Subpackage (`com.openclaw.bot.sentiment`)

A **standalone subsystem** — not called by the bot's tick loop (`NasdaqBot` only mentions "sentiment" inside the narrative prompt).

| Class | Role |
|-------|------|
| `SentimentCli` | CLI: single `--text` / `--file` / `--batch`; `--json` for jq; `--deep`/`--force-deep`/`--cross-check` escalation |
| `FinBertSentiment` | Local DJL FinBERT classifier (fast pass) |
| `OllamaSentimentAnalyzer` | Deep LLM analysis (structured JSON: sentiment, score, entities, key_drivers, caveats); empty-content retry |
| `FinGptSentiment` | Optional cross-check classifier (disagreement → auto-escalate) |
| `SentimentAggregator` | Time-decayed rolling score (60-min half-life, 24h window); `DeepBudget` gate |
| `SeenStore` | Persistent article dedupe |
| `NewsPoller` | Standalone polling loop (Finnhub news → ingest → per-symbol score); requires `FINNHUB_API_KEY` |
| `SeenStoreTest` | Offline self-test |

> ⚠️ **Port/model pitfall:** the sentiment package defaults to Ollama on **11434** with `gemma4-e4b-64k:latest`; the bot's dedicated server runs on **11435**. `FinBertSentiment` reloads its model per headline in batch mode (known inefficiency).

See `src/main/java/com/openclaw/bot/sentiment/README.md` for the full sentiment runbook and test matrix.

---

## 7. Configuration (precedence: `--flag` > env > default)

| Flag | Env | Default | Meaning |
|------|-----|---------|---------|
| `--symbol SYM` | — | `COMP` | Index symbol |
| `--interval SEC` | — | `60` | Poll interval (seconds) |
| `--once` | — | off | Single tick then exit (cron/smoke) |
| `--demo` | — | off | Replay + loopback SMTP sink (no real email) |
| `--replay FILE` | — | — | CSV for demo/CSV provider |
| `--provider NAME` | — | `NASDAQ` | `NASDAQ|YAHOO|FINNHUB|CSV` |
| `--finnhub-key KEY` | `FINNHUB_API_KEY` | — | Required for FINNHUB |
| `--model MODEL` | — | `gemma4-e4b-64k:latest` | Ollama model tag |
| `--ollama-url URL` | — | `http://localhost:11434` | Ollama base URL |
| `--confirm N` | — | `2` | Swing confirmation bars |
| `--min-delta-pct PCT` | — | `0.05` | Min % vs previous extreme to alert |
| `--smtp-host/port/user` | `SMTP_HOST/PORT/USERNAME` | — | SMTP settings |
| `--from / --to` | `MAIL_FROM / MAIL_TO` | — | Alert sender/recipients |
| `--state FILE` | — | `state/bot-state.json` | State file |
| `--capture-dir DIR` | — | `capture` | Offline alert capture dir |

---

## 8. Build, Test & Run

```bash
# Build (fat jar)
mvn -q -DskipTests package          # → target/nasdaq-swing-bot-0.1.0.jar
# or use full mvn path if not on PATH:
# ~/.hermes/tools/apache-maven-3.9.6/bin/mvn

# Tests (JUnit 5; offline-safe — no network / Ollama)
mvn test

# Offline demo (no keys, no real email)
java -jar target/nasdaq-swing-bot-0.1.0.jar --demo \
  --replay data/replay.csv --ollama-url http://127.0.0.1:11435

# Live
java -jar target/nasdaq-swing-bot-0.1.0.jar \
  --ollama-url http://127.0.0.1:11435 --symbol COMP

# One tick (smoke / cron)
java -jar target/nasdaq-swing-bot-0.1.0.jar --once

# Real SMTP
SMTP_HOST=smtp.gmail.com SMTP_PORT=587 SMTP_USERNAME=x SMTP_PASSWORD=... \
  MAIL_TO=me@corp.com java -jar target/nasdaq-swing-bot-0.1.0.jar
```

### Test suite

| File | Covers |
|------|--------|
| `SwingDetectorTest` | Fractal detection, minDelta filtering, duplicate suppression |
| `FinnhubClientTest` | Parsing, alias mapping (COMP→^IXIC), missing optional fields, API-key requirement |
| `DemoPipelineTest` | End-to-end demo with stub narrator + replay data |
| `SeenStoreTest` (sentiment) | Seen-store persistence, round-trip, corrupt/missing file tolerance |

---

## 9. State & Persistence

`StateStore` persists (JSON, corrupt/missing → fresh state):

- `lastSwingHigh` / `lastSwingLow` (+ timestamps) — restore extremes across restarts
- `lastAlertFingerprint` — duplicate-alert suppression (`SwingEvent.fingerprint()` = `TYPE@price#atMillis`, 4-dp rounded)
- `lastTickMillis`, `samplesSeen` — counters

> ℹ️ **Do not commit** `state/bot-state.json` or `capture/` artifacts. No credentials in code/README — SMTP password and API keys are env-only.

---

## 10. Conventions & Pitfalls

- **`think=false` is mandatory** in both `OllamaClient` and `OllamaSentimentAnalyzer` — thinking models return empty responses otherwise. Keep `num_predict ≥ 500` for narratives.
- **No markdown in narratives** — prompt instructs plain text; some models (`mimo-v2.6`) emit `**bold**` anyway.
- Keep `buildPrompt` concise (~150 tokens).
- Tests must not require network or a live Ollama server.
- Avoid `sun.misc.Unsafe`-style deprecation warnings (warnings only; build stays green).

---

## 11. Related Docs

- `src/main/java/com/openclaw/bot/sentiment/README.md` — sentiment package runbook + scenario test matrix
- `docs/TECHNICAL.md` — detailed design
- `docs/model-benchmarks.md` — Ollama model measurements & selection
- `openclaw/plugin/handlers.py` — OpenClaw plugin (start/stop/status/alert_tail)
- `openclaw/skill/SKILL.md` — operator runbook
