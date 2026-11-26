package com.openclaw.bot.sentiment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.openclaw.bot.sentiment.OllamaSentimentAnalyzer.DeepAnalysis;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Command-line sentiment client (FinBERT fast pass + optional Ollama deep analysis).
 *
 * <p>Usage:
 * <pre>
 *   SentimentCli --text "Tesla beats Q3 estimates by 12%"
 *   SentimentCli --text "..." --deep --force-deep --json
 *   SentimentCli --file news.txt --deep --json | jq .ollama.entities
 *   SentimentCli --batch headlines.txt --deep --json   // outputs JSON array
 * </pre>
 */
public final class SentimentCli {

    private static final double MIN_CONFIDENCE = 0.65;
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    public static void main(String[] args) {
        Map<String, String> opts = parseArgs(args);

        if (opts.containsKey("--batch")) {
            runBatch(opts);
            return;
        }

        String text = resolveText(opts);
        if (text == null) {
            printUsage();
            System.exit(2);
            return;
        }

        boolean jsonMode = opts.containsKey("--json");
        boolean deepEnabled = opts.containsKey("--deep") || opts.containsKey("--force-deep");
        boolean forceDeep = opts.containsKey("--force-deep");
        double minConfidence = Double.parseDouble(
                opts.getOrDefault("--min-confidence", String.valueOf(MIN_CONFIDENCE)));

        RunResult run = analyzeOne(text, opts, minConfidence, forceDeep);

        if (run.error() != null) {
            if (jsonMode) {
                ObjectNode out = MAPPER.createObjectNode();
                out.put("headline", text);
                out.put("error", run.error());
                System.out.println(MAPPER.writeValueAsString(out));
            } else {
                System.err.println("ERROR: " + run.error());
            }
            System.exit(1);
            return;
        }

        if (jsonMode) {
            System.out.println(MAPPER.writeValueAsString(buildJson(text, run)));
        } else {
            printHuman(text, run, minConfidence, deepEnabled, opts.getOrDefault("--model", "qwen3.5:9b"));
        }
    }

    // ------------------------------------------------------------------
    // Batch mode
    // ------------------------------------------------------------------

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

        // Filter out blank lines
        headlines = headlines.stream()
                .filter(line -> !line.isBlank())
                .toList();

        boolean deepEnabled = opts.containsKey("--deep") || opts.containsKey("--force-deep");
        boolean forceDeep = opts.containsKey("--force-deep");
        double minConfidence = Double.parseDouble(
                opts.getOrDefault("--min-confidence", String.valueOf(MIN_CONFIDENCE)));

        // Batch always emits JSON — human mode makes little sense for files
        ArrayNode results = MAPPER.createArrayNode();

        for (String headline : headlines) {
            RunResult run = analyzeOne(headline, opts, minConfidence, forceDeep);
            if (run.error() != null) {
                ObjectNode out = MAPPER.createObjectNode();
                out.put("headline", headline);
                out.put("error", run.error());
                results.add(out);
            } else {
                results.add(buildJson(headline, run));
            }
        }

