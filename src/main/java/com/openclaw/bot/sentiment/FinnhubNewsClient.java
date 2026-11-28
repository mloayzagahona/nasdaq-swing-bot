/* (C)2026 */
package com.openclaw.bot.sentiment;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Minimal Finnhub news client (free tier). No external dependencies beyond JDK HttpClient + Jackson
 * 3.
 */
public final class FinnhubNewsClient {

  /** One article as returned by GET /company-news. */
  public record FinNewsArticle(
      long id,
      Instant datetime,
      String headline,
      String summary,
      String source,
      String url,
      String related) {}

  private static final String BASE = "https://finnhub.io/api/v1";

  private final String apiKey;
  private final HttpClient httpClient;
  private final JsonMapper mapper = JsonMapper.builder().build();

  public FinnhubNewsClient(String apiKey) {
    this.apiKey = apiKey;
    this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
  }

  /**
   * Fetches company news for a symbol between two dates (inclusive).
   *
   * @throws IOException if the HTTP call fails or returns non-200
   * @throws InterruptedException if interrupted while waiting
   */
  public List<FinNewsArticle> companyNews(String symbol, LocalDate from, LocalDate to)
      throws IOException, InterruptedException {

    String url = BASE + "/company-news" + "?symbol=" + symbol + "&from=" + from + "&to=" + to;

    HttpRequest request =
        HttpRequest.newBuilder(URI.create(url))
            .header("X-Finnhub-Token", apiKey)
            .timeout(Duration.ofSeconds(20))
            .GET()
            .build();

    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

    if (response.statusCode() != 200) {
      throw new IOException(
          "Finnhub returned HTTP " + response.statusCode() + ": " + response.body());
    }

    return parse(response.body());
  }

  private List<FinNewsArticle> parse(String body) throws IOException {
    JsonNode root = mapper.readTree(body);
    List<FinNewsArticle> out = new ArrayList<>();
    if (!root.isArray()) {
      return out;
    }
    for (JsonNode n : root) {
      out.add(
          new FinNewsArticle(
              n.path("id").asLong(-1),
              Instant.ofEpochSecond(n.path("datetime").asLong(0)),
              n.path("headline").asText(""),
              n.path("summary").asText(""),
              n.path("source").asText(""),
              n.path("url").asText(""),
              n.path("related").asText("")));
    }
    return out;
  }

  private FinnhubNewsClient() {
    throw new UnsupportedOperationException();
  }
}
