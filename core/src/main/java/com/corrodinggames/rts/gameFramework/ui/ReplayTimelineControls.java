package com.corrodinggames.rts.gameFramework.ui;

import java.util.function.IntConsumer;

/** Shared geometry and press/drag/release behavior for all HUD rendering backends. */
public final class ReplayTimelineControls {
    public record Layout(float left, float top, float width, float height, float buttonWidth) {
        public float trackLeft() { return left + buttonWidth; }
        public float trackRight() { return left + width - buttonWidth; }
        public float centerY() { return top + height / 2; }
        public boolean contains(float x, float y) {
            return x >= left && x <= left + width && y >= top && y <= top + height;
        }
        public int timeAt(float x, int duration) {
            float fraction = Math.max(0, Math.min(1, (x - trackLeft()) / (trackRight() - trackLeft())));
            return (int) (fraction * duration);
        }
    }

    private boolean wasDown;
    private boolean captured;
    private int action; // -1/+1 buttons, 0 track
    private int preview = -1;

    public static Layout layout(float viewportWidth, float scale, float top) {
        float width = Math.max(1, Math.min(viewportWidth - 16, 420 * scale));
        float button = Math.min(48 * scale, width / 4);
        return new Layout((viewportWidth - width) / 2, top, width, Math.max(28, 28 * scale), button);
    }

    public boolean isCaptured() { return captured; }
    public int getPreviewMillis() { return preview; }

    public void reset() {
        captured = false;
        wasDown = false;
        preview = -1;
    }

    public boolean handle(Layout layout, boolean down, float x, float y,
                          int current, int duration, IntConsumer seek) {
        boolean consumed = captured;
        if (down && !wasDown && layout.contains(x, y)) {
            captured = true;
            consumed = true;
            action = x < layout.trackLeft() ? -1 : x > layout.trackRight() ? 1 : 0;
        }
        if (captured && duration >= 0) {
            preview = action == 0 ? layout.timeAt(x, duration)
                    : (int) Math.max(0L, Math.min(duration, (long) current + action * 10_000L));
            if (!down) {
                if (action == 0 || layout.contains(x, y)) seek.accept(preview);
            }
        }
        if (!down) { captured = false; preview = -1; }
        wasDown = down;
        return consumed;
    }
}
