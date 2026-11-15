package io.github.abd3lraouf.campopup;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class LiveGridTest {
    @Test public void oneCameraFillsTheScreen() {
        assertEquals(1, LiveGrid.columns(1));
        assertEquals(1, LiveGrid.rows(1));
    }

    @Test public void twoCamerasSitSideBySide() {
        assertEquals(2, LiveGrid.columns(2));
        assertEquals(1, LiveGrid.rows(2));
    }

    @Test public void upToFourMakeTwoByTwo() {
        assertEquals(2, LiveGrid.columns(3));
        assertEquals(2, LiveGrid.rows(3));
        assertEquals(2, LiveGrid.rows(4));
    }

    @Test public void eightMakeThreeByThree() {
        assertEquals(3, LiveGrid.columns(8));
        assertEquals(3, LiveGrid.rows(8));
    }
}
