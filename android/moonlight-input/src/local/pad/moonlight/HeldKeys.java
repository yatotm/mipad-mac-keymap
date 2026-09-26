package local.pad.moonlight;

import java.util.LinkedHashSet;
import java.util.Set;

/** 只保留本会话仍按下的协议键码；不保存文字或输入历史。 */
public final class HeldKeys {
    private final Set<Integer> held = new LinkedHashSet<>();
    public synchronized void record(short key, byte direction, byte flags) {
        int id = ((key & 0xffff) << 8) | (flags & 0xff);
        if (direction == 4) held.remove(id);
        else if (direction == 3 && held.size() < 256) held.add(id);
    }
    public synchronized int[] drain() {
        int[] result = new int[held.size()];
        int i = result.length;
        // 按相反顺序释放，一般先释放普通键，再释放作为前缀按下的修饰键。
        for (int id : held) result[--i] = id;
        held.clear();
        return result;
    }
}
