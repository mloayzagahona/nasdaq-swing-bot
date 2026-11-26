---
name: nasdaq-swing-bot
description: Run and manage the Java Nasdaq swing bot from OpenClaw (start/stop/status, alert tail).
version: 0.1.0
---

# Nasdaq Swing Bot (OpenClaw skill)

The PoC bot is a Java program. OpenClaw has no native Java SDK surface yet,
so this skill shells out to the packaged jar. It is a thin operational layer:
start, stop, status, and read captured alerts.

## Quick start

```bash
# Build the jar (from repo root)
mvn -q -DskipTests package

# Start the bot in demo/replay mode (fast, offline)
java -jar target/nasdaq-swing-bot-0.1.0.jar --demo --replay data/replay.csv \
  --ollama-url http://127.0.0.1:11435 --model gemma3:4b

# Or live against the Nasdaq API, every 60 s
java -jar target/nasdaq-swing-bot-0.1.0.jar --symbol COMP \
  --ollama-url http://127.0.0.1:11435 --model gemma3:4b
```

## Plugin handlers

- `start` — launch the jar as a subprocess (config from `plugin.json`).
- `stop` — SIGTERM the bot; graceful shutdown persists state.
- `status` — running? PID, last tick from `state/bot-state.json`.
- `alert_tail` — last lines of `capture/debug-smtp.log`.

## Requirements

- JDK 21+ and Maven 3.9+ to build.
- A local Ollama server reachable at `OLLAMA_BASE_URL` (see `docs/TECHNICAL.md`
  for the CPU-only server setup used on this machine — port 11435).
- SMTP credentials for real email delivery; without them the bot falls back to
  writing `.eml` files under `capture/` (never silently drops alerts).

## Usage notes

- Default symbol is `COMP` (Nasdaq Composite), matching "Nasdaq Index".
- `--demo` replays synthetic data fast and delivers over a loopback SMTP sink —
  useful for validating the whole pipeline without credentials.
- State persists across restarts; duplicate alerts for the same confirmed
  extremity are suppressed via a fingerprint (`state/bot-state.json`).
