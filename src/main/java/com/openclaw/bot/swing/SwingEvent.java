package com.openclaw.bot.swing;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** A confirmed new swing high or low. */
public record SwingEvent(Type type, double price, long atMillis, double previousExtreme, double deltaPct) {

    public enum Type { HIGH, LOW }

    public String fingerprint() {
        return type.name() + "@" + BigDecimal.valueOf(price).setScale(4, RoundingMode.HALF_UP)
                + "#" + atMillis;
    }

    public String description() {
        return type == Type.HIGH ? "new swing HIGH" : "new swing LOW";
    }
}
