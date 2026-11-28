The issue: `--file` reads the whole file as **one string** and passes it to FinBERT as a single headline. Your `news.txt` is a Finnhub JSON array (thousands of articles), so FinBERT is tokenizing the entire JSON blob as one "headline" — producing garbage sentiment. The CLI has no idea it's looking at a JSON array.

## Quick fix without code (if you just want results now)

Extract the headline fields to a plain text file (one headline per line), then use `--batch`:

```bash
jq -r '.[].headline | select(length > 0)' news.txt > headlines.txt
mvn -q compile exec:java -Dexec.mainClass=com.openclaw.bot.sentiment.SentimentCli \
  -Dexec.args="--batch headlines.txt --deep --json"
```

But since you asked to run `--file news.txt` directly, here's the proper code fix:

## Make `--file` auto-detect Finnhub JSON arrays

Add this to `SentimentCli.java` — when `--file` points at a JSON array, it parses it and scores each article's `headline` individually (same pipeline as `--batch`).

**1. New imports:**

```java
import java.io.IOException;
import java.util.ArrayList;
import tools.jackson.databind.JsonNode;
```

**2. In `main()`, before `resolveText`:**

```java
// NEW: if --file is a Finnhub JSON array, process each headline as a batch.
if (opts.containsKey("--file")) {
    Path filePath = Path.of(opts.get("--file"));
    if (isFinnhubJsonArray(filePath)) {
        runFinnhubJsonFile(filePath, opts);
        return;
    }
}
```

**3. The new methods (plus a refactor of `runBatch` to share the loop):**

```java
private static void runBatch(Map<String, String> opts) {
    Path batchFile = Path.of(opts.get("--batch"));
    List<String> headlines;
    try {
        headlines = Files.readAllLines(batchFile);
    } catch (Exception e) {
        LOG.error("Cannot read batch file: {}", e.getMessage());
        System.exit(2);
        return;
    }
    headlines = headlines.stream().filter(l -> !l.isBlank()).toList();
    runHeadlines(headlines, opts);
}

private static void runFinnhubJsonFile(Path file, Map<String, String> opts) {
    List<String> headlines = new ArrayList<>();
    try {
        JsonNode root = MAPPER.readTree(Files.readString(file));
        if (root.isArray()) {
            for (JsonNode item : root) {
                String h = item.path("headline").asText("");
                if (!h.isBlank()) {
                    headlines.add(h);
                }
            }
        }
    } catch (Exception e) {
        LOG.error("Cannot parse Finnhub JSON file: {}", e.getMessage());
        System.exit(2);
        return;
    }
    if (headlines.isEmpty()) {
        LOG.info("No headlines found in {}", file);
        return;
    }
    runHeadlines(headlines, opts);
}

/** Shared scoring loop for batch and Finnhub-file modes. */
private static void runHeadlines(List<String> headlines, Map<String, String> opts) {
    boolean forceDeep = opts.containsKey("--force-deep");
    double minConfidence = Double.parseDouble(
            opts.getOrDefault("--min-confidence", String.valueOf(MIN_CONFIDENCE)));
    int numCtx = resolveNumCtx(opts);
    DeepBudget deepBudget = resolveDeepBudget(opts);
    boolean crossCheck = opts.containsKey("--cross-check") || opts.containsKey("--voting");

    ArrayNode results = MAPPER.createArrayNode();
    for (String headline : headlines) {
        RunResult run = analyzeOne(headline, opts, minConfidence, forceDeep,
                numCtx, crossCheck, deepBudget);
        if (run.error() != null) {
            ObjectNode out = MAPPER.createObjectNode();
            out.put("headline", headline);
            out.put("error", run.error());
            results.add(out);
        } else {
            results.add(buildJson(headline, run));
        }
    }
    // KEEP on stdout: machine-readable JSON (piped to jq etc.)
    System.out.println(MAPPER.writeValueAsString(results));
}

/** True if the file's first non-whitespace character is '[' (JSON array). */
private static boolean isFinnhubJsonArray(Path p) {
    try (var reader = Files.newBufferedReader(p)) {
        int c;
        while ((c = reader.read()) != -1) {
            if (!Character.isWhitespace(c)) {
                return c == '[';
            }
        }
    } catch (IOException ignored) {
        // fall through to false
    }
    return false;
}
```

Now your exact command works:

```bash
mvn -q compile exec:java -Dexec.mainClass=com.openclaw.bot.sentiment.SentimentCli \
  -Dexec.args="--file news.txt --deep --json"
```

It outputs a JSON array — one object per article, each with `headline`, `source`, `finbert`, `ollama`, `cross_check`, etc.:

```json
[
  {
    "headline": "Prediction: This Is What Nvidia Stock Will Be Worth by 2028",
    "source": "finbert",
    "finbert": { "sentiment": "BULLISH", "score": 0.812, "confidence": 0.812 },
    "ollama": { "used": false },
    "cross_check": { "used": false }
  },
  ...
]
```

## Notes

- **`--file` with plain text still works** — the detection only kicks in when the file is a JSON array.
- All the existing flags compose: `--deep`, `--cross-check`, `--max-deep-per-batch`, `--num-ctx`, `--verbose`, `--model`.
- The JSON array output is printed on stdout regardless of `--json` (same as `--batch` behavior) so it pipes cleanly: `... | jq '.[] | select(.finbert.sentiment == "BULLISH")'`.
- Logic is the same as the previous `runBatch` — I just extracted the loop so both `--batch` and Finnhub `--file` share it. If you already added the `DeepBudget`/`resolveNumCtx` helpers from earlier, those are reused as-is.

Want me to also handle the case where some Finnhub items lack a `headline` (skip them — already done above) or dedupe repeated headlines from the file before scoring?


Can you add a per-headline debug print that shows the FinBERT label and confidence for each article, so I can verify the scores?

How can I make the SentimentCli output include the article publish date or symbol so I can match the sentiment back to the original news?

What would be the best way to deduplicate repeated headlines from the Finnhub JSON file before scoring?