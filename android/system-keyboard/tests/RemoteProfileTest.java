import local.pad.uu.RemoteProfile;
import local.pad.uu.HeldFn;
import local.pad.uu.FunctionRoutes;

/** 验证相同物理 Fn 状态下，UU 不再生成无人接收的 Mac 功能请求。 */
public final class RemoteProfileTest {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        HeldFn fn = new HeldFn();
        for (String name : new String[]{"com.limelight", "local.pad.moonlight.client"}) {
            check(fn.rowDown(RemoteProfile.functions(name), 1000) == FunctionRoutes.REMOTE,
                    "Moonlight 裸顶排仍为远端 F 键");
            fn.press();
            check(fn.rowDown(RemoteProfile.functions(name), 1000) == FunctionRoutes.REMOTE_FUNCTION,
                    "Moonlight 按住 Fn 的功能层不变");
            check(fn.rowDown(RemoteProfile.functions("com.netease.uuremote"), 1000) == FunctionRoutes.LOCAL,
                    "切入 UU 后，同一个 Fn 状态不能把顶排发送到旧 Helper");
            fn.cancel();
        }
        for (String name : new String[]{"", "com.netease.uuremote", "com.android.settings", null}) {
            check(!RemoteProfile.functions(name), "其它应用不能启用 Mac 功能层");
        }
        System.out.println("Moonlight 功能层保持、UU 精简及切换边界检查通过。");
    }
}
