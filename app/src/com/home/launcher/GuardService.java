package com.home.launcher;

import android.accessibilityservice.AccessibilityService;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;

/**
 * Google TV's home screen can't be replaced on this TV (it has system priority), so this
 * watches it instead: whenever Google TV shows any tab other than "Live", it switches to our
 * home screen. That keeps the Live tab (Freeplay channels) usable while hiding the banners.
 *
 * It also plays the adhan at MCA's adhan times, but only when our home screen is what's on the
 * TV (or the screensaver came on over it) - never over another app, and never in standby.
 */
public class GuardService extends AccessibilityService {
    private static final String TAG = "HomeGuard";
    private static final long LONG_PRESS_MS = 600;
    private static final String SCREENSAVER = "com.google.android.apps.tv.dreamx";
    private long lastRedirect, homeDownAt;
    /** Last full-screen app window, ignoring the screensaver and system overlays. */
    private String lastWindow = "";
    private final Handler clock = new Handler(Looper.getMainLooper());
    private Prayer prayer;
    private long prayerLoadedAt;
    /** Set by AdhanTestReceiver (adb only): treat the next adhan as due now. */
    static volatile boolean testFire;

    private final Runnable adhanCheck = new Runnable() {
        @Override
        public void run() {
            checkAdhan();
            clock.postDelayed(this, 20000);
        }
    };

    @Override
    protected void onServiceConnected() {
        Log.i(TAG, "connected");
        // Connects at boot: start on our screen rather than Google TV's.
        if (SystemClock.elapsedRealtime() < 5 * 60 * 1000) HomeActivity.open(this);
        clock.removeCallbacks(adhanCheck);
        clock.post(adhanCheck);
    }

    void fireTestNow() {
        testFire = true;
        clock.post(this::checkAdhan);
    }

    static GuardService instance;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
    }

    @Override
    public void onDestroy() {
        instance = null;
        clock.removeCallbacks(adhanCheck);
        super.onDestroy();
    }

    private void checkAdhan() {
        long nowMs = System.currentTimeMillis();
        if (prayer == null || nowMs - prayerLoadedAt > 3600 * 1000L) {
            prayer = new Prayer(this);       // re-read the cache the home screen keeps fresh
            prayerLoadedAt = nowMs;
        }
        java.util.Calendar now = java.util.Calendar.getInstance();
        Prayer.Day d = prayer.day(now);
        if (d == null) return;
        int mins = now.get(java.util.Calendar.HOUR_OF_DAY) * 60 + now.get(java.util.Calendar.MINUTE);
        boolean test = testFire;
        testFire = false;
        for (int i : new int[]{0, 2, 3, 4, 5}) {              // skip Sunrise
            if (test ? d.adhan[i] < mins : d.adhan[i] != mins) continue;
            String key = Prayer.key(now) + "#" + i;
            android.content.SharedPreferences p = getSharedPreferences("adhan", 0);
            if (!test && key.equals(p.getString("last", ""))) return;
            if (!test) p.edit().putString("last", key).apply();
            boolean awake = ((PowerManager) getSystemService(POWER_SERVICE)).isInteractive();
            boolean onHome = lastWindow.equals(HomeActivity.class.getName());
            Log.i(TAG, "adhan time " + Prayer.NAMES[i] + " awake=" + awake + " window=" + lastWindow);
            if (awake && onHome) {
                boolean friday = now.get(java.util.Calendar.DAY_OF_WEEK) == java.util.Calendar.FRIDAY;
                HomeActivity.openAdhan(this, friday && i == 2 ? "Jumu'ah" : Prayer.NAMES[i], i, d);
            }
            return;
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e.getPackageName() == null) return;
        String pkg = e.getPackageName().toString();
        if (e.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && e.getClassName() != null
                && !pkg.equals(SCREENSAVER) && !pkg.equals("com.android.systemui")
                && !pkg.startsWith("com.google.android.inputmethod")) {
            String cls = e.getClassName().toString();
            // Framework classes are popups/widgets over the current screen; app classes are screens.
            if (!cls.startsWith("android.")) lastWindow = cls;
        }
        if (!HomeActivity.LAUNCHERX.equals(pkg)) return;
        long now = System.currentTimeMillis();
        if (now - lastRedirect < 1500) return;

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || root.getPackageName() == null
                || !HomeActivity.LAUNCHERX.contentEquals(root.getPackageName())) return;

        String tab = selectedTab(root);
        if (tab == null || tab.equals("Live")) return;          // Live tab or full-screen player: allow
        if (now - HomeActivity.liveOpenedAt < 4000) return;    // Live tab still loading after our tile
        Log.i(TAG, "Google TV on '" + tab + "' tab -> home");
        lastRedirect = now;
        HomeActivity.open(this);
    }

    private static String selectedTab(AccessibilityNodeInfo root) {
        for (String t : new String[]{"Home", "Live", "Apps", "TCL", "Gemini", "For you", "Library"}) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(t);
            if (nodes == null) continue;
            for (AccessibilityNodeInfo n : nodes) {
                if (n.getText() != null && t.contentEquals(n.getText()) && n.isSelected()) return t;
            }
        }
        return null;
    }

    @Override
    protected boolean onKeyEvent(KeyEvent e) {
        // Only used if this TV delivers remote keys to accessibility services.
        if (e.getKeyCode() != KeyEvent.KEYCODE_HOME) return false;
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            if (e.getRepeatCount() == 0) homeDownAt = SystemClock.uptimeMillis();
        } else if (e.getAction() == KeyEvent.ACTION_UP) {
            if (SystemClock.uptimeMillis() - homeDownAt >= LONG_PRESS_MS) {
                performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS);
            } else {
                HomeActivity.open(this);
            }
        }
        return true;
    }

    @Override
    public void onInterrupt() {
    }
}
