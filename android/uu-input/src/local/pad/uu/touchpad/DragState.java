package local.pad.uu.touchpad;

/** 三指移动达到短距离阈值后开始拖拽，短暂等待让四指手势有机会接管。 */
public final class DragState {
    public static final int NONE = 0, START = 1, MOVE = 2;
    private boolean active;
    private float startX, startY, lastX, lastY, dx, dy;
    private long startTime;

    public void begin(float x, float y, long time) {
        active = false;
        startX = lastX = x; startY = lastY = y;
        startTime = time; dx = dy = 0;
    }

    public int move(float x, float y, long time, float resolutionX, float resolutionY) {
        if (!Float.isFinite(x) || !Float.isFinite(y)) return NONE;
        boolean starting = !active;
        if (starting && (time - startTime < 40
                || Math.hypot((x - startX) / resolutionX, (y - startY) / resolutionY) < 1.0)) return NONE;
        dx = x - lastX; dy = y - lastY;
        lastX = x; lastY = y;
        if (dx == 0 && dy == 0) return NONE;
        active = true;
        return starting ? START : MOVE;
    }

    public float dx() { return dx; }
    public float dy() { return dy; }
    public void reset() { active = false; dx = dy = 0; }
}
