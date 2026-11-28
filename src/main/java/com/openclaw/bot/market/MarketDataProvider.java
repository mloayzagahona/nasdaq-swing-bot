/* (C)2026 */
package com.openclaw.bot.market;

import java.io.IOException;

/**
 * Provides an abstraction layer for fetching market data.
 * <p>Implementations cover live data feeds (e.g., Nasdaq, Finnhub) or historical data replay.
 * All providers must adhere to the contract of fetching a fast, current snapshot.
 * @author OpenClaw Development Team
 */
public interface MarketDataProvider {

  /**
   * Fetches the latest market quote for the configured symbol.
   * <p>This method is designed to be called frequently (every tick) and must execute quickly.
   * @return A {@link Quote} record containing the current snapshot.
   * @throws IOException if connection or data retrieval fails.
   */
  Quote quote() throws IOException;

  /**
   * Checks if there is more data available to process.
   * <p>For live providers, this is generally always true. For replay providers, it returns
   * false when all historical data has been processed.
   * @return true if more data is available, false otherwise.
   */
  default boolean hasNext() {
    return true;
  }
}
