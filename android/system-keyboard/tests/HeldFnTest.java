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
        check(fn.rowDown(true, 1100) == FunctionRoutes.REMOTE, "普通顶排是远端 F 键");
        fn.press();
        check(fn.rowDown(true, 1100) == FunctionRoutes.REMOTE_FUNCTION, "按住 Fn 切换到远端功能层");
        check(fn.rowDown(true, 1100) == FunctionRoutes.REMOTE_FUNCTION, "连续两个功能键保持 Fn 层");
        fn.press();
        check(fn.rowDown(true, 1100) == FunctionRoutes.REMOTE_FUNCTION, "重复按下不会翻转 Fn 状态");
        FunctionRoutes routes = new FunctionRoutes();
        routes.record("volume", true, fn.rowDown(true, 1100));
        fn.release(1000);
        check(routes.record("volume", false, fn.rowDown(true, 1100)) == FunctionRoutes.REMOTE_FUNCTION,
                "先松 Fn 时音量键松开仍使用同一路由");
        check(routes.isRemote("volume"), "远端功能层同样绕过平板处理");
        check(fn.rowDown(true, 1100) == FunctionRoutes.REMOTE, "松开后没有一次性残留层");
        fn.press(); fn.release(1000);
        check(fn.rowDown(true, 1100) == FunctionRoutes.REMOTE, "点按 Fn 不武装后续顶排键");
        fn.press(); fn.cancel();
        check(fn.rowDown(true, 1100) == FunctionRoutes.REMOTE, "失焦清除按住状态");
        fn.press(); fn.reset();
        check(fn.rowDown(true, 1100) == FunctionRoutes.REMOTE, "设备断开清除状态");
        fn.press();
        check(fn.rowDown(false, 1100) == FunctionRoutes.LOCAL, "UU 外仍由平板处理");
        fn.reset(); fn.press(); fn.release(2000);
        check(fn.rowDown(true, 2001) == FunctionRoutes.REMOTE_FUNCTION,
                "回放实测：顶排前1毫秒的临时释放不应丢失Fn");
        routes.record("release-frame", true, fn.rowDown(true, 2001));
        check(routes.record("release-frame", false, fn.rowDown(true, 2050)) == FunctionRoutes.REMOTE_FUNCTION,
                "释放帧结束后，功能键的松开仍按原路由处理");
        fn.press();
        check(fn.rowDown(true, 2100) == FunctionRoutes.REMOTE_FUNCTION, "顶排后重新上报Ctrl恢复按住状态");
        fn.release(2200);
        check(fn.rowDown(true, 2209) == FunctionRoutes.REMOTE, "超过8毫秒不会成为点按式Fn");
        fn.press(); fn.release(3000); fn.cancel();
        check(fn.rowDown(true, 3001) == FunctionRoutes.REMOTE, "失焦立即清除短暂释放帧");
        fn.release(4000);
        check(fn.rowDown(true, 4001) == FunctionRoutes.REMOTE, "孤立松开不应武装Fn");
        fn.press(); fn.release(5000); fn.release(5007);
        check(fn.rowDown(true, 5010) == FunctionRoutes.REMOTE, "重复松开不能延长释放帧");
        System.out.println("通过 " + checks + " 项按住式 Fn 检查");
    }
}
