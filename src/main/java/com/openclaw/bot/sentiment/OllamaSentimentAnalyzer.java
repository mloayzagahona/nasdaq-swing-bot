package com.openclaw.bot.sentiment;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Optional deep-analysis sentiment analyzer backed by Ollama.
 *
 * <p>Opt-in by design: this class is only instantiated when the caller wants
 * richer output than FinBERT provides (entities, key drivers, caveats).
 * It requires a running Ollama server (default: http://localhost:11434)
 * with a model such as qwen3.5:9b pulled.
 *
 * <p>Uses Jackson 3 ({@code tools.jackson}) — no dependency on Jackson 2.
 * This class is independent of {@link FinBertSentiment}.
 */
public final class OllamaSentimentAnalyzer {

    /** Result of a deep analysis. */
    public record DeepAnalysis(
            String sentiment,        // bullish | bearish | neutral
            double score,            // -1.0 .. +1.0
            double confidence,       // 0.0 .. 1.0
            List<Entity> entities,
            List<String> keyDrivers,
            List<String> caveats,
            String rawModelOutput) {

        @Override
        public String toString() {
            return "DeepAnalysis{" +
                    "sentiment='" + sentiment + '\'' +
                    ", score=" + score +
                    ", confidence=" + confidence +
                    ", entities=" + entities +
                    ", keyDrivers=" + keyDrivers +
                    ", caveats=" + caveats +
                    '}';
        }
    }

    /** A company/financial instrument mentioned in the text. */
    public record Entity(String ticker, String name) { }

    private static final String DEFAULT_SYSTEM_PROMPT = """
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

    /** Uses default local Ollama endpoint and qwen3.5:9b. */
    public OllamaSentimentAnalyzer() {
        this("http://localhost:11434", "qwen3.5:9b", DEFAULT_SYSTEM_PROMPT);
    }

    /**
     * @param baseUrl Ollama server URL, e.g. "http://localhost:11434"
     * @param model   model name as shown by {@code ollama list}
     * @param systemPrompt custom system prompt (or null for default)
     */
    public OllamaSentimentAnalyzer(String baseUrl, String model, String systemPrompt) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.model = model;
        this.systemPrompt = (systemPrompt == null) ? DEFAULT_SYSTEM_PROMPT : systemPrompt;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * Sends a headline to Ollama and parses the structured JSON response.
     *
     * <p>Jackson 3 exceptions are unchecked — only HTTP-level checked
     * exceptions remain.
     *
     * @throws OllamaException     if the server rejects the request or the
     *                            model output is not valid JSON per schema
     * @throws java.io.IOException if the HTTP call fails
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    public DeepAnalysis analyze(String text)
            throws OllamaException, java.io.IOException, InterruptedException {

        ObjectNode payload = mapper.createObjectNode();
        payload.put("model", model);

        ArrayNode messages = payload.putArray("messages");
        ObjectNode system = messages.addObject();
        system.put("role", "system");
        system.put("content", systemPrompt);

        ObjectNode user = messages.addObject();
        user.put("role", "user");
        user.put("content", text);

        // Qwen3-family models run a "thinking" pass by default and can spend
        // the whole num_predict budget on it, returning empty content.
        payload.put("stream", false);
        payload.put("think", false);          // <-- disable reasoning mode
        payload.put("format", "json");        // (optional) enforce JSON output

        ObjectNode options = payload.putObject("options");
        options.put("temperature", 0.1);
        options.put("num_predict", 2048);     // was 512 — was too small for reasoning
        options.put("num_ctx", 8192);         // context window headroom

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/chat"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(120))
                .POST(HttpRequest.BodyPublishers.ofString(
                        mapper.writeValueAsString(payload)))
                .build();

        HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new OllamaException("Ollama returned HTTP " + response.statusCode()
                    + ": " + response.body());
        }

        JsonNode root = mapper.readTree(response.body());
        JsonNode message = root.path("message");
        String content = message.path("content").asText("");

        // GUARD: empty content must never silently become a "neutral" analysis.
        if (content.isBlank()) {
            String reason = root.path("done_reason").asText("unknown");
            String thinking = message.path("thinking").asText("");
            throw new OllamaException("Ollama returned empty content (done_reason="
                    + reason + ", thinking_length=" + thinking.length() + ")");
        }

        return parseAnalysis(content);
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
                    entities.add(new Entity(
                            e.path("ticker").asText(""),
                            e.path("name").asText("")));
                }
            }

            List<String> drivers = toStringList(node.path("key_drivers"));
            List<String> caveats = toStringList(node.path("caveats"));

            return new DeepAnalysis(sentiment, score, confidence,
                    entities, drivers, caveats, modelJson);
        } catch (Exception e) {
            throw new OllamaException("Failed to parse model output: "
                    + modelJson, e);
        }
    }

    private static List<String> toStringList(JsonNode array) {
        List<String> out = new ArrayList<>();
        if (array.isArray()) {
            array.forEach(item -> out.add(item.asText()));
        }
        return out;
    }

    /** JSON-escapes a string for embedding in the payload. */
    private static String jsonString(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}