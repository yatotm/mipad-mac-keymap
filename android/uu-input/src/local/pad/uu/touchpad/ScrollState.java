package local.pad.uu.touchpad;

/** 线性转换保留不足一个传输单位的余量，避免慢速手势丢失。 */
public final class ScrollState {
    private final double[] remainder = new double[2];

    public void reset() { remainder[0] = remainder[1] = 0; }

    public int convert(int axis, float delta, float scale) {
        if (!Float.isFinite(delta) || !Float.isFinite(scale)) return 0;
        double value = delta * (double) scale;
        if (value * remainder[axis] < 0) remainder[axis] = 0;
        value += remainder[axis];
        int whole = (int) Math.max(-960, Math.min(960, value));
        remainder[axis] = Math.abs(value) > 960 ? 0 : value - whole;
        return whole;
    }
}
