package local.pad.uu.touchpad;

/** 通过 UU 已有键盘通道传送固定动作；Mac 只识别来自 UU 的 Control+Option+Command 组合。 */
public final class RemoteCommand {
    private RemoteCommand() {}
    public static int key(String action) {
        switch (action) {
            case "brightness-down": return 131;
            case "brightness-up": return 132;
            case "mic-mute": return 133;
            case "screenshot": return 134;
            case "assistant": return 135;
            case "sleep": return 136;
            case "previous": return 137;
            case "play-pause": return 138;
            case "next": return 139;
            case "mute": return 140;
            case "volume-down": return 141;
            case "volume-up": return 142;
            case "navigate-back": return 21;
            case "navigate-forward": return 22;
            case "launchpad": return 62;
            default: throw new IllegalArgumentException("未知控制动作");
        }
    }
}
