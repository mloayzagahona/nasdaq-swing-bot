/* (C)2026 */
package com.openclaw.bot.swing;

import com.openclaw.bot.market.Sample;
import java.util.ArrayDeque;
import java.util.Optional;

/**
 * {@code SwingDetector} uses a fractal pattern recognition rule to identify significant swing highs and lows
 * within a stream of market data samples.
 * <p>
 * A price point is classified as a pivot (high or low) if it is the highest or lowest within a
 * window defined by the {@code confirm} parameter (i.e., greater/smaller than {@code confirm} bars
 * before and after it).
 * <p>
 * An event is only signaled if a new pivot is confirmed *and* it represents a decisive move (a minimum
 * change of {@code minDeltaPct}) away from the previously confirmed extreme of the same type.
 * <p>
 * The detector handles state restoration using {@link com.openclaw.bot.state.StateStore} and uses a
 * sliding window of {@code 2 * confirm + 1} samples.
 * @author OpenClaw Development Team
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

  /**
   * Restore previously confirmed extremes (from persisted state) so the bot does not re-alert on
   * levels it already signaled before a restart.
   */
  public void restore(Double lastHigh, long lastHighAt, Double lastLow, long lastLowAt) {
    this.lastHigh = lastHigh;
    this.lastHighAt = lastHighAt;
    this.lastLow = lastLow;
    this.lastLowAt = lastLowAt;
  }

  /**
 * Retrieves the price of the last confirmed swing high.
 * @return The price of the last confirmed high, or {@code null} if no high has been confirmed.
 */
public Double lastHigh() {
    return lastHigh;
  }

  /**
 * Retrieves the price of the last confirmed swing low.
 * @return The price of the last confirmed low, or {@code null} if no low has been confirmed.
 */
public Double lastLow() {
    return lastLow;
  }

  /**
 * Retrieves the timestamp (in milliseconds) of the last confirmed swing high.
 * @return The timestamp of the last confirmed high, or {@code -1} if no high has been confirmed.
 */
public long lastHighAt() {
    return lastHighAt;
  }

  /**
 * Retrieves the timestamp (in milliseconds) of the last confirmed swing low.
 * @return The timestamp of the last confirmed low, or {@code -1} if no low has been confirmed.
 */
public long lastLowAt() {
    return lastLowAt;
  }

  /**
 * Processes a new market sample and attempts to detect a new swing extreme.
 * <p>
 * This method maintains a sliding window of {@code 2 * confirm + 1} samples. It identifies
 * a pivot (high or low) and then checks if this pivot meets the {@code minDeltaPct} threshold
 * against the currently recorded extreme.
 * @param s The new market sample to process.
 * @return An {@code Optional<SwingEvent>} containing the detected swing event, or {@code Optional.empty()} if no significant new extreme was found.
 */
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
      boolean beats =
          (lastHigh == null) || (pivot > lastHigh && pctDiff(pivot, lastHigh) >= minDeltaPct);
      if (beats) {
        Double prev = lastHigh;
        long prevAt = lastHighAt;
        lastHigh = pivot;
        lastHighAt = s.millis();
        double delta = (prev == null) ? 0.0 : pctDiff(pivot, prev);
        return Optional.of(
            new SwingEvent(
                SwingEvent.Type.HIGH, pivot, s.millis(), prev == null ? 0.0 : prev, delta));
      } else {
        // confirmed extremum but not a decisive new extreme — remember it to avoid noise
        lastHigh = pivot;
        lastHighAt = s.millis();
      }
    } else if (isLow) {
      boolean beats =
          (lastLow == null) || (pivot < lastLow && pctDiff(lastLow, pivot) >= minDeltaPct);
      if (beats) {
        Double prev = lastLow;
        long prevAt = lastLowAt;
        lastLow = pivot;
        lastLowAt = s.millis();
        double delta = (prev == null) ? 0.0 : pctDiff(prev, pivot);
        return Optional.of(
            new SwingEvent(
                SwingEvent.Type.LOW, pivot, s.millis(), prev == null ? 0.0 : prev, delta));
      } else {
        lastLow = pivot;
        lastLowAt = s.millis();
      }
    }
    return Optional.empty();
  }

  /**
 * Retrieves the price at a specific offset within the internal sliding window.
 * @param i The offset index from the start of the window (0 being the oldest).
 * @return The price at the specified index.
 * @throws IllegalStateException if the index is out of bounds for the current window size.
 */
private double get(int i) {
    int j = 0;
    for (double v : window) {
      if (j == i) return v;
      j++;
    }
    throw new IllegalStateException("index out of window");
  }

  /**
 * Calculates the absolute percentage difference between two values.
 * Used to determine if a new extremum is a decisive move compared to a previous extreme.
 * @param from The reference value (the previous extreme).
 * @param to The comparison value (the candidate extreme).
 * @return The absolute percentage difference. Returns 0.0 if {@code from} is zero.
 */
private static double pctDiff(double from, double to) {
    if (from == 0) return 0;
    return Math.abs(to - from) / from * 100.0;
  }

  public int windowSize() {
    return window.size();
  }
}
