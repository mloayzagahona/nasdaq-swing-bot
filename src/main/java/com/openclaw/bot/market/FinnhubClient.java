package com.openclaw.bot.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

/**
 * Market data provider backed by Finnhub (finnhub.io) — real-time quotes.
 *
 * <p>Requires a free API key (env {@code FINNHUB_API_KEY} or CLI). Index symbols
 * are used as-is ({@code ^IXIC}, {@code ^NDX}, {@code ^GSPC} ...); the friendly
 * Nasdaq aliases used elsewhere in this project are translated:
 * <ul>
 *   <li>{@code COMP} → {@code ^IXIC} (Nasdaq Composite)</li>
 *   <li>{@code NDX} → {@code ^NDX} (Nasdaq-100)</li>
 * </ul>
 *
 * <p>Endpoint used: {@code GET /api/v1/quote?symbol={wire}&token={key}}.
 * Response: {@code {"c":current,"h":high,"l":low,"o":open,"pc":prevClose,"t":epochSec}}.
 */
public final class FinnhubClient implements MarketDataProvider {

    private static final String BASE = "https://finnhub.io/api/v1";
    private static final String DEFAULT_SYMBOL = "COMP";

    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();
    private final String symbol;      // user-facing symbol (COMP / NDX / ^IXIC ...)
    private final String wireSymbol;  // symbol sent to Finnhub
    private final String apiKey;
    private final String baseUrl;

    public FinnhubClient(String symbol, String apiKey) {
        this(symbol, apiKey, BASE, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    /** Package-private constructor for tests (inject fake server base + HttpClient). */
    FinnhubClient(String symbol, String apiKey, String baseUrl, HttpClient http) {
        String s = (symbol == null || symbol.isBlank()) ? DEFAULT_SYMBOL : symbol.trim().toUpperCase();
        this.symbol = s;
        this.wireSymbol = toFinnhubSymbol(s);
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.http = http;
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("Finnhub requires an API key (set FINNHUB_API_KEY)");
        }
    }

    private static String toFinnhubSymbol(String s) {
        return switch (s) {
            case "COMP" -> "^IXIC";
            case "NDX" -> "^NDX";
            default -> s; // already a wire symbol (^GSPC, ...)
        };
    }

    @Override
    public Quote quote() throws IOException {
        String url = baseUrl + "/quote?symbol=" + enc(wireSymbol) + "&token=" + enc(apiKey);
        JsonNode root = getJson(url);
        if (root.has("error")) {
            throw new IOException("Finnhub error: " + root.path("error").asText());
        }
        double last = root.path("c").asDouble(Double.NaN);
        double prevClose = root.path("pc").asDouble(Double.NaN);
        double dayHigh = root.path("h").asDouble(Double.NaN);
        double dayLow = root.path("l").asDouble(Double.NaN);
        long epochSec = root.path("t").asLong(0);
        Instant ts = epochSec > 0 ? Instant.ofEpochSecond(epochSec) : Instant.now();
        if (Double.isNaN(dayHigh)) dayHigh = last;
        if (Double.isNaN(dayLow)) dayLow = last;
        return new Quote(symbol, last, prevClose, dayHigh, dayLow, ts, "");
    }

    private JsonNode getJson(String url) throws IOException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "application/json")
                .header("User-Agent", "openclaw-nasdaq-bot/0.1.0")
                .GET()
                .build();
        try {
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new IOException("Finnhub HTTP " + resp.statusCode() + " for " + url);
            }
            return json.readTree(resp.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while calling Finnhub", e);
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "FinnhubClient(symbol=%s, wire=%s)", symbol, wireSymbol);
    }
}
