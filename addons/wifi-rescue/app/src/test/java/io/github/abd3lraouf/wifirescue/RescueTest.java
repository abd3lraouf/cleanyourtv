package io.github.abd3lraouf.wifirescue;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RescueTest {
    private static final long T0 = 1_000_000;

    @Test public void idleUntilUnvalidated() {
        Rescue r = new Rescue();
        assertEquals(Rescue.Action.NONE, r.decide(T0).action);
        assertEquals(-1, r.decide(T0).wakeInMs);
    }

    @Test public void reportsThenToggle() {
        Rescue r = new Rescue();
        r.unvalidated(T0);
        Rescue.Decision d = r.decide(T0 + 5_000);
        assertEquals(Rescue.Action.NONE, d.action);
        assertEquals(15_000, d.wakeInMs);
        assertEquals(Rescue.Action.REPORT, r.decide(T0 + 20_000).action);
        assertEquals(Rescue.Action.REPORT, r.decide(T0 + 40_000).action);
        d = r.decide(T0 + 60_000);
        assertEquals(Rescue.Action.REPORT, d.action);
        assertEquals(15_000, d.wakeInMs);
        assertEquals(3, r.reports());
        assertEquals(Rescue.Action.NONE, r.decide(T0 + 70_000).action);
        d = r.decide(T0 + 75_000);
        assertEquals(Rescue.Action.TOGGLE, d.action);
        assertEquals(-1, d.wakeInMs);
        assertEquals(1, r.toggles());
        assertFalse(r.waiting());
    }

    @Test public void validatedCancels() {
        Rescue r = new Rescue();
        r.unvalidated(T0);
        r.decide(T0 + 20_000);
        r.validated();
        assertEquals(Rescue.Action.NONE, r.decide(T0 + 80_000).action);
        assertEquals(0, r.reports());
    }

    @Test public void repeatedUnvalidatedKeepsFirstTime() {
        Rescue r = new Rescue();
        r.unvalidated(T0);
        r.unvalidated(T0 + 30_000);
        assertEquals(Rescue.Action.REPORT, r.decide(T0 + 20_000).action);
    }

    @Test public void toggleGapAndGiveUp() {
        Rescue r = new Rescue();
        long t = T0;
        r.unvalidated(t);
        assertEquals(Rescue.Action.TOGGLE, r.decide(t + 75_000).action);
        long firstToggle = t + 75_000;
        // Reconnects quickly, still no internet: second toggle must wait for the 3-minute gap.
        t = firstToggle + 10_000;
        r.unvalidated(t);
        Rescue.Decision d = r.decide(t + 75_000);
        assertEquals(Rescue.Action.NONE, d.action);
        assertEquals(firstToggle + Rescue.TOGGLE_GAP_MS - (t + 75_000), d.wakeInMs);
        assertEquals(Rescue.Action.TOGGLE, r.decide(firstToggle + Rescue.TOGGLE_GAP_MS).action);
        t = firstToggle + Rescue.TOGGLE_GAP_MS + 10_000;
        r.unvalidated(t);
        assertEquals(Rescue.Action.TOGGLE, r.decide(t + Rescue.TOGGLE_GAP_MS).action);
        assertTrue(r.gaveUp());
        r.unvalidated(t + 500_000);
        assertEquals(Rescue.Action.NONE, r.decide(t + 900_000).action);
        // A validated network resets the give-up.
        r.validated();
        assertFalse(r.gaveUp());
        r.unvalidated(t + 1_000_000);
        assertEquals(Rescue.Action.REPORT, r.decide(t + 1_020_000).action);
    }

    @Test public void lostResets() {
        Rescue r = new Rescue();
        r.unvalidated(T0);
        r.lost();
        assertEquals(Rescue.Action.NONE, r.decide(T0 + 100_000).action);
    }
}
