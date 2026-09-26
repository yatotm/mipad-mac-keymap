package local.pad.moonlight;

/** 以毫米处理触点，不依赖屏幕分辨率。所有输出都有对应的结束或取消。 */
public final class GestureEngine {
    public interface Output {
        void scroll(float x, float y, String phase, long time);
        void dock(int axis, double progress, double velocity, String phase);
        void magnify(double delta, String phase);
        void drag(float x, float y, boolean start);
        void release();
        void secondaryClick();
    }
    private static final int IDLE = 0, WAIT = 1, SCROLL = 2, DOCK = 3, DRAG = 4, DONE = 5, ZOOM = 6;
    private final Output output;
    private int mode, fingers, axis;
    private float originX, originY, originSpan, lastX, lastY, lastSpan;
    private long began, sampleTime, movedAt, contactBegan;
    private boolean secondaryTap;
    private double progress, velocity;
    public GestureEngine(Output output) { this.output = output; }
    public boolean captured() { return mode != IDLE; }

    public void cancel(long time) {
        secondaryTap = false;
        finish(time, true);
        mode = IDLE;
        fingers = 0;
    }

    private void finish(long time, boolean cancelled) {
        if (mode == SCROLL) output.scroll(0, 0, cancelled ? "cancelled" : "ended", time);
        if (mode == DOCK) {
            double exit = time - sampleTime > 80 ? 0 : velocity;
            output.dock(axis, progress, cancelled ? 0 : exit, cancelled ? "cancelled" : "ended");
        }
        if (mode == DRAG) output.release();
        if (mode == ZOOM) output.magnify(0, cancelled ? "cancelled" : "ended");
        mode = DONE;
    }

    public boolean update(int action, int count, float x, float y, float span, int buttons, long time) {
        if (action == 0) { cancel(time); contactBegan = time; }
        boolean claimed = captured();
        if (action == 1 || action == 3 || count == 0) {
            // 等两指全部抬起再右击，取消、拖动或长时间停留都不能变成点击。
            boolean click = action == 1 && buttons == 0 && time >= contactBegan && time - contactBegan <= 250
                    && (secondaryTap || (mode == WAIT && fingers == 2))
                    && Float.isFinite(x) && Float.isFinite(y)
                    && (!secondaryTap || Math.hypot(x - lastX, y - lastY) < 0.35);
            secondaryTap = false;
            finish(time, action == 3);
            mode = IDLE; fingers = 0;
            if (click) output.secondaryClick();
            return claimed;
        }
        if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(span) || time < sampleTime) {
            cancel(time); return claimed;
        }
        if (count > 4 || buttons != 0) {
            secondaryTap = false;
            if (claimed) finish(time, true);
            return claimed;
        }
        if (mode == DONE) {
            if (count != 1 || Math.hypot(x - lastX, y - lastY) >= 0.35) secondaryTap = false;
            return true;
        }
        if (count != fingers) {
            if (count < fingers) {
                secondaryTap = mode == WAIT && fingers == 2 && count == 1;
                lastX = x; lastY = y;
                finish(time, false); return claimed;
            }
            if (count < 2) return false;
            finish(time, true);
            mode = WAIT; fingers = count; began = sampleTime = time;
            movedAt = -1;
            originX = lastX = x; originY = lastY = y; originSpan = lastSpan = span;
            progress = velocity = 0;
            return true;
        }
        if (action != 2 || mode == IDLE) return claimed;
        float dx = x - originX, dy = y - originY;
        double travel = Math.hypot(dx, dy), shape = originSpan - span;
        if (mode == WAIT) {
            // 停留时间截止于开始移动，不能把缓慢起步的导航误判成停留拖拽。
            if (travel >= 0.35 && movedAt < 0) movedAt = time;
            if (fingers == 2) {
                if (originSpan >= 5 && Math.abs(shape) >= 1 && Math.abs(shape) > travel * 1.5) {
                    mode = ZOOM;
                    output.magnify(0, "began");
                } else {
                    if (travel < 0.35 || Math.abs(shape) > travel * 1.5) return true;
                    mode = SCROLL;
                    output.scroll(0, 0, "began", began);
                }
            } else if (originSpan >= 8 && Math.abs(shape) >= 2 && Math.abs(shape) > travel * 1.3) {
                mode = DOCK; axis = 3;
                output.dock(axis, 0, 0, "began");
            } else if (travel >= 1.2 && travel > Math.abs(shape) * 1.3) {
                if (fingers == 3 && movedAt - began >= 300) {
                    mode = DRAG;
                    output.drag(0, 0, true);
                } else {
                    mode = DOCK; axis = Math.abs(dx) > Math.abs(dy) ? 1 : 2;
                    output.dock(axis, 0, 0, "began");
                }
            } else return true;
        }
        if (mode == SCROLL) output.scroll((x - lastX) * 6, (y - lastY) * 6, "changed", time);
        if (mode == DRAG) output.drag((x - lastX) * 12, (y - lastY) * 12, false);
        if (mode == ZOOM && lastSpan > 0) output.magnify(Math.max(-0.5, Math.min(0.5, (span - lastSpan) / lastSpan)), "changed");
        if (mode == DOCK) {
            double next = axis == 1 ? -dx / 35.0 : axis == 2 ? -dy / 24.0
                    : -shape / Math.max(10, originSpan * 0.5);
            next = Math.max(-3, Math.min(3, next));
            if (time > sampleTime) velocity = Math.max(-8, Math.min(8, (next - progress) * 1000 / (time - sampleTime)));
            progress = next;
            output.dock(axis, progress, velocity, "changed");
        }
        lastX = x; lastY = y; lastSpan = span; sampleTime = time;
        return true;
    }
}
