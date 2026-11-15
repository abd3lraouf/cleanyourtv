package io.github.abd3lraouf.campopup;

/** How many columns and rows the live view uses for a number of cameras: as large as the screen allows. */
final class LiveGrid {
    private LiveGrid() {}

    static int columns(int cameras) {
        if (cameras <= 1) return 1;
        if (cameras <= 4) return 2;
        if (cameras <= 9) return 3;
        return 4;
    }

    static int rows(int cameras) {
        if (cameras <= 0) return 1;
        int cols = columns(cameras);
        return (cameras + cols - 1) / cols;
    }
}
