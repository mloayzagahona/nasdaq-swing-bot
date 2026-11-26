Here's a full Spring Boot integration: configuration, beans, a hybrid service, error handling, and a REST endpoint.

## 1. Add dependencies to `pom.xml`

```xml
<!-- Existing Spring Boot parent + starters... -->

<!-- DJL (for FinBERT) -->
<dependency>
    <groupId>ai.djl</groupId>
    <artifactId>api</artifactId>
    <version>0.39.0</version>
</dependency>
<dependency>
    <groupId>ai.djl.huggingface</groupId>
    <artifactId>tokenizers</artifactId>
    <version>0.40.0</version>
</dependency>
<dependency>
    <groupId>ai.djl.pytorch</groupId>
    <artifactId>pytorch-engine</artifactId>
    <version>0.40.0</version>
</dependency>
<dependency>
    <groupId>ai.djl.pytorch</groupId>
    <artifactId>pytorch-native-auto</artifactId>
    <version>1.9.1</version>
    <scope>runtime</scope>
</dependency>

<!-- Jackson 3 (for Ollama integration) -->
<dependency>
    <groupId>tools.jackson.core</groupId>
    <artifactId>jackson-databind</artifactId>
    <version>3.0.0</version>
</dependency>
```

## 2. Configuration properties — `application.yml`

```yaml
sentiment:
  finbert:
    model-path: /home/mloayzag/models/finbert
    min-confidence: 0.65        # below this, escalate to Ollama
  ollama:
    enabled: true               # opt-in: set false to only use FinBERT
    base-url: http://localhost:11434
    model: qwen3.5:9b
    timeout-seconds: 120
```

## 3. Properties class

```java
package com.yourbot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "sentiment")
public class SentimentProperties {

    private final Finbert finbert = new Finbert();
    private final Ollama ollama = new Ollama();

    public static class Finbert {
        private String modelPath;
        private double minConfidence = 0.65;
        // getters + setters
        public String getModelPath() { return modelPath; }
        public void setModelPath(String modelPath) { this.modelPath = modelPath; }
        public double getMinConfidence() { return minConfidence; }
        public void setMinConfidence(double minConfidence) { this.minConfidence = minConfidence; }
    }

    public static class Ollama {
        private boolean enabled = true;
        private String baseUrl = "http://localhost:11434";
        private String model = "qwen3.5:9b";
        private int timeoutSeconds = 120;
        // getters + setters
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
    }

    public Finbert getFinbert() { return finbert; }
    public Ollama getOllama() { return ollama; }
}
```

## 4. Bean configuration — `SentimentConfig.java`

**Important:** `Predictor` is **not thread-safe**, so the FinBERT bean hands out a *pool* of predictors rather than a singleton spot. The Ollama `HttpClient` is thread-safe, so that bean can be a plain singleton.

```java
package com.yourbot.config;

import com.yourbot.sentiment.FinBertSentiment;
import com.yourbot.sentiment.OllamaSentimentAnalyzer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

@Configuration
public class SentimentConfig {

    @Bean(destroyMethod = "close")
    public FinBertSentiment finBertSentiment(SentimentProperties props) {
        return new FinBertSentiment(
                Path.of(props.getFinbert().getModelPath()));
    }

    @Bean
    public OllamaSentimentAnalyzer ollamaSentimentAnalyzer(SentimentProperties props) {
        // Even when disabled, we build the bean; the service decides whether
        // to call it. This keeps wiring simple.
        return new OllamaSentimentAnalyzer(
                props.getOllama().getBaseUrl(),
                props.getOllama().getModel(),
                null);
    }
}
```

**Note on `@Bean(destroyMethod = "close")`:** Spring will call `FinBertSentiment.close()` on shutdown to release pooled predictors. Also add a `@PreDestroy` hook inside a thread-safe predictor pool — the simplest robust approach:

```java
// In FinBertSentiment, add a helper that hands a predictor to a thread:
public <T> T withPredictor(Function<Predictor<String, Classifications>, T> fn)
        throws ModelException, TranslateException {
    try (Predictor<String, Classifications> p = newPredictor()) {
        return fn.apply(p);
    }
}
```

## 5. Service — `SentimentService.java` (hybrid escalation)

