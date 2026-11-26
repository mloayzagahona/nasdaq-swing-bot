# Technical Document — OpenClaw Nasdaq Swing Bot (PoC)

**Version:** 0.1.0 · **Date:** 2026-11-24 · **Status:** working PoC (tests green, live demo run)

---

## 1. Purpose

A proof-of-concept AI bot that:

1. polls the **Nasdaq Index** (default `COMP`, the Composite — nearest match to
   "Nasdaq Index"; `NDX` also supported) every minute;
2. detects **new swing highs/lows** using a fractal swing rule;
3. uses a **local Ollama LLM** to write a short narrative for each alert;
4. **emails** the alert, or falls back to writing `.eml` files when no SMTP
   server is configured;
5. **persists state** so restarts don't re-alert on already-confirmed swings.

The bot is written in **Java 21** (built with JDK 25, Maven target `release=21`)
and is operated from **OpenClaw** via a thin plugin layer (OpenClaw has no Java
SDK upstream; the plugin shells out to the packaged jar).

---

## 2. Architecture

```
                 ┌──────────────────────────────────────────────────────┐
                 │                        OpenClaw                     │
                 │  openclaw/plugin/handlers.py  (start/stop/status)    │
                 └───────────────┬──────────────────────────────────────┘
                                 │ shell-out (java -jar)
                                 ▼
┌───────────────────────────────────────────────────────────────────────────┐
│                        Java application (JVM)                            │
│                                                                           │
│  Main ──► BotConfig (env/arg precedence)                                  │
│    │                                                                      │
│    ├─► MarketDataProvider <interface>                                     │
│    │     ├─ NasdaqApiClient      (live, api.nasdaq.com)                   │
│    │     ├─ YahooFinanceClient    (fallback; rate-limited from this box)  │
│    │     ├─ FinnhubClient       (live realtime; finnhub.io, needs key)   │
│    │     └─ CsvReplayProvider     (offline replay for demos/tests)        │
│    │                                                                      │
│    ├─► SwingDetector  (fractal rule, confirm=2, minDeltaPct=0.05)        │
│    │     └─ emits SwingEvent (HIGH/LOW, price, at, previousExtreme)       │
│    │                                                                      │
│    ├─► AiNarrator <interface>                                             │
│    │     └─ OllamaClient (POST /api/generate on local server)             │
│    │                                                                      │
│    ├─► EmailSender <interface>                                            │
│    │     ├─ SmtpEmailSender   (Jakarta Mail / real SMTP)                  │
│    │     ├─ FileEmailSender    (.eml capture when SMTP unset)             │
│    │     └─ DebugSmtpSink     (loopback server used by --demo)            │
│    │                                                                      │
│    └─► StateStore (JSON: extremes, last alert fingerprint, ticks)         │
└───────────────────────────────────────────────────────────────────────────┘
```

**Tick loop (scheduled, single thread):** fetch quote → feed `Sample` to
detector → if `SwingEvent` confirmed, ask Ollama for a narrative → send email →
persist extremes + tick. `--demo` replays at a fast cadence; live mode defaults
to 60 s.

---

## 3. Swing detection rule

Fractal rule implemented in `SwingDetector`:

- A bar at index *i* is a swing **HIGH** if its price is strictly greater than
  the `confirm` bars before and after it (default `confirm = 2`).
- A swing **LOW** is the mirror image.
- A confirmed extremum becomes an **event only if it beats the previous
  confirmed extreme of the same type by at least `minDeltaPct`** (default
  0.05 %). Otherwise it only updates the internal baseline.
- Extremes are tracked separately for high and low; each event records
  `previousExtreme` and `deltaPct` for the email.

The synthetic series in `data/replay.csv` yields **4 events**, which the tests
assert:

| # | Type  | Price  | Notes |
|---|-------|--------|-------|
| 1 | HIGH  | 105.00 | first confirmed pivot |
| 2 | LOW   | 98.60  | lower than prior low |
| 3 | HIGH  | 112.10 | beats 105.00 |
| 4 | HIGH  | 113.10 | beats 112.10 |

> Earlier hand-traces predicted 5 events; the tests falsified that and the
> expected trace was corrected (see `SwingDetectorTest` comments).

---

## 4. Market data source

