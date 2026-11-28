/* (C)2026 */
package com.openclaw.bot.sentiment;

import ai.djl.Model;
import ai.djl.ModelException;
import ai.djl.inference.Predictor;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ModelZoo;
import ai.djl.translate.TranslateException;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * FinBERT (ProsusAI) financial-headline sentiment classifier. Standalone — no Ollama dependency.
 */
public final class FinBertSentiment {

  public static final List<String> DEFAULT_LABELS = List.of("positive", "negative", "neutral");

  private final Path modelDir;
  private final List<String> labels;

  private volatile Model cachedModel;
  private volatile FinBertTranslator cachedTranslator;

  public FinBertSentiment(Path modelDir) {
    this(modelDir, DEFAULT_LABELS);
  }

  public FinBertSentiment(Path modelDir, List<String> labels) {
    this.modelDir = modelDir.toAbsolutePath();
    this.labels = List.copyOf(labels);
  }

  public SentimentResult analyze(String headline)
      throws ModelException, TranslateException, IOException {
    try (Predictor<String, float[]> predictor = newPredictor()) {
      return toResult(headline, predictor.predict(headline));
    }
  }

  public List<SentimentResult> analyzeBatch(List<String> headlines)
      throws ModelException, TranslateException, IOException {

    if (headlines == null || headlines.isEmpty()) {
      return Collections.emptyList();
    }
    List<SentimentResult> results = new ArrayList<>(headlines.size());
    try (Predictor<String, float[]> predictor = newPredictor()) {
      for (String h : headlines) {
        results.add(toResult(h, predictor.predict(h)));
      }
    }
    return results;
  }

  public Predictor<String, float[]> newPredictor()
      throws ModelException, TranslateException, IOException {
    return loadModel().newPredictor(getTranslator());
  }

  // ------------------------------------------------------------------
  // Lazily initialized shared resources
  // ------------------------------------------------------------------

  private Model loadModel() throws ModelException, TranslateException, IOException {
    Model local = cachedModel;
    if (local == null) {
      synchronized (this) {
        local = cachedModel;
        if (local == null) {
          // Attach the translator to the Criteria — this is what the
          // model loader uses to resolve input/output types.
          Criteria<String, float[]> criteria =
              Criteria.builder()
                  .setTypes(String.class, float[].class)
                  .optEngine("PyTorch")
                  .optModelUrls("file://" + modelDir)
                  .optTranslator(getTranslator())
                  .build();
          local = ModelZoo.loadModel(criteria);
          cachedModel = local;
        }
      }
    }
    return local;
  }

  private FinBertTranslator getTranslator() throws ModelException {
    FinBertTranslator local = cachedTranslator;
    if (local == null) {
      synchronized (this) {
        local = cachedTranslator;
        if (local == null) {
          try {
            local = new FinBertTranslator(modelDir);
            cachedTranslator = local;
          } catch (IOException e) {
            throw new ModelException(
                "Failed to load FinBERT tokenizer from " + modelDir + ": " + e.getMessage(), e);
          }
        }
      }
    }
    return local;
  }

  /** Closes the shared model. Safe to call repeatedly; no-op after first close. */
  public void close() {
    synchronized (this) {
      if (cachedModel != null) {
        try {
          cachedModel.close();
        } catch (Exception ignored) {
          // best-effort cleanup
        }
        cachedModel = null;
      }
    }
  }

  // ------------------------------------------------------------------
  // Result handling
  // ------------------------------------------------------------------

  public record SentimentResult(
      String headline, TradingSentiment sentiment, float score, float confidence) {

    @Override
    public String toString() {
      return "SentimentResult{"
          + "sentiment="
          + sentiment
          + ", score="
          + score
          + ", confidence="
          + confidence
          + ", headline='"
          + headline
          + '\''
          + '}';
    }
  }

  public enum TradingSentiment {
    BULLISH,
    BEARISH,
    NEUTRAL
  }

