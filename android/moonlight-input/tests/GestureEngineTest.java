import java.util.ArrayList;
import java.util.List;
import local.pad.moonlight.GestureEngine;

public final class GestureEngineTest {
    static final class Sink implements GestureEngine.Output {
        List<String> phases = new ArrayList<>();
        double position;
        int press, release, moves;
        public void scroll(float x, float y, String phase, long time) { phases.add("scroll:" + phase); }
        public void dock(int axis, double value, double velocity, String phase) {
            phases.add("dock" + axis + ":" + phase); position = value;
        }
        public void drag(float x, float y, boolean start) { if (start) press++; else moves++; }
        public void magnify(double delta, String phase) { phases.add("zoom:" + phase); position = delta; }
        public void release() { release++; }
    }
    static void check(boolean value) { if (!value) throw new AssertionError(); }
    public static void main(String[] args) {
        Sink out = new Sink(); GestureEngine engine = new GestureEngine(out);
        check(!engine.update(0, 1, 0, 0, 0, 0, 1000));
        engine.update(5, 2, 0, 0, 20, 0, 1001);
        engine.update(2, 2, 0, 1, 20, 0, 1010);
        engine.update(6, 1, 3, 9, 0, 0, 1020);
        engine.update(2, 1, 10, 30, 0, 0, 1030);
        engine.update(1, 1, 10, 30, 0, 0, 1040);
        check(out.phases.equals(List.of("scroll:began", "scroll:changed", "scroll:ended")));

        out.phases.clear();
        engine.update(0, 1, 0, 0, 0, 0, 2000);
        engine.update(5, 3, 0, 0, 20, 0, 2010);
        engine.update(2, 3, -10, 0, 20, 0, 2040);
        double halfway = out.position;
        engine.update(2, 3, -10, 0, 20, 0, 2200);
        check(out.position == halfway);
        engine.update(2, 3, -3, 0, 20, 0, 2240);
        check(out.position < halfway && out.position > 0);
        engine.cancel(2250);
        check(out.phases.get(0).equals("dock1:began"));
        check(out.phases.get(out.phases.size()-1).equals("dock1:cancelled"));
        check(out.press == 0);

        out.phases.clear();
        engine.update(0, 1, 0, 0, 0, 0, 3000);
        engine.update(5, 3, 0, 0, 20, 0, 3010);
        engine.update(2, 3, 4, 0, 20, 0, 3320);
        engine.update(2, 3, 8, 0, 20, 0, 3340);
        engine.cancel(3350); engine.cancel(3360);
        check(out.press == 1 && out.release == 1 && out.moves == 2 && out.phases.isEmpty());

        engine.update(5, 3, 0, 0, 20, 0, 4000);
        engine.update(2, 3, 0, 0, 15, 0, 4020);
        check(out.phases.get(0).equals("dock3:began") && out.position < 0);
        engine.update(2, 3, Float.NaN, 0, 15, 0, 4030);
        check(out.phases.get(out.phases.size()-1).equals("dock3:cancelled"));
        check(out.press == 1);

        out.phases.clear();
        engine.update(5, 2, 0, 0, 20, 0, 5000);
        engine.update(2, 2, 2, 0, 20, 0, 5010);
        engine.update(5, 3, 20, 20, 30, 0, 5020);
        check(out.phases.get(out.phases.size()-1).equals("scroll:cancelled"));
        engine.update(2, 3, 20, 18, 30, 0, 5030);
        check(out.phases.contains("dock2:began"));
        check(Math.abs(out.position) < 0.1);
        engine.cancel(5040);
        out.phases.clear();
        engine.update(5, 2, 0, 0, 20, 0, 6000);
        engine.update(2, 2, 0, 0, 23, 0, 6020);
        check(out.position > 0 && out.phases.equals(List.of("zoom:began", "zoom:changed")));
        engine.update(6, 1, 10, 0, 0, 0, 6030);
        check(out.phases.get(out.phases.size()-1).equals("zoom:ended"));
        System.out.println("触点增减、半程保持/反向、取消、拖拽释放与捏合隔离检查通过。");
    }
}
