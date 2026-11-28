# Sequence Diagrams — Nasdaq Swing Bot

Sequence diagrams for the OpenClaw Nasdaq Swing Bot, drawn from the source
(`NasdaqBot.run/tick/handleEvent`, `StateStore`, `SwingDetector`,
`OllamaClient`, and the sentiment subsystem). Mermaid blocks render on GitHub
and [mermaid.live](https://mermaid.live); plain ASCII equivalents are included
for viewers without a Mermaid renderer.

---

## 1. Main bot — startup + tick loop

Mermaid:

```mermaid
sequenceDiagram
    autonumber
    participant U as User / cron
    participant M as Main
    participant B as NasdaqBot
    participant S as Scheduler (ExecutorService)
    participant MD as MarketDataProvider<br/>(NasdaqApi / Finnhub / Yahoo / CsvReplay)
    participant SD as SwingDetector
    participant AI as AiNarrator<br/>(OllamaClient)
    participant E as EmailSender<br/>(SMTP / File .eml / Debug sink)
    participant ST as StateStore

    U->>M: java -jar ... --symbol COMP (--once or --demo or --provider ...)
    M->>M: BotConfig.builder() — flag wins over env, env over default
    M->>MD: new provider(symbol)
    M->>SD: new SwingDetector(confirm=2, minDelta=0.05%)
    M->>AI: new OllamaClient(baseUrl, model, timeout, temperature)
    M->>E: new SmtpEmailSender / FileEmailSender / DebugSmtpSink
    M->>ST: new StateStore(state/bot-state.json) → load()
    M->>B: new NasdaqBot(cfg, market, detector, narrator, email, state)
    B->>ST: lastSwingHigh/Low/At
    ST-->>B: previously persisted extremes
    B->>SD: restore(high, highAt, low, lowAt)
    B->>B: run() — scheduleAtFixedRate(safeTick, 0, period)

    loop every tick (60s live / 1.5s replay)
        S->>B: safeTick()
        B->>MD: quote()
        MD-->>B: Quote(last, prevClose, dayHigh, dayLow, ts)
        B->>SD: accept(new Sample(now, q.last()))
        alt no decisive swing (window too small or not beating previous extreme)
            SD-->>B: Optional.empty()
        else confirmed HIGH/LOW beats previous extreme by minDelta%
            SD-->>B: Optional.of(SwingEvent(type, price, at, prevExtreme, deltaPct))
        end
        B->>ST: recordExtremes(detector.lastHigh/Low/At) — every tick
        B->>ST: recordTick(now) → save() → bot-state.json
        alt event present
            B->>B: handleEvent(e, q)
            alt fingerprint == lastAlertFingerprint
                B->>B: suppress duplicate event
            else
                B->>B: buildPrompt(e, q) — ~150 tokens, "no markdown"
                B->>AI: narrate(prompt) — POST /api/generate with think=false, num_predict=500
                AI-->>B: narrative text
                B->>E: send(subject, body) — alert subject + narrative body
                E-->>B: delivered (SMTP / .eml / debug-smtp.log)
                B->>ST: recordAlert(fingerprint) → save()
            end
        end
        alt demo mode and replay exhausted
            B->>B: stop() — replay exhausted, shutdown scheduler
        end
    end
```

Plain ASCII equivalent:

```
User/cron   Main      NasdaqBot      MarketDataProvider   SwingDetector   AiNarrator(Ollama)   EmailSender      StateStore
   |          |          |               |                  |                |                   |                |
   |-- flags ->|          |               |                  |                |                   |                |
   |          |-- new provider --------->|                  |                |                   |                |
   |          |-- new detector --------------------------->|                |                   |                |
   |          |-- new narrator ------------------------------------------->|                   |                |
   |          |-- new email ---------------------------------------------------------------->|                |
   |          |-- new StateStore ----------------------------------------------------------------------------->|
   |          |          |-- restore(high/low from state) ------------------>|                   |                |
   |          |          |-- run(): scheduleAtFixedRate(safeTick)                             |                |
   |          |          |                                                                   |                |
   ========= loop: every interval (60s live / 1.5s replay) ================================================    |
   |          |  safeTick |          |                  |                |                   |                |
   |          |          |-- quote() ------------------>|                  |                |                |
   |          |          |<-- Quote --------------------|                  |                |                |
   |          |          |-- accept(Sample(now,last)) -------------------->|                |                |
   |          |          |                              |-- Optional.empty (no swing)                       |
   |          |          |                              |-- Optional.of(SwingEvent) if HIGH/LOW             |
   |          |          |-- recordExtremes(...) (every tick) --------------------------------------------->|
   |          |          |-- recordTick(now) -> save() ---------------------------------------------------->|
   |          |          |                                                                   |                |
   |          | [event]  |-- handleEvent(e,q)                                                    |                |
   |          |          |   [dup fingerprint? -> suppress]                                        |                |
   |          |          |-- buildPrompt(e,q) ~150 tokens, no markdown                             |                |
   |          |          |-- narrate(prompt) ----------------------------------------------------->|                |
   |          |          |                               |-- POST /api/generate {think:false,  |                |
   |          |          |                               |    num_predict:500} -> narrative     |                |
   |          |          |<-- narrative -------------------------------------------------------------|                |
   |          |          |-- send(subject, body) -------------------------------------------------------------->| |
   |          |          |<-- delivered -----------------------------------------------------------------------| |
   |          |          |-- recordAlert(fingerprint) ----------------------------------------------------->|  |
   |          |          |    ... next tick ...                                                            |  |
```

---

## 2. Sentiment subsystem (standalone — not in the tick loop)

`NewsPoller` micro-batch pipeline over Finnhub news:

```mermaid
sequenceDiagram
    autonumber
    participant P as NewsPoller
    participant FH as FinnhubNewsClient
    participant A as SentimentAggregator
    participant SE as SeenStore
    participant FB as FinBertSentiment
    participant FG as FinGptSentiment<br/>(cross-check)
    participant OL as OllamaSentimentAnalyzer<br/>(deep)

    P->>FH: companyNews(symbol, from=2d, to=today) with X-Finnhub-Token header
    FH-->>P: list of FinNewsArticle
    loop each watchlist symbol (NVDA, MSFT, …)
        P->>A: ingest(symbol, articles, IngestConfig)
        loop each headline
            A->>SE: alreadySeen(symbol, id, datetime)?
            alt seen
                SE-->>A: true → skip (dedupe)
            else unseen
                A->>FB: analyze(headline) → BULLISH/BEARISH/NEUTRAL
                FB-->>A: SentimentResult(score, confidence)
                opt cross-check enabled AND opposite-side disagreement
                    A->>FG: analyze(headline) → positive/negative/neutral
                    FG-->>A: FinGptResult
                end
                opt escalation allowed (force deep, low confidence, or disagreement) within DeepBudget
                    A->>OL: analyze(headline) → structured JSON
                    OL-->>A: DeepAnalysis (entities, drivers, caveats)
                end
                A->>SE: mark(symbol, id, datetime) then save()
                A->>A: update time-decayed score(symbol)
            end
        end
        P->>A: score(symbol) → rolling score for log line
    end
```

---

## 3. Key facts encoded in the diagrams

- **Period** comes from `BotConfig`: live = `intervalSeconds` (60s default);
  demo/CSV = `replayIntervalMillis` (1.5s default).
- **Fingerprint suppression** happens before narration: if
  `SwingEvent.fingerprint()` equals the stored `lastAlertFingerprint`, the
  alert is dropped.
- **State is written twice per tick**: `recordExtremes(...)` (every tick, so
  restarts never re-alert old levels) and `recordTick(now)` (persists + bumps
  `samplesSeen`).
- **OllamaClient** sends `think:false` and `num_predict:500` to avoid empty
  responses from thinking models; `buildPrompt` instructs "no markdown".
- **Email** is polymorphic: real SMTP (Jakarta Mail), `.eml` file fallback, or
  loopback debug sink — all satisfy `EmailSender.send(subject, body)`.
- The **sentiment subsystem** is fully standalone: `NasdaqBot` never calls it;
  the only link is that the narrative prompt asks the LLM to consider
  "sentiment" as a plausible cause.
