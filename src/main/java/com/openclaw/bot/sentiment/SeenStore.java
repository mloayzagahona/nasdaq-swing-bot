/* (C)2026 */
package com.openclaw.bot.sentiment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Persistent seen-store for article dedupe. State: symbol -> [maxId, maxDatetime]. */
final class SeenStore {

  private final Path file;
  private final Map<String, long[]> state = new HashMap<>(); // [maxId, maxDatetime]
  private final JsonMapper mapper = JsonMapper.builder().build();

  SeenStore(Path file) {
    this.file = file;
    load();
  }

  synchronized boolean alreadySeen(String symbol, long id, Instant datetime) {
    long[] s = state.get(symbol);
    if (s == null) {
      return false;
    }
    return (id != -1 && id <= s[0]) || datetime.getEpochSecond() <= s[1];
  }

  synchronized void mark(String symbol, long id, Instant datetime) {
    long[] s = state.computeIfAbsent(symbol, k -> new long[] {-1, 0});
    s[0] = Math.max(s[0], id);
    s[1] = Math.max(s[1], datetime.getEpochSecond());
  }

  synchronized int size() {
    return state.size();
  }

  synchronized void save() {
    try {
      ObjectNode root = mapper.createObjectNode();
      state.forEach((sym, s) -> {
        ObjectNode o = root.putObject(sym);
        o.put("max_id", s[0]);
        o.put("max_datetime", s[1]);
      });
      Files.writeString(file, mapper.writeValueAsString(root));
    } catch (IOException e) {
      System.err.println("[SeenStore] save failed: " + e.getMessage());
    }
  }

  private void load() {
    try {
      if (!Files.exists(file)) {
        return;
      }
      String body = Files.readString(file);
      for (Map.Entry<String, tools.jackson.databind.JsonNode> entry :
          mapper.readTree(body).properties()) {
        long[] s =
            new long[] {
              entry.getValue().path("max_id").asLong(-1),
              entry.getValue().path("max_datetime").asLong(0)
            };
        state.put(entry.getKey(), s);
      }
    } catch (Exception e) {
      System.err.println("[SeenStore] load failed: " + e.getMessage());
    }
  }
}