| Source | Endpoint | Status on this box |
|---|---|---|
| **Nasdaq public API** | `https://api.nasdaq.com/api/quote/{SYM}/info?assetclass=index` | ✅ works (needs browser UA header) |
| Nasdaq historical | `.../api/quote/{SYM}/chart?assetclass=index` | ✅ works (daily OHLC rows) |
| Yahoo Finance | `query1.finance.yahoo.com/v8/finance/chart/...` | ❌ `Too Many Requests` |
| CNBC | quote webservice | ❌ `Access Denied` (Akamai) |
| Stooq | `q/d/l/?s=^ixic&i=5` | ❌ blocked/no data |
| Nasdaq intraday | `.../api/quote/{SYM}/intraday` | ❌ empty (no intraday series exposed) |
| **Finnhub** (realtime) | `https://finnhub.io/api/v1/quote?symbol=^IXIC&token=KEY` | ✅ code integrated; needs free API key (env `FINNHUB_API_KEY`) |

**Finnhub integration** — selected by the operator as the realtime replacement for
the delayed Nasdaq snapshot. `FinnhubClient` implements `MarketDataProvider`
(`src/main/java/com/openclaw/bot/market/FinnhubClient.java`):

- Index aliases: `COMP` → `^IXIC` (Nasdaq Composite), `NDX` → `^NDX`
  (Nasdaq-100); any other symbol is passed through verbatim.
- Quote endpoint returns `{"c":last,"h":high,"l":low,"o":open,"pc":prevClose,"t":epochSec}`;
  absent/null fields tolerated (`prevClose` NaN, high/low fall back to last).
