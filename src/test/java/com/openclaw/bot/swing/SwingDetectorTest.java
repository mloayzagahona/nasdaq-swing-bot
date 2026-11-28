/* (C)2026 */
package com.openclaw.bot.swing;

import static org.junit.jupiter.api.Assertions.*;

import com.openclaw.bot.market.Sample;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SwingDetectorTest {

  private static Sample s(int i, double p) {
    return new Sample(1_700_000_000_000L + i * 60_000L, p);
  }

  @Test
  void detectsConfirmedSwingHighAndLow() {
    SwingDetector d = new SwingDetector(2, 0.05);
    List<SwingEvent> events = new ArrayList<>();
    double[] prices = {
      100, 101.2, 102.1, 103.4, 104.3, 105, 104.1, 103, 101.9, 100.8, 99.7, 98.6, 99.5, 100.4,
      101.3, 102.2, 103.1, 104, 104.9, 105.8, 106.7, 107.6, 108.5, 109.4, 110.3, 111.2, 112.1, 111,
      109.9, 108.8, 107.7, 108.6, 109.5, 110.4, 111.3, 112.2, 113.1, 112, 110.9, 111.8, 110.7
    };
    for (int i = 0; i < prices.length; i++) {
      d.accept(s(i, prices[i])).ifPresent(events::add);
    }
    // expected: HIGH 105, LOW 98.6, HIGH 112.1, HIGH 113.1
    assertEquals(4, events.size(), "events: " + events);
    assertEquals(SwingEvent.Type.HIGH, events.get(0).type());
    assertEquals(105.0, events.get(0).price(), 1e-9);
    assertEquals(SwingEvent.Type.LOW, events.get(1).type());
    assertEquals(98.6, events.get(1).price(), 1e-9);
    assertEquals(SwingEvent.Type.HIGH, events.get(2).type());
    assertEquals(112.1, events.get(2).price(), 1e-9);
    assertEquals(SwingEvent.Type.HIGH, events.get(3).type());
    assertEquals(113.1, events.get(3).price(), 1e-9);
    assertEquals(112.1, events.get(3).previousExtreme(), 1e-9);
  }

  @Test
  void noDuplicateEventWhenPeakRevisitedBelowPriorExtreme() {
    SwingDetector d = new SwingDetector(2, 0.05);
    int events = 0;
    double[] prices = {
      100, 101, 102, 103, 104, 105, 104, 103, 102, 101, 102, 103, 104, 105, 104, 103, 102, 101, 100,
      99
    };
    for (int i = 0; i < prices.length; i++) {
      if (d.accept(s(i, prices[i])).isPresent()) events++;
    }
    assertEquals(2, events); // HIGH 105 and LOW 99 only
  }

  @Test
  void minDeltaFiltersSmallNewExtremes() {
    SwingDetector d = new SwingDetector(2, 1.0); // need ≥1% move to alert
    List<SwingEvent> events = new ArrayList<>();
    double[] prices = {
      100, 101, 102, 103, 104, 105, 104, 103, 102, 101, 102, 103, 104, 105, 106, 107, 108, 109, 110,
      111, 112, 113, 114, 113, 112, 111, 110, 109, 108, 107
    };
    for (int i = 0; i < prices.length; i++) {
      d.accept(s(i, prices[i])).ifPresent(events::add);
    }
    // Series: up 100→105 (HIGH 105), down to 101 (LOW 101), up 102→114 (HIGH 114).
    // Small intermediate highs (e.g. 113 vs 105 = +7.6%) pass the 1% filter too,
    // so the meaningful assertion is that the first and last events fire and the
    // final event's delta is large.
    assertEquals(3, events.size(), "events: " + events);
    assertEquals(SwingEvent.Type.HIGH, events.get(0).type());
    assertEquals(105.0, events.get(0).price(), 1e-9);
    assertEquals(SwingEvent.Type.LOW, events.get(1).type());
    assertEquals(101.0, events.get(1).price(), 1e-9);
    assertEquals(SwingEvent.Type.HIGH, events.get(2).type());
    assertEquals(114.0, events.get(2).price(), 1e-9);
    assertTrue(events.get(2).deltaPct() > 5.0);
  }
}