  private SentimentResult toResult(String headline, float[] probArray) {
    int bestIdx = 0;
    float bestProb = probArray[0];
    for (int i = 1; i < probArray.length; i++) {
      if (probArray[i] > bestProb) {
        bestProb = probArray[i];
        bestIdx = i;
      }
    }

    String label = labels.get(bestIdx).toLowerCase();
    float confidence = bestProb;
    float score = 0f;

    TradingSentiment sentiment =
        switch (label) {
          case "positive" -> {
            score = confidence;
            yield TradingSentiment.BULLISH;
          }
          case "negative" -> {
            score = -confidence;
            yield TradingSentiment.BEARISH;
          }
          default -> TradingSentiment.NEUTRAL;
        };

    return new SentimentResult(headline, sentiment, score, confidence);
  }

  // ------------------------------------------------------------------
  // Custom translator — self-contained WordPiece tokenizer from vocab.txt
  // (replaces DJL's BertTokenizer, whose API differs in this version)
  // ------------------------------------------------------------------

  static final class FinBertTranslator implements Translator<String, float[]> {

    private static final int MAX_LENGTH = 512;
    private static final String CLS = "[CLS]";
    private static final String SEP = "[SEP]";
    private static final String UNK = "[UNK]";

    private final Map<String, Integer> vocab;

    FinBertTranslator(Path modelDir) throws IOException {
      this.vocab = loadVocab(modelDir.resolve("vocab.txt"));
    }

    @Override
    public NDList processInput(TranslatorContext ctx, String input) {
      List<String> tokens = wordPieceTokenize(input);
      int n = Math.min(tokens.size(), MAX_LENGTH - 2); // room for [CLS]/[SEP]

      NDManager manager = ctx.getNDManager();
      long[] inputIds = new long[n + 2];
      long[] attn = new long[n + 2];

      inputIds[0] = idOf(CLS);
      attn[0] = 1;
      for (int i = 0; i < n; i++) {
        inputIds[i + 1] = idOf(tokens.get(i));
        attn[i + 1] = 1;
      }
      inputIds[n + 1] = idOf(SEP);
      attn[n + 1] = 1;

      return new NDList(manager.create(inputIds), manager.create(attn));
    }

    @Override
    public float[] processOutput(TranslatorContext ctx, NDList list) {
      NDArray logits = list.singletonOrThrow(); // [numLabels]
      return logits.softmax(-1).toFloatArray();
    }

    // ------------------------------------------------------------------
    // Vocabulary
    // ------------------------------------------------------------------

    private static Map<String, Integer> loadVocab(Path vocabFile) throws IOException {
      List<String> lines = Files.readAllLines(vocabFile);
      Map<String, Integer> map = new HashMap<>(lines.size() * 2);
      for (int i = 0; i < lines.size(); i++) {
        String line = lines.get(i).trim();
        if (!line.isEmpty()) {
          map.put(line, i);
        }
      }
      return map;
    }

    private int idOf(String token) {
      Integer id = vocab.get(token);
      if (id == null) {
        id = vocab.get(UNK);
      }
      return id == null ? 0 : id;
    }

    // ------------------------------------------------------------------
    // Simplified WordPiece tokenization (BERT-compatible enough for FinBERT)
    // ------------------------------------------------------------------

    private List<String> wordPieceTokenize(String input) {
      List<String> out = new ArrayList<>();

      // Lowercase + strip diacritics (BERT-style normalization)
      String cleaned =
          Normalizer.normalize(input, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase();

      // Split on whitespace, keeping punctuation as separate tokens
      for (String word : cleaned.replaceAll("([\\p{Punct}])", " $1 ").trim().split("\\s+")) {
        if (word.isEmpty()) {
          continue;
        }
        if (vocab.containsKey(word)) {
          out.add(word);
          continue;
        }
        // Subword fallback: longest-prefix WordPiece
        List<String> pieces = new ArrayList<>();
        String remaining = word;
        while (!remaining.isEmpty()) {
          String matched = null;
          for (int end = remaining.length(); end >= 1; end--) {
            String candidate = remaining.substring(0, end);
            String probe = pieces.isEmpty() ? candidate : "##" + candidate;
            if (vocab.containsKey(probe)) {
              matched = probe;
              break;
            }
          }
          if (matched == null) {
            out.add(UNK); // no vocab match at all
            break;
          }
          pieces.add(matched);
          remaining =
              remaining.substring(
                  matched.startsWith("##") ? matched.length() - 2 : matched.length());
        }
        out.addAll(pieces);
      }
      return out;
    }
  }
}
