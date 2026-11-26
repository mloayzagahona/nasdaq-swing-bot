package com.openclaw.bot.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Calls a local Ollama server (default http://localhost:11434) using the
 * /api/generate endpoint. This keeps the "AI" part fully local — no cloud LLM
 * is involved. The configured model (e.g. gemma4-e4b-64k:latest,
 * mimo-v2.6:9b) is loaded on the fly by Ollama.
 */
public final class OllamaClient implements AiNarrator {

    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();
    private final String baseUrl;
    private final String model;
    private final Duration timeout;
    private final double temperature;

    public OllamaClient(String baseUrl, String model, int timeoutSeconds, double temperature) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.model = model;
        this.timeout = Duration.ofSeconds(timeoutSeconds);
        this.temperature = temperature;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Override
    public String narrate(String prompt) throws Exception {
        ObjectNode body = json.createObjectNode();
        body.put("model", model);
        body.put("prompt", prompt);
        body.put("stream", false);
        // Some installed models (e.g. gemma4-e4b-64k) default to a "thinking"
        // mode: they spend the entire num_predict budget on hidden chain-of-
        // thought and return an empty response. The alert narrative needs no
        // reasoning, so disable it (ignored by non-thinking models).
        body.put("think", false);
        ObjectNode options = body.putObject("options");
        options.put("temperature", temperature);
        options.put("num_predict", 500);

        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/api/generate"))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IOException("Ollama HTTP " + resp.statusCode() + ": " + truncate(resp.body(), 200));
        }
        String text = json.readTree(resp.body()).path("response").asText("");
        if (text.isBlank()) {
            throw new IOException("Ollama returned empty response");
        }
        return text.trim();
    }

    private static String truncate(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n);
    }

    @Override
    public String toString() {
        return "OllamaClient(" + baseUrl + ", model=" + model + ")";
    }
}
