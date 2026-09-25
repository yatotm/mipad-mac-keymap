package local.pad.uu.touchpad;

/** 三指或四指导航每次只触发一次，增减手指不能产生重心跳跃。 */
public final class SwipeState {
    public static final int PASS = 0, CONSUME = 1, LEFT = 2, RIGHT = 3, UP = 4, DOWN = 5;
    private boolean captured, fired, cancelled;
    private int fingers;
    private float originX, originY;

    public boolean captured() { return captured; }

    public void reset() {
        captured = fired = cancelled = false;
        fingers = 0;
    }

    public int update(int action, int count, float x, float y, boolean obstructed, float threshold) {
        if (action == 0) reset();
        if (action == 1 || action == 3) {
            int result = captured ? CONSUME : PASS;
            reset();
            return result;
        }
        if (!Float.isFinite(x) || !Float.isFinite(y)) return captured ? CONSUME : PASS;
        if (!captured) {
            if ((count != 3 && count != 4) || obstructed) return PASS;
            captured = true;
            fingers = count;
            originX = x;
            originY = y;
            return CONSUME;
        }
        if (obstructed || count > 4 || count < fingers) cancelled = true;
        if (fired || cancelled) return CONSUME;
        if (count != fingers || action == 5 || action == 6) {
            fingers = count;
            originX = x;
            originY = y;
            return CONSUME;
        }
        if (action != 2) return CONSUME;
        float dx = x - originX, dy = y - originY;
        float ax = Math.abs(dx), ay = Math.abs(dy);
        if (Math.max(ax, ay) < threshold || Math.max(ax, ay) < 1.4f * Math.min(ax, ay)) {
            return CONSUME;
        }
        fired = true;
        return ax > ay ? (dx < 0 ? LEFT : RIGHT) : (dy < 0 ? UP : DOWN);
    }
}
