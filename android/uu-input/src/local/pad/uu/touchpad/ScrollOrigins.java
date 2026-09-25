package local.pad.uu.touchpad;

import java.util.IdentityHashMap;

/** 来源随已入队的事件保留；不同事件即使位移相等，也不能共享来源。 */
public final class ScrollOrigins<T> {
    private final IdentityHashMap<Object, T> pending = new IdentityHashMap<>();

    public synchronized void mark(Object event, T origin) {
        // 正常事件会在当前投递循环中取走，限制异常情况下的残留。
        if (pending.size() >= 256) pending.clear();
        pending.put(event, origin);
    }

    public synchronized T take(Object event, T expected) {
        T found = pending.get(event);
        if (found == null || found != expected) return null;
        pending.remove(event);
        return found;
    }

    public synchronized void discard(T origin) {
        pending.values().removeIf(value -> value == origin);
    }
}
