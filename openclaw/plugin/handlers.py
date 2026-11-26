"""
OpenClaw plugin handlers for the Nasdaq Swing Bot (Java PoC).

The bot itself is a JVM program (`target/nasdaq-swing-bot-0.1.0.jar`).
OpenClaw does not expose a Java SDK yet, so this plugin drives the jar via
process management: start/stop/status plus a tail of the alert capture logs.

Lifecycle is intentionally dumb and synchronous:
  - start(): launches `java -jar ...` as a subprocess, keeps its stdout/stderr
    in a log file under the workspace, and records the PID in state.json.
  - stop(): signals the PID (SIGTERM); the bot's shutdown hook persists state.
  - status(): reports running/stopped plus last tick from state/bot-state.json.
  - alert_tail(): last N lines of capture/debug-smtp.log or latest .eml name.

Config keys mirror Main CLI flags (see docs/TECHNICAL.md).
"""

from __future__ import annotations

import json
import os
import signal
import subprocess
from pathlib import Path

PLUGIN_DIR = Path(__file__).resolve().parent
STATE_FILE = PLUGIN_DIR / "state.json"


def _load_cfg(params: dict) -> dict:
    cfg = dict(PLUGIN_DIR / "plugin.json" and json.loads((PLUGIN_DIR / "plugin.json").read_text()).get("config", {}))
    cfg.update(params or {})
    return cfg


def _bot_cmd(cfg: dict, extra: list[str]) -> list[str]:
    cmd = [cfg.get("java", "java"), "-jar", str(cfg["jar"])]
    if extra:
        cmd += extra
    return cmd


def start(params: dict) -> dict:
    cfg = _load_cfg(params)
    if not Path(cfg["jar"]).exists():
        return {"ok": False, "error": f"jar not found: {cfg['jar']}"}
    log = PLUGIN_DIR / "bot.log"
    bot_args = [
        "--symbol", str(cfg.get("symbol", "COMP")),
        "--interval", str(cfg.get("intervalSeconds", 60)),
        "--ollama-url", str(cfg.get("ollamaBaseUrl", "http://127.0.0.1:11435")),
        "--model", str(cfg.get("ollamaModel", "gemma3:4b")),
    ]
    provider = cfg.get("provider", "NASDAQ")
    if provider and provider != "NASDAQ":
        bot_args += ["--provider", str(provider).upper()]
        if str(provider).upper() == "FINNHUB":
            key = cfg.get("finnhubApiKey", "")
            if key:
                bot_args += ["--finnhub-key", str(key)]
    proc = subprocess.Popen(
        _bot_cmd(cfg, bot_args),
        stdout=log.open("ab"), stderr=subprocess.STDOUT, start_new_session=True,
    )
    STATE_FILE.write_text(json.dumps({"pid": proc.pid, "started": True}))
    return {"ok": True, "pid": proc.pid, "log": str(log)}


def stop(params: dict | None = None) -> dict:
    if not STATE_FILE.exists():
        return {"ok": False, "error": "not running (no state)"}
    st = json.loads(STATE_FILE.read_text())
    pid = st.get("pid")
    if pid:
        try:
            os.kill(pid, signal.SIGTERM)
            return {"ok": True, "pid": pid, "signal": "SIGTERM"}
        except ProcessLookupError:
            pass
    STATE_FILE.write_text(json.dumps({"pid": None, "started": False}))
    return {"ok": True, "pid": None}


def status(params: dict | None = None) -> dict:
    running = False
    pid = None
    if STATE_FILE.exists():
        pid = json.loads(STATE_FILE.read_text()).get("pid")
        if pid:
            try:
                os.kill(pid, 0)
                running = True
            except ProcessLookupError:
                running = False
    last_tick = None
    state = Path("state/bot-state.json")
    if state.exists():
        try:
            last_tick = json.loads(state.read_text()).get("lastTickMillis")
        except Exception:
            pass
    return {"ok": True, "running": running, "pid": pid, "lastTickMillis": last_tick}


def alert_tail(params: dict | None = None) -> dict:
    lines: list[str] = []
    smtp = PLUGIN_DIR.parent / "capture" / "debug-smtp.log"
    if smtp.exists():
        lines = smtp.read_text().splitlines()[-int((params or {}).get("lines", 30)):]
    return {"ok": True, "lines": lines}
