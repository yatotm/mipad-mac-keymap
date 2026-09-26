package local.pad.moonlight;

/** 从捕获后的触控板原始坐标计算位移，不依赖设备未提供的相对轴。 */
public final class PointerEngine {
    public interface Output {
        void move(float x, float y);
        void button(int button, boolean down);
    }
    private final Output output;
    private boolean tracking, tap;
    private int buttons;
    private float x, y, originX, originY;
    private long began;

    public PointerEngine(Output output) { this.output = output; }

    public void updateButtons(int value) {
        value &= 7;
        int changed = value ^ buttons;
        for (int i = 0; i < 3; i++) {
            int mask = 1 << i;
            if ((changed & mask) != 0) output.button(i == 0 ? 1 : i == 1 ? 3 : 2, (value & mask) != 0);
        }
        if (value != 0 || changed != 0) tap = false;
        buttons = value;
    }

    public void ignoreContact() { tracking = tap = false; }

    public void cancel() { ignoreContact(); updateButtons(0); }

    public void update(int action, int count, float nx, float ny, long time) {
        if (action == 3) { cancel(); return; }
        if (count != 1 || !Float.isFinite(nx) || !Float.isFinite(ny)) {
            ignoreContact(); return;
        }
        if (action == 0) {
            tracking = true; tap = buttons == 0; began = time;
            x = originX = nx; y = originY = ny;
        } else if (action == 2 && tracking) {
            if (Math.hypot(nx - originX, ny - originY) >= 1) tap = false;
            output.move((nx - x) * 12, (ny - y) * 12);
            x = nx; y = ny;
        } else if (action == 1) {
            if (tracking && tap && time >= began && time - began < 250) {
                output.button(1, true); output.button(1, false);
            }
            ignoreContact();
        }
    }
}
