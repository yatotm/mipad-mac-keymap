package local.pad.uu;

import android.view.KeyEvent;

/** 仅在 UU 中交换前两个物理键；同时重建随其他按键携带的修饰状态。 */
public final class FnControlLayout {
    public static final int FN_SCAN = 29;
    public static final int CONTROL_SCAN = 192;
    private FnControlLayout() {}

    public static int keyCode(int scan, int original) {
        return scan == CONTROL_SCAN ? KeyEvent.KEYCODE_CTRL_LEFT : original;
    }

    public static int metaState(int original) {
        // 原左 Ctrl 现在是 Fn，原 FUNCTION 才对应远端左 Control；右 Ctrl 仍保持原意。
        int changed = original & ~(KeyEvent.META_FUNCTION_ON
                | KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON);
        if ((original & KeyEvent.META_CTRL_RIGHT_ON) != 0) changed |= KeyEvent.META_CTRL_ON;
        if ((original & KeyEvent.META_FUNCTION_ON) != 0) {
            changed |= KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON;
        }
        return changed;
    }
}