- The wire symbol is URL-encoded for the query string (Java's `URI` rejects a raw `^`).
- Free tier is 30 calls/min — the bot's 1/min cadence is well within that.
- **No credentials are stored in code.** The key is read from env
  `FINNHUB_API_KEY` or `--finnhub-key KEY` (CLI) and held in `BotConfig` memory only.
- Select with `--provider FINNHUB` (default remains `NASDAQ`); a missing key is a
  hard startup error, not a silent fallback.

**Consequences**

- Live polling hits the quote endpoint every minute; swing detection accumulates
  the stream of samples.
- There is no public intraday minutes endpoint from this host, so “swing” here
  operates on the polled price stream, not on true 1-minute bars.
- The Nasdaq quote JSON flags `"isRealTime": false` — the snapshot is delayed;
  fine for a PoC, worth noting in requirements for production.
- Provider abstraction exists (`MarketDataProvider`); swapping in a licensed
  feed is a one-class change.

---

## 5. Local Ollama setup (CPU-only)

The machine's GPU is nearly full (≈9.1/10.2 GiB in use) and `llama-server`
throws CUDA OOM on the default port **11434**:

```
cudaMalloc failed: out of memory
failed to allocate CUDA0 buffer of size 538972160
```

The working solution is a **CPU-only server on 127.0.0.1:11435**:

```bash
OLLAMA_NUM_GPU=0 \
OLLAMA_MODELS=/usr/share/ollama/.ollama/models \
OLLAMA_HOST=127.0.0.1:11435 \
ollama serve
```

**Store discovery pitfall.** The system daemon runs as user `ollama` and stores
models in `/usr/share/ollama/.ollama/models` (world-readable/writable), *not*
under `~/.ollama/models` — pointing `OLLAMA_MODELS` at the empty home dir makes
the server report `total blobs: 0` and no models. Settings `OLLAMA_MODELS` to
the system store fixed model discovery (23 models visible).

**Verified inference** (CPU): `gemma3:4b` answers a generation request in
~27 s including first-load; per-token eval is fast. Other local models include
`qwen3.5:4b`, `llama3.2:3b`, `phi4-mini`, `hermes3:8b`, `mimo-v2.6:9b`, etc.

---

## 6. Email path

- `SmtpEmailSender` — real SMTP via Jakarta Mail; host/port/user/password/from/to
  come from config (see §8). Port 465 uses implicit SSL; otherwise STARTTLS.
- `FileEmailSender` — used when no SMTP host is set: writes RFC-822-style
  `.eml` files under `capture/` with millisecond-timestamped filenames plus a
  collision suffix, so alerts within the same minute never clobber each other.
- `DebugSmtpSink` — loopback SMTP server (port 2525). In `--demo` mode `Main`
  wires the **real** `SmtpEmailSender` client to this sink, so the demo
  exercises the actual SMTP dialogue (EHLO/MAIL/RCPT/DATA/QUIT) end-to-end
  without external credentials.

**Credentials.** No SMTP credentials exist for this environment; the bot
therefore runs in capture mode by default. Set `SMTP_HOST/PORT/USERNAME/
PASSWORD/MAIL_FROM/MAIL_TO` (or CLI flags) to enable real delivery.

---

## 7. State & restart semantics

`StateStore` writes `state/bot-state.json` after every relevant tick:

- `lastSwingHigh/lastSwingLow` + timestamps — last confirmed extremes;
- `lastAlertFingerprint` — `TYPE@price#epochMillis` of the last alert, used to
  suppress duplicate emails for the same confirmed swing;
- `lastTickMillis`, `samplesSeen` — observability.

**Known caveat:** the detector’s confirmation window is in-memory, so a restart
does not replay the full price history; only the extremes are restored. The bot
will not re-alert on the *same* fingerprint, but a brand-new swing shortly after
restart is computed from a cold window (next `confirm` bars re-baseline). If
replay-across-restart behavior is required, seed the detector from the Nasdaq
historical endpoint on startup (a natural next step).

---

## 8. Configuration reference

Precedence: JVM arg → environment variable → default.

| CLI flag | Env var | Default | Meaning |
|---|---|---|---|
| `--symbol` | `SYMBOL` | `COMP` | Index: `COMP` or `NDX` |
| `--provider` | `PROVIDER` | `NASDAQ` | `NASDAQ` \| `YAHOO` \| `FINNHUB` \| `CSV` |
| `--finnhub-key` | `FINNHUB_API_KEY` | (empty) | Finnhub API key; required when provider is `FINNHUB` |
| `--interval` | `INTERVAL_SECONDS` | `60` | Live polling period (s) |
| `--once` | — | off | Single tick then exit |
| `--demo` | — | off | Replay `--replay` file; fast cadence; loopback SMTP sink |
| `--replay` | `REPLAY_FILE` | — | Path to CSV (`millis,price` or ISO-8601) |
| `--model` | `OLLAMA_MODEL` | `gemma4-e4b-64k:latest` | Ollama model (thinking disabled via `think=false`) |
| `--ollama-url` | `OLLAMA_BASE_URL` | `http://localhost:11434` | Ollama server (use `http://127.0.0.1:11435` here) |
| `--smtp-host/port/user/password/from/to` | `SMTP_*`, `MAIL_FROM`, `MAIL_TO` | empty | Real email delivery |
| `--swing-confirm` | `SWING_CONFIRM` | `2` | Bars before/after to confirm |
| `--swing-min-delta` | `SWING_MIN_DELTA_PCT` | `0.05` | Minimum % move to trigger |

---

## 9. Build, test, run

```bash
# Build (JDK 25, target 21)
mvn -q -DskipTests package          # → target/nasdaq-swing-bot-0.1.0.jar

# Tests (4/4 green: unit + end-to-end)
mvn test

# Offline demo (replay + real SMTP client against loopback sink + local Ollama)
java -jar target/nasdaq-swing-bot-0.1.0.jar --demo \
  --replay data/replay.csv --ollama-url http://127.0.0.1:11435 --model gemma3:4b

# Live (every 60 s)
java -jar target/nasdaq-swing-bot-0.1.0.jar --symbol COMP \
  --ollama-url http://127.0.0.1:11435 --model gemma3:4b
```

**Verified live demo output (2026-11-24):** all four expected swing events
detected; each produced a local OLLama narrative; three SMTP-delivered
messages captured in `capture/debug-smtp.log` with correct subject lines
(`[NASDAQ Swing Alert] NEW SWING HIGH COMP @ 105.00`, etc.); final state file
shows extremes and last fingerprint.

---

## 10. Test matrix

| Test | What it proves |
|---|---|
| `SwingDetectorTest` | Fractal rule: event count/types/prices on synthetic series; delta% invariant |
| `DemoPipelineTest` | Full offline pipeline emits exactly one `.eml` per swing event; body contains event type and latest price |

---

## 11. Limitations & next steps

**Limitations**

- Nasdaq quote is delayed (`isRealTime: false`); no intraday endpoint available.
- Swing rule is a simple fractal; no trend/volume context.
- Detector window is in-memory (see §7).
- OpenClaw integration is shell-out based — there is no published Java SDK.
- CPU inference is slower than GPU; 4b model is fine at 1-minute cadence.

**Next steps**

1. Seed detector on startup from Nasdaq historical daily bars.
2. Wire real SMTP credentials (env/secret manager) and test external delivery.
3. Add a second signal source (e.g., licensed intraday feed) for true 1-minute
   bars.
4. Move OpenClaw plugin to a native integration if an SDK ships.
5. Package as a systemd unit / container with health checks.
6. Add alert throttling (per-day cap) and a simple dashboard.

---

## 12. Security & data notes

- No API keys or credentials are required for the Nasdaq public API; a browser
  User-Agent header is needed.
- No SMTP credentials are stored or invented — delivery is opt-in via config.
- All local processing: model inference happens on this machine; no market or
  email data leaves the host except the Nasdaq API poll itself.
- Demo artifacts (`capture/`, `state/`) are git-ignored.
