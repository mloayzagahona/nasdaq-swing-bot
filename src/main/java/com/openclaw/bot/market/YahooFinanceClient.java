package com.openclaw.bot.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;

/**
 * Alternative provider that would use Yahoo Finance chart API (^IXIC etc.).
 * Note: Yahoo frequently rate-limits datacenter IPs ("Too Many Requests"); this
 * provider is kept for environments where access is allowed. Prefer {@link NasdaqApiClient}.
 */
public final class YahooFinanceClient implements MarketDataProvider {

    private static final String CHART = "https://query1.finance.yahoo.com/v8/finance/chart/";

    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();
    private final String symbol;

    public YahooFinanceClient(String symbol) {
        this.symbol = symbol;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Override
    public Quote quote() throws IOException {
        String url = CHART + symbol + "?interval=1m&range=1d";
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", "Mozilla/5.0")
                .header("Accept", "application/json")
                .GET().build();
        try {
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new IOException("Yahoo chart HTTP " + resp.statusCode() + " (" + resp.body().substring(0, Math.min(120, resp.body().length())) + ")");
            }
            JsonNode meta = json.readTree(resp.body()).path("chart").path("result").path(0).path("meta");
            if (meta.isMissingNode()) throw new IOException("Yahoo chart meta missing");
            double last = meta.path("regularMarketPrice").asDouble();
            double prevClose = meta.path("chartPreviousClose").asDouble();
            double dayHigh = meta.path("regularMarketDayHigh").asDouble();
            double dayLow = meta.path("regularMarketDayLow").asDouble();
            Instant ts = Instant.ofEpochSecond(meta.path("regularMarketTime").asLong());
            if (last == 0) throw new IOException("Yahoo returned price 0");
            return new Quote(symbol, last, prevClose, dayHigh, dayLow, ts, "");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted calling Yahoo", e);
        }
    }
}
