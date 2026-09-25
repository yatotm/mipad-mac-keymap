package local.pad.uu.touchpad;

/** 用触点间距区分收拢和平移，停留三百毫秒后平移才拖拽。所有距离均为毫米。 */
public final class ThreeFingerMode {
    public static final int WAIT = 0, NAVIGATE = 1, DRAG = 2, PINCH = 3;
    private int mode;
    private float x, y, span;
    private long time;
    private boolean changingShape, expanding;

    public void begin(float x, float y, float span, long time) {
        this.x = x; this.y = y; this.span = span; this.time = time;
        mode = WAIT; changingShape = expanding = false;
    }

    public int move(float x, float y, float span, long time) {
        // 收拢只发一次；已开始的导航或拖拽不再改判。
        if (mode != WAIT) return mode == PINCH ? WAIT : mode;
        if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(span)
                || !Float.isFinite(this.x) || !Float.isFinite(this.y)
                || !Float.isFinite(this.span) || span < 0 || this.span < 0) return WAIT;
        double travel = Math.hypot(x - this.x, y - this.y);
        float contraction = this.span - span;
        float deformation = Math.abs(contraction);
        if (!changingShape && this.span >= 8f && deformation >= 0.8f
                && deformation >= travel * 1.2) {
            changingShape = true;
            expanding = contraction < 0;
        }
        if (changingShape) {
            // 小幅调整指距不触发；外张也不在途中改判为收拢。
            if (!expanding && contraction >= Math.max(4f, this.span * 0.20f)
                    && contraction >= travel * 1.2) {
                mode = PINCH;
                return PINCH;
            }
            return WAIT;
        }
        if (travel >= 1.0 && travel >= deformation * 1.25) {
            mode = time - this.time >= 300 ? DRAG : NAVIGATE;
        }
        return mode;
    }
}
