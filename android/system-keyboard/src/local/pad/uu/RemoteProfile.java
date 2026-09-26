package local.pad.uu;

/** UU 只保留修饰键布局；Moonlight 保持已验收的完整功能层。 */
public final class RemoteProfile {
    private RemoteProfile() {}

    public static boolean functions(String packageName) {
        return "com.limelight".equals(packageName) || "local.pad.moonlight.client".equals(packageName);
    }
}
