package local.pad.uu;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import android.view.KeyEvent;

/** 同一次按键固定使用按下时的 Fn 层，松开顺序不会改变用途。 */
public final class FunctionRoutes {
    public static final int LOCAL = 0;
    public static final int REMOTE = 1;
    public static final int IGNORE = 2;
    public static final int REMOTE_FUNCTION = 3;

    private static final class Route {
        final int mode;
        boolean released;
        Route(int mode) { this.mode = mode; }
    }

    private final LinkedHashMap<String, Route> routes = new LinkedHashMap<>(128, 0.75f, true);

    public synchronized int record(String key, boolean down, int modeAtStart) {
        Route route = routes.get(key);
        if (route == null) {
            route = new Route(down ? modeAtStart : IGNORE);
            routes.put(key, route);
        }
        route.released = !down;
        // 已松开的记录保留一段时间供后续分发查询，不淘汰尚未松开的键。
        Iterator<Map.Entry<String, Route>> entries = routes.entrySet().iterator();
        while (routes.size() > 128 && entries.hasNext()) {
            Map.Entry<String, Route> entry = entries.next();
            if (entry.getValue().released && !entry.getKey().equals(key)) entries.remove();
        }
        return route.mode;
    }

    public synchronized boolean isRemote(String key) {
        int mode = mode(key);
        return mode == REMOTE || mode == REMOTE_FUNCTION;
    }

    public synchronized int mode(String key) {
        Route route = routes.get(key);
        return route == null ? -1 : route.mode;
    }

    public static int index(int scan) {
        switch (scan) {
            case 224:
            case 191: return 1;
            case 225: return 2;
            case 190: return 3;
            case 99: return 4;
            case 194: return 5;
            case 193: return 6;
            case 165: return 7;
            case 164: return 8;
            case 163: return 9;
            case 113: return 10;
            case 114: return 11;
            case 115: return 12;
            default: return 0;
        }
    }

    public static int stockKeyCode(int index, int original) {
        switch (index) {
            case 1: return KeyEvent.KEYCODE_BRIGHTNESS_DOWN;
            case 2: return KeyEvent.KEYCODE_BRIGHTNESS_UP;
            case 3: return KeyEvent.KEYCODE_BUTTON_4;
            case 4: return KeyEvent.KEYCODE_SYSRQ;
            case 5: return KeyEvent.KEYCODE_BUTTON_3;
            case 6: return KeyEvent.KEYCODE_BUTTON_1;
            case 7: return KeyEvent.KEYCODE_MEDIA_PREVIOUS;
            case 8: return KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;
            case 9: return KeyEvent.KEYCODE_MEDIA_NEXT;
            case 10: return KeyEvent.KEYCODE_VOLUME_MUTE;
            case 11: return KeyEvent.KEYCODE_VOLUME_DOWN;
            case 12: return KeyEvent.KEYCODE_VOLUME_UP;
            default: return original;
        }
    }

    public static int macFunctionKeyCode(int index, int original) {
        switch (index) {
            case 3: return KeyEvent.KEYCODE_MUTE;
            case 5: return KeyEvent.KEYCODE_ASSIST;
            case 6: return KeyEvent.KEYCODE_SLEEP;
            default: return stockKeyCode(index, original);
        }
    }
}
