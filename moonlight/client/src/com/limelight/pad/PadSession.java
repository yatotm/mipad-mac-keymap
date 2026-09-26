package com.limelight.pad;

import android.app.Activity;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Toast;
import com.limelight.nvstream.NvConnection;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;
import java.util.function.BooleanSupplier;
import local.pad.moonlight.GestureEngine;
import local.pad.moonlight.PointerEngine;
import local.pad.moonlight.HeldKeys;
import local.pad.uu.touchpad.RemoteFunctionReceiver;
import local.pad.uu.touchpad.ScrollMomentum;
import org.json.JSONObject;

/** 内置的输入会话，普通键盘复用原协议，增强输入只绑定当前已配对主机。 */
public final class PadSession implements NvConnection.InputAdapter, GestureEngine.Output, PointerEngine.Output,
        PadTransport.Listener, InputManager.InputDeviceListener {
    private final Activity activity;
    private final NvConnection connection;
    private final BooleanSupplier receiving;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final GestureEngine gestures = new GestureEngine(this);
    private final PointerEngine pointer = new PointerEngine(this);
    private final ScrollMomentum momentum = new ScrollMomentum();
    private final HeldKeys heldKeys = new HeldKeys();
    private final Set<Integer> modifiers = new HashSet<>();
    private final PadTransport transport;
    private final PadCursor cursor;
    private final RemoteFunctionReceiver functions;
    private final InputManager inputManager;
    private volatile boolean connected, focused, paused, closed;
    private boolean suppress, dragging, momentumActive;
    private int device = -1;
    private float remainderX, remainderY, moveX, moveY;
    private long nextStatus;
    private Runnable animation;
    private final Runnable health = new Runnable() {
        @Override public void run() {
            if (closed) return;
            refresh();
            transport.heartbeat();
            handler.postDelayed(this, 1000);
        }
    };

    public PadSession(Activity activity, View stream, NvConnection connection, BooleanSupplier receiving) {
        this.activity = activity; this.connection = connection; this.receiving = receiving;
        focused = activity.hasWindowFocus();
        transport = new PadTransport(stream, this);
        cursor = new PadCursor(stream, transport);
        connection.setInputAdapter(this);
        inputManager = activity.getSystemService(InputManager.class);
        inputManager.registerInputDeviceListener(this, handler);
        functions = new RemoteFunctionReceiver(activity.getApplication(), action -> {
            if (isReceiving()) transport.action(action);
        }, "com.limelight.Game");
        handler.post(health);
    }

    private boolean isReceiving() { return connected && focused && !paused && !closed && receiving.getAsBoolean(); }

    public void started() { connected = true; transport.connected(); refresh(); }
    public void resume() { paused = false; focused = activity.hasWindowFocus(); refresh(); }
    public void focus(boolean value) { focused = value; if (value) refresh(); else pauseInput(); }
    public void pause() { paused = true; pauseInput(); }
    public void disconnected() { pauseInput(); connected = false; }
    public void state(byte[] data) { if (!closed) transport.state(data); }

    private void refresh() {
        boolean active = isReceiving();
        if (!active && transport.active()) pauseInput();
        else if (active && !transport.active()) transport.active(true);
    }

    private void pauseInput() {
        releaseKeys();
        resetLocal();
        transport.active(false);
    }

    private void resetLocal() {
        suppress = true;
        stopMomentum(); gestures.cancel(SystemClock.uptimeMillis()); pointer.cancel(); release();
        suppress = false;
        cursor.close(); moveX = moveY = remainderX = remainderY = 0;
    }

    public void close() {
        if (closed) return;
        pauseInput(); closed = true;
        handler.removeCallbacks(health);
        inputManager.unregisterInputDeviceListener(this);
        functions.close(); connection.setInputAdapter(null);
    }

    public void key(KeyEvent event) {
        int key = event.getKeyCode();
        if (key == KeyEvent.KEYCODE_CTRL_LEFT || key == KeyEvent.KEYCODE_CTRL_RIGHT
                || key == KeyEvent.KEYCODE_ALT_LEFT || key == KeyEvent.KEYCODE_ALT_RIGHT
                || key == KeyEvent.KEYCODE_META_LEFT || key == KeyEvent.KEYCODE_META_RIGHT
                || key == KeyEvent.KEYCODE_SHIFT_LEFT || key == KeyEvent.KEYCODE_SHIFT_RIGHT) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) modifiers.add(key);
            else modifiers.remove(key);
        }
        int flags = 0;
        for (int held : modifiers) {
            if (held == KeyEvent.KEYCODE_SHIFT_LEFT || held == KeyEvent.KEYCODE_SHIFT_RIGHT) flags |= KeyEvent.META_SHIFT_ON;
            if (held == KeyEvent.KEYCODE_CTRL_LEFT || held == KeyEvent.KEYCODE_CTRL_RIGHT) flags |= KeyEvent.META_CTRL_ON;
            if (held == KeyEvent.KEYCODE_ALT_LEFT || held == KeyEvent.KEYCODE_ALT_RIGHT) flags |= KeyEvent.META_ALT_ON;
            if (held == KeyEvent.KEYCODE_META_LEFT || held == KeyEvent.KEYCODE_META_RIGHT) flags |= KeyEvent.META_META_ON;
        }
        transport.modifiers(flags);
    }

    @Override public void keySent(short key, byte action, byte flags) { heldKeys.record(key, action, flags); }

    private void releaseKeys() {
        for (int key : heldKeys.drain()) connection.sendKeyboardInput((short)(key >>> 8), (byte)4, (byte)0, (byte)key);
        modifiers.clear(); transport.modifiers(0);
    }

    public boolean motion(MotionEvent event) {
        if (!supported(event) || !isReceiving()) return false;
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_CANCEL || (event.getFlags() & MotionEvent.FLAG_CANCELED) != 0) {
            resetLocal(); transport.active(false); refresh(); return true;
        }
        if (device != event.getDeviceId()) {
            if (device >= 0) { resetLocal(); transport.active(false); refresh(); }
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
                span += (float)Math.hypot((event.getX(i) - event.getX(j)) / rx, (event.getY(i) - event.getY(j)) / ry); pairs++;
            }
        }
        if (count > 0) { x /= count; y /= count; }
        if (pairs > 0) span /= pairs;
        pointer.updateButtons(event.getButtonState());
        boolean claimed = transport.available() && gestures.update(action, count, x, y, span, event.getButtonState(), event.getEventTime());
        if (claimed) pointer.ignoreContact();
        else pointer.update(action, count, x, y, event.getEventTime());
        return true;
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

    // 非界面线程产生的鼠标事件也按同一顺序进入主线程，避免跨线程操作 View。
    @Override public boolean mouseMove(short x, short y) {
        if (!isReceiving() || !transport.available()) return false;
        if (Looper.myLooper() != Looper.getMainLooper()) { handler.post(() -> mouseMove(x, y)); return true; }
        return cursor.move(x, y);
    }
    @Override public boolean mousePosition(short x, short y, short w, short h) {
        if (!isReceiving() || !transport.available()) return false;
        if (Looper.myLooper() != Looper.getMainLooper()) { handler.post(() -> mousePosition(x, y, w, h)); return true; }
        return cursor.position(x, y, w, h);
    }
    @Override public boolean mouseButton(byte button, boolean down) {
        if (!isReceiving() || !transport.available()) return false;
        if (Looper.myLooper() != Looper.getMainLooper()) { handler.post(() -> mouseButton(button, down)); return true; }
        return cursor.button(button, down);
    }
    @Override public void move(float x, float y) {
        if (suppress) return;
        if (cursor.move(x, y)) return;
        moveX += x; moveY += y;
        short dx = (short)Math.max(-2048, Math.min(2048, moveX)), dy = (short)Math.max(-2048, Math.min(2048, moveY));
        moveX -= dx; moveY -= dy;
        if (dx != 0 || dy != 0) connection.sendMouseMove(dx, dy);
    }
    @Override public void button(int button, boolean down) {
        if (suppress) return;
        if (down) connection.sendMouseButtonDown((byte)button); else connection.sendMouseButtonUp((byte)button);
    }
    @Override public void secondaryClick() { button(3, true); button(3, false); }
    @Override public void drag(float x, float y, boolean start) {
        if (start) { dragging = true; moveX = moveY = 0; button(1, true); }
        move(x, y);
    }
    @Override public void release() { boolean held = dragging; dragging = false; if (held) button(1, false); }

    private void sendScroll(float x, float y, String phase) {
        if (suppress) return;
        remainderX += x; remainderY += y;
        int ix = Math.max(-2048, Math.min(2048, (int)remainderX)), iy = Math.max(-2048, Math.min(2048, (int)remainderY));
        remainderX -= ix; remainderY -= iy;
        if (!transport.scroll(ix, iy, phase)) { remainderX = remainderY = 0; stopMomentum(); }
    }
    @Override public void scroll(float x, float y, String phase, long time) {
        if (phase.equals("began")) { stopMomentum(); remainderX = remainderY = 0; momentum.begin(time); }
        if (phase.equals("changed")) momentum.sample(x, y, time);
        sendScroll(x, y, phase);
        if (phase.equals("cancelled")) stopMomentum();
        if (!suppress && phase.equals("ended") && momentum.release(time, 4)) {
            momentumActive = true; sendScroll(0, 0, "momentum-began");
            animation = new Runnable() {
                @Override public void run() {
                    if (!isReceiving() || InputDevice.getDevice(device) == null || !momentum.step(SystemClock.uptimeMillis())) {
                        stopMomentum(); return;
                    }
                    sendScroll(momentum.dx(), momentum.dy(), "momentum-changed");
                    if (momentumActive) handler.postDelayed(this, 8);
                }
            };
            handler.post(animation);
        }
    }
    private void stopMomentum() {
        if (animation != null) handler.removeCallbacks(animation);
        animation = null; momentum.stop();
        if (momentumActive) { momentumActive = false; if (!suppress) transport.scroll(0, 0, "momentum-ended"); }
    }
    @Override public void dock(int axis, double progress, double velocity, String phase) {
        if (!suppress) transport.gesture(axis, progress, velocity, phase);
    }
    @Override public void magnify(double delta, String phase) { if (!suppress) transport.magnify(delta, phase); }

    @Override public void state(JSONObject data, boolean geometryChanged) {
        if (geometryChanged) resetLocal();
        if (!data.optBoolean("active")) releaseKeys();
        cursor.metadata(data, geometryChanged);
        long now = SystemClock.uptimeMillis();
        if (now >= nextStatus) {
            nextStatus = now + 1000;
            try {
                JSONObject status = new JSONObject().put("version", 1).put("transport", "moonlight-control")
                        .put("at", System.currentTimeMillis()).put("ready", data.optBoolean("ready")).put("epoch", data.optLong("epoch"))
                        .put("client_sent", transport.sequence()).put("server_received", data.optLong("received"));
                Files.write(new File(activity.getFilesDir(), "pad-native-status.json").toPath(), status.toString().getBytes(StandardCharsets.UTF_8));
            } catch (Exception ignored) { }
        }
    }
    @Override public void failed() {
        handler.post(() -> {
            pauseInput();
            Toast.makeText(activity, "增强输入连接中断，请重新进入桌面", Toast.LENGTH_LONG).show();
            activity.finish();
        });
    }
    @Override public void onInputDeviceAdded(int id) {}
    @Override public void onInputDeviceChanged(int id) {}
    @Override public void onInputDeviceRemoved(int id) {
        releaseKeys();
        if (device == id) { resetLocal(); transport.active(false); device = -1; refresh(); }
    }
}
