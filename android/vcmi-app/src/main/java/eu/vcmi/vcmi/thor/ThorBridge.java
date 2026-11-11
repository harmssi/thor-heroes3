package eu.vcmi.vcmi.thor;

import android.os.Handler;
import android.os.Looper;

/**
 * Thor Edition: mailbox between the game (native, SDL thread) and the deck (UI thread).
 * Native side calls {@link #poll()} a few times per second and {@link #push(String)} with a
 * JSON snapshot when something changed (see client/thor/ThorDeck.cpp).
 */
public final class ThorBridge {
    private ThorBridge() {}

    public interface Listener {
        void onState(String json);

        void onArt(String key, int w, int h, byte[] rgba, int[] meta);
    }

    private static final StringBuilder pending = new StringBuilder();
    private static final Handler ui = new Handler(Looper.getMainLooper());
    private static volatile boolean shown;
    private static volatile Listener listener;

    static void setShown(boolean value, Listener l) {
        shown = value;
        listener = value ? l : null;
    }

    /** Deck -> game: one command per call, e.g. "key adventureEndTurn", "hero 12", "tile 30 41". */
    public static void send(String command) {
        synchronized (pending) {
            pending.append(command).append('\n');
        }
    }

    /** Called by native code: "A" or "N" (deck shown?), then pending commands, one per line. */
    @SuppressWarnings("unused")
    public static String poll() {
        synchronized (pending) {
            String out = (shown ? "A\n" : "N\n") + pending;
            pending.setLength(0);
            return out;
        }
    }

    /** Called by native code: one piece of interface art read from the player's game files (w = 0: not available). */
    @SuppressWarnings("unused")
    public static void art(final String key, final int w, final int h, final byte[] rgba, final int[] meta) {
        final Listener l = listener;
        if (l != null) {
            ui.post(() -> l.onArt(key, w, h, rgba, meta));
        }
    }

    /** Called by native code with a JSON snapshot. */
    @SuppressWarnings("unused")
    public static void push(final String json) {
        final Listener l = listener;
        if (l != null) {
            ui.post(() -> l.onState(json));
        }
    }
}
