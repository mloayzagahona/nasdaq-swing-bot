# Ollama narrative-model benchmarks

Measured on the bot's dedicated local server (port `11435`), using the bot's real alert prompt,
`num_predict=150` (first pass), temperature 0.3, `stream=false`. "Cold" = model not resident in
memory at request time (first call after load); "warm" = cached.

- Date: 2026-11-25
- Ollama: 0.40.18, `OLLAMA_MODELS=/usr/share/ollama/.ollama/models`
- Hardware: NVIDIA RTX 3080 (CUDA 0, 9.6 GiB total / 8.9 GiB available), default ctx 4096
- Prompt: swing-high market alert, 4–6 sentences, label speculation, no markdown

## Results

| Model | Cold wall | Load | Gen rate | Output |
|---|---|---|---|---|
| `gemma4-e4b-64k:latest` | 44.8 s | 42.2 s | ~211 tok/s | empty* |
| `gemma3:4b` | 14.0 s | 12.9 s | ~151 tok/s | good |
| `hermes3:8b` | 12.9 s | 5.0 s | ~121 tok/s | best prose |
| `qwen3.5:4b` | 46.3 s | 16.1 s | ~112 tok/s | empty* |
| `mimo-v2.6:9b` | 17.7 s | 15.8 s | ~94 tok/s | partial† |
| `nemotron-mini:4b` | 16.9 s | 15.2 s | ~53 tok/s | short, compliant |
| `phi4-mini:latest` | 45.4 s | 19.5 s | ~11 tok/s | ok, slow |
| `qwen3:8b` | 19.4 s | 5.1 s | ~11 tok/s | empty* |
| `qwen2.5:14b` | 51.5 s | 20.7 s | ~8.5 tok/s | ok, marginal |

Warm reruns: `gemma3:4b` 9.3 s, `hermes3:8b` 10.0 s, `qwen3.5:4b` 8.5 s (empty),
`gemma4-e4b-64k:latest` 18.0 s (empty).

\* **Empty responses — root cause:** `gemma4-e4b-64k:latest` and `qwen3.5:4b` are
*reasoning/thinking* models. With a small `num_predict` budget they spend the entire budget on a
hidden chain-of-thought (server returns it in a `thinking` field) and emit `""` in `response`.
Same request pattern works for non-thinking models. Confirmed: `eval_count=200` all consumed as
thinking, `done_reason=length`, `response=""`. Fix: send `"think": false` in `/api/generate`
(`OllamaClient` now does). With thinking disabled, `gemma4-e4b-64k:latest` writes the alert
directly (~150–210 tok/s, ~1 s warm after load).

† `mimo-v2.6:9b` emits the alert but uses `**bold**` markdown despite the no-markdown instruction.

## Recommendation

- **Default:** `gemma4-e4b-64k:latest` (fast, clean output once `think=false` is sent).
- **Fallback:** `gemma3:4b` (proven in earlier demos, nearly as fast, no thinking behavior).
- **Richer prose option:** `hermes3:8b` (explicitly labels "Speculation:"; ~10 s warm).
- **Avoid for the 60 s cadence:** reasoning-heavy models (`deepseek-r1` family,
  `lfm2.5-thinking`, coder models, 14B+) — multi-second reasoning per alert; and the ~11 tok/s
  `qwen3:8b` / `phi4-mini:latest`.
