/* (C)2026 */
package com.openclaw.bot.config;

import java.nio.file.Path;

/** Runtime configuration for the bot. Precedence: JVM arg > environment variable > default. */
/**
 * Configuration holder for the entire bot.
 * <p>Uses the Builder pattern to allow flexible configuration through CLI flags and environment variables.
 * <p>This class holds critical design choices, such as the swing detection parameters
 * ({@link #swingConfirm}, {@link #swingMinDeltaPct}) and the communication endpoints
 * (Ollama, SMTP).
 * @author OpenClaw Development Team
 */
public final class BotConfig {

  public enum MarketProvider {
    NASDAQ,
    YAHOO,
    FINNHUB,
    CSV
  }

  public final MarketProvider provider;
  public final String symbol;

  public final long intervalSeconds;
  public final boolean once; // run a single tick and exit
  public final boolean demo; // replay mode: fast paced, no real SMTP
  public final String replayFile; // CSV (millis,price) when provider == CSV
  public final long replayIntervalMillis;

  public final String ollamaBaseUrl;
  public final String ollamaModel;
  public final int ollamaTimeoutSeconds;
  public final double ollamaTemperature;

  public final String smtpHost; // empty => no real SMTP, alert is written to capture dir
  public final int smtpPort;
  public final String smtpUser;
  public final String smtpPassword;
  public final String mailFrom;
  public final String mailTo; // comma separated

  public final String finnhubApiKey; // required for provider == FINNHUB (env FINNHUB_API_KEY)

  public final Path stateFile;
  public final Path captureDir;

  public final int swingConfirm; // bars left/right to confirm a swing point
  public final double swingMinDeltaPct;

  private BotConfig(Builder b) {
    this.provider = b.provider;
    this.symbol = b.symbol;
    this.intervalSeconds = b.intervalSeconds;
    this.once = b.once;
    this.demo = b.demo;
    this.replayFile = b.replayFile;
    this.replayIntervalMillis = b.replayIntervalMillis;
    this.ollamaBaseUrl = b.ollamaBaseUrl;
    this.ollamaModel = b.ollamaModel;
    this.ollamaTimeoutSeconds = b.ollamaTimeoutSeconds;
    this.ollamaTemperature = b.ollamaTemperature;
    this.smtpHost = b.smtpHost;
    this.smtpPort = b.smtpPort;
    this.smtpUser = b.smtpUser;
    this.smtpPassword = b.smtpPassword;
    this.mailFrom = b.mailFrom;
    this.mailTo = b.mailTo;
    this.finnhubApiKey = b.finnhubApiKey;
    this.stateFile = b.stateFile;
    this.captureDir = b.captureDir;
    this.swingConfirm = b.swingConfirm;
    this.swingMinDeltaPct = b.swingMinDeltaPct;
  }

  public static BotConfig defaults() {
    return new Builder().build();
  }

  public static Builder builder() {
    return new Builder();
  }

  public String describe() {
    return "provider="
        + provider
        + " symbol="
        + symbol
        + " interval="
        + intervalSeconds
        + "s"
        + " ollama="
        + ollamaBaseUrl
        + " model="
        + ollamaModel
        + " smtp="
        + (smtpHost.isEmpty() ? "off(file-capture)" : smtpHost + ":" + smtpPort)
        + " state="
        + stateFile
        + " swingConfirm="
        + swingConfirm
        + " minDelta%="
        + swingMinDeltaPct;
  }

