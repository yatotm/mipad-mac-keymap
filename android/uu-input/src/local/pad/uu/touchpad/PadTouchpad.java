package local.pad.uu.touchpad;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.PointF;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.WeakHashMap;
import java.util.Map;
import java.util.Set;
import org.json.JSONObject;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** 仅在本机 UU 主进程中适配实体触控板，不改变 APK 或系统输入服务。 */
public final class PadTouchpad implements IXposedHookLoadPackage {
    private static final String TAG = "PadUuTouchpad";
    private static final String PACKAGE = "com.netease.uuremote";
    private static final String FRAGMENT = "com.remote.app.ui.fragment.screen.ScreenGestureFragment";
    private static final String INPUT_VIEW = "com.remote.inputdevice.view.GVDeviceInputView";
    private final WeakHashMap<Object, Session> sessions = new WeakHashMap<>();
    private final ThreadLocal<Session> active = new ThreadLocal<>();
    private final ScrollOrigins<Session> scrollOrigins = new ScrollOrigins<>();
    private Field motionField, deltaX, deltaY;
    private Constructor<?> motionWrapper;
    private Constructor<?> pointerWrapper;
    private Method sendCommand, horizontal, vertical, fragmentView, fragmentActivity;
    private Method mouseButton, updatePointer;
    private InputPipe inputPipe;
    private File configFile;
    private long configStamp = Long.MIN_VALUE;
    private boolean enabled = true, reverse = true, trace, failed;
    private float scrollScale = 0.25f, swipeMm = 8f, inertiaStrength = 2.5f;
    private int traceBudget = 100, scrollTraceBudget = 32, keyTraceBudget = 100;
    private int momentumTraceBudget = 16;
    private int functionTraceBudget = 8;
    private Handler handler;
    private boolean scrollAnnounced;
    private boolean repeatAnnounced;
    private boolean launchpadAnnounced;
    private boolean controlAnnounced;
    private boolean controlErrorAnnounced;
    private final WeakHashMap<Object, RepeatGate> repeatGates = new WeakHashMap<>();
    private final WeakHashMap<Object, RepeatGate> controlGates = new WeakHashMap<>();

