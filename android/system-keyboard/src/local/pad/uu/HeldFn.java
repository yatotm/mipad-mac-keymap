package local.pad.uu;

/** 原 Ctrl 仅作为按住式本地层选择键；不向远端发送 Fn。 */
public final class HeldFn {
    private boolean held;

    public synchronized void press() { held = true; }
    public synchronized void release() { held = false; }
    public synchronized void cancel() { held = false; }
    public synchronized void reset() { held = false; }

    public synchronized int rowDown(boolean enabled) {
        if (!enabled) return FunctionRoutes.LOCAL;
        return held ? FunctionRoutes.REMOTE_FUNCTION : FunctionRoutes.REMOTE;
    }
}
