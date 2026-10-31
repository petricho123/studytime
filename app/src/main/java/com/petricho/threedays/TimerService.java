package com.petricho.threedays;

import android.app.AlarmManager;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import java.time.LocalDate;
import java.time.ZoneId;

public class TimerService extends Service {
    static TimerService current;
    static final String START = "com.petricho.threedays.START", PAUSE = "com.petricho.threedays.PAUSE", STOP = "com.petricho.threedays.STOP";
    private static final String CHANNEL = "study_timer";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private StudyApp app;
    private LocalDate shownDate;
    private PendingIntent midnightIntent;
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_TIME_CHANGED.equals(action)) app.reanchorClock();
            if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                // Running continues. An already auto-paused session must never resume on screen-off.
                app.engine.checkpoint(app.now()); app.save();
            }
            if (Intent.ACTION_USER_PRESENT.equals(action)) {
                // Give the activity a chance to resume. Unlocking into another app pauses recording.
                handler.postDelayed(() -> {
                    if (app.activityResumed) {
                        app.engine.returnToApp(app.now()); app.save(); refresh();
                    } else if (isUnlockedAndOn()) autoPause(app.now());
                }, 650);
            }
            refresh();
        }
    };
    private final Runnable heartbeat = new Runnable() {
        @Override public void run() {
            app.engine.checkpoint(app.now()); app.save();
            LocalDate date = LocalDate.now(ZoneId.systemDefault());
            if (!date.equals(shownDate)) refresh();
            handler.postDelayed(this, 15000);
        }
    };
    @Override public void onCreate() {
        super.onCreate(); current = this; app = (StudyApp) getApplication();
        NotificationChannel channel = new NotificationChannel(CHANNEL, "공부시간 기록", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("잠금화면과 알림창에서 오늘 누적 공부시간을 확인합니다.");
        channel.setShowBadge(false); channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF); filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_USER_PRESENT); filter.addAction(Intent.ACTION_TIME_CHANGED);
        filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(receiver, filter);
        handler.postDelayed(heartbeat, 15000);
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        long now = app.now();
        if (START.equals(action)) {
            if (isUnlockedAndOn() && !app.activityResumed) {
                // A resume action in the shade while using another app opens the study screen first.
                app.engine.state = StudyEngine.State.AUTO_PAUSED;
            } else app.engine.start(now);
        } else if (PAUSE.equals(action)) app.engine.pause(false, now);
        else if (STOP.equals(action)) app.engine.stop(now);
        app.save();
        if (app.engine.state == StudyEngine.State.STOPPED) {
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY;
        }
        Notification n = notification();
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(1, n);
        scheduleMidnight();
        return START_NOT_STICKY;
    }
    boolean isUnlockedAndOn() {
        return getSystemService(PowerManager.class).isInteractive()
                && !getSystemService(KeyguardManager.class).isKeyguardLocked();
    }
    void autoPause(long leaveAt) {
        app.engine.leaveScreen(getSystemService(PowerManager.class).isInteractive(),
                getSystemService(KeyguardManager.class).isKeyguardLocked(), Math.max(app.engine.activeStart, leaveAt));
        app.save(); refresh();
    }
    void refresh() {
        if (app.engine.state == StudyEngine.State.STOPPED) {
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return;
        }
        getSystemService(NotificationManager.class).notify(1, notification());
        scheduleMidnight();
    }
    private PendingIntent action(String action, int id) {
        return PendingIntent.getService(this, id, new Intent(this, TimerService.class).setAction(action),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
    private Notification notification() {
        shownDate = LocalDate.now(ZoneId.systemDefault());
        long total = app.engine.totalForDay(shownDate, ZoneId.systemDefault(), app.now());
        boolean running = app.engine.state == StudyEngine.State.RUNNING;
        PendingIntent open = PendingIntent.getActivity(this, 10,
                new Intent(this, MainActivity.class).putExtra("open_study", true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_timer).setContentIntent(open)
                .setContentTitle(running ? "공부시간 기록 중" : app.engine.state == StudyEngine.State.AUTO_PAUSED ? "다른 화면 사용 · 자동 일시정지" : "공부시간 일시정지")
                .setContentText(running ? "오늘 누적 공부시간" : "오늘 누적 " + StudyEngine.clock(total))
                .setOnlyAlertOnce(true).setOngoing(true).setVisibility(Notification.VISIBILITY_PUBLIC)
                .setCategory(Notification.CATEGORY_SERVICE);
        if (running) {
            n.setWhen(System.currentTimeMillis() - total).setShowWhen(true).setUsesChronometer(true);
            n.addAction(new Notification.Action.Builder(null, "일시정지", action(PAUSE, 11)).build());
        } else {
            n.setShowWhen(false);
            // Resume by returning to the app, or directly from the lock screen.
            if (!isUnlockedAndOn()) n.addAction(new Notification.Action.Builder(null, "다시 시작", action(START, 12)).build());
            else n.addAction(new Notification.Action.Builder(null, "앱 열기", open).build());
        }
        n.addAction(new Notification.Action.Builder(null, "종료", action(STOP, 13)).build());
        return n.build();
    }
    private void scheduleMidnight() {
        midnightIntent = PendingIntent.getBroadcast(this, 14, new Intent(this, MidnightReceiver.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        long midnight = LocalDate.now(ZoneId.systemDefault()).plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        getSystemService(AlarmManager.class).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, midnight, midnightIntent);
    }
    @Override public void onTaskRemoved(Intent rootIntent) {
        app.engine.stop(app.now()); app.save(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf();
        super.onTaskRemoved(rootIntent);
    }
    @Override public void onDestroy() {
        if (app.engine.state == StudyEngine.State.RUNNING) app.engine.pause(false, app.now());
        app.save(); handler.removeCallbacksAndMessages(null);
        unregisterReceiver(receiver);
        if (midnightIntent != null) getSystemService(AlarmManager.class).cancel(midnightIntent);
        current = null; super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
