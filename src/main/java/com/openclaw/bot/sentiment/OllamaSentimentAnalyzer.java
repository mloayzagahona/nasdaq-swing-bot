/* (C)2026 */
package com.openclaw.bot.sentiment;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Optional deep-analysis sentiment analyzer backed by Ollama.
 *
 * <p>Opt-in by design: this class is only instantiated when the caller wants richer output than
 * FinBERT provides (entities, key drivers, caveats). It requires a running Ollama server (default:
 * http://localhost:11434) with a model such as qwen3.5:9b pulled.
 *
 * <p>Uses Jackson 3 ({@code tools.jackson}) — no dependency on Jackson 2. This class is independent
 * of {@link FinBertSentiment}.
 */
public final class OllamaSentimentAnalyzer {

  private static final Logger LOG = LoggerFactory.getLogger(OllamaSentimentAnalyzer.class);

  /** Result of a deep analysis. */
  public record DeepAnalysis(
      String sentiment, // bullish | bearish | neutral
      double score, // -1.0 .. +1.0
      double confidence, // 0.0 .. 1.0
      List<Entity> entities,
      List<String> keyDrivers,
      List<String> caveats,
      String rawModelOutput) {

    @Override
    public String toString() {
      return "DeepAnalysis{"
          + "sentiment='"
          + sentiment
          + '\''
          + ", score="
          + score
          + ", confidence="
          + confidence
          + ", entities="
          + entities
          + ", keyDrivers="
          + keyDrivers
          + ", caveats="
          + caveats
          + '}';
    }
  }

  /** A company/financial instrument mentioned in the text. */
  public record Entity(String ticker, String name) {}

  private static final String DEFAULT_SYSTEM_PROMPT =
      """
      You are a senior financial sentiment analyst specializing in news text.
      Analyze the provided text and respond with a single JSON object with
      exactly these fields:

      {
        "sentiment": "bullish" | "bearish" | "neutral",
        "score": <number between -1.0 and +1.0>,
        "confidence": <number between 0.0 and 1.0>,
        "entities": [{"ticker": "<TICKER>", "name": "<Company Name>"}],
        "key_drivers": ["<short phrase from the text>", ...],
        "caveats": ["<reason the text may mislead>", ...]
      }

      Rules:
      - Base your analysis ONLY on the provided text. Never infer facts that
        are not present.
      - "bullish" means a positive/optimistic outlook (score > +0.2);
        "bearish" means negative/pessimistic (score < -0.2);
        otherwise use "neutral" (score between -0.2 and +0.2).
      - Scale scores by strength of language: "smashes" or "misses badly"
        should be further from zero than "in line" or "slight dip".
      - Reduce confidence when the text uses hedging, uncertainty, or mixed
        signals ("may", "could", "but", "despite", "unclear").
      - Entities: only companies/financial instruments explicitly mentioned.
        Use the ticker when you can identify it; otherwise set "ticker" to ""
        and put the proper name in "name". At most 5 entities.
      - key_drivers: 2-5 short verbatim phrases from the text that most drive
        your judgment — do not paraphrase them.
      - caveats: 0-3 reasons the text could mislead (missing context,
        forward-looking statements, ambiguity, conflicting clauses).
      - Output ONLY the JSON object. No markdown, no code fences, no comments,
        no trailing text.
      """;

  private final String baseUrl;
  private final String model;
  private final String systemPrompt;
  private final HttpClient httpClient;

  // Jackson 3: JsonMapper is the concrete class, built via its builder.
  private final JsonMapper mapper = JsonMapper.builder().build();

  /** Full 64k context window for gemma4-e4b-64k:latest. */
  private static final int NUM_CTX_FULL = 65536;

  /** Default context window for models without a large-context tag. */
  private static final int NUM_CTX_DEFAULT = 8192;

  private final int numCtxOverride; // 0 = auto-select based on model tag

  /** Uses default local Ollama endpoint and qwen3.5:9b and gemma4-e4b-64k:latest */
  public OllamaSentimentAnalyzer() {
    this("http://localhost:11434", "qwen3.5:9b", DEFAULT_SYSTEM_PROMPT, 0);
  }

  public OllamaSentimentAnalyzer(String baseUrl, String model, String systemPrompt) {
    this(baseUrl, model, systemPrompt, 0);
  }

  /**
   * @param baseUrl Ollama server URL, e.g. "http://localhost:11434"
   * @param model model name as shown by {@code ollama list}
   * @param systemPrompt custom system prompt (or null for default)
   */
  /**
   * @param numCtxOverride 0 = auto-select (64k for "...64k..." tags, else 8k)
   */
  public OllamaSentimentAnalyzer(
      String baseUrl, String model, String systemPrompt, int numCtxOverride) {
    this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    this.model = model;
    this.systemPrompt = (systemPrompt == null) ? DEFAULT_SYSTEM_PROMPT : systemPrompt;
    this.numCtxOverride = numCtxOverride;
    this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
  }

  /**
   * Sends a headline to Ollama and parses the structured JSON response.
   *
   * <p>Qwen3-family models may spend the entire generation budget on their "thinking" pass and
   * return an empty {@code content}. We request {@code think=false} and retry once with a much
   * larger budget before giving up — this keeps the batch pipeline resilient to transient empty
   * responses.
   *
   * @throws OllamaException if the server rejects the request, the model output is not valid JSON
   *     per schema, or content is empty after the retry
   * @throws IOException if the HTTP call fails
   * @throws InterruptedException if the thread is interrupted while waiting
   */
  public DeepAnalysis analyze(String text)
      throws OllamaException, IOException, InterruptedException {

    // Attempt 1: normal budget
    String content = requestContent(text, 2048);

    // Attempt 2 (retry): transient empty response — much larger budget.
    if (content.isBlank()) {
      content = requestContent(text, 8192);
    }

    if (content.isBlank()) {
      throw new OllamaException(
          "Ollama returned empty content after retry "
              + "(model="
              + model
              + ", text=\""
              + truncate(text)
              + "\")");
    }

    return parseAnalysis(content);
  }

  /**
   * Performs a single chat request and returns the model's content string. Never throws on empty
   * content — returns it as-is so the caller can retry.
   */
  private String requestContent(String text, int numPredict)
      throws OllamaException, IOException, InterruptedException {

    // Build the request body as a proper JSON tree — this guarantees
    // correct escaping of quotes, newlines, and Unicode in the prompts.
    ObjectNode payload = mapper.createObjectNode();
    payload.put("model", model);

    ArrayNode messages = payload.putArray("messages");

    ObjectNode system = messages.addObject();
    system.put("role", "system");
    system.put("content", systemPrompt);

    ObjectNode user = messages.addObject();
    user.put("role", "user");
    user.put("content", text);

    payload.put("stream", false);
    payload.put("think", false); // Qwen3-family only; ignored by Gemma
    payload.put("format", "json"); // enforce valid JSON output

    ObjectNode options = payload.putObject("options");
    options.put("temperature", 0.1);
    options.put("num_predict", numPredict);

    // Per-model context selection: models tagged "64k" get the full window
    // (e.g. gemma4-e4b-64k); everything else stays at a sane 8k default
    // (e.g. qwen3.5:9b) to avoid paying the huge KV-cache memory cost.
    // Per-model context selection: explicit --num-ctx wins; otherwise models
    // tagged "64k" get the full window, everything else defaults to 8k.
    int numCtx =
        numCtxOverride > 0
            ? numCtxOverride
            : (model.toLowerCase().contains("64k") ? NUM_CTX_FULL : NUM_CTX_DEFAULT);
    options.put("num_ctx", numCtx);

    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/api/chat"))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(120))
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)))
            .build();

    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

    if (response.statusCode() != 200) {
      throw new OllamaException(
          "Ollama returned HTTP " + response.statusCode() + ": " + response.body());
    }

    // Unwrap: response -> message -> content (the model's JSON string)
    JsonNode root = mapper.readTree(response.body());
    JsonNode message = root.path("message");
    String content = message.path("content").asText("");

    // Diagnostic when it comes back empty — helps tell "budget exhausted"
    // (done_reason=length) apart from other causes.
    if (content.isBlank()) {
      String reason = root.path("done_reason").asText("unknown");
      String thinking = message.path("thinking").asText("");
      LOG.debug("empty content: done_reason={}, thinking_length={}", reason, thinking.length());
    }

    return content;
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

  // ------------------------------------------------------------------
  // Private helpers
  // ------------------------------------------------------------------

  private DeepAnalysis parseAnalysis(String modelJson) throws OllamaException {
    try {
      JsonNode node = mapper.readTree(modelJson);

      String sentiment = node.path("sentiment").asText("neutral").toLowerCase();
      double score = node.path("score").asDouble(0.0);
      double confidence = node.path("confidence").asDouble(0.0);

      List<Entity> entities = new ArrayList<>();
      JsonNode entitiesNode = node.path("entities");
      if (entitiesNode.isArray()) {
        for (JsonNode e : entitiesNode) {
          entities.add(new Entity(e.path("ticker").asText(""), e.path("name").asText("")));
        }
      }

      List<String> drivers = toStringList(node.path("key_drivers"));
      List<String> caveats = toStringList(node.path("caveats"));

      return new DeepAnalysis(sentiment, score, confidence, entities, drivers, caveats, modelJson);
    } catch (Exception e) {
      throw new OllamaException("Failed to parse model output: " + modelJson, e);
    }
  }

  private static List<String> toStringList(JsonNode array) {
    List<String> out = new ArrayList<>();
    if (array.isArray()) {
      array.forEach(item -> out.add(item.asText()));
    }
    return out;
  }

  private static String truncate(String s) {
    if (s.length() <= 80) {
      return s;
    }
    return s.substring(0, 80) + "...";
  }
}
