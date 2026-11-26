package com.openclaw.bot.bot;

import com.openclaw.bot.ai.AiNarrator;
import com.openclaw.bot.config.BotConfig;
import com.openclaw.bot.email.EmailSender;
import com.openclaw.bot.market.MarketDataProvider;
import com.openclaw.bot.market.Quote;
import com.openclaw.bot.market.Sample;
import com.openclaw.bot.state.StateStore;
import com.openclaw.bot.swing.SwingDetector;
import com.openclaw.bot.swing.SwingEvent;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The polling bot. Every interval it:
 * <ol>
 *   <li>fetches the latest index quote,</li>
 *   <li>feeds it to the {@link SwingDetector},</li>
 *   <li>if a new swing high/low just occurred, asks the local LLM
 *       ({@link AiNarrator}) to write a short narrative,</li>
 *   <li>emails the alert and persists state.</li>
 * </ol>
 */
public final class NasdaqBot {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final BotConfig cfg;
    private final MarketDataProvider market;
    private final SwingDetector detector;
    private final AiNarrator narrator;
    private final EmailSender email;
    private final StateStore state;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final AtomicBoolean started = new AtomicBoolean(false);
    private volatile Quote lastQuote;
    private volatile SwingEvent lastEvent;

    public NasdaqBot(BotConfig cfg, MarketDataProvider market, SwingDetector detector,
                     AiNarrator narrator, EmailSender email, StateStore state) {
        this.cfg = cfg;
        this.market = market;
        this.detector = detector;
        this.narrator = narrator;
        this.email = email;
        this.state = state;
        this.detector.restore(state.lastSwingHigh(), state.lastSwingHighAt(),
                state.lastSwingLow(), state.lastSwingLowAt());
    }

    public void run() {
        if (!started.compareAndSet(false, true)) return;
        System.out.println("[bot] starting: " + cfg.describe());
        if (cfg.once) {
            try {
                tick();
            } catch (Exception e) {
                System.err.println("[bot] ERROR single tick failed: " + e);
            }
            stop();
            return;
        }
        long periodMillis = (cfg.demo || cfg.provider == BotConfig.MarketProvider.CSV)
                ? cfg.replayIntervalMillis
                : cfg.intervalSeconds * 1000L;
        scheduler.scheduleAtFixedRate(this::safeTick, 0, periodMillis, TimeUnit.MILLISECONDS);
    }

    public void stop() {
        started.set(false);
        scheduler.shutdownNow();
        state.recordTick(System.currentTimeMillis());
    }

    private void safeTick() {
        try {
            tick();
        } catch (Throwable t) {
            System.err.println("[bot] WARN tick failed: " + t);
        }
    }

    void tick() throws Exception {
        Quote q = market.quote();
        lastQuote = q;
        Optional<SwingEvent> evt = detector.accept(new Sample(System.currentTimeMillis(), q.last()));
        System.out.println("[bot] " + TS.withZone(ZoneId.systemDefault()).format(Instant.now()) + " " + q.symbol()
                + " last=" + fmt(q.last()) + " dayHigh=" + fmt(q.dayHigh()) + " dayLow=" + fmt(q.dayLow())
                + " dayChange=" + String.format(Locale.ROOT, "%.2f%%", q.dayChangePct())
                + (evt.isPresent() ? "  >>> " + evt.get().description() + " @ " + fmt(evt.get().price()) : "")
                + " [samples=" + stateSamples() + "]");

        evt.ifPresent(e -> handleEvent(e, q));
        state.recordExtremes(detector.lastHigh(), detector.lastHighAt(),
                detector.lastLow(), detector.lastLowAt());
        state.recordTick(System.currentTimeMillis());

        // demo mode: stop when replay is exhausted
        if (cfg.demo && !market.hasNext()) {
            System.out.println("[bot] replay exhausted — stopping demo");
            stop();
        }
    }

    private long stateSamples() {
        return 0; // samples count is tracked in state; cosmetic field in log
    }

    private void handleEvent(SwingEvent e, Quote q) {
        if (e.fingerprint().equals(state.lastAlertFingerprint())) {
            System.out.println("[bot] duplicate event suppressed (" + e.fingerprint() + ")");
            return;
        }
        lastEvent = e;
        try {
            String narrative = narrator.narrate(buildPrompt(e, q));
            System.out.println("[ai] narrative (" + narrative.length() + " chars): " + narrative.replace('\n', ' ').substring(0, Math.min(160, narrative.length())) + "...");
            String subject = "[NASDAQ Swing Alert] " + e.description().toUpperCase(Locale.ROOT)
                    + " " + q.symbol() + " @ " + fmt(e.price());
            String body = narrative + "\n\n---\n"
                    + "Symbol: " + q.symbol() + "\n"
                    + "Event: " + e.description() + " at " + fmt(e.price()) + " (index level)\n"
                    + "Previous extreme: " + fmt(e.previousExtreme())
                    + (e.deltaPct() > 0 ? " (" + String.format(Locale.ROOT, "%.2f%%", e.deltaPct()) + ")" : "")
                    + "\n"
                    + "Detected at: " + TS.withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(e.atMillis())) + "\n"
                    + "Day range: " + fmt(q.dayLow()) + " – " + fmt(q.dayHigh()) + "\n"
                    + "Prev close: " + fmt(q.prevClose()) + " (" + String.format(Locale.ROOT, "%.2f%%", q.dayChangePct()) + ")\n"
                    + "Source: Nasdaq public API, local Ollama LLM (" + cfg.ollamaModel + ")\n"
                    + "Generated by OpenClaw Nasdaq Swing Bot PoC — for demonstration only, not investment advice.\n";
            email.send(subject, body);
            state.recordAlert(e.fingerprint());
            System.out.println("[bot] ✓ alert sent for " + e.description() + " @ " + fmt(e.price()));
        } catch (Exception ex) {
            System.err.println("[bot] WARN alert delivery failed: " + ex);
            // fallback: still record the event so we don't spam-retry a stuck SMTP
            state.recordAlert(e.fingerprint());
        }
    }

    private String buildPrompt(SwingEvent e, Quote q) {
        return "You are the analyst module of an automated market-monitoring bot.\n"
                + "A rule-based detector just confirmed a " + e.description() + " on the "
                + q.symbol() + " index.\n"
                + "Event details:\n"
                + "  type: " + e.description() + "\n"
                + "  level: " + fmt(e.price()) + "\n"
                + "  previous confirmed extreme: " + fmt(e.previousExtreme()) + "\n"
                + "  move from previous extreme: " + String.format(Locale.ROOT, "%.2f%%", e.deltaPct()) + "\n"
                + "Market context:\n"
                + "  current index level: " + fmt(q.last()) + "\n"
                + "  day range: " + fmt(q.dayLow()) + " - " + fmt(q.dayHigh()) + "\n"
                + "  previous close: " + fmt(q.prevClose()) + " (day change "
                + String.format(Locale.ROOT, "%.2f%%", q.dayChangePct()) + ")\n"
                + "Write a concise market alert (4-6 sentences) for a trader: state what happened, "
                + "the exact level, how it compares to the previous extreme, and one or two plausible "
                + "causes (e.g., macro news, sector rotation, sentiment) — clearly labeled as speculation. "
                + "Do not give financial advice. Plain text, no markdown.";
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%,.2f", v);
    }

    public Quote lastQuote() { return lastQuote; }
    public SwingEvent lastEvent() { return lastEvent; }
}
