package com.petricho.threedays;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class MidnightReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (TimerService.current != null) TimerService.current.refresh();
    }
}
