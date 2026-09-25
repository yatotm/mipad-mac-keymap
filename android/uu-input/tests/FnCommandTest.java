import local.pad.uu.touchpad.FnCommand;

public final class FnCommandTest {
    private static void check(boolean ok) { if (!ok) throw new AssertionError("Fn command validation"); }
    public static void main(String[] args) {
        check(FnCommand.valid(1000, 1, 1000, 1020, true));
        check(!FnCommand.valid(2000, 1, 1000, 1020, true));
        check(!FnCommand.valid(0, 1, 1000, 1020, true));
        check(!FnCommand.valid(-1, 1, 1000, 1020, true));
        check(!FnCommand.valid(1000, 2, 1000, 1020, true));
        check(!FnCommand.valid(1000, 1, 1000, 1600, true));
        check(!FnCommand.valid(1000, 1, 1000, 900, true));
        check(!FnCommand.valid(1000, 1, 1000, 1020, false));
        check(FnCommand.action(11).equals("volume-down"));
        check(FnCommand.action(1).equals("brightness-down"));
        check(FnCommand.action(0) == null && FnCommand.action(13) == null);
        System.out.println("系统 Fn 消息身份、时效、前台范围和编号检查通过");
    }
}
