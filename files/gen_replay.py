#!/usr/bin/env python3
"""
Replay CSV generator for CsvReplayProvider (com.openclaw.bot.market).

Writes `epochMillis,price` rows (one per tick) that the bot's CsvReplayProvider
consumes. `#` lines are allowed as comments.

Two modes:

  1. Synthetic random walk (default, no network, deterministic via --seed).
  2. Real historical data via the Yahoo Finance API (wrapper: yfinance).
     This is the data source FXReplay-style backtesting tools use; yfinance
     talks to Yahoo's v8 chart endpoint (https://query1.finance.yahoo.com/v8/
     finance/chart/{symbol}?interval=...&range=...) and returns OHLCV rows.

Usage:
    # Synthetic mode
    python3 files/gen_replay.py --points 180 --interval-sec 60 \
        --start-ts 2026-11-24T09:00:00 --out data/replay-last-tuesday-morning.csv

    # Yahoo Finance mode (real data; 1m bars are limited to ~last 5 days)
    python3 files/gen_replay.py --yfinance --symbol ^NDX \
        --interval 1m --period 5d --out data/replay-ndx-1m.csv
            
    python3 files/gen_replay.py --yfinance --symbol ^NDX --interval 1m --period 5d \
    --date 2026-11-24 --utc-start 14:00 --utc-end 17:00 \
    --out data/replay-ndx-last-tuesday-morning.csv

Requirements for --yfinance:  pip install yfinance pandas
"""
import argparse
import random
from datetime import datetime, timezone, time

# ---------------------------------------------------------------------------
# Mode 1: Synthetic random walk
# ---------------------------------------------------------------------------
def generate_synthetic(args: argparse.Namespace) -> None:
    rng = random.Random(args.seed)
    start_dt = datetime.fromisoformat(args.start_ts).replace(tzinfo=timezone.utc)
    t_ms = int(start_dt.timestamp() * 1000)
    price = args.start_price

    rows = []
    for _ in range(args.points):
        rows.append(f"{t_ms},{price:.2f}")
        # Regime drift: occasionally pick a persistent direction, otherwise random walk.
        drift = rng.choice([-0.0015, -0.0005, 0.0005, 0.0015]) if rng.random() < 0.15 else 0.0
        price *= 1.0 + drift + rng.gauss(0.0, 0.0015)
        t_ms += args.interval_sec * 1000

    with open(args.out, "w") as f:
        f.write("# OpenClaw Nasdaq Swing Bot — synthetic replay\n")
        f.write("# Format: epochMillis,price\n")
        f.writelines(line + "\n" for line in rows)

    print(f"wrote {len(rows)} points -> {args.out} (first ts {rows[0].split(',')[0]})")


# ---------------------------------------------------------------------------
# Mode 2: Yahoo Finance API (real historical data)
# ---------------------------------------------------------------------------
def fetch_yfinance(args: argparse.Namespace) -> None:
    """Download real OHLCV bars from Yahoo Finance and convert to epochMillis,price.

    This is the actual API call the user asked about: yfinance -> Yahoo's
    v8 chart endpoint. `ticker.history(...)` returns a pandas DataFrame with
    a datetime index and Open/High/Low/Close/Volume columns.
    """
    try:
        import yfinance as yf  # pip install yfinance pandas
    except ImportError:
        print("ERROR: --yfinance requires 'yfinance' and 'pandas'.")
        print("       Install with:  pip install yfinance pandas")
        raise SystemExit(2)

    print(f"Fetching {args.symbol} {args.interval} bars for period={args.period} ...")
    ticker = yf.Ticker(args.symbol)  # API call 1: Ticker object
    data = ticker.history(interval=args.interval, period=args.period)  # API call 2: chart endpoint

    if data is None or data.empty:
        print(
            f"ERROR: yfinance returned no data for {args.symbol} "
            f"({args.interval}, {args.period}). "
            f"Try a longer period or a different symbol."
        )
        raise SystemExit(2)

    # Convert the DataFrame index (datetime) to UTC epoch milliseconds.
    idx = data.index
    if idx.tz is None:
        idx = idx.tz_localize("UTC")
    else:
        idx = idx.tz_convert("UTC")

    points: list[tuple[int, float]] = []
    for ts, close in zip(idx, data["Close"]):
        if close != close:  # NaN (no print for that bar)
            continue
        points.append((int(ts.timestamp() * 1000), float(close)))
    points.sort()

    # Optional filters: keep only bars on a given UTC date and/or within a UTC window.
    if args.date:
        points = [
            p for p in points
            if datetime.fromtimestamp(p[0] / 1000, tz=timezone.utc).strftime("%Y-%m-%d")
            == args.date
        ]
    if args.utc_start:
        start_t = time.fromisoformat(args.utc_start)
        end_t = time.fromisoformat(args.utc_end) if args.utc_end else time(23, 59)
        points = [
            p for p in points
            if start_t
            <= datetime.fromtimestamp(p[0] / 1000, tz=timezone.utc).time()
            <= end_t
        ]

    if not points:
        print(
            f"ERROR: no bars match the filters for {args.symbol} "
            f"(date={args.date}, utc {args.utc_start}-{args.utc_end})."
        )
        raise SystemExit(2)

    rows = [f"{ts_ms},{price:.2f}" for ts_ms, price in points]

    with open(args.out, "w") as f:
        f.write(f"# OpenClaw Nasdaq Swing Bot — yfinance replay: {args.symbol} ({args.interval})\n")
        f.write("# Format: epochMillis,price\n")
        f.writelines(line + "\n" for line in rows)

    print(f"wrote {len(rows)} points -> {args.out} (first ts {rows[0].split(',')[0]})")


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------
def main() -> None:
    ap = argparse.ArgumentParser(description="Generate replay CSV for CsvReplayProvider.")
    ap.add_argument("-o", "--out", required=True, help="Output CSV file path")

    # Synthetic mode args
    ap.add_argument("-n", "--points", type=int, default=240, help="number of synthetic sample rows")
    ap.add_argument("--start-price", type=float, default=100.0)
    ap.add_argument("--start-ts", default="2026-01-05T09:30:00", help="ISO local time, UTC")
    ap.add_argument("--interval-sec", type=int, default=60, help="seconds between synthetic ticks")
    ap.add_argument("--seed", type=int, default=7, help="RNG seed for reproducibility")

    # Yahoo Finance mode args
    ap.add_argument("--yfinance", action="store_true", help="Fetch real data from the Yahoo Finance API")
    ap.add_argument("--symbol", default="^NDX", help="Yahoo symbol (e.g. ^NDX, ^IXIC, AAPL)")
    ap.add_argument("--interval", default="1d", help="Yahoo bar size: 1m, 5m, 15m, 30m, 1h, 1d")
    ap.add_argument("--period", default="5d", help="Yahoo lookback: e.g. 1d, 5d, 1mo, 3mo, 1y")
    ap.add_argument("--date", default=None, help="Keep only bars on this UTC date (YYYY-MM-DD)")
    ap.add_argument("--utc-start", default=None, help="Earliest bar time in UTC (HH:MM)")
    ap.add_argument("--utc-end", default=None, help="Latest bar time in UTC (HH:MM)")

    args = ap.parse_args()

    if args.yfinance:
        fetch_yfinance(args)
    else:
        generate_synthetic(args)


if __name__ == "__main__":
    main()
