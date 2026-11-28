/* (C)2026 */
package com.openclaw.bot.sentiment;

import com.openclaw.bot.sentiment.OllamaSentimentAnalyzer.DeepAnalysis;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code SentimentCli} serves as the command-line interface entry point for the sentiment analysis subsystem.
 * <p>
 * It acts as an orchestrator, handling command-line argument parsing and routing the input
 * through the necessary analysis stages: fast FinBERT sentiment analysis, optional FinGPT cross-check,
 * and conditional deep LLM analysis via Ollama.
 * <p>
 * Output is designed to be flexible: human-readable text by default, or structured JSON suitable
 * for piping into tools like `jq`.
 * @author OpenClaw Development Team
 */
public final class SentimentCli {

  private static final Logger LOG = LoggerFactory.getLogger(SentimentCli.class);
  private static final double MIN_CONFIDENCE = 0.65;
  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  /**
 * Main execution method.
 * <p>
 * Parses command-line arguments to determine the execution mode (single headline, file, or batch).
 * Based on configuration, it executes the core analysis pipeline (`analyzeOne` or `runBatch`)
 * and formats the output according to the requested mode (human-readable or JSON).
 * @param args Command line arguments passed to the program.
 * @throws RuntimeException if a critical failure occurs during argument parsing or execution.
 */
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
    double minConfidence =
        Double.parseDouble(opts.getOrDefault("--min-confidence", String.valueOf(MIN_CONFIDENCE)));

    int numCtx = resolveNumCtx(opts);
    boolean crossCheck = opts.containsKey("--cross-check") || opts.containsKey("--voting");
    RunResult run = analyzeOne(text, opts, minConfidence, forceDeep, numCtx, crossCheck);

    if (run.error() != null) {
      if (jsonMode) {
        ObjectNode out = MAPPER.createObjectNode();
        out.put("headline", text);
        out.put("error", run.error());
        // KEEP on stdout: machine-readable output (piped to jq etc.)
        System.out.println(MAPPER.writeValueAsString(out));
      } else {
        LOG.error("Sentiment analysis failed: {}", run.error());
      }
      System.exit(1);
      return;
    }

