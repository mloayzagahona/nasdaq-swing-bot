package com.openclaw.bot.market;

import java.time.Instant;

/** One observed price sample at a point in time. */
public record Sample(long millis, double price) {
    public Instant time() { return Instant.ofEpochMilli(millis); }
    public static Sample now(double price) { return new Sample(System.currentTimeMillis(), price); }
}
