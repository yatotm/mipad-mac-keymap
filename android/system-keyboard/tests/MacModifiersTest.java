import local.pad.uu.MacModifiers;
import static android.view.KeyEvent.*;

public final class MacModifiersTest {
    private static void equal(int actual, int expected) {
        if (actual != expected) throw new AssertionError(actual + " != " + expected);
    }

    public static void main(String[] args) {
        equal(MacModifiers.swapKeyCode(KEYCODE_ALT_LEFT), KEYCODE_META_LEFT);
        equal(MacModifiers.swapKeyCode(KEYCODE_META_LEFT), KEYCODE_ALT_LEFT);
        equal(MacModifiers.swapKeyCode(KEYCODE_ALT_RIGHT), KEYCODE_META_RIGHT);
        equal(MacModifiers.swapKeyCode(KEYCODE_META_RIGHT), KEYCODE_ALT_RIGHT);
        equal(MacModifiers.swapKeyCode(KEYCODE_CTRL_LEFT), KEYCODE_CTRL_LEFT);
        equal(MacModifiers.swapMetaState(0), 0);
        equal(MacModifiers.swapMetaState(META_ALT_ON | META_ALT_LEFT_ON | META_SHIFT_ON),
                META_META_ON | META_META_LEFT_ON | META_SHIFT_ON);
        equal(MacModifiers.swapMetaState(META_META_ON | META_META_RIGHT_ON | META_CTRL_ON),
                META_ALT_ON | META_ALT_RIGHT_ON | META_CTRL_ON);
        equal(MacModifiers.swapMetaState(META_META_ON | META_META_LEFT_ON
                | META_ALT_ON | META_ALT_RIGHT_ON),
                META_ALT_ON | META_ALT_LEFT_ON | META_META_ON | META_META_RIGHT_ON);
        // 穷举左右修饰位组合，检查互换可逆且不损坏 Ctrl、Shift、Fn、锁定状态。
        int unchanged = META_CTRL_ON | META_CTRL_LEFT_ON | META_SHIFT_ON | META_SHIFT_RIGHT_ON
                | META_CAPS_LOCK_ON | META_NUM_LOCK_ON | META_FUNCTION_ON;
        int[] bits = {META_ALT_ON, META_ALT_LEFT_ON, META_ALT_RIGHT_ON,
                META_META_ON, META_META_LEFT_ON, META_META_RIGHT_ON};
        for (int mask = 0; mask < 64; mask++) {
            int original = unchanged;
            for (int bit = 0; bit < bits.length; bit++) {
                if ((mask & (1 << bit)) != 0) original |= bits[bit];
            }
            int changed = MacModifiers.swapMetaState(original);
            equal(changed & unchanged, unchanged);
            equal(MacModifiers.swapMetaState(changed), original);
        }
        for (int key = 0; key <= 304; key++) {
            equal(MacModifiers.swapKeyCode(MacModifiers.swapKeyCode(key)), key);
        }
        System.out.println("Mac modifier mapping: all checks passed");
    }
}
