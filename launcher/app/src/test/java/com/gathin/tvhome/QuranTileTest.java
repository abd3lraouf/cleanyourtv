package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class QuranTileTest {

    @Test
    public void opensTheAppWhenAnotherAppWasLast() {
        // Another app's session holds the media buttons: a play key would start that app, not Quran.
        assertEquals(QuranTile.OPEN, QuranTile.action(false, false));
        assertEquals(QuranTile.OPEN, QuranTile.action(false, true));
    }

    @Test
    public void togglesQuransOwnSessionWhenItWasLast() {
        assertEquals(QuranTile.PAUSE, QuranTile.action(true, true));
        assertEquals(QuranTile.PLAY, QuranTile.action(true, false));
    }
}
