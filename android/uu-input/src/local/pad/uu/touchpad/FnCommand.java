package local.pad.uu.touchpad;

/** 仅允许系统进程发来的短时效固定功能编号。 */
public final class FnCommand {
    public static final String ACTION = "local.pad.uu.FUNCTION";
    private static final String[] ACTIONS = {"brightness-down", "brightness-up", "mic-mute", "screenshot",
            "assistant", "sleep", "previous", "play-pause", "next", "mute", "volume-down", "volume-up"};
    private FnCommand() {}
    public static String action(int index) { return index >= 1 && index <= 12 ? ACTIONS[index - 1] : null; }
    public static boolean valid(int uid, int version, long when, long now, boolean focused) {
        return uid == 1000 && version == 1 && focused && when > 0 && now >= when && now - when <= 500;
    }
}
