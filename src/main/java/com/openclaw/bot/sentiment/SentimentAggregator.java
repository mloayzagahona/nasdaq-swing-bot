/* (C)2026 */
package com.openclaw.bot.sentiment;

import com.openclaw.bot.sentiment.OllamaSentimentAnalyzer.DeepAnalysis;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tracks seen articles (persisted to disk so restarts don't re-score old headlines) and maintains a
 * time-decayed rolling sentiment score per symbol. Since v2: ingest() can optionally run FinGPT
 * cross-check and Ollama deep analysis, mirroring SentimentCli's escalation logic.
 */
public final class SentimentAggregator {

  private final FinBertSentiment finbert;
  private final SeenStore seen;
  private final Map<String, List<ScoredArticle>> scores = new HashMap<>();
  private final Map<String, List<DeepAnalysis>> deepBySymbol = new HashMap<>();

  // Weight halves every 60 minutes; articles older than 24h are dropped.
  private static final double HALF_LIFE_MINUTES = 60.0;
  private static final Duration WINDOW = Duration.ofHours(24);

  private record ScoredArticle(Instant timestamp, float score) {}

  /** Configuration for per-headline escalation. */
  public record IngestConfig(
      boolean deepEnabled,
      boolean forceDeep,
      double minConfidence,
      boolean useCrossCheck,
      String ollamaModel,
      String ollamaBase,
      int numCtx,
      boolean verbose,
      int maxDeepPerBatch) {

    public static IngestConfig defaults() {
      return new IngestConfig(
          false,
          false,
          0.65,
          false,
          "gemma4-e4b-64k:latest",
          "http://localhost:11434",
          0,
          false,
          0);
    }
  }

  /** Outcome of one ingest() call. */
  public record IngestResult(int newScored, int deepUsed, int deepSkipped, int disagreements) {}

  public SentimentAggregator(FinBertSentiment finbert, Path seenFile) {
    this.finbert = finbert;
    this.seen = new SeenStore(seenFile);
  }

  /** Ingest with default config (FinBERT only, no escalation). */
  public int ingest(String symbol, List<FinnhubNewsClient.FinNewsArticle> articles) {
    return ingest(symbol, articles, IngestConfig.defaults()).newScored();
  }

  /**
   * Ingest with escalation config. Scores unseen headlines through FinBERT, optionally cross-checks
   * with FinGPT, and escalates to the Ollama deep analyzer when configured (deep flag / low
   * confidence / cross-check disagreement) and the deep budget allows.
   */
  public IngestResult ingest(
      String symbol, List<FinnhubNewsClient.FinNewsArticle> articles, IngestConfig cfg) {

    DeepBudget budget = cfg.maxDeepPerBatch() > 0 ? new DeepBudget(cfg.maxDeepPerBatch()) : null;

    int newScored = 0;
    int deepUsed = 0;
    int deepSkipped = 0;
    int disagreements = 0;

    for (FinnhubNewsClient.FinNewsArticle a : articles) {
      if (seen.alreadySeen(symbol, a.id(), a.datetime())) {
        continue;
      }
      seen.mark(symbol, a.id(), a.datetime());

      FinBertSentiment.SentimentResult fast;
      try {
        fast = finbert.analyze(a.headline());
      } catch (Exception e) {
        if (cfg.verbose()) {
          System.err.println("[SentimentAggregator] FinBERT skip: " + e.getMessage());
        }
        continue;
      }

      scores
          .computeIfAbsent(symbol, k -> new ArrayList<>())
          .add(new ScoredArticle(Instant.now(), fast.score()));
      newScored++;

      // --- optional cross-check with FinGPT -------------------------
      FinGptSentiment.FinGptResult crossCheck = null;
      if (cfg.useCrossCheck()) {
        try {
          crossCheck =
              new FinGptSentiment(cfg.ollamaBase(), "fingpt-sentiment").analyze(a.headline());
        } catch (Exception e) {
          if (cfg.verbose()) {
            System.err.println("[SentimentAggregator] FinGPT cross-check skip: " + e.getMessage());
          }
        }
      }

      boolean disagree = false;
      if (crossCheck != null) {
        disagree = !mapFinBertLabel(fast.sentiment()).equals(crossCheck.sentiment());
        if (disagree) {
          disagreements++;
        }
      }

      boolean shouldEscalate =
          (cfg.deepEnabled()
                  && (cfg.forceDeep() || fast.confidence() < (float) cfg.minConfidence()))
              || (cfg.useCrossCheck() && disagree);

      if (!shouldEscalate) {
        continue;
      }

      // --- deep budget gate ----------------------------------------
      if (budget != null && !budget.tryAcquire()) {
        deepSkipped++;
        if (cfg.verbose()) {
          System.err.println("[SentimentAggregator] deep skipped (budget): " + a.headline());
        }
        continue;
      }

      try {
        DeepAnalysis deep =
            new OllamaSentimentAnalyzer(cfg.ollamaBase(), cfg.ollamaModel(), null, cfg.numCtx())
                .analyze(a.headline());
        deepBySymbol.computeIfAbsent(symbol, k -> new ArrayList<>()).add(deep);
        deepUsed++;
      } catch (Exception e) {
        if (cfg.verbose()) {
          System.err.println("[SentimentAggregator] deep fallback: " + e.getMessage());
        }
      }
    }

    seen.save();
    return new IngestResult(newScored, deepUsed, deepSkipped, disagreements);
  }

  /** Weighted average sentiment for a symbol in [-1, +1], or 0 if no news. */
  public double score(String symbol) {
    List<ScoredArticle> all = scores.getOrDefault(symbol, List.of());
    double totalWeight = 0.0;
    double weighted = 0.0;
    Instant now = Instant.now();

    for (ScoredArticle s : all) {
      double ageMinutes = Duration.between(s.timestamp(), now).toMinutes();
      if (ageMinutes > WINDOW.toMinutes()) {
        continue; // stale
      }
      double weight = Math.pow(0.5, ageMinutes / HALF_LIFE_MINUTES);
      weighted += s.score() * weight;
      totalWeight += weight;
    }
    return totalWeight == 0.0 ? 0.0 : weighted / totalWeight;
  }

  /** Deep analyses collected for a symbol (may be empty). */
  public List<DeepAnalysis> deepAnalyses(String symbol) {
    return deepBySymbol.getOrDefault(symbol, List.of());
  }

  public long seenCount() {
    return seen.size();
  }

  private static String mapFinBertLabel(FinBertSentiment.TradingSentiment s) {
    return switch (s) {
      case BULLISH -> "positive";
      case BEARISH -> "negative";
      case NEUTRAL -> "neutral";
    };
  }

  // ------------------------------------------------------------------
  // Deep call budget
  // ------------------------------------------------------------------

  private static final class DeepBudget {
    private final int max;
    private int used;

    DeepBudget(int max) {
      this.max = max;
    }

    synchronized boolean tryAcquire() {
      if (used >= max) {
        return false;
      }
      used++;
      return true;
    }
  }

  private SentimentAggregator() {
    throw new UnsupportedOperationException();
  }
}
