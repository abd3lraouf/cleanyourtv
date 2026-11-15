package io.github.abd3lraouf.campopup;

/**
 * When the pop-up opens, which channel it shows and when it closes. Pure Java, unit tested; the service feeds
 * it motion on/off per channel and the clock, and does what it answers.
 *
 * <ul>
 *   <li>Motion on an enabled channel opens the pop-up on that channel, unless that channel is cooling down
 *       (it closed less than {@code cooldownMs} ago).</li>
 *   <li>The pop-up closes {@code holdMs} after its channel's motion ends. A recorder that never sends the
 *       "ended" message still gets it closed {@code maxActiveMs} after the last "started".</li>
 *   <li>Motion on another channel while the shown channel is quiet switches the pop-up to it.</li>
 * </ul>
 */
final class PopupPolicy {
    static final int MAX_CHANNEL = 32;

    enum Action { NONE, SHOW, SWITCH, EXTEND }

    final long holdMs;
    final long cooldownMs;
    final long maxActiveMs;

    private final boolean[] active = new boolean[MAX_CHANNEL + 1];
    private final long[] changedAt = new long[MAX_CHANNEL + 1];
    private final long[] closedAt = new long[MAX_CHANNEL + 1];
    private int showing;

    PopupPolicy(long holdMs, long cooldownMs, long maxActiveMs) {
        this.holdMs = holdMs;
        this.cooldownMs = cooldownMs;
        this.maxActiveMs = maxActiveMs;
        java.util.Arrays.fill(closedAt, Long.MIN_VALUE);
    }

    static boolean valid(int channel) {
        return channel >= 1 && channel <= MAX_CHANNEL;
    }

    int showing() {
        return showing;
    }

    boolean cooling(int channel, long now) {
        return closedAt[channel] != Long.MIN_VALUE && now - closedAt[channel] < cooldownMs;
    }

    /**
     * @param allowed whether pop-ups are on and this channel is in the enabled list
     */
    Action onMotion(int channel, boolean on, long now, boolean allowed) {
        if (!valid(channel)) return Action.NONE;
        active[channel] = on;
        changedAt[channel] = now;
        if (channel == showing) return Action.EXTEND;   // its "ended" message must still start the close timer
        if (!on || !allowed || cooling(channel, now)) return Action.NONE;
        if (showing == 0) {
            showing = channel;
            return Action.SHOW;
        }
        if (active[showing]) return Action.NONE;         // the shown camera still sees something: stay on it
        showing = channel;
        return Action.SWITCH;
    }

    /** Show a channel now whatever the settings say (the test broadcast), as a motion pulse. */
    Action force(int channel, long now) {
        if (!valid(channel)) return Action.NONE;
        Action a = showing == 0 ? Action.SHOW : channel == showing ? Action.EXTEND : Action.SWITCH;
        showing = channel;
        active[channel] = false;
        changedAt[channel] = now;
        return a;
    }

    /** When the pop-up should close, or 0 when it is not up. */
    long closeAt() {
        if (showing == 0) return 0;
        return changedAt[showing] + (active[showing] ? maxActiveMs : holdMs);
    }

    boolean due(long now) {
        return showing != 0 && now >= closeAt();
    }

    /** The pop-up has closed: its channel starts cooling down. */
    void closed(long now) {
        if (showing == 0) return;
        closedAt[showing] = now;
        active[showing] = false;
        showing = 0;
    }
}
