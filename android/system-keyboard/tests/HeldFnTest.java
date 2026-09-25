import local.pad.uu.HeldFn;
import local.pad.uu.FunctionRoutes;

/** 验证按住层的起止、焦点变化和先松 Fn 时的路由配对。 */
public final class HeldFnTest {
    private static int checks;
    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        HeldFn fn = new HeldFn();
        check(fn.rowDown(true) == FunctionRoutes.REMOTE, "普通顶排是远端 F 键");
        fn.press();
        check(fn.rowDown(true) == FunctionRoutes.REMOTE_FUNCTION, "按住 Fn 切换到远端功能层");
        check(fn.rowDown(true) == FunctionRoutes.REMOTE_FUNCTION, "连续两个功能键保持 Fn 层");
        fn.press();
        check(fn.rowDown(true) == FunctionRoutes.REMOTE_FUNCTION, "重复按下不会翻转 Fn 状态");
        FunctionRoutes routes = new FunctionRoutes();
        routes.record("volume", true, fn.rowDown(true));
        fn.release();
        check(routes.record("volume", false, fn.rowDown(true)) == FunctionRoutes.REMOTE_FUNCTION,
                "先松 Fn 时音量键松开仍使用同一路由");
        check(routes.isRemote("volume"), "远端功能层同样绕过平板处理");
        check(fn.rowDown(true) == FunctionRoutes.REMOTE, "松开后没有一次性残留层");
        fn.press(); fn.release();
        check(fn.rowDown(true) == FunctionRoutes.REMOTE, "点按 Fn 不武装后续顶排键");
        fn.press(); fn.cancel();
        check(fn.rowDown(true) == FunctionRoutes.REMOTE, "失焦清除按住状态");
        fn.press(); fn.reset();
        check(fn.rowDown(true) == FunctionRoutes.REMOTE, "设备断开清除状态");
        fn.press();
        check(fn.rowDown(false) == FunctionRoutes.LOCAL, "UU 外仍由平板处理");
        System.out.println("通过 " + checks + " 项按住式 Fn 检查");
    }
}
