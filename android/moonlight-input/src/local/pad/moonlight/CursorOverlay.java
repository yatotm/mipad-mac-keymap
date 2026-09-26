package local.pad.moonlight;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.json.JSONObject;
import local.pad.uu.touchpad.InputPipe;

/** 本地立即绘制光标，位置与按钮按顺序发送给唯一的 Mac Helper。 */
final class CursorOverlay extends View {
    private final View stream;
    private final ViewGroup decor;
    private final InputPipe pipe;
    private final InputDiagnostics diagnostics;
    private final File capabilities;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path arrow = new Path();
    private final float density;
    private final int[] streamOrigin = new int[2], decorOrigin = new int[2];
    private int remoteWidth, remoteHeight, refWidth, refHeight;
    private long readAt;
    private long inputTime, pendingDrawTime;
    private float x, y;
    private int left, top;
    private boolean visible;

    CursorOverlay(View stream, File files, InputPipe pipe, InputDiagnostics diagnostics) {
        super(stream.getContext());
        this.stream = stream; this.decor = (ViewGroup) stream.getRootView(); this.pipe = pipe;
        this.diagnostics = diagnostics;
        setWillNotDraw(false);
        setFocusable(false); setClickable(false); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        capabilities = new File(files, "pad_uu_input.pointer.json");
        density = stream.getResources().getDisplayMetrics().density;
        fill.setColor(0xff101010); stroke.setColor(0xffffffff);
        stroke.setStyle(Paint.Style.STROKE); stroke.setStrokeWidth(1.6f); stroke.setStrokeJoin(Paint.Join.ROUND);
        arrow.moveTo(0, 0); arrow.lineTo(0, 22); arrow.lineTo(5.5f, 16.5f);
        arrow.lineTo(10, 26); arrow.lineTo(14, 24); arrow.lineTo(9.5f, 15); arrow.lineTo(18, 15); arrow.close();
    }

    private CursorGeometry geometry() {
        if (!pipe.available()) { close(); return null; }
        long now = SystemClock.uptimeMillis();
        if (now >= readAt) {
            readAt = now + 500;
            try {
                if (capabilities.length() > 1024) throw new IllegalStateException();
                JSONObject data = new JSONObject(new String(Files.readAllBytes(capabilities.toPath()), StandardCharsets.UTF_8));
                int w = data.getInt("w"), h = data.getInt("h");
                if (data.getInt("v") != 1 || w < 1 || h < 1 || w > 32767 || h > 32767) throw new IllegalStateException();
                remoteWidth = w; remoteHeight = h;
            } catch (Exception ignored) { remoteWidth = remoteHeight = 0; }
        }
        int w = stream.getWidth(), h = stream.getHeight();
        if (remoteWidth == 0 || remoteHeight == 0 || w < 1 || h < 1 || w > 32767 || h > 32767) { close(); return null; }
        if (w != refWidth || h != refHeight) {
            x = refWidth == 0 ? w / 2f : x * w / refWidth;
            y = refHeight == 0 ? h / 2f : y * h / refHeight;
            refWidth = w; refHeight = h;
        }
        return new CursorGeometry(w, h, remoteWidth, remoteHeight);
    }

    private boolean send(float nx, float ny) {
        if (!pipe.pointerPosition(Math.round(nx), Math.round(ny), refWidth, refHeight)) { close(); return false; }
        x = nx; y = ny;
        // 使用普通子视图参与透明区域计算，防止 SurfaceView 将光标位置当作视频空洞跳过合成。
        if (!visible) {
            decor.addView(this, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            visible = true;
        }
        stream.getLocationOnScreen(streamOrigin); decor.getLocationOnScreen(decorOrigin);
        left = Math.round(x + streamOrigin[0] - decorOrigin[0]);
        top = Math.round(y + streamOrigin[1] - decorOrigin[1]);
        pendingDrawTime = inputTime; inputTime = 0;
        invalidate();
        return true;
    }

    boolean move(float dx, float dy) {
        CursorGeometry g = geometry();
        return g != null && send(g.clampX(x + dx * g.scale), g.clampY(y + dy * g.scale));
    }

    boolean position(int nx, int ny, int w, int h) {
        if (w <= 0 || h <= 0) return false;
        CursorGeometry g = geometry();
        return g != null && send(g.clampX((float) nx * refWidth / w), g.clampY((float) ny * refHeight / h));
    }

    boolean button(int button, boolean down) {
        if (button < 1 || button > 3 || geometry() == null) return false;
        if (!visible && !down) return false;
        if (!visible && !move(0, 0)) return false;
        if (pipe.pointerButton(button, down)) return true;
        close(); return false;
    }

    void close() { if (visible) decor.removeView(this); visible = false; pendingDrawTime = inputTime = 0; }

    void inputTime(long value) { inputTime = value; }

    @Override protected void onDraw(Canvas canvas) {
        // 限时诊断使用蓝色区分本地箭头与视频中的箭头，结束后自动恢复。
        fill.setColor(diagnostics.active() ? 0xff00aaff : 0xff101010);
        canvas.save(); canvas.translate(left, top); canvas.scale(density, density);
        canvas.drawPath(arrow, stroke); canvas.drawPath(arrow, fill); canvas.restore();
        diagnostics.cursorDrawn(pendingDrawTime); pendingDrawTime = 0;
    }
}
