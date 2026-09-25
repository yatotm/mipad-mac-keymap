import local.pad.uu.FunctionRoutes;
import static local.pad.uu.FunctionRoutes.*;

public final class FunctionRoutesTest {
    private static void check(boolean ok) {
        if (!ok) throw new AssertionError("Fn route mismatch");
    }
    public static void main(String[] args) {
        FunctionRoutes routes = new FunctionRoutes();
        check(routes.record("brightness-local", true, LOCAL) == LOCAL);
        // 先松 Fn，亮度键的松开仍必须交给平板，以停止持续调节。
        check(routes.record("brightness-local", false, REMOTE) == LOCAL);
        check(!routes.isRemote("brightness-local"));
        check(routes.record("f1-remote", true, REMOTE) == REMOTE);
        // F 键按下后才按 Fn，松开仍必须发送远端 F 键释放。
        check(routes.record("f1-remote", false, LOCAL) == REMOTE);
        check(routes.isRemote("f1-remote"));
        check(routes.record("orphan-up", false, REMOTE) == IGNORE);
        check(routes.record("held", true, REMOTE) == REMOTE);
        for (int i = 0; i < 300; i++) {
            routes.record("stroke-" + i, true, REMOTE);
            routes.record("stroke-" + i, false, REMOTE);
        }
        check(routes.isRemote("held"));
        check(routes.record("held", false, LOCAL) == REMOTE);
        check(routes.record("ignored-chord", true, IGNORE) == IGNORE);
        check(routes.record("ignored-chord", false, LOCAL) == IGNORE);
        int[] scans = {224, 225, 190, 99, 194, 193, 165, 164, 163, 113, 114, 115};
        for (int i = 0; i < scans.length; i++) check(FunctionRoutes.index(scans[i]) == i + 1);
        check(FunctionRoutes.index(1) == 0);   // Esc 保持原样。
        check(FunctionRoutes.index(111) == 0); // Delete 保持原样。
        check(FunctionRoutes.index(192) == 0); // Fn 自身不作为 F 键发送。
        check(FunctionRoutes.index(191) == 1); // Fn+亮度减的另一种原始上报。
        check(FunctionRoutes.stockKeyCode(1, 0) == 220);
        check(FunctionRoutes.stockKeyCode(2, 191) == 221);
        check(FunctionRoutes.stockKeyCode(3, 133) == 191);
        check(FunctionRoutes.stockKeyCode(0, 111) == 111);
        System.out.println("Fn layer and release-order checks passed");
    }
}
