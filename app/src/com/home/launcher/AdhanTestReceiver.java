package com.home.launcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Test hook: `adb shell am broadcast -n com.home.launcher/.AdhanTestReceiver` makes the guard
 * treat the next adhan as due now. Protected by DUMP, which only the adb shell holds.
 */
public class AdhanTestReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        GuardService g = GuardService.instance;
        if (g != null) g.fireTestNow();
    }
}
