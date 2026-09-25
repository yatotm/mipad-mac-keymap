package local.pad.uu.touchpad;

/** 只认双指同向平移，捏合仍交给 UU；无需先停留等待。 */
public final class TwoFingerScroll {
    public static final int WAIT = 0, SCROLL = 1, PINCH = 2;
    private float x0, y0, x1, y1, lastX, lastY, dx, dy;
    private int decision;

    public void begin(float a, float b, float c, float d) {
        x0 = a; y0 = b; x1 = c; y1 = d;
        lastX = (a + c) / 2; lastY = (b + d) / 2;
        decision = WAIT; dx = dy = 0;
    }

    public int move(float a, float b, float c, float d, float rx, float ry) {
        if (!Float.isFinite(a) || !Float.isFinite(b) || !Float.isFinite(c)
                || !Float.isFinite(d) || !(rx > 0) || !(ry > 0)) return WAIT;
        if (decision == PINCH) return PINCH;
        if (decision == WAIT) {
            float ax = (a - x0) / rx, ay = (b - y0) / ry;
            float bx = (c - x1) / rx, by = (d - y1) / ry;
            double da = Math.hypot(ax, ay), db = Math.hypot(bx, by);
            if (Math.max(da, db) < 0.35) return WAIT;
            if (Math.min(da, db) < 0.15) {
                if (Math.max(da, db) >= 1.0) decision = PINCH;
                return decision;
            }
            if (ax * bx + ay * by < 0.5 * da * db) {
                decision = PINCH;
                return PINCH;
            }
            if (Math.hypot((ax + bx) / 2, (ay + by) / 2) < 0.35) return WAIT;
            decision = SCROLL;
        }
        float x = (a + c) / 2, y = (b + d) / 2;
        dx = x - lastX; dy = y - lastY;
        lastX = x; lastY = y;
        return SCROLL;
    }

    public float dx() { return dx; }
    public float dy() { return dy; }
}
