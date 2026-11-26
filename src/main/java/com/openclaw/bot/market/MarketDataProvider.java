package com.openclaw.bot.market;

import java.io.IOException;

/** Abstraction over any market data source the bot can poll. */
public interface MarketDataProvider {

    /** Fetch the latest quote. Must be cheap and fast (called every tick). */
    Quote quote() throws IOException;

    /** Whether the provider still has data (replay providers return false when exhausted). */
    default boolean hasNext() { return true; }
}
