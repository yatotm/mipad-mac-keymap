package local.pad.uu.touchpad;

import android.app.Activity;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import java.lang.ref.WeakReference;
import java.util.function.Consumer;

/** 功能键使用受系统权限和发送者身份保护的本机消息，不再等待媒体键进入输入视图。 */
public final class RemoteFunctionReceiver extends BroadcastReceiver implements Application.ActivityLifecycleCallbacks {
    private WeakReference<Activity> foreground = new WeakReference<>(null);
    private final Consumer<String> accept;
    private int traceBudget = 12;

    public RemoteFunctionReceiver(Application app, Consumer<String> accept) {
        this.accept = accept;
        app.registerActivityLifecycleCallbacks(this);
        app.registerReceiver(this, new IntentFilter(FnCommand.ACTION), "android.permission.INJECT_EVENTS",
                new Handler(Looper.getMainLooper()), Context.RECEIVER_EXPORTED);
    }

    @Override public void onReceive(Context context, Intent intent) {
        if (!FnCommand.ACTION.equals(intent.getAction())) return;
        Activity activity = foreground.get();
        boolean focused = activity != null && activity.hasWindowFocus();
        int uid = getSentFromUid();
        long when = intent.getLongExtra("when", 0), now = SystemClock.elapsedRealtime();
        if (!FnCommand.valid(uid, intent.getIntExtra("v", 0), when, now, focused)) {
            if (traceBudget-- > 0) Log.i("PadUuTouchpad", "系统 Fn 请求未采用：uid=" + uid
                    + " focused=" + focused + " age=" + (now - when));
            return;
        }
        String action = FnCommand.action(intent.getIntExtra("index", 0));
        if (action == null) return;
        accept.accept(action);
        if (traceBudget-- > 0) Log.i("PadUuTouchpad", "系统 Fn 请求已接收：" + action);
    }

    @Override public void onActivityResumed(Activity activity) {
        if ("com.remote.app.ui.activity.ScreenActivity".equals(activity.getClass().getName())) {
            foreground = new WeakReference<>(activity);
        }
    }
    @Override public void onActivityPaused(Activity activity) {
        if (foreground.get() == activity) foreground.clear();
    }
    @Override public void onActivityCreated(Activity a, Bundle b) {}
    @Override public void onActivityStarted(Activity a) {}
    @Override public void onActivityStopped(Activity a) {}
    @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}
    @Override public void onActivityDestroyed(Activity a) { if (foreground.get() == a) foreground.clear(); }
}
