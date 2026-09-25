package local.pad.uu.touchpad;

import java.util.LinkedHashMap;

/** 同一次长按的重复事件只发送一次，避免辅助输入和普通输入重复投递。 */
public final class RepeatGate {
    private final LinkedHashMap<Long, long[]> seen = new LinkedHashMap<>(32, 0.75f, true);

    private static long key(int device, int code) {
        return ((long) device << 32) | (code & 0xffffffffL);
    }

    public boolean claim(int device, int code, long downTime, int repeatCount) {
        if (repeatCount <= 0) return false;
        return claimPress(device, code, downTime, repeatCount);
    }

    public boolean claimPress(int device, int code, long downTime, int repeatCount) {
        if (repeatCount < 0) return false;
        long key = key(device, code);
        long[] last = seen.get(key);
        if (last != null && last[0] == downTime && repeatCount <= last[1]) return false;
        if (seen.size() >= 128 && !seen.containsKey(key)) seen.remove(seen.keySet().iterator().next());
        seen.put(key, new long[]{downTime, repeatCount});
        return true;
    }

    public void release(int device, int code) { seen.remove(key(device, code)); }
}
