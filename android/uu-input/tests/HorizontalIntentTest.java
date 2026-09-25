import local.pad.uu.touchpad.TwoFingerIntent;

public final class HorizontalIntentTest {
    private static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }
    public static void main(String[] args) {
        TwoFingerIntent state = new TwoFingerIntent();
        check(state.move(.1f, .1f, 2) == 0, "落指抖动不导航");
        check(state.move(.6f, 0, 0) == 0, "横向起步未过阈值不翻页");
        check(state.move(1.4f, .1f, 2) == TwoFingerIntent.RIGHT, "短距离右滑立即提交一次");
        check(state.move(50, 0, 0) == 0, "快速长距离不追加导航");
        check(state.move(-100, 0, 0) == 0, "同次落指反向不重复触发");
        check(!state.isVertical(), "横向离手不进入滚动惯性");
        state.reset();
        check(state.move(-2.1f, 0, 0) == TwoFingerIntent.LEFT, "下一次落指可以左滑");
        state.reset();
        check(state.move(0, .4f, 8) == TwoFingerIntent.VERTICAL, "纵向保留原起步阈值");
        check(state.takeVertical() == 8, "起步位移完整保留");
        check(state.move(10, .3f, 6) == TwoFingerIntent.VERTICAL, "纵向途中不误判导航");
        check(state.takeVertical() == 6, "连续纵向位移不重复累积");
        state.reset();
        check(state.move(.2f, .2f, 4) == 0, "对角线先等待方向明确");
        check(state.move(0, .4f, 8) == TwoFingerIntent.VERTICAL && state.takeVertical() == 12,
                "等待方向时不丢失纵向位移");
        state.reset();
        check(state.move(Float.NaN, 3, 20) == 0, "拒绝异常触点");
        System.out.println("横向一次性导航、纵向连续滚动检查通过");
    }
}
