package io.github.abd3lraouf.campopup;

import static io.github.abd3lraouf.campopup.PopupPolicy.Action.EXTEND;
import static io.github.abd3lraouf.campopup.PopupPolicy.Action.NONE;
import static io.github.abd3lraouf.campopup.PopupPolicy.Action.SHOW;
import static io.github.abd3lraouf.campopup.PopupPolicy.Action.SWITCH;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PopupPolicyTest {
    private final PopupPolicy p = new PopupPolicy(30_000, 120_000, 120_000);

    @Test public void opensOnMotionAndClosesThirtySecondsAfterItEnds() {
        assertEquals(SHOW, p.onMotion(2, true, 1_000, true));
        assertEquals(2, p.showing());
        assertEquals(121_000, p.closeAt());           // still moving: the safety cap
        assertEquals(EXTEND, p.onMotion(2, false, 10_000, true));
        assertEquals(40_000, p.closeAt());
        assertFalse(p.due(39_999));
        assertTrue(p.due(40_000));
    }

    @Test public void newMotionOnTheShownChannelKeepsItOpen() {
        p.onMotion(1, true, 0, true);
        p.onMotion(1, false, 5_000, true);
        assertEquals(EXTEND, p.onMotion(1, true, 20_000, true));
        p.onMotion(1, false, 30_000, true);
        assertEquals(60_000, p.closeAt());
    }

    @Test public void noEndMessageStillCloses() {
        p.onMotion(1, true, 0, true);
        assertTrue(p.due(120_000));
    }

    @Test public void disallowedChannelsAndOffMessagesDoNothing() {
        assertEquals(NONE, p.onMotion(3, true, 0, false));
        assertEquals(NONE, p.onMotion(3, false, 0, true));
        assertEquals(NONE, p.onMotion(0, true, 0, true));
        assertEquals(NONE, p.onMotion(99, true, 0, true));
        assertEquals(0, p.showing());
        assertEquals(0, p.closeAt());
    }

    @Test public void cooldownAfterClosing() {
        p.onMotion(1, true, 0, true);
        p.onMotion(1, false, 1_000, true);
        p.closed(31_000);
        assertEquals(NONE, p.onMotion(1, true, 100_000, true));
        assertTrue(p.cooling(1, 150_999));
        assertEquals(SHOW, p.onMotion(1, true, 151_000, true));
    }

    @Test public void cooldownIsPerChannel() {
        p.onMotion(1, true, 0, true);
        p.closed(31_000);
        assertEquals(SHOW, p.onMotion(2, true, 32_000, true));
    }

    @Test public void switchesOnlyWhenTheShownChannelIsQuiet() {
        p.onMotion(1, true, 0, true);
        assertEquals(NONE, p.onMotion(2, true, 1_000, true));
        assertEquals(1, p.showing());
        p.onMotion(1, false, 2_000, true);
        assertEquals(SWITCH, p.onMotion(2, true, 3_000, true));
        assertEquals(2, p.showing());
    }

    @Test public void forceIgnoresSettingsAndCooldown() {
        p.onMotion(4, true, 0, true);
        p.closed(1_000);
        assertEquals(SHOW, p.force(4, 2_000));
        assertEquals(32_000, p.closeAt());
        assertEquals(EXTEND, p.force(4, 3_000));
        assertEquals(SWITCH, p.force(5, 4_000));
        assertEquals(5, p.showing());
    }
}
