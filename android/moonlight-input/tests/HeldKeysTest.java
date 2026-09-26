import local.pad.moonlight.HeldKeys;

public final class HeldKeysTest {
    static void check(boolean value) { if (!value) throw new AssertionError(); }
    public static void main(String[] args) {
        HeldKeys keys = new HeldKeys();
        keys.record((short) 0x80a4, (byte) 3, (byte) 0);
        keys.record((short) 0x8026, (byte) 3, (byte) 1);
        keys.record((short) 0x8026, (byte) 3, (byte) 1);
        int[] release = keys.drain();
        check(release.length == 2 && release[0] == 0x802601 && release[1] == 0x80a400);
        check(keys.drain().length == 0);
        keys.record((short) 0x8041, (byte) 3, (byte) 0);
        keys.record((short) 0x8041, (byte) 4, (byte) 0);
        check(keys.drain().length == 0);
        keys.record((short) 0x8041, (byte) 3, (byte) 1);
        keys.record((short) 0x8041, (byte) 4, (byte) 0);
        check(keys.drain()[0] == 0x804101);
        System.out.println("按住状态去重、左右修饰键、协议标志与取消释放检查通过。");
    }
}
