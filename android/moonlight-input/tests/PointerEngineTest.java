import local.pad.moonlight.PointerEngine;
import java.util.ArrayList;
import java.util.List;

public final class PointerEngineTest {
    static final class Sink implements PointerEngine.Output {
        float x, y;
        List<String> buttons = new ArrayList<>();
        public void move(float dx, float dy) { x += dx; y += dy; }
        public void button(int button, boolean down) { buttons.add(button + ":" + down); }
    }
    static void check(boolean value) { if (!value) throw new AssertionError(); }
    public static void main(String[] args) {
        Sink out = new Sink(); PointerEngine p = new PointerEngine(out);
        p.update(0, 1, 20, 20, 1000);
        p.update(2, 1, 21, 22, 1010);
        p.update(2, 1, 22, 23, 1020);
        p.update(1, 1, 22, 23, 1030);
        check(out.x == 24 && out.y == 36 && out.buttons.isEmpty());
        p.update(0, 1, 10, 10, 2000); p.update(1, 1, 10, 10, 2100);
        check(out.buttons.equals(List.of("1:true", "1:false")));
        out.buttons.clear();
        p.update(0, 1, 10, 10, 3000); p.ignoreContact(); p.update(1, 1, 10, 10, 3010);
        check(out.buttons.isEmpty());
        p.update(0, 1, 10, 10, 4000); p.updateButtons(1); p.updateButtons(0);
        p.update(1, 1, 10, 10, 4100);
        check(out.buttons.equals(List.of("1:true", "1:false")));
        out.buttons.clear(); p.updateButtons(2); p.cancel(); p.cancel();
        check(out.buttons.equals(List.of("3:true", "3:false")));
        out.buttons.clear(); p.updateButtons(1); p.update(3, 1, 0, 0, 5000); p.cancel();
        check(out.buttons.equals(List.of("1:true", "1:false")));
        System.out.println("原始坐标指针位移、轻触、实体点击、多指接管与取消释放检查通过。");
    }
}
