package com.limelight.pad;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;
import android.view.ViewGroup;
import local.pad.moonlight.CursorGeometry;
import org.json.JSONObject;

/** 前景普通 View 参与 SurfaceView 透明区域合成，位置在本地立即显示。 */
final class PadCursor extends View {
    private final View stream;
    private final ViewGroup decor;
    private final PadTransport transport;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path arrow = new Path();
    private final int[] streamOrigin = new int[2], decorOrigin = new int[2];
    private final float size;
    private int remoteWidth, remoteHeight, refWidth, refHeight;
    private float x, y, initialX, initialY;
    private boolean visible, initialise;

    PadCursor(View stream, PadTransport transport) {
        super(stream.getContext());
        this.stream = stream; this.transport = transport; decor = (ViewGroup) stream.getRootView();
        size = stream.getResources().getDisplayMetrics().density * 0.65f;
        setWillNotDraw(false); setFocusable(false); setClickable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        fill.setColor(0xff101010); border.setColor(0xffffffff);
        border.setStyle(Paint.Style.STROKE); border.setStrokeWidth(1.6f); border.setStrokeJoin(Paint.Join.ROUND);
        arrow.moveTo(0, 0); arrow.lineTo(0, 22); arrow.lineTo(5.5f, 16.5f);
        arrow.lineTo(10, 26); arrow.lineTo(14, 24); arrow.lineTo(9.5f, 15); arrow.lineTo(18, 15); arrow.close();
    }

    void metadata(JSONObject data, boolean changed) {
        if (!changed) return;
        close();
        remoteWidth = data.optInt("w"); remoteHeight = data.optInt("h");
        initialX = data.optInt("x", remoteWidth / 2); initialY = data.optInt("y", remoteHeight / 2);
        initialise = true;
    }

    private CursorGeometry geometry() {
        int w = stream.getWidth(), h = stream.getHeight();
        if (!transport.available() || remoteWidth < 1 || remoteHeight < 1 || w < 1 || h < 1 || w > 32767 || h > 32767) return null;
        CursorGeometry g = new CursorGeometry(w, h, remoteWidth, remoteHeight);
        if (initialise) {
            x = g.left + initialX * g.scale; y = g.top + initialY * g.scale; initialise = false;
        } else if (w != refWidth || h != refHeight) {
            x = refWidth == 0 ? w / 2f : x * w / refWidth;
            y = refHeight == 0 ? h / 2f : y * h / refHeight;
        }
        refWidth = w; refHeight = h;
        return g;
    }

    private boolean update(float nx, float ny) {
        if (!transport.position(Math.round(nx), Math.round(ny), refWidth, refHeight)) return false;
        x = nx; y = ny;
        if (!visible) {
            decor.addView(this, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            visible = true;
        }
        stream.getLocationOnScreen(streamOrigin); decor.getLocationOnScreen(decorOrigin);
        invalidate();
        return true;
    }

    boolean move(float dx, float dy) {
        CursorGeometry g = geometry();
        return g != null && update(g.clampX(x + dx * g.scale), g.clampY(y + dy * g.scale));
    }
    boolean position(int px, int py, int w, int h) {
        CursorGeometry g = geometry();
        return g != null && w > 0 && h > 0 && update(g.clampX((float)px * refWidth / w), g.clampY((float)py * refHeight / h));
    }
    boolean button(int button, boolean down) {
        if (button < 1 || button > 3 || geometry() == null) return false;
        if (!visible && !down) return false;
        if (!visible && !move(0, 0)) return false;
        return transport.button(button, down);
    }
    void close() { if (visible) decor.removeView(this); visible = false; }
    @Override protected void onDraw(Canvas canvas) {
        canvas.save(); canvas.translate(x + streamOrigin[0] - decorOrigin[0], y + streamOrigin[1] - decorOrigin[1]);
        canvas.scale(size, size); canvas.drawPath(arrow, border); canvas.drawPath(arrow, fill); canvas.restore();
    }
}
