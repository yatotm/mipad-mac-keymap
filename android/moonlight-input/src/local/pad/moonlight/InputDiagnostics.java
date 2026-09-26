package local.pad.moonlight;

import android.view.MotionEvent;
import android.view.KeyEvent;
import android.os.SystemClock;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.json.JSONObject;

/** 按需覆盖一份状态快照；有截止时间，不保存按键文字或事件历史。 */
final class InputDiagnostics {
    private final File arm, state;
    private long nextCheck, until, nextWrite, motions, moves, positions;
    private int maxPointers, maxPadPointers;
    private long inputCount, inputTotal, inputMax, drawCount, drawTotal, drawMax, keys, sentKeys, cancellations;
    private JSONObject value = new JSONObject();

    InputDiagnostics(File directory) {
        arm = new File(directory, "pad_moonlight_probe_until");
        state = new File(directory, "pad_moonlight_probe.json");
    }

    private boolean enabled() {
        long now = System.currentTimeMillis();
        if (now >= nextCheck) {
            nextCheck = now + 1000;
            try {
                long deadline = Long.parseLong(new String(Files.readAllBytes(arm.toPath()), StandardCharsets.UTF_8).trim());
                if (deadline != until) {
                    motions = moves = positions = inputCount = inputTotal = inputMax = drawCount = drawTotal = drawMax = 0;
                    keys = sentKeys = cancellations = 0;
                    maxPointers = maxPadPointers = 0;
                    value = new JSONObject();
                }
                until = deadline;
            }
            catch (Exception ignored) { until = 0; }
        }
        return now < until && until - now <= 180000;
    }

    void motion(MotionEvent event, boolean supported, boolean receiving, boolean capture, boolean claimed) {
        if (!enabled()) return;
        try {
            motions++;
            if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) cancellations++;
            maxPointers = Math.max(maxPointers, event.getPointerCount());
            if (supported) {
                maxPadPointers = Math.max(maxPadPointers, event.getPointerCount());
                value.put("pad_max_pointers", maxPadPointers);
                if (event.getActionMasked() == MotionEvent.ACTION_MOVE && event.getPointerCount() == 1) {
                    long age = Math.max(0, SystemClock.uptimeMillis() - event.getEventTime());
                    inputCount++; inputTotal += age; inputMax = Math.max(inputMax, age);
                }
            }
            value.put("source", event.getSource()).put("action", event.getActionMasked())
                    .put("motion_at", event.getEventTime()).put("device", event.getDeviceId())
                    .put("cancellations", cancellations)
                    .put("pointers", event.getPointerCount()).put("max_pointers", maxPointers)
                    .put("supported", supported).put("receiving", receiving).put("captured", capture)
                    .put("claimed", claimed).put("buttons", event.getButtonState())
                    .put("relative_x", event.getAxisValue(MotionEvent.AXIS_RELATIVE_X))
                    .put("relative_y", event.getAxisValue(MotionEvent.AXIS_RELATIVE_Y));
            if (event.getActionMasked() == MotionEvent.ACTION_UP) nextWrite = 0;
            write();
        } catch (Exception ignored) { }
    }

    void packet(Object[] args) {
        if (!enabled()) return;
        if (args.length == 4) {
            positions++;
            try {
                value.put("absolute_x", args[0]).put("absolute_y", args[1])
                        .put("reference_width", args[2]).put("reference_height", args[3]);
            } catch (Exception ignored) { }
        } else moves++;
        write();
    }

    void gesture(int axis, double progress, String phase) {
        if (!enabled()) return;
        try {
            value.put("gesture_axis", axis).put("gesture_progress", progress).put("gesture_phase", phase);
            write();
        } catch (Exception ignored) { }
    }

    void flush() { if (enabled()) write(); }
    boolean active() { return enabled(); }

    void key(KeyEvent event) {
        if (!enabled()) return;
        try {
            value.put("keys", ++keys).put("key_at", event.getEventTime()).put("key_device", event.getDeviceId());
            nextWrite = 0;
            write();
        } catch (Exception ignored) { }
    }

    void keyboardSent() {
        if (!enabled()) return;
        try {
            value.put("sent_keys", ++sentKeys).put("sent_key_at", SystemClock.uptimeMillis());
            nextWrite = 0;
            write();
        } catch (Exception ignored) { }
    }

    void cursorDrawn(long eventTime) {
        if (!enabled() || eventTime <= 0) return;
        long age = Math.max(0, SystemClock.uptimeMillis() - eventTime);
        drawCount++; drawTotal += age; drawMax = Math.max(drawMax, age);
    }

    private void write() {
        long now = System.currentTimeMillis();
        if (now < nextWrite) return;
        nextWrite = now + 250;
        try {
            value.put("at", now).put("motions", motions).put("mouse_moves", moves).put("mouse_positions", positions);
            value.put("input_samples", inputCount).put("input_mean_ms", inputCount == 0 ? 0 : (double) inputTotal / inputCount)
                    .put("input_max_ms", inputMax).put("cursor_frames", drawCount)
                    .put("cursor_mean_ms", drawCount == 0 ? 0 : (double) drawTotal / drawCount).put("cursor_max_ms", drawMax);
            Files.write(state.toPath(), value.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) { }
    }
}
