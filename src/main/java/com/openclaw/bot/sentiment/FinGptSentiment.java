/* (C)2026 */
package com.openclaw.bot.sentiment;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Thin Ollama client for the FinGPT sentiment classifier (fingpt-sentiment). Trained to output
 * exactly one of: negative/neutral/positive.
 */
public final class FinGptSentiment {

  /** Sentiment result from FinGPT. */
  public record FinGptResult(
      String sentiment, // negative | neutral | positive
      double confidence) { // not provided by classifier; fixed at 1.0

    @Override
    public String toString() {
      return "FinGptResult{"
          + "sentiment='"
          + sentiment
          + '\''
          + ", confidence="
          + confidence
          + '}';
    }
  }

  private final String baseUrl;
  private final String model;
  private final HttpClient httpClient;
  private final JsonMapper mapper = JsonMapper.builder().build();

  public FinGptSentiment(String baseUrl, String model) {
    this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    this.model = model;
    this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
  }

  public FinGptResult analyze(String text)
      throws OllamaException, java.io.IOException, InterruptedException {

    ObjectNode payload = mapper.createObjectNode();
    payload.put("model", model);

    ArrayNode messages = payload.putArray("messages");
    ObjectNode user = messages.addObject();
    user.put("role", "user");
    user.put("content", text);

    payload.put("stream", false);

    ObjectNode options = payload.putObject("options");
    options.put("temperature", 0.0);
    options.put("num_predict", 8);

    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/api/chat"))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(30))
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)))
            .build();

    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

    if (response.statusCode() != 200) {
      throw new OllamaException(
          "Ollama returned HTTP " + response.statusCode() + ": " + response.body());
    }

    String content =
        mapper
            .readTree(response.body())
            .path("message")
            .path("content")
            .asText("")
            .trim()
            .toLowerCase();

    // Normalize the classifier output.
    String sentiment;
    if (content.contains("positive")) {
      sentiment = "positive";
    } else if (content.contains("negative")) {
      sentiment = "negative";
    } else if (content.contains("neutral")) {
      sentiment = "neutral";
    } else {
      throw new OllamaException("Unexpected FinGPT output: " + content);
    }

    return new FinGptResult(sentiment, 1.0);
  }

  /** Exception for Ollama-specific failures. */
  public static final class OllamaException extends Exception {
    public OllamaException(String message) {
      super(message);
    }

    public OllamaException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