  public static final class Builder {
    private MarketProvider provider = MarketProvider.NASDAQ;
    private String symbol = "COMP";
    private long intervalSeconds = 60;
    private boolean once = false;
    private boolean demo = false;
    private String replayFile = "data/replay.csv";
    private long replayIntervalMillis = 1500;
    private String ollamaBaseUrl =
        System.getenv().getOrDefault("OLLAMA_BASE_URL", "http://localhost:11434");
    private String ollamaModel =
        System.getenv().getOrDefault("OLLAMA_MODEL", "gemma4-e4b-64k:latest");
    private int ollamaTimeoutSeconds = 90;
    private double ollamaTemperature = 0.3;
    private String smtpHost = System.getenv().getOrDefault("SMTP_HOST", "");
    private int smtpPort = Integer.parseInt(System.getenv().getOrDefault("SMTP_PORT", "587"));
    private String smtpUser = System.getenv().getOrDefault("SMTP_USERNAME", "");
    private String smtpPassword = System.getenv().getOrDefault("SMTP_PASSWORD", "");
    private String mailFrom = System.getenv().getOrDefault("MAIL_FROM", "nasdaq-swing-bot@local");
    private String mailTo = System.getenv().getOrDefault("MAIL_TO", "");
    private String finnhubApiKey = System.getenv().getOrDefault("FINNHUB_API_KEY", "");
    private Path stateFile =
        Path.of(System.getenv().getOrDefault("BOT_STATE", "state/bot-state.json"));
    private Path captureDir = Path.of(System.getenv().getOrDefault("BOT_CAPTURE_DIR", "capture"));
    private int swingConfirm = 2;
    private double swingMinDeltaPct = 0.05;

    public Builder provider(MarketProvider v) {
      this.provider = v;
      return this;
    }

    public Builder symbol(String v) {
      if (v != null && !v.isBlank()) this.symbol = v.trim().toUpperCase();
      return this;
    }

    public Builder intervalSeconds(long v) {
      if (v > 0) this.intervalSeconds = v;
      return this;
    }

    public Builder once(boolean v) {
      this.once = v;
      return this;
    }

    public Builder demo(boolean v) {
      this.demo = v;
      return this;
    }

    public Builder replayFile(String v) {
      if (v != null && !v.isBlank()) this.replayFile = v;
      return this;
    }

    public Builder replayIntervalMillis(long v) {
      if (v > 0) this.replayIntervalMillis = v;
      return this;
    }

    public Builder ollamaBaseUrl(String v) {
      if (v != null && !v.isBlank()) this.ollamaBaseUrl = v;
      return this;
    }

    public Builder ollamaModel(String v) {
      if (v != null && !v.isBlank()) this.ollamaModel = v;
      return this;
    }

    public Builder ollamaTimeoutSeconds(int v) {
      if (v > 0) this.ollamaTimeoutSeconds = v;
      return this;
    }

    public Builder ollamaTemperature(double v) {
      if (v >= 0) this.ollamaTemperature = v;
      return this;
    }

    public Builder smtpHost(String v) {
      if (v != null) this.smtpHost = v;
      return this;
    }

    public Builder smtpPort(int v) {
      if (v > 0) this.smtpPort = v;
      return this;
    }

    public Builder smtpUser(String v) {
      if (v != null) this.smtpUser = v;
      return this;
    }

    public Builder smtpPassword(String v) {
      if (v != null) this.smtpPassword = v;
      return this;
    }

    public Builder mailFrom(String v) {
      if (v != null && !v.isBlank()) this.mailFrom = v;
      return this;
    }

    public Builder mailTo(String v) {
      if (v != null) this.mailTo = v;
      return this;
    }

    public Builder finnhubApiKey(String v) {
      if (v != null) this.finnhubApiKey = v.trim();
      return this;
    }

    public Builder stateFile(String v) {
      if (v != null && !v.isBlank()) this.stateFile = Path.of(v);
      return this;
    }

    public Builder captureDir(String v) {
      if (v != null && !v.isBlank()) this.captureDir = Path.of(v);
      return this;
    }

    public Builder swingConfirm(int v) {
      if (v > 0) this.swingConfirm = v;
      return this;
    }

    public Builder swingMinDeltaPct(double v) {
      if (v >= 0) this.swingMinDeltaPct = v;
      return this;
    }

    public BotConfig build() {
      return new BotConfig(this);
    }
  }
}
