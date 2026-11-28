/* (C)2026 */
package com.openclaw.bot.market;

import java.time.Instant;

/**
 * A market snapshot for a single index as of now.
 *
 * @param last last traded value of the index
 * @param prevClose previous session close (reference level)
 * @param dayHigh highest value observed in the current session (from exchange)
 * @param dayLow lowest value observed in the current session (from exchange)
 * @param ts as-of timestamp
 * @param status exchange market status string, e.g. "Open"/"Closed" (may be empty)
 */
public record Quote(
    String symbol,
    double last,
    double prevClose,
    double dayHigh,
    double dayLow,
    Instant ts,
    String status) {

  /**
   * Calculates the percentage change of the last price relative to the previous session close.
   * @return The daily percentage change (as a decimal value). Returns 0.0 if {@code prevClose} is zero.
   */
  public double dayChangePct() {
    return prevClose > 0 ? (last - prevClose) / prevClose * 100.0 : 0.0;
  }
}
