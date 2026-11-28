/* (C)2026 */
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
import java.util.Locale;

/**
 * Market data provider backed by the public Nasdaq API (api.nasdaq.com). No API key required.
 * Symbols: COMP (Nasdaq Composite), NDX (Nasdaq-100), etc.
 *
 * <p>Endpoints used:
 *
 * <ul>
 *   <li>GET /api/quote/{symbol}/info?assetclass=index — live snapshot, previous close, session day
 *       range
 * </ul>
 */
public final class NasdaqApiClient implements MarketDataProvider {

  private static final String BASE = "https://api.nasdaq.com";
  private static final String UA =
      "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0"
          + " Safari/537.36";

  private final HttpClient http;
  private final ObjectMapper json = new ObjectMapper();
  private final String symbol;

  public NasdaqApiClient(String symbol) {
    this.symbol = symbol;
    this.http =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
  }

  @Override
  public Quote quote() throws IOException {
    String url = BASE + "/api/quote/" + symbol + "/info?assetclass=index";
    JsonNode root = getJson(url);
    JsonNode data = root.path("data");
    if (data.isMissingNode()) {
      throw new IOException(
          "Nasdaq API returned no data payload (status="
              + root.path("status").path("rCode").asText()
              + ")");
    }

    JsonNode primary = data.path("primaryData");
    double last = parseNumber(primary.path("lastSalePrice").asText());

    JsonNode keyStats = data.path("keyStats");
    double prevClose = parseNumber(keyStats.path("previousclose").path("value").asText());

    double dayHigh = Double.NaN, dayLow = Double.NaN;
    String dayRange = keyStats.path("dayrange").path("value").asText();
    if (dayRange != null && !dayRange.isBlank()) {
      String[] parts = dayRange.split("[-–]"); // hyphen or en-dash
      if (parts.length >= 2) {
        dayLow = parseNumber(parts[0].trim());
        dayHigh = parseNumber(parts[1].trim());
      }
    }
    if (Double.isNaN(dayHigh)) dayHigh = last;
    if (Double.isNaN(dayLow)) dayLow = last;

    String status = data.path("marketStatus").asText();
    Instant ts = Instant.now();

    return new Quote(symbol, last, prevClose, dayHigh, dayLow, ts, status);
  }

  private JsonNode getJson(String url) throws IOException {
    HttpRequest req =
        HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(20))
            .header("User-Agent", UA)
            .header("Accept", "application/json")
            .header("Accept-Language", "en-US,en;q=0.9")
            .GET()
            .build();
    try {
      HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
      if (resp.statusCode() != 200) {
        throw new IOException("Nasdaq API HTTP " + resp.statusCode() + " for " + url);
      }
      return json.readTree(resp.body());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted while calling Nasdaq API", e);
    }
  }

  /** Parse numbers like "26,214.17" or "--" (→ NaN). */
  static double parseNumber(String raw) {
    if (raw == null) return Double.NaN;
    String s = raw.trim().replace(",", "").replace("$", "");
    if (s.isEmpty() || s.equals("--") || s.equals("-") || s.equals("N/A")) return Double.NaN;
    try {
      return Double.parseDouble(s);
    } catch (NumberFormatException e) {
      return Double.NaN;
    }
  }

  @Override
  public String toString() {
    return String.format(Locale.ROOT, "NasdaqApiClient(symbol=%s)", symbol);
  }
}
