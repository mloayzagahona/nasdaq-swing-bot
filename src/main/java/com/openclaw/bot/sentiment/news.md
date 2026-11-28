Here's how to read that output, line by line:

## Per-field meaning

| Field | Meaning | Your values |
|---|---|---|
| `new=1` | One **new** (previously unseen) article was scored this poll | All symbols had exactly 1 new headline |
| `deep=0/1` | How many of those went through the **Ollama deep analyzer** | NVDA/MSFT/AAPL: 0; AMZN/META/GOOGL/TSLA: 1 |
| `skipped=0` | How many deep calls were **skipped due to the budget cap** (max 20) | 0 everywhere — cap not hit |
| `disagreements=0/1` | How many headlines where **FinBERT and FinGPT disagreed** | META, GOOGL: 1; others: 0 |
| `score=0.000` | **Rolling weighted sentiment** for the symbol (FinBERT score, time-decayed, 24h window) | See below |

## What's happening per symbol

- **NVDA, MSFT, AAPL** — 1 new article scored by FinBERT, deep=0. With `deepEnabled=true`, deep should run when confidence < 0.65. Since deep didn't run, FinBERT's confidence on those headlines was **≥ 0.65** (no escalation needed). Score 0.000 means the article scored exactly neutral (FinBERT score ≈ 0).
- **AMZN, TSLA** — new=1, deep=1, disagreements=0. Deep ran (low confidence < 0.65), FinGPT agreed with FinBERT (no disagreement). Score 0.000 (AMZN) and 0.585 (TSLA) — the FinBERT score after the decayed weighting.
- **META, GOOGL** — new=1, deep=1, disagreements=1. Deep ran, **and** FinBERT vs FinGPT disagreed — that's the most interesting signal. Score ~0.86–0.90, strongly positive.

## The one thing that looks off: `score=0.000` with `deep=0` or `deep=1`

FinBERT's score comes from its sentiment probability: `positive → +confidence`, `negative → -confidence`, `neutral → 0`. So a 0.000 score means **FinBERT classified the headline as neutral** — for NVDA/MSFT/AAPL/AMZN. But wait — for AMZN, deep ran (so confidence < 0.65, implying *non-neutral*), yet score is 0.000. That's a red flag worth checking.

**If FinBERT says non-neutral (deep ran due to low confidence), the score should not be exactly 0.000.** Zero exactly means the article scored neutral. That suggests either:

1. The headline is genuinely neutral (confidence just below 0.65 — e.g. 0.62 for `neutral`, which is not classically "low"), or
2. There's a subtle issue in how `score()` reads — but since these are single articles, score == the article's score, so 0.000 means the article itself scored 0.0.

For AMZN specifically: if deep ran because confidence < 0.65, the *label* is likely neutral with confidence ~0.5–0.64 (neutral is still the predicted label but not confident). That's plausible.

## Key takeaway

- `disagreements=1` on META/GOOGL is your "second opinion" flag — those headlines have conflicting FinBERT vs FinGPT sentiment, so the deep analyzer ran to resolve (good).
- `score=0.857/0.896` — strongly positive rolling sentiment for META/GOOGL (FinBERT leaning bullish on those headlines).
- `score=0.000` — neutral or very faint signal.
- `deep=0` on NVDA/MSFT/AAPL — those headlines were confident enough for FinBERT alone (≥ 0.65), so no deep call was needed.

**Does the 0.000 for AMZN look right to you?** If you'd expect that headline to be non-neutral, I can add a per-headline debugging print (label + confidence) to see what FinBERT actually said. Also, if you intend to use deep analysis results in the score (not just as confirmation), we can blend the deep score into `score()` — right now it's FinBERT-only by design.


Can you add a per-headline debugging print to show FinBERT's label and confidence for each article?

How can I blend the deep analysis result into the rolling score instead of using FinBERT only?

What would be a good way to handle the disagreement flag when FinBERT and FinGPT conflict?