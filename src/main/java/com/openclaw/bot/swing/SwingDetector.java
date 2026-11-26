package com.openclaw.bot.swing;

import com.openclaw.bot.market.Sample;

import java.util.ArrayDeque;
import java.util.Optional;

/**
 * Detects swing highs/lows on a sampled price series using a fractal rule:
 * a bar is a swing HIGH if it is greater than the {@code confirm} bars before AND
 * {@code confirm} bars after it; symmetric for LOW. A "trigger" occurs when a
 * confirmed extremum strictly beats the previously confirmed extreme of the same
 * type by at least {@code minDeltaPct}% — that is the alertable occurrence.
 *
 * <p>The detector is deliberately stateless across restarts only via the
 * {@link com.openclaw.bot.state.StateStore}; in-memory state starts fresh on boot
 * and re-baselines after {@code 2*confirm+1} samples.
 */
public final class SwingDetector {

    private final int confirm;
    private final double minDeltaPct;
    private final ArrayDeque<Double> window = new ArrayDeque<>();

    private Double lastHigh;
    private Double lastLow;
    private long lastHighAt = -1;
    private long lastLowAt = -1;

    public SwingDetector(int confirm, double minDeltaPct) {
        this.confirm = confirm;
        this.minDeltaPct = minDeltaPct;
    }

    /** Restore previously confirmed extremes (from persisted state) so the bot
     *  does not re-alert on levels it already signaled before a restart. */
    public void restore(Double lastHigh, long lastHighAt, Double lastLow, long lastLowAt) {
        this.lastHigh = lastHigh;
        this.lastHighAt = lastHighAt;
        this.lastLow = lastLow;
        this.lastLowAt = lastLowAt;
    }

    public Double lastHigh() { return lastHigh; }
    public Double lastLow() { return lastLow; }
    public long lastHighAt() { return lastHighAt; }
    public long lastLowAt() { return lastLowAt; }

    public synchronized Optional<SwingEvent> accept(Sample s) {
        window.addLast(s.price());
        int needed = 2 * confirm + 1;
        if (window.size() < needed) return Optional.empty();
        // only keep what we need for pivot evaluation
        while (window.size() > needed) window.removeFirst();

        int mid = confirm;
        double pivot = get(mid);

        boolean isHigh = true, isLow = true;
        for (int d = 1; d <= confirm; d++) {
            if (!(pivot > get(mid - d) && pivot > get(mid + d))) isHigh = false;
            if (!(pivot < get(mid - d) && pivot < get(mid + d))) isLow = false;
            if (!isHigh && !isLow) break;
        }

        if (isHigh) {
            boolean beats = (lastHigh == null) || (pivot > lastHigh && pctDiff(pivot, lastHigh) >= minDeltaPct);
            if (beats) {
                Double prev = lastHigh;
                long prevAt = lastHighAt;
                lastHigh = pivot;
                lastHighAt = s.millis();
                double delta = (prev == null) ? 0.0 : pctDiff(pivot, prev);
                return Optional.of(new SwingEvent(SwingEvent.Type.HIGH, pivot, s.millis(), prev == null ? 0.0 : prev, delta));
            } else {
                // confirmed extremum but not a decisive new extreme — remember it to avoid noise
                lastHigh = pivot;
                lastHighAt = s.millis();
            }
        } else if (isLow) {
            boolean beats = (lastLow == null) || (pivot < lastLow && pctDiff(lastLow, pivot) >= minDeltaPct);
            if (beats) {
                Double prev = lastLow;
                long prevAt = lastLowAt;
                lastLow = pivot;
                lastLowAt = s.millis();
                double delta = (prev == null) ? 0.0 : pctDiff(prev, pivot);
                return Optional.of(new SwingEvent(SwingEvent.Type.LOW, pivot, s.millis(), prev == null ? 0.0 : prev, delta));
            } else {
                lastLow = pivot;
                lastLowAt = s.millis();
            }
        }
        return Optional.empty();
    }

    private double get(int i) {
        int j = 0;
        for (double v : window) {
            if (j == i) return v;
            j++;
        }
        throw new IllegalStateException("index out of window");
    }

    private static double pctDiff(double from, double to) {
        if (from == 0) return 0;
        return Math.abs(to - from) / from * 100.0;
    }

    public int windowSize() { return window.size(); }
}
