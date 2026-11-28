Here's how to parallelize batch processing with a `--concurrency N` option — including an important refactor: instead of creating a new `FinBertSentiment` per headline (which would re-load the model for every line), we now share one instance across all worker threads, since its `analyze()` already creates/closes a predictor per call and the lazy model load is synchronized.

## 1. Add the flag to `parseArgs`

`--concurrency` is a value flag, so it's already handled by the existing `else if (i + 1 < args.length)` branch — no change needed there. It just needs reading in `runBatch`.

## 2. Rewrite `runBatch` with a thread pool

```java
private static void runBatch(Map<String, String> opts) {
    Path batchFile = Path.of(opts.get("--batch"));
    List<String> headlines;
    try {
        headlines = Files.readAllLines(batchFile);
    } catch (Exception e) {
        System.err.println("Cannot read batch file: " + e.getMessage());
        System.exit(2);
        return;
    }

    headlines = headlines.stream()
            .filter(line -> !line.isBlank())
            .toList();

    boolean deepEnabled = opts.containsKey("--deep") || opts.containsKey("--force-deep");
    boolean forceDeep = opts.containsKey("--force-deep");
    double minConfidence = Double.parseDouble(
            opts.getOrDefault("--min-confidence", String.valueOf(MIN_CONFIDENCE)));

    int concurrency = Integer.parseInt(opts.getOrDefault("--concurrency", "1"));
    if (concurrency < 1) concurrency = 1;

    // Shared instances across all workers — safe because analyze() creates
    // a fresh predictor per call and model loading is synchronized.
    FinBertSentiment finbert = new FinBertSentiment(
            Path.of(opts.getOrDefault("--model-path", "/home/mloayzag/models/finbert")));
    OllamaSentimentAnalyzer ollama = new OllamaSentimentAnalyzer(
            opts.getOrDefault("--base-url", "http://localhost:11434"),
            opts.getOrDefault("--model", "qwen3.5:9b"),
            null);

    ArrayNode results = MAPPER.createArrayNode();

    try {
        if (concurrency == 1) {
            // Sequential path — no pool overhead
            for (String headline : headlines) {
                results.add(buildSingleJson(headline, finbert, ollama,
                        minConfidence, forceDeep, deepEnabled));
            }
        } else {
            ExecutorService pool = Executors.newFixedThreadPool(concurrency);
            try {
                List<Callable<ObjectNode>> tasks = new ArrayList<>();
                for (String headline : headlines) {
                    tasks.add(() -> buildSingleJson(headline, finbert, ollama,
                            minConfidence, forceDeep, deepEnabled));
                }
                // invokeAll preserves input order in the futures list
                List<Future<ObjectNode>> futures = pool.invokeAll(tasks);
                for (Future<ObjectNode> future : futures) {
                    try {
                        results.add(future.get());
                    } catch (ExecutionException e) {
                        ObjectNode err = MAPPER.createObjectNode();
                        err.put("headline", "");
                        err.put("error", e.getCause() == null
                                ? e.toString() : e.getCause().getMessage());
                        results.add(err);
                    }
                }
            } finally {
                pool.shutdown();
            }
        }
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        ObjectNode err = MAPPER.createObjectNode();
        err.put("error", "Batch interrupted: " + e.getMessage());
        results.add(err);
    } finally {
        finbert.close();
    }

    System.out.println(MAPPER.writeValueAsString(results));
}
```

## 3. Extract the per-headline worker (`buildSingleJson`)

This replaces the inline loop body with a callable that returns an `ObjectNode` directly (including error cases):

```java
private static ObjectNode buildSingleJson(String text,
                                          FinBertSentiment finbert,
                                          OllamaSentimentAnalyzer ollama,
                                          double minConfidence,
                                          boolean forceDeep,
                                          boolean deepEnabled) {
    try {
        FinBertSentiment.SentimentResult fast = finbert.analyze(text);

        boolean shouldEscalate = deepEnabled
                && (forceDeep || fast.confidence() < (float) minConfidence);

        if (!shouldEscalate) {
            return buildJson(text, new RunResult(fast, null, null, "finbert"));
        }

        try {
            DeepAnalysis deep = ollama.analyze(text);
            return buildJson(text, new RunResult(fast, deep, null, "both"));
        } catch (Exception e) {
            // Ollama failed — fall back to FinBERT verdict
            return buildJson(text, new RunResult(fast, null, null, "finbert"));
        }
    } catch (Exception e) {
        ObjectNode out = MAPPER.createObjectNode();
        out.put("headline", text);
        out.put("error", e.getMessage());
        return out;
    }
}
```

## 4. Required imports

```java
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
```

## Usage

```bash
# Sequential (default)
java -cp ... SentimentCli --batch headlines.txt --deep --json

# Parallel with 4 workers
java -cp ... SentimentCli --batch headlines.txt --deep --json --concurrency 4
```

## How it behaves

