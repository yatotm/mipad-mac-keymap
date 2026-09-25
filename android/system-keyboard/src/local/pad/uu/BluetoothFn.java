package local.pad.uu;

/** 还原蓝牙在按住原 Ctrl 时误放进修饰字节的 Consumer 用途码。 */
public final class BluetoothFn {
    private int mask, published, holding;
    private boolean active, blocked, lastDown;
    private long lastTime = -1;

    public static int bit(int scan) {
        switch (scan) {
            case 29: return 1;
            case 42: return 2;
            case 56: return 4;
            case 125: return 8;
            case 97: return 16;
            case 54: return 32;
            case 100: return 64;
            case 126: return 128;
            default: return 0;
        }
    }

    public static int function(int usage) {
        switch (usage) {
            case 0x70: return 1;
            case 0x6f: return 2;
            case 0xb6: return 7;
            case 0xcd: return 8;
            case 0xb5: return 9;
            case 0xe2: return 10;
            case 0xea: return 11;
            case 0xe9: return 12;
            default: return 0;
        }
    }

    public synchronized boolean update(int scan, boolean down, long time, boolean enabled) {
        int bit = bit(scan);
        if (bit == 0) return false;
        if (active && mask == 0 && time != lastTime) {
            active = blocked = false;
        }
        lastTime = time;
        int before = mask;
        mask = down ? mask | bit : mask & ~bit;
        if (!active && enabled && scan == 29 && down && before == 0) {
            active = true; blocked = false; published = 0;
        }
        if (!enabled && active) blocked = true;
        if (before != mask) holding = 0;
        lastDown = down;
        return active;
    }

    // 同一报告包含多个修饰事件；等报告收齐再解码，不能把释放时的中间位图当成新动作。
    public synchronized int finishFrame() {
        if (!active) return 0;
        // 空位图可能只是同一报告中先释放左 Ctrl；只在下一报告开始时结束所有权。
        if (mask == 0) { published = holding = 0; return 0; }
        if (mask == 1) { published = holding = 0; return 0; }
        int index = function(mask);
        if (blocked || !lastDown || index == 0 || published == mask) return 0;
        published = mask; holding = index;
        return index;
    }

    public synchronized int holding() { return blocked ? 0 : holding; }
    public synchronized boolean enabled() { return active && !blocked; }
    public synchronized void cancel() { blocked = active; holding = 0; }
    public synchronized void reset() {
        mask = published = holding = 0;
        lastTime = -1;
        active = blocked = lastDown = false;
    }
}
