package local.pad.uu;

/** 原 Ctrl 仅作为按住式本地层选择键；不向远端发送 Fn。 */
public final class HeldFn {
    private boolean held;
    private long releasedAt = -1;
    private static final long RELEASE_FRAME_MS = 8;

    public synchronized void press() { held = true; releasedAt = -1; }
    public synchronized void release(long time) {
        if (held) releasedAt = time;
        held = false;
    }
    public synchronized void cancel() { held = false; releasedAt = -1; }
    public synchronized void reset() { cancel(); }

    public synchronized int rowDown(boolean enabled, long time) {
        if (!enabled) return FunctionRoutes.LOCAL;
        // 实测顶排前有短暂 Ctrl Up，顶排结束后再恢复 Ctrl Down；只桥接同一输入帧。
        boolean releaseFrame = releasedAt >= 0 && time >= releasedAt && time - releasedAt <= RELEASE_FRAME_MS;
        return held || releaseFrame ? FunctionRoutes.REMOTE_FUNCTION : FunctionRoutes.REMOTE;
    }
}
