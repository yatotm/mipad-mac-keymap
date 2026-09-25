import local.pad.uu.BluetoothFn;

/** 回放实测的 0xEA/0x70 报告及恢复 Ctrl 时的中间状态。 */
public final class BluetoothFnTest {
    private static int checks;
    private static long time;
    private static void check(boolean value, String message) {
        checks++; if (!value) throw new AssertionError(message);
    }
    private static final int[] SCANS = {29, 42, 56, 125, 97, 54, 100, 126};
    private static int transition(BluetoothFn fn, int from, int to, boolean enabled, int expected) {
        int emitted = 0;
        long frame = ++time;
        for (int i = 0; i < 8; i++) if (((from ^ to) & (1 << i)) != 0) {
            check(fn.update(SCANS[i], (to & (1 << i)) != 0, frame, enabled), "Fn 报告中的修饰键必须消费");
            int action = fn.finishFrame();
            if (action != 0) { check(action == expected, "报告中间状态不能触发其他功能"); emitted++; }
        }
        return emitted;
    }
    public static void main(String[] args) {
        int[] reports = {0x70, 0x6f, 0xb6, 0xcd, 0xb5, 0xe2, 0xea, 0xe9};
        for (int report : reports) {
            BluetoothFn fn = new BluetoothFn();
            check(fn.update(29, true, ++time, true), "原 Ctrl 启动专用 Fn 层"); fn.finishFrame();
            check(transition(fn, 1, report, true, BluetoothFn.function(report)) == 1, "一次报告只发一次动作");
            check(fn.holding() == BluetoothFn.function(report), "保留当前按住动作");
            check(fn.finishFrame() == 0, "重复刷新不重复提交");
            // 0xEA→1 的中间值是 0xE9；尤其不能在松开音量减时误发音量加。
            check(transition(fn, report, 1, true, 0) == 0 && fn.holding() == 0, "释放不产生相反动作");
            check(transition(fn, 1, report, true, BluetoothFn.function(report)) == 1, "Fn 保持时可再次按同一键");
            fn.cancel();
            check(fn.holding() == 0, "失焦立即终止重复");
            transition(fn, report, 0, false, 0);
            check(!fn.update(42, true, ++time, true), "整组释放后普通 Shift 直通");
        }
        BluetoothFn outside = new BluetoothFn();
        check(!outside.update(29, true, ++time, false), "UU 外的 Ctrl 不被接管");
        check(!outside.update(42, true, ++time, false), "UU 外组合键不被接管");
        BluetoothFn chord = new BluetoothFn();
        check(!chord.update(125, true, ++time, true), "普通 Option 直通");
        check(!chord.update(29, true, ++time, true), "不抢夺已经开始的多修饰键组合");
        BluetoothFn released = new BluetoothFn();
        released.update(29, true, ++time, true); released.finishFrame();
        released.update(29, false, ++time, true); released.finishFrame();
        check(!released.update(97, true, ++time, true), "松开 Fn 后右 Ctrl 保持原义");
        check(BluetoothFn.function(3) == 0 && BluetoothFn.function(255) == 0, "未知位图不执行动作");
        System.out.println("通过 " + checks + " 项蓝牙 Fn 报告与修饰状态检查");
    }
}
