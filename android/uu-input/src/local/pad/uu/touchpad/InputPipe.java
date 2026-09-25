package local.pad.uu.touchpad;

import android.os.SystemClock;
import android.os.Handler;
import android.os.Looper;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import java.io.File;
import java.io.FileDescriptor;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** 通过应用私有 FIFO 与已授权的 ADB 读取端通信；不写日志文件，也不阻塞输入线程。 */
public final class InputPipe {
    private final String path;
    private FileDescriptor output;
    private long retryAt;
    private int modifiers;

    public InputPipe(File directory) throws Exception {
        path = new File(directory, "pad_uu_input.pipe").getAbsolutePath();
        try { Os.mkfifo(path, 0600); }
        catch (ErrnoException error) { if (error.errno != OsConstants.EEXIST) throw error; }
        if (!OsConstants.S_ISFIFO(Os.lstat(path).st_mode)) throw new IllegalStateException("输入通道不是 FIFO");
        Os.chmod(path, 0600);
        Handler timer = new Handler(Looper.getMainLooper());
        timer.post(new Runnable() {
            @Override public void run() {
                heartbeat();
                timer.postDelayed(this, 1000);
            }
        });
    }

    private JSONObject packet(String type) throws Exception {
        return new JSONObject().put("v", 1).put("t", type).put("at", SystemClock.elapsedRealtime());
    }

    private void write(JSONObject packet) throws Exception {
        byte[] data = (packet.toString() + "\n").getBytes(StandardCharsets.UTF_8);
        if (data.length > 4096 || Os.write(output, data, 0, data.length) != data.length) {
            throw new IllegalStateException("输入通道背压或消息过长");
        }
    }

    private boolean open() {
        if (output != null) return true;
        long now = SystemClock.uptimeMillis();
        if (now < retryAt) return false;
        retryAt = now + 250;
        try {
            output = Os.open(path, OsConstants.O_WRONLY | OsConstants.O_NONBLOCK | OsConstants.O_CLOEXEC, 0);
            write(packet("hello").put("mods", modifiers));
            return true;
        } catch (Exception error) { close(); return false; }
    }

    private boolean send(JSONObject packet) {
        if (!open()) return false;
        try { write(packet); return true; }
        catch (Exception error) { close(); return false; }
    }

    private void close() {
        if (output != null) try { Os.close(output); } catch (Exception ignored) {}
        output = null;
    }

    public synchronized boolean action(String action) {
        try { return send(packet("action").put("a", action)); }
        catch (Exception ignored) { return false; }
    }

    public synchronized void modifiers(int value) {
        if (modifiers == value) return;
        modifiers = value;
        try { send(packet("meta").put("mods", value)); } catch (Exception ignored) {}
    }

    private synchronized void heartbeat() {
        // 心跳不落盘；让读取端识别断线，也让残留的旧读取进程及时退出。
        try { send(packet("sync").put("mods", modifiers)); } catch (Exception ignored) {}
    }

    public synchronized boolean scroll(int x, int y, String phase) {
        try { return send(packet("scroll").put("x", x).put("y", y).put("phase", phase).put("mods", modifiers)); }
        catch (Exception ignored) { return false; }
    }

    public synchronized void reset() {
        modifiers = 0;
        try { send(packet("reset")); } catch (Exception ignored) {}
    }
}
