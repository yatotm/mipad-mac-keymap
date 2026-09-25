package local.pad.uu.touchpad;

/** 纵向连续滚动；横向越过短阈值只提交一次导航，不按速度累计翻页。 */
public final class TwoFingerIntent {
    public static final int WAIT = 0, VERTICAL = 1, LEFT = 2, RIGHT = 3;
    private int axis;
    private boolean fired;
    private float x, y, pendingY;

    public void reset() { axis = 0; fired = false; x = y = pendingY = 0; }

    public int move(float dxMm, float dyMm, float rawDy) {
        if (!Float.isFinite(dxMm) || !Float.isFinite(dyMm) || !Float.isFinite(rawDy)) return WAIT;
        if (fired) return WAIT;
        x += dxMm; y += dyMm; pendingY += rawDy;
        if (axis == 0) {
            if (Math.abs(y) >= 0.35f && Math.abs(y) > Math.abs(x) * 1.3f) axis = 1;
            else if (Math.abs(x) >= 0.5f && Math.abs(x) > Math.abs(y) * 1.3f) axis = 2;
        }
        if (axis == 1) return VERTICAL;
        if (axis == 2 && Math.abs(x) >= 2f) {
            fired = true;
            return x > 0 ? RIGHT : LEFT;
        }
        return WAIT;
    }

    public float takeVertical() { float result = pendingY; pendingY = 0; return result; }
    public boolean isVertical() { return axis == 1; }
}
