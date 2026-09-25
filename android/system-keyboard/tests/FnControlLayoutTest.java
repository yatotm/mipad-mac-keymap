import local.pad.uu.FnControlLayout;
import local.pad.uu.FunctionRoutes;
import local.pad.uu.MacModifiers;
import static android.view.KeyEvent.*;

public final class FnControlLayoutTest {
    private static int checks;
    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        check(FnControlLayout.keyCode(192, KEYCODE_FUNCTION) == KEYCODE_CTRL_LEFT, "第二键映射为 Control");
        check(FnControlLayout.keyCode(103, KEYCODE_DPAD_UP) == KEYCODE_DPAD_UP, "方向键保持键码");
        check(FnControlLayout.metaState(META_CTRL_ON | META_CTRL_LEFT_ON) == 0, "原 Ctrl 作为 Fn 不污染组合键");
        check(FnControlLayout.metaState(META_FUNCTION_ON) == (META_CTRL_ON | META_CTRL_LEFT_ON), "原语音键携带 Control 状态");
        check(FnControlLayout.metaState(META_CTRL_RIGHT_ON | META_CTRL_ON) == (META_CTRL_RIGHT_ON | META_CTRL_ON), "右 Control 保留");
        check(FnControlLayout.metaState(META_FUNCTION_ON | META_CTRL_RIGHT_ON | META_CTRL_ON)
                == (META_CTRL_LEFT_ON | META_CTRL_RIGHT_ON | META_CTRL_ON), "左右 Control 合并");
        int others = META_SHIFT_ON | META_SHIFT_LEFT_ON | META_ALT_ON | META_ALT_LEFT_ON | META_CAPS_LOCK_ON;
        check(FnControlLayout.metaState(others | META_FUNCTION_ON) == (others | META_CTRL_ON | META_CTRL_LEFT_ON), "不损坏 Option Shift 或锁定状态");
        int clover = META_META_ON | META_META_LEFT_ON;
        check(FnControlLayout.metaState(MacModifiers.swapMetaState(clover)) == (META_ALT_ON | META_ALT_LEFT_ON), "四叶草组合仍携带 Option");
        FunctionRoutes routes = new FunctionRoutes();
        routes.record("fn-inside", true, FunctionRoutes.IGNORE);
        check(routes.record("fn-inside", false, FunctionRoutes.LOCAL) == FunctionRoutes.IGNORE, "Fn 按下后切出 UU，松开仍被消费");
        routes.record("ctrl-outside", true, FunctionRoutes.LOCAL);
        check(routes.record("ctrl-outside", false, FunctionRoutes.IGNORE) == FunctionRoutes.LOCAL, "UU 外按下的 Ctrl 保持原始松开行为");
        System.out.println("通过 " + checks + " 项 Fn/Control 交换检查");
    }
}