- **Order preserved** — `invokeAll` + iterating futures in submission order guarantees the JSON array matches the file order regardless of which thread finishes first.
- **Shared models, per-call predictors** — one `FinBertSentiment` serves all threads; each `analyze()` call gets its own predictor via try-with-resources, so no predictor sharing across threads (which would be unsafe).
- **Ollama `HttpClient` is thread-safe**, so sharing one analyzer is fine.
- **Errors don't kill the batch** — a failing line becomes `{"headline": "...", "error": "..."}` in the array.

## Performance caveats (important for your hardware)

- The thread pool parallelizes **CPU/HTTP work**, but your RTX 3080 is shared: concurrent Ollama calls (qwen3.5:9b, ~6–7GB VRAM) will contend for the GPU. Modest N (2–4) will speed up FinBERT CPU passes but can actually *slow down* Ollama responses beyond that. If you use `--force-deep`, keep N ≤ 2.
- FinBERT alone (no `--deep`) parallelizes better — it's small and fast, so N up to 8 is fine.

Want me to add a `--timeout` per-task guard so a hung Ollama call can't stall an entire parallel batch?

Can you add a `--timeout` per-task guard so a hung Ollama call doesn't stall the batch?

How can I use thread-local FinBERT models to avoid model loading contention in the concurrent batch?

How can I make the `--concurrency` option dynamically cap itself based on GPU memory when Ollama is enabled?


Here's how to implement it. The key architectural change: a **shared `DeepBudget` counter** passed through `analyzeOne`, so the cap is enforced across the whole batch (not per-headline). All deep calls — whether triggered by `--deep`, low confidence, `--force-deep`, or cross-check disagreement — draw from the same budget.

## 1. Add the budget class (nested in `SentimentCli`)

```java
/**
 * Tracks how many deep Ollama calls a batch may make. Shared across all
 * headlines so the cap applies to the whole run.
 */
private static final class DeepBudget {
    private final int max;
    private int used;

    DeepBudget(int max) {
        this.max = max;
    }

    /** Returns true if a deep call is allowed, consuming one slot. */
    synchronized boolean tryAcquire() {
        if (used >= max) {
            return false;
        }
        used++;
        return true;
    }

    int used() { return used; }
    int max() { return max; }
}
```

(`synchronized` is defensive — the batch loop is single-threaded today, but it keeps this safe if you parallelize later.)

## 2. Parse and validate the flag

```java
/** Returns null when --max-deep-per-batch is absent (unlimited). */
private static DeepBudget resolveDeepBudget(Map<String, String> opts) {
    String raw = opts.get("--max-deep-per-batch");
    if (raw == null) {
        return null;
    }
    try {
        int v = Integer.parseInt(raw.trim());
        if (v < 1) {
            throw new NumberFormatException("must be >= 1");
        }
        return new DeepBudget(v);
    } catch (NumberFormatException e) {
        LOG.error("Invalid --max-deep-per-batch value: \"{}\" — {}", raw, e.getMessage());
        printUsage();
        System.exit(2);
        return null; // unreachable
    }
}
```

## 3. Add the `deepSkipReason` field to `RunResult`

```java
private record RunResult(
        FinBertSentiment.SentimentResult finbert,
        DeepAnalysis ollama,
        String error,
        String source,
        String ollamaError,
        FinGptSentiment.FinGptResult crossCheck,
        String deepSkipReason) { }
```

## 4. Update `analyzeOne()` — budget check before deep call

