Here's the full async variant: a job queue backed by an in-memory store, a background executor, and two new endpoints (submit + poll).

## 1. Config additions — `application.yml`

```yaml
sentiment:
  finbert:
    model-path: /home/mloayzag/models/finbert
    min-confidence: 0.65
  ollama:
    enabled: true
    base-url: http://localhost:11434
    model: qwen3.5:9b
    timeout-seconds: 120
  jobs:
    pool-size: 4              # concurrent workers
    retention-minutes: 60     # how long finished jobs stay queryable
```

Extend `SentimentProperties` with:

```java
private final Jobs jobs = new Jobs();

public static class Jobs {
    private int poolSize = 4;
    private int retentionMinutes = 60;
    // getters + setters for poolSize and retentionMinutes
}

public Jobs getJobs() { return jobs; }
```

## 2. Job model — `SentimentJob.java`

```java
package com.yourbot.job;

import com.yourbot.service.SentimentService.AnalysisResult;

import java.time.Instant;

public final class SentimentJob {

    public enum Status { PENDING, PROCESSING, COMPLETED, FAILED }

    private final String id;
    private final String text;
    private final Instant createdAt;
    private volatile Status status = Status.PENDING;
    private volatile AnalysisResult result;
    private volatile String error;
    private volatile Instant startedAt;
    private volatile Instant completedAt;

    public SentimentJob(String id, String text) {
        this.id = id;
        this.text = text;
        this.createdAt = Instant.now();
    }

    public String getId() { return id; }
    public String getText() { return text; }
    public Instant getCreatedAt() { return createdAt; }
    public Status getStatus() { return status; }
    public AnalysisResult getResult() { return result; }
    public String getError() { return error; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getCompletedAt() { return completedAt; }

    public void markProcessing() {
        this.status = Status.PROCESSING;
        this.startedAt = Instant.now();
    }

    public void complete(AnalysisResult result) {
        this.result = result;
        this.status = Status.COMPLETED;
        this.completedAt = Instant.now();
    }

    public void fail(String message) {
        this.error = message;
        this.status = Status.FAILED;
        this.completedAt = Instant.now();
    }
}
```

## 3. Job store — `SentimentJobStore.java` (in-memory, thread-safe, TTL)

```java
package com.yourbot.job;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class SentimentJobStore {

    private final Map<String, SentimentJob> jobs = new ConcurrentHashMap<>();
    private final long retentionMillis;

    public SentimentJobStore(SentimentProperties props) {
        this.retentionMillis = Duration.ofMinutes(props.getJobs().getRetentionMinutes()).toMillis();
    }

    public void save(SentimentJob job) {
        jobs.put(job.getId(), job);
    }

    public Optional<SentimentJob> find(String id) {
        SentimentJob job = jobs.get(id);
        if (job == null) {
            return Optional.empty();
        }
        // Expire old jobs silently
        if (job.getCompletedAt() != null
                && job.getCompletedAt().isBefore(Instant.now().minusMillis(retentionMillis))) {
            jobs.remove(id);
            return Optional.empty();
        }
        return Optional.of(job);
    }

    public List<SentimentJob> activeJobs() {
        return new ArrayList<>(jobs.values());
    }

    /** Called by a scheduler or on-demand to purge expired entries. */
    public void purgeExpired() {
        Instant cutoff = Instant.now().minusMillis(retentionMillis);
        jobs.values().removeIf(job ->
                job.getCompletedAt() != null && job.getCompletedAt().isBefore(cutoff));
    }
}
```

## 4. Job service — `SentimentJobService.java`

```java
package com.yourbot.job;

import com.yourbot.config.SentimentProperties;
import com.yourbot.service.SentimentService;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@Service
public class SentimentJobService {

    private final SentimentService sentimentService;
    private final SentimentJobStore jobStore;
    private final ExecutorService executor;

    public SentimentJobService(SentimentService sentimentService,
                               SentimentJobStore jobStore,
                               SentimentProperties props) {
        this.sentimentService = sentimentService;
        this.jobStore = jobStore;
        this.executor = Executors.newFixedThreadPool(props.getJobs().getPoolSize());
    }

    /** Submits a headline for background analysis; returns the job id. */
    public String submit(String text) {
        String id = UUID.randomUUID().toString();
        SentimentJob job = new SentimentJob(id, text);
        jobStore.save(job);

        CompletableFuture.runAsync(() -> process(job), executor);
        return id;
    }

    private void process(SentimentJob job) {
        job.markProcessing();
        try {
            var result = sentimentService.analyze(job.getText());
            job.complete(result);
        } catch (Exception e) {
            job.fail(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    /** Polls a job's status. Returns Optional.empty if unknown/expired. */
    public java.util.Optional<SentimentJob> getJob(String id) {
        return jobStore.find(id);
    }

    /** Springs/restarts any stuck jobs (optional utility). */
    public void requeue(String id) {
        jobStore.find(id).ifPresent(job -> {
            if (job.getStatus() == SentimentJob.Status.FAILED
                    || job.getStatus() == SentimentJob.Status.PENDING) {
                CompletableFuture.runAsync(() -> process(job), executor);
            }
        });
    }

    /** Release the executor on shutdown. */
    public void shutdown() {
        executor.shutdown();
    }
}
```

