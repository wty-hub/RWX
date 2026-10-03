package com.corrodinggames.rts.gameFramework.ui;

import java.util.function.IntConsumer;

/** Shared geometry and press/drag/release behavior for all HUD rendering backends. */
public final class ReplayTimelineControls {
    public record Layout(float left, float top, float width, float height, float buttonWidth) {
        public float unit() { return height / 100; }
        public float trackLeft() { return left + 16 * unit(); }
        public float trackRight() { return left + width - 16 * unit(); }
        public float centerY() { return top + 20 * unit(); }
        public float buttonTop() { return top + 60 * unit(); }
        public float buttonBottom() { return top + height - 10 * unit(); }
        public float buttonLeft(int direction) {
            return left + width / 2 + (direction < 0 ? -buttonWidth - 5 * unit() : 5 * unit());
        }
        public boolean buttonContains(int direction, float x, float y) {
            return x >= buttonLeft(direction) && x <= buttonLeft(direction) + buttonWidth
                    && y >= buttonTop() && y <= buttonBottom();
        }
        public boolean trackContains(float x, float y) {
            return x >= trackLeft() - 8 * unit() && x <= trackRight() + 8 * unit()
                    && y >= top + 6 * unit() && y <= top + 34 * unit();
        }
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
    private int action; // -1/+1 buttons, 0 track, 2 inert panel area
    private int preview = -1;

    public static Layout layout(float viewportWidth, float scale, float top) {
        float width = Math.max(1, Math.min(viewportWidth - 16, 420 * scale));
        float unit = Math.max(.75f, Math.min(scale, width / 280));
        return new Layout((viewportWidth - width) / 2, top, width, 100 * unit, 76 * unit);
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
            action = layout.buttonContains(-1, x, y) ? -1 : layout.buttonContains(1, x, y) ? 1
                    : layout.trackContains(x, y) ? 0 : 2;
        }
        if (captured && duration >= 0 && action != 2) {
            preview = action == 0 ? layout.timeAt(x, duration)
                    : (int) Math.max(0L, Math.min(duration, (long) current + action * 10_000L));
            if (!down) {
                if (action == 0 || layout.buttonContains(action, x, y)) seek.accept(preview);
            }
        }
        if (!down) { captured = false; preview = -1; }
        wasDown = down;
        return consumed;
    }
}