```java
private static RunResult analyzeOne(String text,
                                    Map<String, String> opts,
                                    double minConfidence,
                                    boolean forceDeep,
                                    int numCtx,
                                    boolean useCrossCheck,
                                    DeepBudget deepBudget) {

    Path modelPath = Path.of(opts.getOrDefault("--model-path",
            "/home/mloayzag/models/finbert"));
    String ollamaModel = opts.getOrDefault("--model", "gemma4-e4b-64k:latest");
    String ollamaBase = opts.getOrDefault("--base-url", "http://localhost:11434");
    boolean deepEnabled = opts.containsKey("--deep") || opts.containsKey("--force-deep");
    boolean verbose = opts.containsKey("--verbose");
    String fingptModel = opts.getOrDefault("--fingpt-model", "fingpt-sentiment");

    FinBertSentiment finbert = new FinBertSentiment(modelPath);
    try {
        FinBertSentiment.SentimentResult fast = finbert.analyze(text);

        // Optional cross-check with FinGPT (best-effort; never fatal).
        FinGptSentiment.FinGptResult crossCheck = null;
        if (useCrossCheck) {
            try {
                crossCheck = new FinGptSentiment(ollamaBase, fingptModel).analyze(text);
            } catch (Exception e) {
                if (verbose) {
                    LOG.debug("FinGPT cross-check skipped: {}", e.getMessage());
                }
            }
        }

        // Disagreement between FinBERT and FinGPT is an explicit escalation
        // trigger — it forces deep analysis even without --deep.
        boolean disagree = false;
        if (crossCheck != null) {
            String finbertLabel = mapFinBertLabel(fast.sentiment());
            disagree = !finbertLabel.equals(crossCheck.sentiment());
        }

        boolean shouldEscalate = (deepEnabled
                && (forceDeep || fast.confidence() < (float) minConfidence))
                || (useCrossCheck && disagree);

        if (!shouldEscalate) {
            return new RunResult(fast, null, null, "finbert", null, crossCheck, null);
        }

        // Budget gate: if the batch cap is exhausted, skip deep analysis
        // and record why (the headline still gets FinBERT + cross-check).
        if (deepBudget != null && !deepBudget.tryAcquire()) {
            String reason = "max deep calls reached (" + deepBudget.max() + ")";
            if (verbose) {
                LOG.warn("Deep analysis SKIPPED for headline=\"{}\" | {}", text, reason);
            }
            return new RunResult(fast, null, null, "finbert", null, crossCheck, reason);
        }

        try {
            DeepAnalysis deep = new OllamaSentimentAnalyzer(
                    ollamaBase, ollamaModel, null, numCtx).analyze(text);
            return new RunResult(fast, deep, null, "both", null, crossCheck, null);
        } catch (Exception e) {
            String reason = (e.getMessage() == null) ? e.toString() : e.getMessage();
            if (verbose) {
                LOG.warn("Ollama deep analysis FAILED — fell back to FinBERT | headline=\"{}\" | {}",
                        text, reason);
            } else {
                LOG.debug("Ollama deep analysis FAILED — fell back to FinBERT | headline=\"{}\" | {}",
                        text, reason);
            }
            return new RunResult(fast, null, null, "finbert", reason, crossCheck, null);
        }
    } catch (Exception e) {
        LOG.error("FinBERT analysis failed for headline=\"{}\"", text, e);
        return new RunResult(null, null, e.getMessage(), null, null, null, null);
    } finally {
        finbert.close();
    }
}
```

## 5. Wire it into `main()` and `runBatch()`

In `main()`:

```java
int numCtx = resolveNumCtx(opts);
DeepBudget deepBudget = resolveDeepBudget(opts);
boolean crossCheck = opts.containsKey("--cross-check") || opts.containsKey("--voting");

RunResult run = analyzeOne(text, opts, minConfidence, forceDeep, numCtx, crossCheck, deepBudget);
```

In `runBatch()`:

```java
int numCtx = resolveNumCtx(opts);
DeepBudget deepBudget = resolveDeepBudget(opts);
boolean crossCheck = opts.containsKey("--cross-check") || opts.containsKey("--voting");

for (String headline : headlines) {
    RunResult run = analyzeOne(headline, opts, minConfidence, forceDeep,
            numCtx, crossCheck, deepBudget);
    ...
}

// Summary line after the loop — always logged, regardless of --verbose:
if (deepBudget != null) {
    LOG.info("Deep budget: {} / {} used", deepBudget.used(), deepBudget.max());
}
```

## 6. Surface the skip in JSON and human output

In `buildJson()`:

```java
if (run.deepSkipReason() != null) {
    out.put("deep_skipped", run.deepSkipReason());
}
```

In `printHuman()` (after the FinBERT line):

```java
if (run.deepSkipReason() != null) {
    System.out.printf("(deep analysis skipped: %s)%n", run.deepSkipReason());
}
```

## 7. Usage text

```java
LOG.error("  [--max-deep-per-batch]  Cap deep Ollama calls per batch (default: unlimited)");
```

No `parseArgs` change needed — `--max-deep-per-batch` is not in the value-less flag list, so it naturally consumes the next token as its value.

## Behavior

```bash
mvn -q compile exec:java -Dexec.mainClass=com.openclaw.bot.sentiment.SentimentCli \
  -Dexec.args="--batch headlines.txt --cross-check --max-deep-per-batch 3 --json"
```

- First 3 headlines needing escalation → deep analysis runs normally (`"source": "both"`).
- Headlines 4+ still needing escalation → deep call skipped; `"source": "finbert"` and `"deep_skipped": "max deep calls reached (3)"` in the JSON; FinBERT + cross-check results still present.
- The batch summary line prints `Deep budget: 3 / 3 used` to stderr via slf4j.
- In single mode (no `--batch`), the same flag works naturally: `--max-deep-per-batch 1` means at most one deep call, which for a single headline is effectively an on/off switch.

One design note: the budget is consumed **only when escalation is actually attempted** (after `shouldEscalate`), so headlines that don't need deep analysis never waste slots. And the count includes deep calls triggered by cross-check disagreement — that's the point of the cap, since `--cross-check` alone can otherwise generate one deep call per conflicted headline.

Want me to also emit the budget usage (used/max) as a top-level field on the batch output, so downstream consumers don't have to scrape stderr for it?


Can you show me how to add the deep budget usage as a top-level field in the batch JSON output?

How would I parallelize the batch processing to speed up the analysis, and what changes would the DeepBudget need to support that?

Could you add a --min-agreement-threshold flag to only escalate on disagreement when the confidence between FinBERT and FinGPT is above a certain level?