        System.out.println(MAPPER.writeValueAsString(results));
    }

    // ------------------------------------------------------------------
    // Core pipeline (shared by single & batch)
    // ------------------------------------------------------------------

    private record RunResult(
            FinBertSentiment.SentimentResult finbert,
            DeepAnalysis ollama,
            String error,
            String source) { }

    private static RunResult analyzeOne(String text,
                                        Map<String, String> opts,
                                        double minConfidence,
                                        boolean forceDeep) {

        Path modelPath = Path.of(opts.getOrDefault("--model-path",
                "/home/mloayzag/models/finbert"));
        String ollamaModel = opts.getOrDefault("--model", "qwen3.5:9b");
        String ollamaBase = opts.getOrDefault("--base-url", "http://localhost:11434");
        boolean deepEnabled = opts.containsKey("--deep") || opts.containsKey("--force-deep");

        FinBertSentiment finbert = new FinBertSentiment(modelPath);
        try {
            FinBertSentiment.SentimentResult fast = finbert.analyze(text);

            boolean shouldEscalate = deepEnabled
                    && (forceDeep || fast.confidence() < (float) minConfidence);

            if (!shouldEscalate) {
                return new RunResult(fast, null, null, "finbert");
            }

            try {
                DeepAnalysis deep = new OllamaSentimentAnalyzer(
                        ollamaBase, ollamaModel, null).analyze(text);
                return new RunResult(fast, deep, null, "both");
            } catch (Exception e) {
                // Ollama failed — still return FinBERT verdict, with note
                return new RunResult(fast, null, null, "finbert");
            }
        } catch (Exception e) {
        	e.printStackTrace();
            return new RunResult(null, null, e.getMessage(), null);
        } finally {
            finbert.close();
        }
    }

    // ------------------------------------------------------------------
    // JSON builders
    // ------------------------------------------------------------------

    private static ObjectNode buildJson(String text, RunResult run) {
        ObjectNode out = MAPPER.createObjectNode();
        out.put("headline", text);
        out.put("source", run.source());

        ObjectNode fb = out.putObject("finbert");
        if (run.finbert() != null) {
            fb.put("sentiment", run.finbert().sentiment().name());
            fb.put("score", run.finbert().score());
            fb.put("confidence", run.finbert().confidence());
        }

        ObjectNode ol = out.putObject("ollama");
        DeepAnalysis deep = run.ollama();
        if (deep != null) {
            ol.put("used", true);
            ol.put("sentiment", deep.sentiment());
            ol.put("score", deep.score());
            ol.put("confidence", deep.confidence());

            ArrayNode entities = ol.putArray("entities");
            for (OllamaSentimentAnalyzer.Entity e : deep.entities()) {
                ObjectNode ent = entities.addObject();
                ent.put("ticker", e.ticker());
                ent.put("name", e.name());
            }

            ArrayNode drivers = ol.putArray("key_drivers");
            deep.keyDrivers().forEach(drivers::add);

            ArrayNode caveats = ol.putArray("caveats");
            deep.caveats().forEach(caveats::add);
        } else {
            ol.put("used", false);
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Human output (single mode only)
    // ------------------------------------------------------------------

    private static void printHuman(String text, RunResult run,
                                   double minConfidence, boolean deepEnabled,
                                   String ollamaModel) {

        System.out.printf("FinBERT   : %s (score=%.3f, confidence=%.3f)%n",
                run.finbert().sentiment(), run.finbert().score(),
                run.finbert().confidence());

        DeepAnalysis deep = run.ollama();
        if (deep != null) {
            System.out.printf("Escalated to Ollama (%s)%n", ollamaModel);
            System.out.println("Ollama deep analysis:");
            System.out.printf("  sentiment   : %s (score=%.3f, confidence=%.3f)%n",
                    deep.sentiment(), deep.score(), deep.confidence());
            if (!deep.entities().isEmpty()) {
                System.out.println("  entities    : " + deep.entities());
            }
            if (!deep.keyDrivers().isEmpty()) {
                System.out.println("  key drivers : " + deep.keyDrivers());
            }
            if (!deep.caveats().isEmpty()) {
                System.out.println("  caveats     : " + deep.caveats());
            }
        } else if (deepEnabled) {
            System.out.printf("(no escalation: confidence %.3f >= %.3f)%n",
                    run.finbert().confidence(), minConfidence);
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static void printUsage() {
        System.err.println("Usage: SentimentCli (--text \"<headline>\" | --file <path> | "
                + "--batch <file>)");
        System.err.println("  [--deep]            Enable Ollama escalation (opt-in)");
        System.err.println("  [--force-deep]      Always escalate to Ollama");
        System.err.println("  [--json]            Machine-readable JSON output");
        System.err.println("  [--min-confidence]  FinBERT confidence threshold (default 0.65)");
        System.err.println("  [--model]           Ollama model (default qwen3.5:9b)");
        System.err.println("  [--base-url]        Ollama URL (default http://localhost:11434)");
        System.err.println("  [--model-path]      FinBERT model dir (default ~/models/finbert)");
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> opts = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.startsWith("--")) {
                if (a.equals("--deep") || a.equals("--force-deep")
                        || a.equals("--json") || a.equals("--batch")) {
                    // --batch takes a value, handle below
                    if (a.equals("--batch") && i + 1 < args.length) {
                        opts.put(a, args[++i]);
                    } else if (!a.equals("--batch")) {
                        opts.put(a, "true");
                    }
                } else if (i + 1 < args.length) {
                    opts.put(a, args[++i]);
                } else {
                    opts.put(a, "true");
                }
            }
        }
        return opts;
    }

    private static String resolveText(Map<String, String> opts) {
        if (opts.containsKey("--text")) {
            return opts.get("--text");
        }
        if (opts.containsKey("--file")) {
            try {
                return Files.readString(Path.of(opts.get("--file")));
            } catch (Exception e) {
                System.err.println("Cannot read file: " + e.getMessage());
                return null;
            }
        }
        return null;
    }

    private SentimentCli() {
        // utility class
    }
}