## 5. Updated controller — add job endpoints

```java
package com.yourbot.web;

import com.yourbot.job.SentimentJob;
import com.yourbot.job.SentimentJobService;
import com.yourbot.service.SentimentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/sentiment")
public class SentimentController {

    private final SentimentService service;
    private final SentimentJobService jobService;

    public SentimentController(SentimentService service, SentimentJobService jobService) {
        this.service = service;
        this.jobService = jobService;
    }

    // ---- Synchronous (unchanged) ----

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

    // ---- Asynchronous: submit job ----

    @PostMapping("/jobs")
    public ResponseEntity<?> submitJob(@RequestBody Map<String, String> body) {
        String text = body.get("text");
        if (text == null || text.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "text is required"));
        }
        String jobId = jobService.submit(text);
        return ResponseEntity.accepted().body(Map.of(
                "jobId", jobId,
                "status", "PENDING",
                "pollUrl", "/api/sentiment/jobs/" + jobId));
    }

    // ---- Asynchronous: poll job ----

    @GetMapping("/jobs/{id}")
    public ResponseEntity<?> getJob(@PathVariable String id) {
        return jobService.getJob(id)
                .map(job -> ResponseEntity.ok(toJobResponse(job)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private Map<String, Object> toJobResponse(SentimentJob job) {
        return Map.of(
                "jobId", job.getId(),
                "status", job.getStatus().name(),
                "createdAt", job.getCreatedAt().toString(),
                "startedAt", job.getStartedAt() == null ? null : job.getStartedAt().toString(),
                "completedAt", job.getCompletedAt() == null ? null : job.getCompletedAt().toString(),
                "result", job.getResult() == null ? null : job.getResult(),
                "error", job.getError());
    }
}
```

## 6. Lifecycle — register shutdown

Add to your main application class (or a `@Configuration`):

```java
@SpringBootApplication
public class TradingBotApplication {

    private final SentimentJobService jobService;

    public TradingBotApplication(SentimentJobService jobService) {
        this.jobService = jobService;
    }

    public static void main(String[] args) {
        SpringApplication.run(TradingBotApplication.class, args);
    }

    @PreDestroy
    public void shutdown() {
        jobService.shutdown();
    }
}
```

## 7. Usage flow

```bash
# 1. Submit — returns 202 with jobId
curl -X POST http://localhost:8080/api/sentiment/jobs \
  -H "Content-Type: application/json" \
  -d '{"text":"Tesla beats Q3 delivery estimates by 12%, but supply chain issues loom."}'

# → {"jobId":"8f2c...","status":"PENDING","pollUrl":"/api/sentiment/jobs/8f2c..."}

# 2. Poll until COMPLETED/FAILED
curl http://localhost:8080/api/sentiment/jobs/8f2c...

# → {"jobId":"8f2c...","status":"COMPLETED","result":{"sentiment":"bullish",...}}
```

## Design notes

- **Thread safety:** `SentimentJob` fields are `volatile`; the store uses `ConcurrentHashMap`. Each job is processed by exactly one worker.
- **The pool size (4)** bounds concurrent Ollama calls — if your RTX 3080 runs `qwen3.5:9b` well, 2–4 concurrent workers is a sane limit; tune from there.
- **Backpressure:** if you submit faster than workers drain, jobs queue in `PENDING` — memory grows with queue depth. For production, add a bounded queue (e.g., `ThreadPoolExecutor` with a `LinkedBlockingQueue` + rejection handler) and return `429` when the queue is full.
- **Persistence:** this store is in-memory — jobs vanish on restart. If you need history across restarts, swap the store for a Postgres table (or Redis) with the same interface.
- **The `@Async` alternative** — Spring's `@Async` + `CompletableFuture` works too, but the explicit executor above gives you pool control and keeps job reconciliation explicit, which fits polling better.

---

Want me to add the bounded-queue backpressure variant (429 on overload) and/or a WebSocket push so clients don't have to poll at all?