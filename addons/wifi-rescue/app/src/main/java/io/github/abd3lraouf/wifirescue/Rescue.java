package io.github.abd3lraouf.wifirescue;

/**
 * The decision table, with no Android in it so it runs as a plain JVM test.
 *
 * Wi-Fi connected but not validated: ask Android to re-check at 20, 40 and 60 seconds
 * (ConnectivityManager.reportNetworkConnectivity), then at 75 seconds switch Wi-Fi off and on. At most one switch
 * every 3 minutes and 3 in a row; a validated network resets everything.
 */
final class Rescue {
    enum Action { NONE, REPORT, TOGGLE }

    static final long FIRST_REPORT_MS = 20_000;
    static final long REPORT_EVERY_MS = 20_000;
    static final int MAX_REPORTS = 3;
    static final long TOGGLE_AT_MS = 75_000;
    static final long TOGGLE_GAP_MS = 180_000;
    static final int MAX_TOGGLES = 3;
    static final long OFF_FOR_MS = 4_000;

    /** What to do now, and in how many ms to ask again (-1: nothing pending). */
    static final class Decision {
        final Action action;
        final long wakeInMs;
        Decision(Action action, long wakeInMs) { this.action = action; this.wakeInMs = wakeInMs; }
        @Override public String toString() { return action + (wakeInMs >= 0 ? " wake+" + wakeInMs : " idle"); }
    }

    private long unvalidatedSince = -1;
    private int reports;
    private int toggles;
    private long lastToggle = Long.MIN_VALUE / 4;

    int reports() { return reports; }
    int toggles() { return toggles; }
    boolean waiting() { return unvalidatedSince >= 0; }
    boolean gaveUp() { return toggles >= MAX_TOGGLES; }

    /** Android validated the Wi-Fi: cancel everything and forget past toggles. */
    void validated() {
        unvalidatedSince = -1;
        reports = 0;
        toggles = 0;
    }

    /** Wi-Fi is connected without VALIDATED. Repeated calls keep the first time. */
    void unvalidated(long now) {
        if (unvalidatedSince < 0) {
            unvalidatedSince = now;
            reports = 0;
        }
    }

    /** Wi-Fi went away (off, out of range, or our own toggle): nothing to rescue until it connects again. */
    void lost() {
        unvalidatedSince = -1;
        reports = 0;
    }

    Decision decide(long now) {
        if (unvalidatedSince < 0 || gaveUp()) return new Decision(Action.NONE, -1);
        long elapsed = now - unvalidatedSince;

        if (elapsed >= TOGGLE_AT_MS) {
            long gapLeft = lastToggle + TOGGLE_GAP_MS - now;
            if (gapLeft > 0) return new Decision(Action.NONE, gapLeft);
            toggles++;
            lastToggle = now;
            // The off/on brings a new connection; its own callback starts the next round.
            unvalidatedSince = -1;
            reports = 0;
            return new Decision(Action.TOGGLE, -1);
        }

        long reportDue = FIRST_REPORT_MS + reports * REPORT_EVERY_MS;
        if (reports < MAX_REPORTS && elapsed >= reportDue) {
            reports++;
            return new Decision(Action.REPORT, nextWake(elapsed));
        }
        return new Decision(Action.NONE, nextWake(elapsed));
    }

    private long nextWake(long elapsed) {
        long next = TOGGLE_AT_MS;
        if (reports < MAX_REPORTS) next = Math.min(next, FIRST_REPORT_MS + reports * REPORT_EVERY_MS);
        return Math.max(0, next - elapsed);
    }
}
