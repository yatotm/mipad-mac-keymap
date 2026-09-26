package com.limelight.pad;

import android.os.SystemClock;
import android.view.View;
import com.limelight.nvstream.jni.MoonBridge;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** 只使用当前 Moonlight 加密会话；没有 ADB、主机探测或额外网络连接。 */
final class PadTransport {
    static final int FEATURE = 0x00010000;
    interface Listener { void state(JSONObject data, boolean geometryChanged); void failed(); }
    private final View view;
    private final Listener listener;
    private long sequence, epoch, helloAt;
    private int modifiers;
    private volatile boolean supported, active, ready, failed;
    private boolean flushing;
    private JSONObject pendingPosition;
    private final Runnable flush = () -> { flushing = false; flushPosition(); };

    PadTransport(View view, Listener listener) { this.view = view; this.listener = listener; }
    boolean available() { return supported && active && ready && !failed; }
    boolean active() { return active; }
    long sequence() { return sequence; }

    void connected() { supported = (MoonBridge.getHostFeatureFlags() & FEATURE) != 0; }

    void active(boolean value) {
        value &= supported && !failed;
        if (active == value) return;
        pendingPosition = null;
        view.removeCallbacks(flush); flushing = false;
        if (!value) sendNow(packet("reset"));
        active = value; ready = false;
        if (value) hello();
    }

    void heartbeat() { if (active && !failed) sendNow(packet("sync")); }
    private void hello() { helloAt = SystemClock.uptimeMillis(); sendNow(packet("hello")); }
    void modifiers(int value) { if (value != modifiers) { flushPosition(); modifiers = value; } }

    private JSONObject packet(String type) {
        try { return new JSONObject().put("v", 1).put("t", type).put("at", SystemClock.elapsedRealtime()); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }

    private boolean sendNow(JSONObject frame) {
        if (!supported || failed) return false;
        try {
            byte[] data = frame.put("seq", ++sequence).put("epoch", epoch).put("mods", modifiers)
                    .toString().getBytes(StandardCharsets.UTF_8);
            if (data.length <= 1024 && MoonBridge.sendPadInput(data) == 0) return true;
        } catch (Exception ignored) { }
        failed = true; ready = active = false; pendingPosition = null;
        listener.failed();
        return false;
    }

    private void flushPosition() {
        JSONObject position = pendingPosition; pendingPosition = null;
        if (position != null && available()) sendNow(position);
    }

    private boolean send(JSONObject frame) {
        if (!available()) return false;
        // 离散事件前先提交位置，点击不会落到前一个鼠标位置。
        flushPosition();
        return sendNow(frame);
    }

    boolean position(int x, int y, int w, int h) {
        if (!available()) return false;
        try { pendingPosition = packet("position").put("x", x).put("y", y).put("w", w).put("h", h).put("local", true); }
        catch (Exception ignored) { return false; }
        if (!flushing) { flushing = true; view.postOnAnimation(flush); }
        return true;
    }

    boolean button(int button, boolean down) {
        try { return send(packet("button").put("button", button).put("down", down)); }
        catch (Exception ignored) { return false; }
    }
    boolean scroll(int x, int y, String phase) {
        try { return send(packet("scroll").put("x", x).put("y", y).put("phase", phase)); }
        catch (Exception ignored) { return false; }
    }
    void gesture(int axis, double progress, double velocity, String phase) {
        try { send(packet("gesture").put("axis", axis).put("progress", progress).put("velocity", velocity).put("phase", phase)); }
        catch (Exception ignored) { }
    }
    void magnify(double delta, String phase) {
        try { send(packet("magnify").put("progress", delta).put("phase", phase)); }
        catch (Exception ignored) { }
    }
    void action(String action) {
        try { send(packet("action").put("a", action)); }
        catch (Exception ignored) { }
    }

    void state(byte[] bytes) {
        if (!active || bytes == null || bytes.length > 512) return;
        try {
            JSONObject data = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            long nextEpoch = data.getLong("epoch"), acknowledged = data.getLong("seq");
            if (data.getInt("v") != 1 || nextEpoch < 1 || acknowledged < 0 || acknowledged > sequence) return;
            int w = data.getInt("w"), h = data.getInt("h");
            boolean nextReady = data.getBoolean("ready") && w > 0 && h > 0 && w <= 32767 && h <= 32767;
            boolean changed = epoch != nextEpoch || ready != nextReady;
            // 切屏先清理本地状态，再接收新坐标，期间不补发旧位移。
            if (changed) { pendingPosition = null; ready = false; }
            epoch = nextEpoch;
            listener.state(data, changed);
            ready = nextReady;
            if (!data.optBoolean("active") && !data.optBoolean("busy") && SystemClock.uptimeMillis() - helloAt >= 1000) hello();
        } catch (Exception ignored) { }
    }
}
