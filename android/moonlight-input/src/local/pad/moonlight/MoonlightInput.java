package local.pad.moonlight;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import java.util.WeakHashMap;
import java.util.HashSet;
import java.util.Set;
import java.lang.ref.WeakReference;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import local.pad.uu.touchpad.InputPipe;
import local.pad.uu.touchpad.RemoteFunctionReceiver;
import local.pad.uu.touchpad.ScrollMomentum;

/** 只接管官方 Moonlight 12.2 远控页面中的指定实体触控板。 */
public final class MoonlightInput implements IXposedHookLoadPackage {
    private InputPipe pipe;
    private InputDiagnostics diagnostics;
    private final WeakHashMap<Activity, Session> sessions = new WeakHashMap<>();
    private Handler handler;
    private final Set<Integer> heldModifiers = new HashSet<>();
    private final HeldKeys forwardedKeys = new HeldKeys();
    private Class<?> moonBridge;
    private WeakReference<Activity> gameActivity = new WeakReference<>(null);
    private boolean failed;
    private boolean checking;
    private final Runnable healthCheck = new Runnable() {
        public void run() {
            if (diagnostics != null) diagnostics.flush();
            for (Session s : sessions.values()) {
                if (!receiving(s.activity)) { s.cancel(); releaseKeys(); }
                else if (s.device >= 0 && InputDevice.getDevice(s.device) == null) s.cancel();
                else if (!pipe.available()) { s.cancelGestures(); s.cursor.close(); }
            }
            checking = !sessions.isEmpty();
            if (checking) handler.postDelayed(this, 250);
        }
    };

    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam p) throws Throwable {
        if (!"com.limelight".equals(p.packageName) || !p.packageName.equals(p.processName)) return;
        Class<?> game = XposedHelpers.findClass("com.limelight.Game", p.classLoader);
        XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam hook) throws Throwable {
                Application app = (Application) hook.thisObject;
                if (!"12.2".equals(app.getPackageManager().getPackageInfo(p.packageName, 0).versionName)) {
                    failed = true; Log.w("PadMoonlight", "版本未验证，保留 Moonlight 原输入"); return;
                }
                handler = new Handler(Looper.getMainLooper());
                pipe = new InputPipe(app.getFilesDir());
                diagnostics = new InputDiagnostics(app.getFilesDir());
                pipe.active(false);
                new RemoteFunctionReceiver(app, action -> {
                    Activity activity = gameActivity.get();
                    if (receiving(activity)) pipe.action(action);
                }, "com.limelight.Game");
                app.getSystemService(InputManager.class).registerInputDeviceListener(new InputManager.InputDeviceListener() {
                    public void onInputDeviceAdded(int id) {}
                    public void onInputDeviceChanged(int id) {}
                    public void onInputDeviceRemoved(int id) {
                        for (Session s : sessions.values()) if (s.device == id) s.cancel();
                        releaseKeys();
                        Activity activity = gameActivity.get();
                        if (activity != null) {
                            XposedHelpers.setIntField(activity, "modifierFlags", 0);
                            XposedHelpers.setIntField(activity, "specialKeyCode", KeyEvent.KEYCODE_UNKNOWN);
                            XposedHelpers.setBooleanField(activity, "waitingForAllModifiersUp", false);
                        }
                    }
                }, handler);
            }
        });
        // 官方 release 会内联 NvConnection 的薄包装；JNI 入口由上游 keep 规则保留。
        moonBridge = XposedHelpers.findClass("com.limelight.nvstream.jni.MoonBridge", p.classLoader);
        XposedHelpers.findMethodExact(moonBridge, "sendMouseMove", short.class, short.class);
        XposedHelpers.findMethodExact(moonBridge, "sendMouseButton", byte.class, byte.class);
        for (String name : new String[]{"sendMouseMove", "sendMousePosition", "sendMouseButton"}) {
            XposedBridge.hookAllMethods(moonBridge, name, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam hook) {
                    if (diagnostics != null && !name.equals("sendMouseButton")) diagnostics.packet(hook.args);
                    Activity activity = gameActivity.get();
                    if (failed || pipe == null || !receiving(activity)) return;
                    Session s = session(activity);
                    boolean sent = name.equals("sendMouseMove")
                            ? s.cursor.move((Short) hook.args[0], (Short) hook.args[1])
                            : name.equals("sendMousePosition")
                            ? s.cursor.position((Short) hook.args[0], (Short) hook.args[1], (Short) hook.args[2], (Short) hook.args[3])
                            : s.cursor.button((Byte) hook.args[1], ((Byte) hook.args[0]) == 7);
                    if (sent) hook.setResult(null);
                }
            });
        }
        for (String field : new String[]{"connected", "grabbedInput", "modifierFlags", "specialKeyCode", "waitingForAllModifiersUp"}) {
            XposedHelpers.findField(game, field);
        }
        XposedHelpers.findAndHookMethod(moonBridge, "sendKeyboardInput", short.class, byte.class, byte.class, byte.class, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam hook) {
                if (failed || pipe == null || hook.hasThrowable()) return;
                forwardedKeys.record((Short) hook.args[0], (Byte) hook.args[1], (Byte) hook.args[3]);
                if (diagnostics != null) diagnostics.keyboardSent();
            }
        });
        XposedHelpers.findAndHookMethod(game, "handleMotionEvent", View.class, MotionEvent.class, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam hook) {
                if (diagnostics == null) return;
                Activity activity = (Activity) hook.thisObject;
                MotionEvent event = (MotionEvent) hook.args[1];
                View stream = (View) XposedHelpers.getObjectField(activity, "streamView");
                diagnostics.motion(event, supported(event), receiving(activity), stream.hasPointerCapture(),
                        Boolean.TRUE.equals(hook.getResult()));
            }
            @Override protected void beforeHookedMethod(MethodHookParam hook) {
                if (failed || pipe == null) return;
                Activity activity = (Activity) hook.thisObject;
                MotionEvent event = (MotionEvent) hook.args[1];
                Session session = sessions.get(activity);
                if ((event.getActionMasked() == MotionEvent.ACTION_CANCEL
                        || (event.getFlags() & MotionEvent.FLAG_CANCELED) != 0) && session != null) {
                    session.cancel();
                    if (supported(event)) hook.setResult(true);
                    return;
                }
                if (!supported(event) || !receiving(activity)) return;
                try {
                    pipe.active(true);
                    if (session == null) session = session(activity);
                    session.cursor.inputTime(event.getEventTime());
                    if (session.motion(event, pipe.available())) hook.setResult(true);
                } catch (Throwable error) {
                    if (session != null) session.cancel();
                    failed = true;
                    Log.e("PadMoonlight", "适配已停止，保留原输入", error);
                }
            }
        });
        XposedBridge.hookAllMethods(game, "onWindowFocusChanged", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam hook) {
                if (pipe == null) return;
                boolean focus = (Boolean) hook.args[0];
                if (focus) gameActivity = new WeakReference<>((Activity) hook.thisObject);
                if (!focus) {
                    Session session = sessions.get((Activity) hook.thisObject);
                    if (session != null) session.cancel();
                    releaseKeys();
                    gameActivity.clear();
                }
                pipe.active(focus);
            }
        });
        XposedBridge.hookAllMethods(game, "onPause", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam hook) {
                if (gameActivity.get() == hook.thisObject) gameActivity.clear();
                Session session = sessions.remove((Activity) hook.thisObject);
                if (session != null) session.cancel();
                if (pipe != null) pipe.active(false);
                releaseKeys();
            }
        });
        for (String method : new String[]{"handleKeyDown", "handleKeyUp"}) {
            XposedHelpers.findAndHookMethod(game, method, KeyEvent.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam hook) {
                    KeyEvent event = (KeyEvent) hook.args[0];
                    if (diagnostics != null) diagnostics.key(event);
                    int key = event.getKeyCode();
                    if (key == KeyEvent.KEYCODE_CTRL_LEFT || key == KeyEvent.KEYCODE_CTRL_RIGHT
                            || key == KeyEvent.KEYCODE_ALT_LEFT || key == KeyEvent.KEYCODE_ALT_RIGHT
                            || key == KeyEvent.KEYCODE_META_LEFT || key == KeyEvent.KEYCODE_META_RIGHT
                            || key == KeyEvent.KEYCODE_SHIFT_LEFT || key == KeyEvent.KEYCODE_SHIFT_RIGHT) {
                        if (event.getAction() == KeyEvent.ACTION_DOWN) heldModifiers.add(key);
                        else heldModifiers.remove(key);
                    }
                    int mods = 0;
                    for (int held : heldModifiers) {
                        if (held == KeyEvent.KEYCODE_CTRL_LEFT || held == KeyEvent.KEYCODE_CTRL_RIGHT) mods |= KeyEvent.META_CTRL_ON;
                        if (held == KeyEvent.KEYCODE_ALT_LEFT || held == KeyEvent.KEYCODE_ALT_RIGHT) mods |= KeyEvent.META_ALT_ON;
                        if (held == KeyEvent.KEYCODE_META_LEFT || held == KeyEvent.KEYCODE_META_RIGHT) mods |= KeyEvent.META_META_ON;
                        if (held == KeyEvent.KEYCODE_SHIFT_LEFT || held == KeyEvent.KEYCODE_SHIFT_RIGHT) mods |= KeyEvent.META_SHIFT_ON;
                    }
                    if (pipe != null) pipe.modifiers(mods);
                }
            });
        }
        Log.i("PadMoonlight", "0.2.6 已加载；不记录按键文字，不修改 Moonlight APK");
    }

    private Session session(Activity activity) {
        Session s = sessions.get(activity);
        if (s == null) { s = new Session(activity); sessions.put(activity, s); }
        if (!checking) { checking = true; handler.postDelayed(healthCheck, 250); }
        return s;
    }

    private void releaseKeys() {
        for (int key : forwardedKeys.drain()) {
            try { XposedHelpers.callStaticMethod(moonBridge, "sendKeyboardInput", (short) (key >>> 8), (byte) 4, (byte) 0, (byte) key); }
            catch (Throwable ignored) { /* 会话已结束时由 Sunshine 释放。 */ }
        }
        heldModifiers.clear();
        if (pipe != null) pipe.modifiers(0);
    }

    private static boolean receiving(Activity activity) {
        return activity != null && activity.hasWindowFocus()
                && XposedHelpers.getBooleanField(activity, "connected")
                && XposedHelpers.getBooleanField(activity, "grabbedInput");
    }

    private static boolean supported(MotionEvent event) {
        InputDevice d = event.getDevice();
        return d != null && event.isFromSource(InputDevice.SOURCE_TOUCHPAD)
                && ((d.getVendorId() == 0x15d9 && d.getProductId() == 0x00a1 && "Xiaomi Touch".equals(d.getName()))
                    || (d.getVendorId() == 0xbf01 && d.getProductId() == 0x0040
                    && ("Xiaomi Pad Keyboard".equals(d.getName()) || "Xiaomi Pad Keyboard Touchpad".equals(d.getName()))));
    }

    private static float resolution(InputDevice d, int axis) {
        InputDevice.MotionRange range = d.getMotionRange(axis, InputDevice.SOURCE_TOUCHPAD);
        if (range == null) range = d.getMotionRange(axis);
        if (range != null && range.getResolution() > 0) return range.getResolution();
        return range != null && range.getRange() > 0 ? range.getRange() / (axis == 0 ? 120f : 70f) : 25f;
    }

    private final class Session implements GestureEngine.Output, PointerEngine.Output {
        final Activity activity;
        final GestureEngine engine = new GestureEngine(this);
        final PointerEngine pointer = new PointerEngine(this);
        final CursorOverlay cursor;
        final ScrollMomentum momentum = new ScrollMomentum();
        int device = -1;
        boolean dragging, momentumActive;
        float remainderX, remainderY, dragX, dragY;
        Runnable animation;
        Session(Activity activity) {
            this.activity = activity;
            cursor = new CursorOverlay((View) XposedHelpers.getObjectField(activity, "streamView"), activity.getFilesDir(), pipe, diagnostics);
        }

        boolean motion(MotionEvent event, boolean extended) {
            int action = event.getActionMasked();
            if (device != event.getDeviceId()) {
                if (device >= 0) cancel();
                device = event.getDeviceId();
            }
            if (action == MotionEvent.ACTION_DOWN) stopMomentum();
            int removed = action == MotionEvent.ACTION_POINTER_UP ? event.getActionIndex() : -1;
            int count = event.getPointerCount() - (removed >= 0 ? 1 : 0);
            float rx = resolution(event.getDevice(), 0), ry = resolution(event.getDevice(), 1);
            float x = 0, y = 0, span = 0; int pairs = 0;
            for (int i = 0; i < event.getPointerCount(); i++) if (i != removed) {
                x += event.getX(i) / rx; y += event.getY(i) / ry;
                for (int j = i + 1; j < event.getPointerCount(); j++) if (j != removed) {
                    span += (float) Math.hypot((event.getX(i) - event.getX(j)) / rx,
                            (event.getY(i) - event.getY(j)) / ry); pairs++;
                }
            }
            if (count > 0) { x /= count; y /= count; }
            if (pairs > 0) span /= pairs;
            pointer.updateButtons(event.getButtonState());
            if (!extended) cancelGestures();
            boolean claimed = extended && engine.update(action, count, x, y, span, event.getButtonState(), event.getEventTime());
            if (claimed) pointer.ignoreContact();
            else pointer.update(action, count, x, y, event.getEventTime());
            return true;
        }

        void sendScroll(float x, float y, String phase) {
            remainderX += x; remainderY += y;
            int ix = Math.max(-2048, Math.min(2048, (int) remainderX));
            int iy = Math.max(-2048, Math.min(2048, (int) remainderY));
            remainderX -= ix; remainderY -= iy;
            if (!pipe.scroll(ix, iy, phase)) {
                // 背压时不积累旧位移，也不在重连后补发旧手势。
                remainderX = remainderY = 0;
                stopMomentum();
            }
        }

        @Override public void scroll(float x, float y, String phase, long time) {
            if (phase.equals("began")) { stopMomentum(); remainderX = remainderY = 0; momentum.begin(time); }
            if (phase.equals("changed")) momentum.sample(x, y, time);
            sendScroll(x, y, phase);
            if (phase.equals("cancelled")) stopMomentum();
            if (phase.equals("ended") && momentum.release(time, 4)) {
                momentumActive = true;
                sendScroll(0, 0, "momentum-began");
                animation = new Runnable() {
                    @Override public void run() {
                        if (!activity.hasWindowFocus() || InputDevice.getDevice(device) == null
                                || !momentum.step(SystemClock.uptimeMillis())) { stopMomentum(); return; }
                        sendScroll(momentum.dx(), momentum.dy(), "momentum-changed");
                        if (momentumActive) handler.postDelayed(this, 8);
                    }
                };
                handler.post(animation);
            }
        }

        @Override public void dock(int axis, double progress, double velocity, String phase) {
            if (diagnostics != null) diagnostics.gesture(axis, progress, phase);
            pipe.gesture(axis, progress, velocity, phase);
        }
        @Override public void magnify(double delta, String phase) { pipe.magnify(delta, phase); }
        @Override public void button(int button, boolean down) {
            XposedHelpers.callStaticMethod(moonBridge, "sendMouseButton", (byte) (down ? 7 : 8), (byte) button);
        }
        @Override public void secondaryClick() { button(3, true); button(3, false); }
        @Override public void move(float x, float y) {
            if (cursor.move(x, y)) return;
            dragX += x; dragY += y;
            short dx = (short) Math.max(-2048, Math.min(2048, dragX));
            short dy = (short) Math.max(-2048, Math.min(2048, dragY));
            dragX -= dx; dragY -= dy;
            if (dx != 0 || dy != 0) XposedHelpers.callStaticMethod(moonBridge, "sendMouseMove", dx, dy);
        }
        @Override public void drag(float x, float y, boolean start) {
            if (start) {
                dragging = true; dragX = dragY = 0;
                XposedHelpers.callStaticMethod(moonBridge, "sendMouseButton", (byte) 7, (byte) 1);
            }
            move(x, y);
        }
        @Override public void release() {
            boolean held = dragging;
            dragging = false;
            if (held) {
                try { XposedHelpers.callStaticMethod(moonBridge, "sendMouseButton", (byte) 8, (byte) 1); }
                catch (Throwable ignored) { /* 连接已关闭时由 Sunshine 的会话清理释放。 */ }
            }
        }
        void stopMomentum() {
            if (animation != null) handler.removeCallbacks(animation);
            animation = null; momentum.stop();
            if (momentumActive) { momentumActive = false; pipe.scroll(0, 0, "momentum-ended"); }
        }
        void cancelGestures() {
            stopMomentum(); engine.cancel(SystemClock.uptimeMillis()); release();
        }
        void cancel() {
            cancelGestures(); pointer.cancel();
            cursor.close();
            dragX = dragY = 0;
            if (pipe != null) pipe.reset();
        }
    }
}
