## How to run it

**From the project root (Maven, with the deps from before):**

```bash
MAVEN_OPTS="--enable-native-access=ALL-UNNAMED" mvn -q compile exec:java \
  -Dexec.mainClass=com.openclaw.bot.sentiment.SentimentCli \
  -Dorg.slf4j.simpleLogger.defaultLogLevel=debug \
  -Dexec.args="--text 'Tesla beats Q3 delivery estimates by 12%, but supply chain issues loom.'"
```

```bash
MAVEN_OPTS="--enable-native-access=ALL-UNNAMED" mvn -q compile exec:java \
  -Dexec.mainClass=com.openclaw.bot.sentiment.SentimentCli \
  -Dorg.slf4j.simpleLogger.defaultLogLevel=debug \
  -Dexec.args="--text 'Tesla beats Q3 delivery estimates by 12%, but supply chain issues loom.' --force-deep --json"
```

**With a plain `java -cp` (after building / jar):**

```bash
java -cp target/classes:$(cat cp.txt) com.openclaw.bot.sentiment.SentimentCli \
  --text "Fed signals rate cut, markets rally"
```

(`cp.txt` = the classpath output of `mvn dependency:build-classpath -Dmdep.outputFile=cp.txt`.)

**Example invocations:**

```bash
# Fast pass only (FinBERT)
java -cp ... SentimentCli --text "Apple beats earnings estimates"

# FinBERT + escalation to Ollama only when confidence < 0.65
java -cp ... SentimentCli --text "..." --deep

# Force Ollama deep analysis regardless of FinBERT confidence
java -cp ... SentimentCli --text "..." --deep --force-deep

# Read from file, custom Ollama model
java -cp ... SentimentCli --file headlines.txt --deep --model qwen3.5:9b

# Custom FinBERT model directory
java -cp ... SentimentCli --text "..." --model-path /opt/models/finbert
```

## Expected output

```
FinBERT   : BULLISH (score=0.920, confidence=0.920)
(no escalation: confidence 0.920 >= 0.650)
```

or with `--deep --force-deep`:

```
FinBERT   : BULLISH (score=0.920, confidence=0.920)
Escalating to Ollama (qwen3.5:9b)...

Ollama deep analysis:
  sentiment   : bullish (score=0.550, confidence=0.700)
  entities    : [Entity[ticker=TSLA, name=Tesla]]
  key drivers : [record Q3 deliveries beating estimates by 12%]
  caveats     : [supply chain issues may weigh on Q4 margins]
```

## Notes

- The client assumes the two analyzer classes (`FinBertSentiment`, `OllamaSentimentAnalyzer`) are on the classpath — either from your Spring Boot project's module or a shared library.
- If Ollama isn't running, `--deep` will print an error and exit with code 1 — the FinBERT result is already shown beforehand, so you don't lose it.
- `finbert.close()` in `finally` releases DJL predictors; the Ollama client needs no cleanup.

---

Want me to also add a `--json` output mode that prints both results as a single machine-readable JSON do