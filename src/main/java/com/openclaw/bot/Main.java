package com.openclaw.bot;

import com.openclaw.bot.ai.AiNarrator;
import com.openclaw.bot.ai.OllamaClient;
import com.openclaw.bot.bot.NasdaqBot;
import com.openclaw.bot.config.BotConfig;
import com.openclaw.bot.email.DebugSmtpSink;
import com.openclaw.bot.email.EmailSender;
import com.openclaw.bot.email.FileEmailSender;
import com.openclaw.bot.email.SmtpEmailSender;
import com.openclaw.bot.market.CsvReplayProvider;
import com.openclaw.bot.market.FinnhubClient;
import com.openclaw.bot.market.MarketDataProvider;
import com.openclaw.bot.market.NasdaqApiClient;
import com.openclaw.bot.market.YahooFinanceClient;
import com.openclaw.bot.state.StateStore;
import com.openclaw.bot.swing.SwingDetector;

import java.nio.file.Path;

/**
 * Entry point. Usage examples:
 * <pre>
 *   # live mode (Nasdaq public API + local Ollama)
 *   java -jar target/nasdaq-swing-bot-0.1.0.jar --symbol COMP --interval 60
 *
 *   # one tick, then exit (good for cron / smoke test)
 *   java -jar target/nasdaq-swing-bot-0.1.0.jar --once
 *
 *   # offline demo over replay data using the built-in debug SMTP sink
 *   java -jar target/nasdaq-swing-bot-0.1.0.jar --demo
 *
 *   # real SMTP
 *   SMTP_HOST=smtp.gmail.com SMTP_PORT=587 SMTP_USERNAME=x SMTP_PASSWORD=... \
 *     MAIL_TO=me@corp.com java -jar target/nasdaq-swing-bot-0.1.0.jar
 * </pre>
 */
public final class Main {

    public static void main(String[] args) throws Exception {
        BotConfig.Builder b = BotConfig.builder();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--symbol" -> b.symbol(args[++i]);
                case "--interval" -> b.intervalSeconds(Long.parseLong(args[++i]));
                case "--once" -> b.once(true);
                case "--demo" -> b.demo(true);
                case "--replay" -> b.replayFile(args[++i]);
                case "--model" -> b.ollamaModel(args[++i]);
                case "--ollama-url" -> b.ollamaBaseUrl(args[++i]);
                case "--confirm" -> b.swingConfirm(Integer.parseInt(args[++i]));
                case "--min-delta-pct" -> b.swingMinDeltaPct(Double.parseDouble(args[++i]));
                case "--smtp-host" -> b.smtpHost(args[++i]);
                case "--smtp-port" -> b.smtpPort(Integer.parseInt(args[++i]));
                case "--smtp-user" -> b.smtpUser(args[++i]);
                case "--from" -> b.mailFrom(args[++i]);
                case "--to" -> b.mailTo(args[++i]);
                case "--state" -> b.stateFile(args[++i]);
                case "--capture-dir" -> b.captureDir(args[++i]);
                case "--provider" -> b.provider(BotConfig.MarketProvider.valueOf(args[++i].toUpperCase()));
                case "--finnhub-key" -> b.finnhubApiKey(args[++i]);
                case "-h", "--help" -> { usage(); return; }
                default -> { System.err.println("Unknown argument: " + args[i]); usage(); return; }
            }
        }
        BotConfig cfg = b.build();

        MarketDataProvider market;
        if (cfg.demo || cfg.provider == BotConfig.MarketProvider.CSV) {
            market = new CsvReplayProvider(Path.of(cfg.replayFile), cfg.symbol);
        } else if (cfg.provider == BotConfig.MarketProvider.YAHOO) {
            market = new YahooFinanceClient(cfg.symbol);
        } else if (cfg.provider == BotConfig.MarketProvider.FINNHUB) {
            if (cfg.finnhubApiKey.isEmpty()) {
                throw new IllegalStateException("Provider FINNHUB requires an API key (set FINNHUB_API_KEY or --finnhub-key)");
            }
            market = new FinnhubClient(cfg.symbol, cfg.finnhubApiKey);
        } else {
            market = new NasdaqApiClient(cfg.symbol);
        }

        AiNarrator narrator = new OllamaClient(cfg.ollamaBaseUrl, cfg.ollamaModel,
                cfg.ollamaTimeoutSeconds, cfg.ollamaTemperature);

        EmailSender email;
        if (!cfg.smtpHost.isEmpty()) {
            email = new SmtpEmailSender(cfg.smtpHost, cfg.smtpPort, cfg.smtpUser, cfg.smtpPassword,
                    cfg.mailFrom, cfg.mailTo);
        } else if (cfg.demo) {
            // Loopback SMTP sink + the real Jakarta Mail client talking to it,
            // so the demo exercises the actual SMTP delivery path end-to-end.
            DebugSmtpSink sink = new DebugSmtpSink(2525, cfg.captureDir.resolve("debug-smtp.log"));
            email = new SmtpEmailSender("127.0.0.1", 2525, null, null,
                    "nasdaq-swing-bot@local", "demo@local");
        } else {
            email = new FileEmailSender(cfg.captureDir, cfg.mailFrom, cfg.mailTo);
        }

        SwingDetector detector = new SwingDetector(cfg.swingConfirm, cfg.swingMinDeltaPct);
        StateStore state = new StateStore(cfg.stateFile);

        NasdaqBot bot = new NasdaqBot(cfg, market, detector, narrator, email, state);
        Runtime.getRuntime().addShutdownHook(new Thread(bot::stop));
        bot.run();
    }

    private static void usage() {
        System.out.println("""
                Usage: java -jar nasdaq-swing-bot-0.1.0.jar [options]
                  --symbol SYM          index symbol (COMP default, NDX, ^IXIC for Yahoo)
                  --interval SEC        polling interval (default 60)
                  --once                run a single tick then exit
                  --demo                replay mode + built-in SMTP sink (no real email)
                  --replay FILE         CSV of millis,price for --demo / CSV provider
                  --provider NAME       NASDAQ | YAHOO | FINNHUB | CSV (default NASDAQ)
                  --finnhub-key KEY    Finnhub API key (env FINNHUB_API_KEY; required for FINNHUB)
                  --model MODEL         local Ollama model tag
                  --ollama-url URL      Ollama server base URL (default http://localhost:11434)
                  --confirm N           swing confirmation bars (default 2)
                  --min-delta-pct PCT   min % vs previous extreme to alert (default 0.05)
                  --smtp-host HOST      SMTP server (env SMTP_HOST)
                  --smtp-port PORT      SMTP port (env SMTP_PORT, default 587)
                  --smtp-user USER      SMTP username (env SMTP_USERNAME)
                  --to ADDR[,ADDR..]   recipients (env MAIL_TO)
                  --from ADDR           sender (env MAIL_FROM)
                  --state FILE          state file (default state/bot-state.json)
                  --capture-dir DIR     offline alert capture dir (default capture)
                """);
    }
}
