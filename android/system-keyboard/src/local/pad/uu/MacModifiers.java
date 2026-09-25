package local.pad.uu;

import android.view.KeyEvent;

/** 同时交换键码和修饰位，保证组合键及左右侧状态一致。 */
public final class MacModifiers {
    private MacModifiers() {}

    public static int swapKeyCode(int key) {
        switch (key) {
            case KeyEvent.KEYCODE_ALT_LEFT: return KeyEvent.KEYCODE_META_LEFT;
            case KeyEvent.KEYCODE_ALT_RIGHT: return KeyEvent.KEYCODE_META_RIGHT;
            case KeyEvent.KEYCODE_META_LEFT: return KeyEvent.KEYCODE_ALT_LEFT;
            case KeyEvent.KEYCODE_META_RIGHT: return KeyEvent.KEYCODE_ALT_RIGHT;
            default: return key;
        }
    }

    public static int swapMetaState(int meta) {
        int changed = meta & ~(KeyEvent.META_ALT_MASK | KeyEvent.META_META_MASK);
        if ((meta & KeyEvent.META_ALT_ON) != 0) changed |= KeyEvent.META_META_ON;
        if ((meta & KeyEvent.META_ALT_LEFT_ON) != 0) changed |= KeyEvent.META_META_LEFT_ON;
        if ((meta & KeyEvent.META_ALT_RIGHT_ON) != 0) changed |= KeyEvent.META_META_RIGHT_ON;
        if ((meta & KeyEvent.META_META_ON) != 0) changed |= KeyEvent.META_ALT_ON;
        if ((meta & KeyEvent.META_META_LEFT_ON) != 0) changed |= KeyEvent.META_ALT_LEFT_ON;
        if ((meta & KeyEvent.META_META_RIGHT_ON) != 0) changed |= KeyEvent.META_ALT_RIGHT_ON;
        return changed;
    }
}
