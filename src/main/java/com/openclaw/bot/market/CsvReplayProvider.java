package com.openclaw.bot.market;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic provider for offline demos and tests.
 * Replays a CSV of sample points. Two accepted formats per line:
 * <pre>
 *   millis,price            e.g. 1732400000000,21234.56
 *   yyyy-MM-dd'T'HH:mm:ss,price
 * </pre>
 * For a file called replay.csv, each {@link #quote()} returns the next point in
 * sequence; hasNext() becomes false when the series is exhausted.
 */
public final class CsvReplayProvider implements MarketDataProvider {

    private final List<Sample> samples = new ArrayList<>();
    private final String symbol;
    private int index = 0;
    private double maxSeen, minSeen;
    private double firstPrice;

    public CsvReplayProvider(Path file, String symbol) throws IOException {
        this.symbol = symbol;
        List<String> lines = Files.readAllLines(file);
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] p = line.split(",");
            if (p.length < 2) continue;
            double price = Double.parseDouble(p[1].trim());
            long millis;
            try {
                millis = Long.parseLong(p[0].trim());
            } catch (NumberFormatException e) {
                millis = LocalDateTime.parse(p[0].trim(), DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                        .toInstant(ZoneOffset.UTC).toEpochMilli();
            }
            samples.add(new Sample(millis, price));
        }
        if (samples.isEmpty()) throw new IOException("No samples parsed from " + file);
        firstPrice = samples.get(0).price();
        maxSeen = firstPrice;
        minSeen = firstPrice;
    }

    @Override
    public Quote quote() {
        Sample s = samples.get(Math.min(index, samples.size() - 1));
        index++;
        maxSeen = Math.max(maxSeen, s.price());
        minSeen = Math.min(minSeen, s.price());
        return new Quote(symbol, s.price(), firstPrice, maxSeen, minSeen, s.time(), "Replay");
    }

    @Override
    public boolean hasNext() {
        return index < samples.size();
    }

    public int size() { return samples.size(); }
    public int position() { return index; }
}