    if (jsonMode) {
      // KEEP on stdout: machine-readable output (piped to jq etc.)
      System.out.println(MAPPER.writeValueAsString(buildJson(text, run)));
    } else {
      printHuman(text, run, minConfidence, deepEnabled, opts.getOrDefault("--model", "qwen3.5:9b"));
    }
  }

  // ------------------------------------------------------------------
  // Batch mode
  // ------------------------------------------------------------------

  /**
 * Executes the sentiment analysis pipeline for a batch of headlines provided in a file.
 * <p>
 * The output is strictly JSON formatted, as human reading of a batch is not practical.
 * It iterates over lines, calling {@code analyzeOne} for each, and collects results
 * into a JSON array, logging errors for individual failures while maintaining the batch structure.
 * @param opts Parsed command line options, must contain the "--batch" flag and file path.
 */
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

    // Filter out blank lines
    headlines = headlines.stream().filter(line -> !line.isBlank()).toList();

    boolean forceDeep = opts.containsKey("--force-deep");
    double minConfidence =
        Double.parseDouble(opts.getOrDefault("--min-confidence", String.valueOf(MIN_CONFIDENCE)));
    int numCtx = resolveNumCtx(opts);
    boolean crossCheck = opts.containsKey("--cross-check") || opts.containsKey("--voting");

    // Batch always emits JSON — human mode makes little sense for files
    ArrayNode results = MAPPER.createArrayNode();

    for (String headline : headlines) {
      RunResult run = analyzeOne(headline, opts, minConfidence, forceDeep, numCtx, crossCheck);
      if (run.error() != null) {
        ObjectNode out = MAPPER.createObjectNode();
        out.put("headline", headline);
        out.put("error", run.error());
        results.add(out);
      } else {
        results.add(buildJson(headline, run));
      }
    }

    // KEEP on stdout: machine-readable output (piped to jq etc.)
    System.out.println(MAPPER.writeValueAsString(results));
  }

  // ------------------------------------------------------------------
  // Core pipeline (shared by single & batch)
  // ------------------------------------------------------------------

  private record RunResult(
      FinBertSentiment.SentimentResult finbert,
      DeepAnalysis ollama,
      String error,
      String source,
      String ollamaError,
      FinGptSentiment.FinGptResult crossCheck) {}

  private static RunResult analyzeOne(
      String text,
      Map<String, String> opts,
      double minConfidence,
      boolean forceDeep,
      int numCtx,
      boolean useCrossCheck) {

    Path modelPath = Path.of(opts.getOrDefault("--model-path", "/home/mloayzag/models/finbert"));
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
        // Only escalate when the two models are on opposite sides;
        // disagreements involving neutral don't force deep analysis.
        disagree =
            switch (finbertLabel + "/" + crossCheck.sentiment()) {
              case "positive/negative", "negative/positive" -> true;
              default -> false;
            };
      }

      boolean shouldEscalate =
          (deepEnabled && (forceDeep || fast.confidence() < (float) minConfidence))
              || (useCrossCheck && disagree);

      if (!shouldEscalate) {
        return new RunResult(fast, null, null, "finbert", null, crossCheck);
      }

      try {
        DeepAnalysis deep =
            new OllamaSentimentAnalyzer(ollamaBase, ollamaModel, null, numCtx).analyze(text);
        return new RunResult(fast, deep, null, "both", null, crossCheck);
      } catch (Exception e) {
        String reason = (e.getMessage() == null) ? e.toString() : e.getMessage();
        if (verbose) {
          LOG.warn(
              "Ollama deep analysis FAILED — fell back to FinBERT | headline=\"{}\" | {}",
              text,
              reason);
        } else {
          LOG.debug(
              "Ollama deep analysis FAILED — fell back to FinBERT | headline=\"{}\" | {}",
              text,
              reason);
        }
        return new RunResult(fast, null, null, "finbert", reason, crossCheck);
      }
    } catch (Exception e) {
      LOG.error("FinBERT analysis failed for headline=\"{}\"", text, e);
      return new RunResult(null, null, e.getMessage(), null, null, null);
    } finally {
      finbert.close();
    }
  }

  /** Maps FinBERT enum to the shared positive/negative/neutral vocabulary. */
  private static String mapFinBertLabel(FinBertSentiment.TradingSentiment s) {
    return switch (s) {
      case BULLISH -> "positive";
      case BEARISH -> "negative";
      case NEUTRAL -> "neutral";
    };
  }

  // ------------------------------------------------------------------
  // JSON builders
  // ------------------------------------------------------------------

  private static ObjectNode buildJson(String text, RunResult run) {
    ObjectNode out = MAPPER.createObjectNode();
    out.put("headline", text);
    out.put("source", run.source());
    if (run.ollamaError() != null) {
      out.put("ollama_error", run.ollamaError());
    }

    ObjectNode xc = out.putObject("cross_check");
    if (run.crossCheck() != null) {
      xc.put("used", true);
      xc.put("sentiment", run.crossCheck().sentiment());
      xc.put("confidence", run.crossCheck().confidence());
      if (run.finbert() != null) {
        String finbertLabel = mapFinBertLabel(run.finbert().sentiment());
        xc.put("finbert_sentiment", finbertLabel);
        xc.put("agreement", finbertLabel.equals(run.crossCheck().sentiment()));
      }
    } else {
      xc.put("used", false);
    }

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

  private static void printHuman(
      String text, RunResult run, double minConfidence, boolean deepEnabled, String ollamaModel) {

    System.out.printf(
        "FinBERT   : %s (score=%.3f, confidence=%.3f)%n",
        run.finbert().sentiment(), run.finbert().score(), run.finbert().confidence());

    DeepAnalysis deep = run.ollama();

    if (deep != null) {
      System.out.printf("Escalated to Ollama (%s)%n", ollamaModel);
      if (run.crossCheck() != null) {
        System.out.printf(
            "  (disagreement: FinBERT=%s vs FinGPT=%s)%n",
            mapFinBertLabel(run.finbert().sentiment()), run.crossCheck().sentiment());
      }
      System.out.println("Ollama deep analysis:");
      System.out.printf(
          "  sentiment   : %s (score=%.3f, confidence=%.3f)%n",
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
      System.out.printf(
          "(no escalation: confidence %.3f >= %.3f)%n", run.finbert().confidence(), minConfidence);
    }

    if (run.crossCheck() != null) {
      System.out.printf("FinGPT    : %s (cross-check)%n", run.crossCheck().sentiment());
    }
  }

  // ------------------------------------------------------------------
  // Helpers
  // ------------------------------------------------------------------

  private static void printUsage() {
    LOG.error("Usage: SentimentCli (--text \"<headline>\" | --file <path> | " + "--batch <file>)");
    LOG.error("  [--deep]            Enable Ollama escalation (opt-in)");
    LOG.error("  [--force-deep]      Always escalate to Ollama");
    LOG.error("  [--json]            Machine-readable JSON output");
    LOG.error("  [--min-confidence]  FinBERT confidence threshold (default 0.65)");
    LOG.error("  [--model]           Ollama model (default qwen3.5:9b)");
    LOG.error("  [--base-url]        Ollama URL (default http://localhost:11434)");
    LOG.error("  [--model-path]      FinBERT model dir (default ~/models/finbert)");
    LOG.error("  [--verbose]         Print Ollama fallback warnings to stderr");
    LOG.error("  [--num-ctx]         Ollama context window override (default: auto by model tag)");
    LOG.error("  [--cross-check]     Run FinGPT classifier; auto-escalate on disagreement");
    LOG.error("  [--voting]          Alias for --cross-check");
  }

  /**
 * Resolves the Ollama context window size from command line arguments.
 * <p>
 * If no argument is provided, returns 0, which signals the underlying
 * {@code OllamaSentimentAnalyzer} to auto-select the context based on the model tag (e.g., 64k).
 * Handles argument parsing and validation for minimum context size (≥ 2048).
 * @param opts Parsed command line options.
 * @return The context window size in tokens, or 0 for auto-selection.
 */
private static int resolveNumCtx(Map<String, String> opts) {
    String raw = opts.get("--num-ctx");
    if (raw == null) {
      return 0; // auto-select based on model tag
    }
    try {
      int v = Integer.parseInt(raw.trim());
      if (v < 2048) {
        throw new NumberFormatException("must be >= 2048");
      }
      return v;
    } catch (NumberFormatException e) {
      LOG.error("Invalid --num-ctx value: \"{}\" — {}", raw, e.getMessage());
      printUsage();
      System.exit(2);
      return 0; // unreachable
    }
  }

  private static Map<String, String> parseArgs(String[] args) {
    Map<String, String> opts = new HashMap<>();
    for (int i = 0; i < args.length; i++) {
      String a = args[i];
      if (a.startsWith("--")) {
        if (a.equals("--deep")
            || a.equals("--force-deep")
            || a.equals("--json")
            || a.equals("--batch")
            || a.equals("--verbose")
            || a.equals("--cross-check")
            || a.equals("--voting")) {
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

  /**
 * Resolves the input headline text from command line arguments, checking for
 * an explicit text argument or attempting to read the content from a specified file.
 * @param opts Parsed command line options.
 * @return The resolved headline string, or {@code null} if no text source is provided.
 */
private static String resolveText(Map<String, String> opts) {
    if (opts.containsKey("--text")) {
      return opts.get("--text");
    }
    if (opts.containsKey("--file")) {
      try {
        return Files.readString(Path.of(opts.get("--file")));
      } catch (Exception e) {
        LOG.error("Cannot read file: {}", e.getMessage());
        return null;
      }
    }
    return null;
  }

  private SentimentCli() {
    // utility class
  }
}
