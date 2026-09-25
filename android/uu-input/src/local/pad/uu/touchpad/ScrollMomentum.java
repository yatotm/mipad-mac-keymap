package local.pad.uu.touchpad;

/** 由最近的真实位移估速，抬手后短暂衰减；暂停再抬手不会惯性滑动。 */
public final class ScrollMomentum {
    private static final double MAX_SPEED = 12.0;
    private static final double STOP_SPEED = 0.01;
    private double vx, vy;
    private float dx, dy;
    private long sampleTime, frameTime, endTime;
    private boolean sampled, running;
    private double decayMs = 180, distance;

    public void begin(long time) {
        vx = vy = distance = 0; dx = dy = 0;
        sampleTime = time; sampled = running = false;
    }

    public void sample(float x, float y, long time) {
        long dt = time - sampleTime;
        if (dt <= 0 || !Float.isFinite(x) || !Float.isFinite(y)) return;
        double alpha = sampled && dt < 80 ? 1 - Math.exp(-dt / 35.0) : 1;
        vx += alpha * (x / dt - vx); vy += alpha * (y / dt - vy);
        sampleTime = time; sampled = true;
    }

    public boolean release(long time) {
        return release(time, 2.5f);
    }

    public boolean release(long time, float strength) {
        if (!sampled || time < sampleTime || time - sampleTime > 80) return false;
        double speed = Math.hypot(vx, vy);
        if (speed < 0.04) return false;
        if (!Float.isFinite(strength)) strength = 1;
        strength = Math.max(1, Math.min(4, strength));
        double fast = Math.max(0, Math.min(1, (speed - 0.2) / 0.8));
        double gain = 1 + (strength - 1) * fast;
        vx *= gain; vy *= gain; speed *= gain;
        decayMs = 180 + 60 * fast;
        // 限制偶发大位移的尾速，避免一次异常采样使页面飞走。
        if (speed > MAX_SPEED) { vx *= MAX_SPEED / speed; vy *= MAX_SPEED / speed; }
        frameTime = time; endTime = time + 1500; running = true;
        return true;
    }

    public boolean step(long time) {
        dx = dy = 0;
        if (!running || time <= frameTime) return false;
        if (time > endTime || time - frameTime > 80 || Math.hypot(vx, vy) < STOP_SPEED) {
            running = false;
            return false;
        }
        // 积分连续衰减曲线：初速越大，累计距离越大，到达停止阈值也越晚。
        double decay = Math.exp(-(time - frameTime) / decayMs);
        dx = (float) (vx * decayMs * (1 - decay));
        dy = (float) (vy * decayMs * (1 - decay));
        distance += Math.hypot(dx, dy);
        vx *= decay; vy *= decay; frameTime = time;
        return true;
    }

    public float dx() { return dx; }
    public float dy() { return dy; }
    public double speed() { return Math.hypot(vx, vy); }
    public double distance() { return distance; }
    public long lastSampleTime() { return sampleTime; }
    public void stop() { running = sampled = false; vx = vy = 0; }
}
