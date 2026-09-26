package local.pad.moonlight;

/** 视频留白以外的有效区域；远端尺寸使用逻辑点，不重复乘 HiDPI 倍率。 */
public final class CursorGeometry {
    public final float left, top, width, height, scale;
    public CursorGeometry(float viewWidth, float viewHeight, float remoteWidth, float remoteHeight) {
        if (!(viewWidth > 0 && viewHeight > 0 && remoteWidth > 0 && remoteHeight > 0)) throw new IllegalArgumentException();
        scale = Math.min(viewWidth / remoteWidth, viewHeight / remoteHeight);
        width = remoteWidth * scale; height = remoteHeight * scale;
        left = (viewWidth - width) / 2; top = (viewHeight - height) / 2;
    }
    public float clampX(float x) { return Math.max(left, Math.min(left + width - 1, x)); }
    public float clampY(float y) { return Math.max(top, Math.min(top + height - 1, y)); }
}
