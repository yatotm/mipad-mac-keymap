package local.pad.uu;

import android.os.Build;
import android.util.Log;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyEvent;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** 仅针对本机系统和已确认的小米键盘，在系统输入链路中实现 UU 专用布局。 */
public final class SystemKeyboard implements IXposedHookLoadPackage {
    private static final String TAG = "PadUuKeyboard";
    private static final String TARGET = "com.netease.uuremote";
    private static final int PASS_TO_USER = 0x40000000;
    private final FunctionRoutes routes = new FunctionRoutes();
    private final FunctionRoutes modifierRoutes = new FunctionRoutes();
    private final HeldFn fn = new HeldFn();
    private volatile boolean uuFocused;
    private volatile int fnDevice = -1;
    private Method windowOwner;
    private Field keyField;
    private Field metaField;
    private Field deviceField;
    private Class<?> properties;
    private int traceBudget = 80;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam param) throws Throwable {
        if (!"android".equals(param.packageName)) return;
        if (!"yudi".equals(Build.DEVICE) || Build.VERSION.SDK_INT != 35
                || !"OS3.0.6.0.VMHCNXM".equals(Build.VERSION.INCREMENTAL)) {
            Log.w(TAG, "Unsupported system; no hooks installed");
            return;
        }
        ClassLoader loader = param.classLoader;
        properties = XposedHelpers.findClass("android.os.SystemProperties", loader);
        Class<?> policy = XposedHelpers.findClass(
                "com.android.server.policy.BaseMiuiPhoneWindowManager", loader);
        Class<?> intercept = XposedHelpers.findClass(
                "com.android.server.policy.MiuiKeyInterceptExtend", loader);
        Class<?> window = XposedHelpers.findClass(
                "com.android.server.policy.WindowManagerPolicy$WindowState", loader);
        Class<?> filter = XposedHelpers.findClass(
                "com.android.server.accessibility.AccessibilityInputFilter", loader);
        windowOwner = window.getMethod("getOwningPackage");
        keyField = XposedHelpers.findField(KeyEvent.class, "mKeyCode");
        metaField = XposedHelpers.findField(KeyEvent.class, "mMetaState");
        deviceField = XposedHelpers.findField(KeyEvent.class, "mDeviceId");
        Method early = policy.getDeclaredMethod("interceptKeyBeforeQueueingInternal",
                KeyEvent.class, int.class, boolean.class);
        Method focus = policy.getDeclaredMethod("onDefaultDisplayFocusChangedLw", window);
        Method queue = intercept.getDeclaredMethod("getKeyInterceptTypeBeforeQueueing",
                KeyEvent.class, int.class, window);
        Method dispatch = intercept.getDeclaredMethod("getKeyInterceptTypeBeforeDispatching",
                KeyEvent.class, int.class, window);
        Method input = filter.getDeclaredMethod("onInputEvent", InputEvent.class, int.class);
        List<XC_MethodHook.Unhook> installed = new ArrayList<>();
        try {
            installed.add(XposedBridge.hookMethod(focus, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                    uuFocused = isUuWindow(p.args[0]);
                    if (!uuFocused) {
                        fn.cancel();
                    }
                }
            }));
            installed.add(XposedBridge.hookMethod(early, new QueueHook()));
            installed.add(XposedBridge.hookMethod(queue, new PassHook()));
            installed.add(XposedBridge.hookMethod(dispatch, new PassHook()));
            installed.add(XposedBridge.hookMethod(input, new FilterHook()));
        } catch (Throwable error) {
            for (XC_MethodHook.Unhook hook : installed) hook.unhook();
            throw error;
        }
        Log.i(TAG, "v1.7 已加载：同一键盘跨接口共享 Fn，桥接顶排前的短暂释放帧");
    }

    private boolean isUuWindow(Object window) throws Exception {
        return window != null && TARGET.equals(windowOwner.invoke(window));
    }

    private static int family(InputDevice device) {
        if (device == null) return -1;
        int vendor = device.getVendorId();
        int product = device.getProductId();
        String name = device.getName();
        if (vendor == 0x15d9 && ((product == 0x00a3 && "Xiaomi Keyboard".equals(name))
                || (product == 0x00a4 && "Xiaomi Consumer".equals(name)))) return 0;
        if (vendor == 0xbf01 && product == 0x0040
                && ("Xiaomi Pad Keyboard".equals(name)
                || "Xiaomi Pad Keyboard Consumer Control".equals(name))) return 1;
        return -1;
    }

    private static int family(KeyEvent event) {
        if (event == null || event.getDeviceId() < 0
                || !event.isFromSource(InputDevice.SOURCE_KEYBOARD)) return -1;
        return family(event.getDevice());
    }

    private static boolean mainKeyboard(InputDevice device) {
        return device != null && family(device) >= 0
                && device.getKeyboardType() == InputDevice.KEYBOARD_TYPE_ALPHABETIC;
    }

    private void checkFnDevice() {
        int id = fnDevice;
        if (id >= 0 && InputDevice.getDevice(id) == null) {
            fn.reset();
            fnDevice = -1;
        }
    }

    private static String stroke(int group, KeyEvent event) {
        // 原生 InputReader 为同一次按下、重复和松开保留同一 downTime。
        return group + ":" + event.getScanCode() + ":" + event.getDownTime();
    }

    private KeyEvent copy(KeyEvent original, int key, int meta, int device) throws Exception {
        // 副本保留显示 ID、时间、标志和扫描码，仅修改指定字段。
        KeyEvent mapped = new KeyEvent(original);
        keyField.setInt(mapped, key);
        metaField.setInt(mapped, meta);
        deviceField.setInt(mapped, device);
        return mapped;
    }

    private void traceFn(KeyEvent event, int group, int mode) {
        if (traceBudget <= 0 || !(Boolean) XposedHelpers.callStaticMethod(properties,
                "getBoolean", "debug.pad.uu.trace", false)) return;
        traceBudget--;
        Log.i(TAG, "Fn诊断 time=" + event.getEventTime() + " scan=" + event.getScanCode() + " action=" + event.getAction()
                + " meta=" + event.getMetaState() + " group=" + group + " route=" + mode);
    }

    private abstract static class GuardedHook extends XC_MethodHook {
        private boolean errorLogged;
        @Override protected final void beforeHookedMethod(MethodHookParam p) {
            try {
                apply(p);
            } catch (Throwable error) {
                if (!errorLogged) {
                    errorLogged = true;
                    Log.e(TAG, "Keeping stock handling after hook error", error);
                }
            }
        }
        protected abstract void apply(MethodHookParam p) throws Throwable;
    }

    private final class QueueHook extends GuardedHook {
        private boolean announced;
        private boolean functionAnnounced;
        private boolean localAnnounced;
        private boolean ignoredAnnounced;
        @Override protected void apply(MethodHookParam p) throws Throwable {
            KeyEvent event = (KeyEvent) p.args[0];
            int group = family(event);
            if (group < 0) return;
            checkFnDevice();
            boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
            if (event.getAction() != KeyEvent.ACTION_DOWN
                    && event.getAction() != KeyEvent.ACTION_UP) return;
            uuFocused = isUuWindow(XposedHelpers.getObjectField(p.thisObject, "mFocusedWindow"));
            int scan = event.getScanCode();
            if ((scan == FnControlLayout.FN_SCAN || scan == FnControlLayout.CONTROL_SCAN)
                    && mainKeyboard(event.getDevice())) {
                int initialMode = scan == FnControlLayout.FN_SCAN
                        ? (uuFocused ? FunctionRoutes.IGNORE : FunctionRoutes.LOCAL)
                        : (uuFocused ? FunctionRoutes.REMOTE : FunctionRoutes.IGNORE);
                int modifierMode = modifierRoutes.record(stroke(group, event), down, initialMode);
                if (scan == FnControlLayout.CONTROL_SCAN) {
                    p.setResult(modifierMode == FunctionRoutes.REMOTE ? 1 : 0);
                    return;
                }
                if (modifierMode != FunctionRoutes.IGNORE) return;
                // Fn 只选择功能层，不作为独立按键发往 Mac。
                if (down) {
                    fnDevice = event.getDeviceId();
                    fn.press();
                } else {
                    fn.release(event.getEventTime());
                }
                traceFn(event, group, fn.rowDown(true, event.getEventTime()));
                p.setResult(0);
                return;
            }
            int index = FunctionRoutes.index(event.getScanCode());
            if (index == 0) {
                return;
            }
            String key = stroke(group, event);
            int mode = routes.mode(key);
            if (mode < 0 && down) {
                mode = fn.rowDown(uuFocused && (Boolean) p.args[2], event.getEventTime());
            }
            mode = routes.record(key, down, mode);
            traceFn(event, group, mode);
            if (mode == FunctionRoutes.IGNORE) {
                p.setResult(0);
                if (!ignoredAnnounced) {
                    ignoredAnnounced = true;
                    Log.i(TAG, "已忽略缺少对应按下记录的顶排事件");
                }
                return;
            }
            if (mode == FunctionRoutes.REMOTE || mode == FunctionRoutes.REMOTE_FUNCTION) {
                // 在亮度、截屏、媒体处理之前放行；离开 UU 后不触发本地对应动作。
                p.setResult(uuFocused ? 1 : 0);
                if (!announced) {
                    announced = true;
                    Log.i(TAG, "顶排已直通远端，Fn 功能不再交给平板系统");
                }
                if (mode == FunctionRoutes.REMOTE_FUNCTION && !functionAnnounced) {
                    functionAnnounced = true;
                    Log.i(TAG, "Fn 功能层已命中，接口=" + group + " 扫描码=" + scan);
                }
                return;
            }
            if (uuFocused && !localAnnounced) {
                localAnnounced = true;
                Log.i(TAG, "保留切入 UU 前已按下的平板功能键路由");
            }
            // 新增的标准 F 键路径在 UU 外保留原始系统处理。
            if ((scan >= 59 && scan <= 68) || scan == 87 || scan == 88) return;
            // 按物理功能位置恢复原厂键码，不让系统再次解释已经处理过的 Fn 修饰。
            int stock = FunctionRoutes.stockKeyCode(index, event.getKeyCode());
            int meta = event.getMetaState() & ~KeyEvent.META_FUNCTION_ON;
            if (stock != event.getKeyCode() || meta != event.getMetaState()) {
                p.args[0] = copy(event, stock, meta, event.getDeviceId());
            }
        }
    }

    private final class PassHook extends GuardedHook {
        private boolean announced;
        @Override protected void apply(MethodHookParam p) throws Throwable {
            KeyEvent event = (KeyEvent) p.args[0];
            int group = family(event);
            if (group < 0 || !isUuWindow(p.args[2])
                    || !XposedHelpers.getBooleanField(p.thisObject, "mIsScreenOn")) return;
            if (FunctionRoutes.index(event.getScanCode()) > 0) {
                int mode = routes.mode(stroke(group, event));
                if (mode == FunctionRoutes.REMOTE || mode == FunctionRoutes.REMOTE_FUNCTION) p.setResult(1);
                else if (mode == FunctionRoutes.IGNORE) p.setResult(4);
                return;
            }
            if (!mainKeyboard(event.getDevice()) || localSystemKey(event.getKeyCode())) return;
            p.setResult(1);
            if (!announced) {
                announced = true;
                Log.i(TAG, "UU system shortcut bypass active");
            }
        }
    }

    private final class FilterHook extends GuardedHook {
        private boolean announced;
        @Override protected void apply(MethodHookParam p) throws Throwable {
            if (!(p.args[0] instanceof KeyEvent)) return;
            KeyEvent event = (KeyEvent) p.args[0];
            int group = family(event);
            if (group < 0) return;
            int index = FunctionRoutes.index(event.getScanCode());
            boolean pass = (((Integer) p.args[1]) & PASS_TO_USER) != 0;
            // 已消费的本地按键不进入辅助输入状态机，避免重置其他仍按住的键。
            int scan = event.getScanCode();
            int modifierMode = modifierRoutes.mode(stroke(group, event));
            if ((mainKeyboard(event.getDevice())
                    && ((scan == FnControlLayout.FN_SCAN && modifierMode == FunctionRoutes.IGNORE)
                    || (scan == FnControlLayout.CONTROL_SCAN && modifierMode != FunctionRoutes.REMOTE)))
                    || (index > 0 && (routes.mode(stroke(group, event)) == FunctionRoutes.IGNORE
                    || !pass))) {
                p.setResult(null);
                return;
            }
            if (!uuFocused || !pass) return;
            int key = FnControlLayout.keyCode(scan, MacModifiers.swapKeyCode(event.getKeyCode()));
            int device = event.getDeviceId();
            if (index > 0) {
                if (!routes.isRemote(stroke(group, event))) return;
                key = routes.mode(stroke(group, event)) == FunctionRoutes.REMOTE_FUNCTION
                        ? FunctionRoutes.macFunctionKeyCode(index, event.getKeyCode())
                        : KeyEvent.KEYCODE_F1 + index - 1;
                // UU 只接受完整键盘；媒体控制接口归并到同一物理键盘的主接口。
                for (int id : InputDevice.getDeviceIds()) {
                    InputDevice candidate = InputDevice.getDevice(id);
                    if (family(candidate) == group && mainKeyboard(candidate)) {
                        device = id;
                        break;
                    }
                }
            } else if (!mainKeyboard(event.getDevice())) {
                return;
            }
            int meta = FnControlLayout.metaState(MacModifiers.swapMetaState(event.getMetaState()));
            if (key == event.getKeyCode() && meta == event.getMetaState()
                    && device == event.getDeviceId()) return;
            p.args[0] = copy(event, key, meta, device);
            if (!announced) {
                announced = true;
                Log.i(TAG, "UU transformed input active; no key contents are logged");
            }
        }
    }

    private static boolean localSystemKey(int key) {
        switch (key) {
            case KeyEvent.KEYCODE_UNKNOWN:
            case KeyEvent.KEYCODE_HOME:
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_POWER:
            case KeyEvent.KEYCODE_SLEEP:
            case KeyEvent.KEYCODE_WAKEUP:
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_VOLUME_MUTE:
            case KeyEvent.KEYCODE_BRIGHTNESS_UP:
            case KeyEvent.KEYCODE_BRIGHTNESS_DOWN:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                return true;
            default: return false;
        }
    }
}
