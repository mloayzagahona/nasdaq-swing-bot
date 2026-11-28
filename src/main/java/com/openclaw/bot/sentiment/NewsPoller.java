/* (C)2026 */
package com.openclaw.bot.sentiment;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

/**
 * Standalone polling loop: fetch Finnhub company news for the watchlist, ingest unseen headlines
 * through FinBERT (+ optional Ollama deep/cross-check), and log the rolling sentiment score per
 * symbol.
 *
 * <p>Run: FINNHUB_API_KEY=... mvn -q compile exec:java \
 * -Dexec.mainClass=com.openclaw.bot.sentiment.NewsPoller
 */
public final class NewsPoller {

  private static final List<String> SYMBOLS =
      List.of("NVDA", "MSFT", "AAPL", "AMZN", "META", "GOOGL", "TSLA");

  private static final long POLL_INTERVAL_MINUTES = 5;
  private static final int LOOKBACK_DAYS = 2;

  public static void main(String[] args) throws Exception {
    String apiKey = System.getenv("FINNHUB_API_KEY");
    if (apiKey == null || apiKey.isBlank()) {
      System.err.println("FINNHUB_API_KEY environment variable is required.");
      System.exit(2);
    }

    FinnhubNewsClient finnhub = new FinnhubNewsClient(apiKey);

    FinBertSentiment finbert = new FinBertSentiment(Path.of("/home/mloayzag/models/finbert"));
    SentimentAggregator agg =
        new SentimentAggregator(finbert, Path.of("/home/mloayzag/.finnhub_seen.json"));

    SentimentAggregator.IngestConfig cfg =
        new SentimentAggregator.IngestConfig(
            true, // deepEnabled
            false, // forceDeep
            0.65, // minConfidence
            true, // useCrossCheck
            "gemma4-e4b-64k:latest",
            "http://localhost:11434",
            0, // numCtx: auto (64k for gemma, else 8k)
            true, // verbose
            20); // max deep calls per poll

    System.out.println("NewsPoller started. Ctrl+C to stop.");

    while (true) {
      LocalDate to = LocalDate.now();
      LocalDate from = to.minusDays(LOOKBACK_DAYS);

      for (String symbol : SYMBOLS) {
        try {
          List<FinnhubNewsClient.FinNewsArticle> articles = finnhub.companyNews(symbol, from, to);

          SentimentAggregator.IngestResult r = agg.ingest(symbol, articles, cfg);

          System.out.printf(
              "%s: new=%d deep=%d skipped=%d disagreements=%d score=%.3f%n",
              symbol,
              r.newScored(),
              r.deepUsed(),
              r.deepSkipped(),
              r.disagreements(),
              agg.score(symbol));
        } catch (Exception e) {
          System.err.println("Poll failed for " + symbol + ": " + e.getMessage());
        }
      }

      Thread.sleep(POLL_INTERVAL_MINUTES * 60_000L);
    }
  }

  private NewsPoller() {}
}
