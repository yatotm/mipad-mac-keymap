import local.pad.moonlight.CursorGeometry;

public final class CursorGeometryTest {
    static void near(float a, float b) { if (Math.abs(a-b) > .001) throw new AssertionError(a + " != " + b); }
    public static void main(String[] args) {
        CursorGeometry main = new CursorGeometry(2880, 1800, 1728, 1117);
        near(main.height, 1800); near(main.left * 2 + main.width, 2880);
        near(main.clampX(-10), main.left); near(main.clampY(1900), 1799);
        CursorGeometry virtual = new CursorGeometry(2880, 1800, 2304, 1440);
        near(virtual.left, 0); near(virtual.top, 0); near(virtual.scale, 1.25f);
        CursorGeometry portrait = new CursorGeometry(1800, 2880, 2304, 1440);
        near(portrait.width, 1800); near(portrait.top * 2 + portrait.height, 2880);
        System.out.println("内屏与虚拟屏比例、黑边裁剪和旋转后的本地光标范围检查通过。");
    }
}