    private static final class Session {
        final SwipeState swipe = new SwipeState();
        final ScrollState scroll = new ScrollState();
        final DragState drag = new DragState();
        final ThreeFingerMode three = new ThreeFingerMode();
        final TwoFingerScroll two = new TwoFingerScroll();
        final TwoFingerIntent twoIntent = new TwoFingerIntent();
        boolean twoCaptured;
        int twoSamples;
        boolean scrollActive, momentumActive;
        final ScrollMomentum momentum = new ScrollMomentum();
        Runnable inertia;
        boolean traceMomentum;
        long momentumStarted;
        int first = -1, second = -1, maxFingers;
        int deviceId = -1;
        int mode;
        boolean buttonHeld;
        void reset() {
            swipe.reset(); scroll.reset(); drag.reset(); momentum.stop();
            twoIntent.reset(); twoCaptured = false; twoSamples = 0;
            scrollActive = momentumActive = false;
            first = second = -1; maxFingers = 0; mode = 0;
        }
    }

    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam param) throws Throwable {
        if (!PACKAGE.equals(param.packageName) || !PACKAGE.equals(param.processName)) return;
        XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam hook) {
                try {
                    Context context = (Context) hook.args[0];
                    if (!"yudi".equals(Build.DEVICE) || Build.VERSION.SDK_INT != 35
                            || context.getPackageManager().getPackageInfo(PACKAGE, 0).getLongVersionCode() != 440000) {
                        Log.w(TAG, "版本不匹配，保留原厂输入");
                        return;
                    }
                    configFile = new File(context.getFilesDir(), "pad_uu_touchpad.json");
                    inputPipe = new InputPipe(context.getFilesDir());
                    install(context.getClassLoader());
                    new RemoteFunctionReceiver((Application) hook.thisObject, action -> {
                        if (failed || !enabled) return;
                        for (Session session : sessions.values()) stopMomentum(session);
                        sendControl(action);
                    });
                } catch (Throwable error) { fail(error); }
            }
        });
    }

    private void install(ClassLoader loader) throws Throwable {
        // Vector 构造模块时主线程 Looper 尚未建立，必须等 Application.attach 后初始化。
        handler = new Handler(Looper.getMainLooper());
        Class<?> fragment = XposedHelpers.findClass(FRAGMENT, loader);
        Class<?> motion = XposedHelpers.findClass("oa.Y", loader);
        Class<?> scroll = XposedHelpers.findClass("oa.X", loader);
        Class<?> commands = XposedHelpers.findClass("nf.c", loader);
        motionField = motion.getDeclaredField("a");
        deltaX = scroll.getDeclaredField("c");
        deltaY = scroll.getDeclaredField("d");
        if (motionField.getType() != MotionEvent.class || deltaX.getType() != float.class
                || deltaY.getType() != float.class) throw new IllegalStateException("输入结构不匹配");
        motionField.setAccessible(true); deltaX.setAccessible(true); deltaY.setAccessible(true);
        motionWrapper = motion.getConstructor(MotionEvent.class);
        sendCommand = fragment.getMethod("H", String.class);
        horizontal = commands.getMethod("w", int.class);
        vertical = commands.getMethod("x", int.class);
        mouseButton = commands.getMethod("y", int.class, boolean.class);
        Class<?> pointerEvent = XposedHelpers.findClass("oa.z", loader);
        pointerWrapper = pointerEvent.getConstructor(PointF.class, PointF.class);
        updatePointer = fragment.getMethod("onUpdatePointer", pointerEvent);
        fragmentView = fragment.getMethod("getView");
        fragmentActivity = fragment.getMethod("getActivity");
        Method pointer = fragment.getMethod("onTouchpadMotionEvent", motion);
        Method roller = fragment.getMethod("onScrollRoller", scroll);
        Method post = XposedHelpers.findClass("af.d", loader).getMethod("d", Object.class);
        Class<?> keyboard = XposedHelpers.findClass("r5.e", loader);
        Field heldKeys = keyboard.getDeclaredField("a");
        if (!Map.class.isAssignableFrom(heldKeys.getType())) throw new IllegalStateException("键盘状态结构不匹配");
        heldKeys.setAccessible(true);
        List<XC_MethodHook.Unhook> hooks = new ArrayList<>();
        try {
            hooks.add(XposedBridge.hookMethod(XposedHelpers.findClass("com.remote.app.ui.activity.ScreenActivity", loader).getMethod("dispatchKeyEvent", KeyEvent.class), new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam hook) {
                    if (failed || !enabled || !"com.remote.app.ui.activity.ScreenActivity".equals(hook.thisObject.getClass().getName())) return;
                    KeyEvent event = (KeyEvent) hook.args[0];
                    if (!physicalKeyboard(event.getDevice())) return;
                    String action = macAction(event.getKeyCode());
                    // 回归定位只记录最多八次功能键按下，不记录字母、数字或文本。
                    if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0
                            && (action != null || (event.getKeyCode() >= KeyEvent.KEYCODE_F1
                            && event.getKeyCode() <= KeyEvent.KEYCODE_F12)) && functionTraceBudget-- > 0) {
                        Log.i(TAG, "功能入口 code=" + event.getKeyCode() + " scan=" + event.getScanCode()
                                + " action=" + action);
                    }
                    if (action == null) return;
                    RepeatGate gate = controlGates.computeIfAbsent(hook.thisObject, unused -> new RepeatGate());
                    if (event.getAction() == KeyEvent.ACTION_UP) gate.release(event.getDeviceId(), event.getKeyCode());
                    else if (event.getAction() == KeyEvent.ACTION_DOWN
                            && gate.claimPress(event.getDeviceId(), event.getKeyCode(), event.getDownTime(), event.getRepeatCount())
                            && (event.getRepeatCount() == 0 || action.startsWith("volume-") || action.startsWith("brightness-"))) {
                        for (Session session : sessions.values()) stopMomentum(session);
                        sendControl(action);
                        if (!controlAnnounced) {
                            controlAnnounced = true;
                            Log.i(TAG, "Fn 在远控 Activity 入口已接收");
                        }
                    }
                    hook.setResult(true);
                }
            }));
            hooks.add(XposedBridge.hookMethod(keyboard.getMethod("a", KeyEvent.class), new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam hook) {
                    if (failed || !enabled) return;
                    KeyEvent event = (KeyEvent) hook.args[0];
                    if (!repeatable(event.getKeyCode()) || event.getRepeatCount() <= 0
                            || !physicalKeyboard(event.getDevice()) || !event.isFromSource(InputDevice.SOURCE_KEYBOARD)
                            || (event.getFlags() & KeyEvent.FLAG_SOFT_KEYBOARD) != 0) return;
                    try {
                        Object keys = ((Map<?, ?>) heldKeys.get(hook.thisObject)).get(event.getDeviceId());
                        if (!(keys instanceof Set<?>) || !((Set<?>) keys).contains(event.getKeyCode())) return;
                        RepeatGate gate = repeatGates.computeIfAbsent(hook.thisObject, unused -> new RepeatGate());
                        if (gate.claim(event.getDeviceId(), event.getKeyCode(), event.getDownTime(), event.getRepeatCount())) {
                            // 放行系统已产生的重复按下，不伪造松开，也不对修饰键定时连发。
                            ((Set<?>) keys).remove(event.getKeyCode());
                            if (!repeatAnnounced) {
                                repeatAnnounced = true;
                                Log.i(TAG, "普通按键的系统重复事件已开始透传");
                            }
                        }
                    } catch (Throwable error) { fail(error); }
                }
            }));
            hooks.add(XposedBridge.hookMethod(keyboard.getMethod("b", KeyEvent.class), new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam hook) {
                    KeyEvent event = (KeyEvent) hook.args[0];
                    RepeatGate gate = repeatGates.get(hook.thisObject);
                    if (gate != null) gate.release(event.getDeviceId(), event.getKeyCode());
                }
            }));
            hooks.add(XposedBridge.hookMethod(XposedHelpers.findClass(INPUT_VIEW, loader)
                    .getMethod("dispatchKeyEvent", KeyEvent.class), new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam hook) {
                    if (!INPUT_VIEW.equals(hook.thisObject.getClass().getName())) return;
                    KeyEvent event = (KeyEvent) hook.args[0];
                    if (!failed && enabled && physicalKeyboard(event.getDevice())
                            && event.getKeyCode() == KeyEvent.KEYCODE_FUNCTION) {
                        // 本方案没有向 Mac 发送物理 Fn 的用途，漏过系统过滤的语音键只可作为 Control。
                        if (event.getScanCode() != 192) { hook.setResult(true); return; }
                        KeyEvent mapped = new KeyEvent(event);
                        XposedHelpers.setIntField(mapped, "mKeyCode", KeyEvent.KEYCODE_CTRL_LEFT);
                        int meta = event.getMetaState() & ~KeyEvent.META_FUNCTION_ON;
                        if (event.getAction() == KeyEvent.ACTION_DOWN) meta |= KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON;
                        XposedHelpers.setIntField(mapped, "mMetaState", meta);
                        hook.args[0] = event = mapped;
                    }
                    if (!failed && enabled && physicalKeyboard(event.getDevice())) {
                        // 仅发送修饰状态，不传输或记录用户输入的文字。
                        inputPipe.modifiers(event.getMetaState());
                        String action = macAction(event.getKeyCode());
                        Activity activity = activityOf(((View) hook.thisObject).getContext());
                        if (action != null && activity != null
                                && "com.remote.app.ui.activity.ScreenActivity".equals(activity.getClass().getName())) {
                            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                                for (Session session : sessions.values()) stopMomentum(session);
                            }
                            RepeatGate gate = controlGates.computeIfAbsent(hook.thisObject, unused -> new RepeatGate());
                            if (event.getAction() == KeyEvent.ACTION_UP) {
                                gate.release(event.getDeviceId(), event.getKeyCode());
                            } else if (event.getAction() == KeyEvent.ACTION_DOWN
                                    && gate.claimPress(event.getDeviceId(), event.getKeyCode(),
                                            event.getDownTime(), event.getRepeatCount())
                                    && (event.getRepeatCount() == 0 || action.startsWith("volume-") || action.startsWith("brightness-"))) {
                                try {
                                    sendControl(action);
                                    if (!controlAnnounced) {
                                        controlAnnounced = true;
                                        Log.i(TAG, "Fn 已交给独立输入通道");
                                    }
                                } catch (Throwable error) {
                                    if (!controlErrorAnnounced) {
                                        controlErrorAnnounced = true;
                                        Log.w(TAG, "Mac 功能控制请求未发出", error);
                                    }
                                }
                            }
                            // 消费成对事件，避免未识别的媒体键继续落入安卓或远端键盘处理。
                            hook.setResult(true);
                            return;
                        }
                    }
                    if (event.getAction() == KeyEvent.ACTION_DOWN) {
                        for (Session session : sessions.values()) stopMomentum(session);
                    }
                    if (!trace) return;
                    int code = event.getKeyCode();
                    if (code != KeyEvent.KEYCODE_DEL && code != KeyEvent.KEYCODE_DPAD_UP
                            && code != KeyEvent.KEYCODE_CTRL_LEFT && code != KeyEvent.KEYCODE_ALT_LEFT) return;
                    if (keyTraceBudget-- > 0) Log.i(TAG, "UU键事件 code=" + code
                            + " action=" + event.getAction() + " repeat=" + event.getRepeatCount()
                            + " meta=" + event.getMetaState() + " device=" + event.getDeviceId());
                }
            }));
            hooks.add(XposedBridge.hookMethod(pointer, new PointerHook()));
            hooks.add(XposedBridge.hookMethod(post, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam hook) {
                    Session origin = active.get();
                    if (!failed && enabled && origin != null && scroll.isInstance(hook.args[0])) {
                        scrollOrigins.mark(hook.args[0], origin);
                    }
                }
            }));
            hooks.add(XposedBridge.hookMethod(roller, new RollerHook()));
            for (String name : new String[]{"onPause", "onDestroyView"}) {
                hooks.add(XposedBridge.hookMethod(fragment.getMethod(name), new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam hook) {
                        if (fragment.isInstance(hook.thisObject)) clearSession(hook.thisObject);
                    }
                }));
            }
            hooks.add(XposedBridge.hookMethod(XposedHelpers.findClass(INPUT_VIEW, loader)
                    .getMethod("onPointerCaptureChange", boolean.class), new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam hook) {
                    if (!(Boolean) hook.args[0]) releaseAll();
                }
            }));
        } catch (Throwable error) {
            for (XC_MethodHook.Unhook hook : hooks) hook.unhook();
            throw error;
        }
        loadConfig();
        Log.i(TAG, "v0.11.2 已加载：接收受系统身份保护的 Fn 请求，滚动与手势路径保持恢复版");
    }

    private void fail(Throwable error) {
        if (!failed) Log.e(TAG, "适配停止，保留 UU 原有处理", error);
        releaseAll();
        failed = true;
    }

    private static boolean repeatable(int key) {
        if (KeyEvent.isModifierKey(key)) return false;
        switch (key) {
            case KeyEvent.KEYCODE_CAPS_LOCK:
            case KeyEvent.KEYCODE_NUM_LOCK:
            case KeyEvent.KEYCODE_SCROLL_LOCK:
            case KeyEvent.KEYCODE_VOLUME_MUTE:
            case KeyEvent.KEYCODE_MUTE:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_POWER:
            case KeyEvent.KEYCODE_SLEEP:
            case KeyEvent.KEYCODE_ASSIST:
            case KeyEvent.KEYCODE_SYSRQ:
                return false;
            default: return true;
        }
    }

    private void openLaunchpad(Object fragment) throws Exception {
        sendControl("launchpad");
        if (!launchpadAnnounced) {
            launchpadAnnounced = true;
            Log.i(TAG, "已通过统一 Mac 组件请求打开 Launchpad");
        }
    }

    private void sendControl(String action) {
        boolean sent = inputPipe.action(action);
        if (trace && traceBudget-- > 0) Log.i(TAG, "独立输入动作=" + action + " 发送=" + sent);
    }

    private static Activity activityOf(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) return (Activity) context;
            Context next = ((ContextWrapper) context).getBaseContext();
            if (next == context) return null;
            context = next;
        }
        return null;
    }

    private static String macAction(int key) {
        switch (key) {
            case KeyEvent.KEYCODE_BRIGHTNESS_DOWN: return "brightness-down";
            case KeyEvent.KEYCODE_BRIGHTNESS_UP: return "brightness-up";
            case KeyEvent.KEYCODE_MUTE: return "mic-mute";
            case KeyEvent.KEYCODE_SYSRQ: return "screenshot";
            case KeyEvent.KEYCODE_ASSIST: return "assistant";
            case KeyEvent.KEYCODE_SLEEP: return "sleep";
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS: return "previous";
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE: return "play-pause";
            case KeyEvent.KEYCODE_MEDIA_NEXT: return "next";
            case KeyEvent.KEYCODE_VOLUME_MUTE: return "mute";
            case KeyEvent.KEYCODE_VOLUME_DOWN: return "volume-down";
            case KeyEvent.KEYCODE_VOLUME_UP: return "volume-up";
            default: return null;
        }
    }

    private void releaseDrag(Object fragment, Session session) {
        if (!session.buttonHeld) return;
        session.buttonHeld = false;
        try {
            sendCommand.invoke(fragment, mouseButton.invoke(null, 1, false));
            if (trace && traceBudget-- > 0) Log.i(TAG, "三指拖拽：左键已释放");
        } catch (Throwable error) { Log.w(TAG, "发送左键释放失败", error); }
    }

    private void clearSession(Object fragment) {
        Session session = sessions.remove(fragment);
        if (session != null) {
            stopMomentum(session);
            endScroll(session, true);
            releaseDrag(fragment, session);
            scrollOrigins.discard(session);
        }
        if (sessions.isEmpty()) inputPipe.reset();
    }

    private void releaseAll() {
        for (Object fragment : new ArrayList<>(sessions.keySet())) clearSession(fragment);
    }

    private void stopMomentum(Session session) {
        if (session.inertia != null && session.traceMomentum) {
            Log.i(TAG, "惯性结束：位移=" + session.momentum.distance()
                    + " 时长=" + (SystemClock.uptimeMillis() - session.momentumStarted));
        }
        session.traceMomentum = false;
        if (session.inertia != null) handler.removeCallbacks(session.inertia);
        session.inertia = null;
        session.momentum.stop();
        if (session.momentumActive) {
            inputPipe.scroll(0, 0, "momentum-ended");
            session.momentumActive = false;
        }
    }

    private void startMomentum(Object fragment, Session session, long time) {
        double speed = session.momentum.speed();
        long gap = time - session.momentum.lastSampleTime();
        boolean started = session.momentum.release(time, inertiaStrength);
        session.traceMomentum = trace && momentumTraceBudget-- > 0;
        if (session.traceMomentum) Log.i(TAG, "惯性起步：原速度=" + speed + " 抬手间隔=" + gap
                + " 启动=" + started + " 增益后速度=" + session.momentum.speed()
                + " 强度=" + inertiaStrength);
        if (!started) return;
        session.momentumStarted = SystemClock.uptimeMillis();
        session.inertia = new Runnable() {
            @Override public void run() {
                try {
                    if (failed || !enabled || sessions.get(fragment) != session || !focused(fragment)
                            || !session.momentum.step(SystemClock.uptimeMillis())) {
                        stopMomentum(session);
                        return;
                    }
                    sendScroll(fragment, session, session.momentum.dx(), session.momentum.dy(), 1);
                    handler.postDelayed(this, 16);
                } catch (Throwable error) { fail(error); }
            }
        };
        handler.postDelayed(session.inertia, 16);
    }

    private void endScroll(Session session, boolean cancelled) {
        if (session.scrollActive) {
            inputPipe.scroll(0, 0, cancelled ? "cancelled" : "ended");
            session.scrollActive = false;
        }
    }

    private void sendScroll(Object fragment, Session session, float dx, float dy, float factor) throws Exception {
        // 横向倍率只在输出端应用一次，接触位移与惯性使用相同比例。
        int x = session.scroll.convert(0, dx, factor), y = session.scroll.convert(1, dy, factor);
        if (x != 0) sendCommand.invoke(fragment, horizontal.invoke(null, x));
        if (y != 0) sendCommand.invoke(fragment, vertical.invoke(null, y));
        if (!scrollAnnounced) {
            scrollAnnounced = true;
            Log.i(TAG, "触控板滚动适配已命中：反向=" + reverse + " 倍率=" + scrollScale);
        }
        if (trace && scrollTraceBudget-- > 0) Log.i(TAG, "滚动 " + dx + "," + dy + " → " + x + "," + y);
    }

    private void loadConfig() {
        long stamp = configFile.lastModified();
        if (stamp == configStamp) return;
        configStamp = stamp;
        if (!configFile.isFile()) return;
        try {
            if (configFile.length() > 4096) throw new IllegalArgumentException("配置过大");
            JSONObject data = new JSONObject(new String(Files.readAllBytes(configFile.toPath()), "UTF-8"));
            float scale = (float) data.optDouble("scroll_scale", 0.25);
            float threshold = (float) data.optDouble("swipe_mm", 8);
            float strength = (float) data.optDouble("inertia_strength", 2.5);
            if (!Float.isFinite(scale) || scale < 0.01f || scale > 2f
                    || !Float.isFinite(threshold) || threshold < 3f || threshold > 30f
                    || !Float.isFinite(strength) || strength < 1 || strength > 4) {
                throw new IllegalArgumentException("配置数值越界");
            }
            enabled = data.optBoolean("enabled", true);
            reverse = data.optBoolean("reverse", true);
            trace = data.optBoolean("trace", false);
            scrollScale = scale; swipeMm = threshold; inertiaStrength = strength;
        } catch (Exception error) { Log.w(TAG, "配置无效，保留上次参数", error); }
    }

    private static boolean supported(InputDevice device) {
        if (device == null) return false;
        return (device.getVendorId() == 0x15d9 && device.getProductId() == 0x00a1
                    && "Xiaomi Touch".equals(device.getName()))
                || (device.getVendorId() == 0xbf01 && device.getProductId() == 0x0040
                    && ("Xiaomi Pad Keyboard".equals(device.getName())
                    || "Xiaomi Pad Keyboard Touchpad".equals(device.getName())));
    }

    private static boolean physicalKeyboard(InputDevice device) {
        return device != null && device.getKeyboardType() == InputDevice.KEYBOARD_TYPE_ALPHABETIC
                && ((device.getVendorId() == 0x15d9 && device.getProductId() == 0x00a3)
                || (device.getVendorId() == 0xbf01 && device.getProductId() == 0x0040));
    }

    private static float resolution(InputDevice device, int axis) {
        InputDevice.MotionRange range = device.getMotionRange(axis, InputDevice.SOURCE_TOUCHPAD);
        if (range == null) range = device.getMotionRange(axis);
        if (range != null && range.getResolution() > 0) return range.getResolution();
        return range != null && range.getRange() > 0 ? range.getRange() / (axis == 0 ? 120f : 70f) : 25f;
    }

    private boolean focused(Object fragment) throws Exception {
        View view = (View) fragmentView.invoke(fragment);
        Activity activity = (Activity) fragmentActivity.invoke(fragment);
        return view != null && view.isShown() && activity != null && activity.hasWindowFocus();
    }

    private static float threeFingerSpan(MotionEvent event) {
        if (event.getPointerCount() != 3) return 0;
        float rx = resolution(event.getDevice(), 0), ry = resolution(event.getDevice(), 1);
        float total = 0;
        // 三对触点的平均距离不受指针索引重排、整体平移或旋转影响。
        for (int i = 0; i < 3; i++) for (int j = i + 1; j < 3; j++) {
            total += (float) Math.hypot((event.getX(i) - event.getX(j)) / rx,
                    (event.getY(i) - event.getY(j)) / ry);
        }
        return total / 3;
    }

    private final class PointerHook extends XC_MethodHook {
        @Override protected void beforeHookedMethod(MethodHookParam hook) {
            if (failed) return;
            try {
                MotionEvent event = (MotionEvent) motionField.get(hook.args[0]);
                Session previousSession = sessions.get(hook.thisObject);
                if (event.getActionMasked() == MotionEvent.ACTION_CANCEL && previousSession != null) {
                    clearSession(hook.thisObject);
                    return;
                }
                if (!supported(event.getDevice())) return;
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) loadConfig();
                if (!enabled || !focused(hook.thisObject)) {
                    clearSession(hook.thisObject);
                    return;
                }
                Session session = sessions.computeIfAbsent(hook.thisObject, unused -> new Session());
                if (session.deviceId != event.getDeviceId() || event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    stopMomentum(session);
                    endScroll(session, true);
                    releaseDrag(hook.thisObject, session);
                    session.reset(); session.deviceId = event.getDeviceId();
                }
                hook.setObjectExtra("pad.previous", active.get());
                hook.setObjectExtra("pad.active", Boolean.TRUE);
                active.set(session);
                int action = event.getActionMasked(), count = event.getPointerCount();
                float x = 0, y = 0;
                int removed = action == MotionEvent.ACTION_POINTER_UP ? event.getActionIndex() : -1;
                for (int i = 0; i < count; i++) if (i != removed) { x += event.getX(i); y += event.getY(i); }
                if (removed >= 0) count--;
                if (count > 0) { x /= count; y /= count; }
                session.maxFingers = Math.max(session.maxFingers, count);
                int modifiers = KeyEvent.META_CTRL_ON | KeyEvent.META_ALT_ON | KeyEvent.META_META_ON
                        | KeyEvent.META_SHIFT_ON | KeyEvent.META_FUNCTION_ON;
                if (trace && action == MotionEvent.ACTION_POINTER_DOWN && traceBudget-- > 0) {
                    // Android 15 隐藏轴 53 为手势手指数；同时保留原始触点数作对照。
                    Log.i(TAG, "触点=" + count + " 分类=" + event.getClassification()
                            + " 手势指数量=" + event.getAxisValue(53) + " 来源=" + event.getSource());
                }
                if (trace && session.maxFingers >= 2 && action == MotionEvent.ACTION_UP && traceBudget-- > 0) {
                    Log.i(TAG, "触控结束：最多=" + session.maxFingers + " 模式=" + session.mode);
                }
                if (session.mode == 0 && action == MotionEvent.ACTION_POINTER_DOWN && count == 2) {
                    stopMomentum(session);
                    session.first = event.getPointerId(0); session.second = event.getPointerId(1);
                    session.two.begin(event.getX(0), event.getY(0), event.getX(1), event.getY(1));
                    session.momentum.begin(event.getEventTime());
                }
                if ((session.mode == 0 || session.mode == 2) && count == 2
                        && action == MotionEvent.ACTION_MOVE && event.getButtonState() == 0) {
                    int a = event.findPointerIndex(session.first), b = event.findPointerIndex(session.second);
                    if (a >= 0 && b >= 0 && session.two.move(event.getX(a), event.getY(a),
                            event.getX(b), event.getY(b), resolution(event.getDevice(), 0),
                            resolution(event.getDevice(), 1)) == TwoFingerScroll.SCROLL) {
                        if (session.mode == 0) {
                            session.mode = 2; session.scroll.reset(); session.twoCaptured = true;
                            cancelOriginal(hook, event);
                            if (trace && traceBudget-- > 0) Log.i(TAG, "双指平移已接管");
                        }
                        hook.setResult(null);
                        int intent = session.twoIntent.move(session.two.dx() / resolution(event.getDevice(), 0),
                                session.two.dy() / resolution(event.getDevice(), 1), session.two.dy());
                        if (trace && session.twoSamples++ < 6 && traceBudget-- > 0) {
                            Log.i(TAG, "双指判定 x=" + session.two.dx() / resolution(event.getDevice(), 0)
                                    + " y=" + session.two.dy() / resolution(event.getDevice(), 1) + " result=" + intent);
                        }
                        if (intent == TwoFingerIntent.VERTICAL) {
                            float dy = session.twoIntent.takeVertical();
                            float factor = scrollScale * (reverse ? -1 : 1);
                            sendScroll(hook.thisObject, session, 0, dy, factor);
                            session.momentum.sample(0, dy * factor, event.getEventTime());
                        } else if (intent == TwoFingerIntent.LEFT || intent == TwoFingerIntent.RIGHT) {
                            session.mode = 6;
                            stopMomentum(session);
                            if (trace && traceBudget-- > 0) Log.i(TAG, "双指横向提交=" + intent + " meta=" + event.getMetaState());
                            if ((event.getMetaState() & modifiers) == 0) {
                                horizontalShortcut(hook.thisObject, intent == TwoFingerIntent.RIGHT);
                            }
                        }
                        return;
                    }
                }
                boolean claim = (session.mode == 0 || session.mode == 2) && count >= 3 && count <= 4
                        && event.getButtonState() == 0;
                if (claim) {
                    boolean wasOriginal = session.mode == 0;
                    stopMomentum(session);
                    endScroll(session, true);
                    session.mode = count;
                    session.drag.begin(x, y, event.getEventTime());
                    session.three.begin(x / resolution(event.getDevice(), 0),
                            y / resolution(event.getDevice(), 1), threeFingerSpan(event), event.getEventTime());
                    session.swipe.reset();
                    session.swipe.update(MotionEvent.ACTION_POINTER_DOWN, count,
                            x / resolution(event.getDevice(), 0), y / resolution(event.getDevice(), 1),
                            false, swipeMm);
                    session.scroll.reset();
                    if (wasOriginal) cancelOriginal(hook, event);
                }
                if (session.mode != 0) {
                    hook.setResult(null);
                    if (session.mode == 2 && action == MotionEvent.ACTION_UP) {
                        session.mode = 0;
                        endScroll(session, false);
                        if (session.twoIntent.isVertical()) startMomentum(hook.thisObject, session, event.getEventTime());
                        return;
                    }
                    if (action == MotionEvent.ACTION_UP || count > 4 || event.getButtonState() != 0) {
                        stopMomentum(session);
                        endScroll(session, true);
                        releaseDrag(hook.thisObject, session);
                        session.mode = action == MotionEvent.ACTION_UP ? 0 : 5;
                        session.swipe.reset();
                        return;
                    }
                    if (session.mode == 3 && count == 4) {
                        releaseDrag(hook.thisObject, session);
                        session.mode = 4;
                        session.swipe.reset();
                    }
                    if (session.mode == 3) {
                        if (count < 3) {
                            releaseDrag(hook.thisObject, session);
                            session.mode = 5;
                        } else if (action == MotionEvent.ACTION_MOVE) {
                            int choice = session.three.move(x / resolution(event.getDevice(), 0),
                                    y / resolution(event.getDevice(), 1), threeFingerSpan(event), event.getEventTime());
                            if (choice == ThreeFingerMode.WAIT) return;
                            if (choice == ThreeFingerMode.PINCH) {
                                session.mode = 5;
                                if ((event.getMetaState() & modifiers) == 0) openLaunchpad(hook.thisObject);
                                return;
                            }
                            if (choice == ThreeFingerMode.NAVIGATE) {
                                int result = session.swipe.update(action, count,
                                        x / resolution(event.getDevice(), 0), y / resolution(event.getDevice(), 1),
                                        (event.getMetaState() & modifiers) != 0, swipeMm);
                                if (result > SwipeState.CONSUME) shortcut(hook.thisObject, result);
                                return;
                            }
                            int drag = session.drag.move(x, y, event.getEventTime(),
                                    resolution(event.getDevice(), 0), resolution(event.getDevice(), 1));
                            if (drag == DragState.START) {
                                session.buttonHeld = true;
                                sendCommand.invoke(hook.thisObject, mouseButton.invoke(null, 1, true));
                                if (trace && traceBudget-- > 0) Log.i(TAG, "三指拖拽：左键已按下");
                            }
                            if (drag != DragState.NONE) updatePointer.invoke(hook.thisObject,
                                    pointerWrapper.newInstance(null, new PointF(session.drag.dx(), session.drag.dy())));
                        }
                    } else if (session.mode == 4) {
                        if ((event.getMetaState() & modifiers) != 0) { session.mode = 5; return; }
                        int result = session.swipe.update(action, count,
                                x / resolution(event.getDevice(), 0), y / resolution(event.getDevice(), 1),
                                (event.getMetaState() & modifiers) != 0, swipeMm);
                        if (result > SwipeState.CONSUME) shortcut(hook.thisObject, result);
                    }
                }
            } catch (Throwable error) { fail(error); }
        }

        private void cancelOriginal(MethodHookParam hook, MotionEvent event) throws Throwable {
            MotionEvent cancel = MotionEvent.obtain(event);
            try {
                cancel.setAction(MotionEvent.ACTION_CANCEL);
                XposedBridge.invokeOriginalMethod(hook.method, hook.thisObject,
                        new Object[]{motionWrapper.newInstance(cancel)});
            } finally { cancel.recycle(); }
        }

        @Override protected void afterHookedMethod(MethodHookParam hook) {
            if (Boolean.TRUE.equals(hook.getObjectExtra("pad.active"))) {
                Session previous = (Session) hook.getObjectExtra("pad.previous");
                if (previous == null) active.remove(); else active.set(previous);
            }
        }
    }

    private final class RollerHook extends XC_MethodHook {
        @Override protected void beforeHookedMethod(MethodHookParam hook) {
            Session session = scrollOrigins.take(hook.args[0], sessions.get(hook.thisObject));
            if (failed || !enabled || session == null) return;
            try {
                if (!focused(hook.thisObject) || session.mode != 0 || session.twoCaptured) { hook.setResult(null); return; }
                float dx = deltaX.getFloat(hook.args[0]), dy = deltaY.getFloat(hook.args[0]);
                float factor = scrollScale * (reverse ? -1 : 1);
                hook.setResult(null);
                sendScroll(hook.thisObject, session, dx, dy, factor);
            } catch (Throwable error) { fail(error); }
        }
    }

    private static View findInput(View view) {
        if (INPUT_VIEW.equals(view.getClass().getName())) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findInput(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private void horizontalShortcut(Object fragment, boolean back) throws Exception {
        Activity activity = (Activity) fragmentActivity.invoke(fragment);
        View input = findInput(activity.getWindow().getDecorView());
        int keyboard = -1;
        for (int id : InputDevice.getDeviceIds()) if (physicalKeyboard(InputDevice.getDevice(id))) { keyboard = id; break; }
        if (input == null || keyboard < 0) return;
        int key = back ? KeyEvent.KEYCODE_DPAD_LEFT : KeyEvent.KEYCODE_DPAD_RIGHT;
        int meta = KeyEvent.META_META_ON | KeyEvent.META_META_LEFT_ON;
        long time = SystemClock.uptimeMillis();
        try {
            dispatch(input, keyboard, time, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_META_LEFT, meta);
            dispatch(input, keyboard, time, KeyEvent.ACTION_DOWN, key, meta);
        } finally {
            try { dispatch(input, keyboard, time, KeyEvent.ACTION_UP, key, meta); }
            finally { dispatch(input, keyboard, time, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_META_LEFT, 0); }
        }
    }

    private void shortcut(Object fragment, int direction) throws Exception {
        if (direction == SwipeState.UP) { sendControl("windows"); return; }
        Activity activity = (Activity) fragmentActivity.invoke(fragment);
        View input = findInput(activity.getWindow().getDecorView());
        int keyboard = -1;
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice device = InputDevice.getDevice(id);
            if (device != null && device.getKeyboardType() == InputDevice.KEYBOARD_TYPE_ALPHABETIC
                    && ((device.getVendorId() == 0x15d9 && device.getProductId() == 0x00a3)
                    || (device.getVendorId() == 0xbf01 && device.getProductId() == 0x0040))) { keyboard = id; break; }
        }
        if (input == null || keyboard < 0) { Log.w(TAG, "未找到 UU 输入视图或实体键盘"); return; }
        // 与 Mac 自然滑动一致：手指左滑进入右侧桌面，反向亦然。
        int key = direction == SwipeState.LEFT ? KeyEvent.KEYCODE_DPAD_RIGHT
                : direction == SwipeState.RIGHT ? KeyEvent.KEYCODE_DPAD_LEFT
                : direction == SwipeState.UP ? KeyEvent.KEYCODE_DPAD_UP : KeyEvent.KEYCODE_DPAD_DOWN;
        long time = SystemClock.uptimeMillis();
        int ctrl = KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON;
        boolean accepted = false;
        try {
            boolean controlAccepted = dispatch(input, keyboard, time, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_CTRL_LEFT, ctrl);
            accepted = dispatch(input, keyboard, time, KeyEvent.ACTION_DOWN, key, ctrl) && controlAccepted;
        } finally {
            try { dispatch(input, keyboard, time, KeyEvent.ACTION_UP, key, ctrl); }
            finally { dispatch(input, keyboard, time, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_CTRL_LEFT, 0); }
        }
        if (trace && traceBudget-- > 0) Log.i(TAG, "手势=" + direction + " 快捷键=" + key + " UU接收=" + accepted);
    }

    private static boolean dispatch(View view, int device, long time, int action, int key, int meta) {
        return view.dispatchKeyEvent(new KeyEvent(time, SystemClock.uptimeMillis(), action, key,
                0, meta, device, 0, 0, InputDevice.SOURCE_KEYBOARD));
    }
}