```java
package com.yourbot.service;

import com.yourbot.config.SentimentProperties;
import com.yourbot.sentiment.FinBertSentiment;
import com.yourbot.sentiment.OllamaSentimentAnalyzer;
import com.yourbot.sentiment.OllamaSentimentAnalyzer.DeepAnalysis;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class SentimentService {

    public record AnalysisResult(
            String sentiment,        // bullish | bearish | neutral
            double score,
            double confidence,
            String source,            // "finbert" | "ollama" | "both"
            List<OllamaSentimentAnalyzer.Entity> entities,
            List<String> keyDrivers,
            List<String> caveats) { }

    private final FinBertSentiment finbert;
    private final OllamaSentimentAnalyzer ollama;
    private final double minConfidence;

    public SentimentService(FinBertSentiment finbert,
                            OllamaSentimentAnalyzer ollama,
                            SentimentProperties props) {
        this.finbert = finbert;
        this.ollama = ollama;
        this.minConfidence = props.getFinbert().getMinConfidence();
    }

    public AnalysisResult analyze(String headline) throws Exception {
        // Step 1: fast pass — FinBERT
        FinBertSentiment.SentimentResult fast = finbert.analyze(headline);

        if (!ollamaEnabled() || fast.confidence() >= (float) minConfidence) {
            // Confident enough, or Ollama disabled — return FinBERT verdict
            return new AnalysisResult(
                    fast.sentiment().name().toLowerCase(),
                    fast.score(),
                    fast.confidence(),
                    "finbert",
                    List.of(), List.of(), List.of());
        }

        // Step 2: escalation — deep analysis via Ollama
        DeepAnalysis deep = ollama.analyze(headline);
        return new AnalysisResult(
                deep.sentiment(),
                deep.score(),
                deep.confidence(),
                "ollama",
                deep.entities(),
                deep.keyDrivers(),
                deep.caveats());
    }

    private boolean ollamaEnabled() {
        // Load from properties (or a dedicated toggle)
        return true; // TODO: read from SentimentProperties.getOllama().isEnabled()
    }
}
```

## 6. REST controller — `SentimentController.java`

```java
package com.yourbot.web;

import com.yourbot.service.SentimentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/sentiment")
public class SentimentController {

    private final SentimentService service;

    public SentimentController(SentimentService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<?> analyze(@RequestBody Map<String, String> body) {
        String text = body.get("text");
        if (text == null || text.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "text is required"));
        }
        try {
            return ResponseEntity.ok(service.analyze(text));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/batch")
    public ResponseEntity<?> analyzeBatch(@RequestBody List<String> texts) {
        try {
            return ResponseEntity.ok(texts.stream()
                    .map(t -> {
                        try { return service.analyze(t); }
                        catch (Exception e) { return Map.of("error", e.getMessage()); }
                    })
                    .toList());
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", e.getMessage()));
        }
    }
}
```

## 7. Concurrency notes (important for Spring)

- **`@RestController` is multithreaded** — Spring's default Tomcat thread pool will call `analyze()` concurrently.
- **FinBERT `Predictor` is not thread-safe**: the `analyze()` method creates a fresh predictor per call (`try-with-resources`), which is safe but slower (model load is cached by DJL; a new predictor is cheap after first load). For heavy concurrency, follow DJL's recommendation: **one predictor per worker thread**. You can do this with:
  ```java
  // In FinBertSentiment: maintain a gauge of predictors per thread
  private final ThreadLocal<Predictor<String, Classifications>> threadPredictor =
          ThreadLocal.withInitial(() -> { /* creates + caches one per thread */ });
  ```
- **Ollama calls are blocking HTTP** — 2–10s each. In a web context, consider offloading to a background executor or using `@Async` + `CompletableFuture` so Tomcat threads aren't tied up:
  ```java
  @Async
  public CompletableFuture<AnalysisResult> analyzeAsync(String headline) { ... }
  ```
  (Enable `@EnableAsync` on your main app class.)

## 8. Lifecycle & shutdown

- `@Bean(destroyMethod = "close")` handles FinBERT cleanup — no extra code needed.
- The Ollama class keeps only an `HttpClient`; nothing to close.

## 9. Quick end-to-end test

```bash
# 1. Start Ollama with the model (if enabled)
ollama serve &
ollama pull qwen3.5:9b

# 2. Start your Spring Boot app
./mvnw spring-boot:run

# 3. Test
curl -X POST http://localhost:8080/api/sentiment \
  -H "Content-Type: application/json" \
  -d '{"text":"Tesla beats Q3 delivery estimates by 12%, but supply chain issues loom."}'
```

Expected response (Ollama path):

```json
{
  "sentiment": "bullish",
  "score": 0.55,
  "confidence": 0.7,
  "source": "ollama",
  "entities": [{"ticker": "TSLA", "name": "Tesla"}],
  "keyDrivers": ["record Q3 deliveries beating estimates by 12%"],
  "caveats": ["supply chain issues may weigh on Q4 margins"]
}
```

---

One design choice to confirm: do you want the sentiment calls **synchronous** (simple, blocking — you get the result in the HTTP response) or **asynchronous** (queue + polling/websocket so your API stays responsive during the 2–10s Ollama calls)? I can add the async variant with a job endpoint if that fits your bot better.













































cument (handy for piping into other tools)?