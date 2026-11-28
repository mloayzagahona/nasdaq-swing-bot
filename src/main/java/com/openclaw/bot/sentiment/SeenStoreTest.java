/* (C)2026 */
package com.openclaw.bot.sentiment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Verifies SeenStore.load() with the Jackson 3 properties() API, plus save/load round-trip and
 * failure tolerance.
 */
public final class SeenStoreTest {

  private static int failures = 0;

  public static void main(String[] args) throws Exception {
    testLoadValidJson();
    testRoundTripPersists();
    testMissingFileYieldsEmpty();
    testCorruptFileDoesNotThrow();

    if (failures == 0) {
      System.out.println("All SeenStore tests passed.");
    } else {
      System.out.println(failures + " test(s) FAILED.");
      System.exit(1);
    }
  }

  private static void testLoadValidJson() throws Exception {
    Path file = Files.createTempFile("seenstore", ".json");
    try {
      ObjectNode root = JsonMapper.builder().build().createObjectNode();

      ObjectNode nvda = root.putObject("NVDA");
      nvda.put("max_id", 144145923L);
      nvda.put("max_datetime", 1795679642L);

      ObjectNode tsla = root.putObject("TSLA");
      tsla.put("max_id", 42L);
      tsla.put("max_datetime", 1000L);

      Files.writeString(file, JsonMapper.builder().build().writeValueAsString(root));

      SeenStore store = new SeenStore(file);

      check(
          store.alreadySeen("NVDA", 144145923L, Instant.ofEpochSecond(1795679642L)),
          "load(): existing NVDA id should be seen");
      check(
          !store.alreadySeen("NVDA", 144145924L, Instant.ofEpochSecond(1795679999L)),
          "load(): newer NVDA id + newer timestamp should NOT be seen");
      check(
          store.alreadySeen("TSLA", 42L, Instant.ofEpochSecond(1000L)),
          "load(): existing TSLA should be seen");
      check(
          !store.alreadySeen("AAPL", 1L, Instant.ofEpochSecond(1L)),
          "load(): unknown symbol should not be seen");
    } finally {
      Files.deleteIfExists(file);
    }
  }

  private static void testRoundTripPersists() throws Exception {
    Path file = Files.createTempFile("seenstore-roundtrip", ".json");
    try {
      SeenStore store = new SeenStore(file);
      store.mark("NVDA", 999L, Instant.ofEpochSecond(8888L));
      store.save();

      SeenStore reloaded = new SeenStore(file);
      check(
          reloaded.alreadySeen("NVDA", 999L, Instant.ofEpochSecond(8888L)),
          "round-trip: saved entry visible after reload");
    } finally {
      Files.deleteIfExists(file);
    }
  }

  private static void testMissingFileYieldsEmpty() throws Exception {
    Path missing =
        Path.of(
            System.getProperty("java.io.tmpdir"),
            "definitely-missing-" + System.nanoTime() + ".json");
    SeenStore store = new SeenStore(missing);
    check(
        !store.alreadySeen("NVDA", 1L, Instant.ofEpochSecond(1L)),
        "missing file: store loads empty without errors");
  }

  private static void testCorruptFileDoesNotThrow() throws Exception {
    Path file = Files.createTempFile("seenstore-corrupt", ".json");
    try {
      Files.writeString(file, "{ this is not valid json !!!");
      SeenStore store = new SeenStore(file); // must not throw
      check(
          !store.alreadySeen("NVDA", 1L, Instant.ofEpochSecond(1L)),
          "corrupt file: store loads empty without throwing");
    } finally {
      Files.deleteIfExists(file);
    }
  }

  private static void check(boolean condition, String message) {
    if (!condition) {
      failures++;
      System.err.println("FAIL: " + message);
    } else {
      System.out.println("PASS: " + message);
    }
  }

  private SeenStoreTest() {}
}
