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