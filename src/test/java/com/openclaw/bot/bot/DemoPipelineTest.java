/* (C)2026 */
package com.openclaw.bot.bot;

import static org.junit.jupiter.api.Assertions.*;

import com.openclaw.bot.ai.AiNarrator;
import com.openclaw.bot.config.BotConfig;
import com.openclaw.bot.email.EmailSender;
import com.openclaw.bot.email.FileEmailSender;
import com.openclaw.bot.market.CsvReplayProvider;
import com.openclaw.bot.market.MarketDataProvider;
import com.openclaw.bot.state.StateStore;
import com.openclaw.bot.swing.SwingDetector;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** End-to-end: replay data → detector → narrator → email file. */
class DemoPipelineTest {

  @TempDir Path tmp;

  private static int countFiles(Path dir) throws Exception {
    if (!Files.exists(dir)) return 0;
    try (var s = Files.list(dir)) {
      return (int) s.filter(p -> p.toString().endsWith(".eml")).count();
    }
  }

  @Test
  void fullPipelineEmitsEmailsForEverySwingEvent() throws Exception {
    Path fixture = Path.of("data/replay.csv");
    MarketDataProvider market = new CsvReplayProvider(fixture, "COMP");

    BotConfig cfg =
        BotConfig.builder()
            .symbol("COMP")
            .swingConfirm(2)
            .swingMinDeltaPct(0.05)
            .stateFile(tmp.resolve("state.json").toString())
            .captureDir(tmp.resolve("capture").toString())
            .build();

    SwingDetector detector = new SwingDetector(cfg.swingConfirm, cfg.swingMinDeltaPct);
    StateStore state = new StateStore(cfg.stateFile);
    AiNarrator narrator =
        p -> "Test narrative: confirmed " + p.split("type: ")[1].split("\n")[0] + ".";
    EmailSender email = new FileEmailSender(cfg.captureDir, cfg.mailFrom, cfg.mailTo);

    NasdaqBot bot = new NasdaqBot(cfg, market, detector, narrator, email, state);
    // drive ticks manually until replay exhausted (max 100)
    int ticks = 0;
    while (market.hasNext() && ticks < 100) {
      bot.tick();
      ticks++;
    }
    assertEquals(41, ticks);
    assertEquals(4, countFiles(cfg.captureDir), "expected 4 alert emails");

    String allBodies;
    try (var s = Files.list(cfg.captureDir)) {
      allBodies =
          s.filter(p -> p.toString().endsWith(".eml"))
              .map(p -> {
                try {
                  return Files.readString(p);
                } catch (Exception e) {
                  throw new RuntimeException(e);
                }
              })
              .reduce("", (a, b) -> a + "\n" + b);
    }
    assertTrue(allBodies.contains("swing HIGH") || allBodies.contains("swing LOW"));
    assertTrue(
        allBodies.contains("113.10"), "latest event price should be in an email: " + allBodies);

    // state persisted with a fingerprint (no re-alert after restart)
    assertFalse(state.lastAlertFingerprint().isEmpty());
    assertNotNull(state.lastSwingHigh());
    assertEquals(113.10, state.lastSwingHigh(), 1e-9);
  }
}
