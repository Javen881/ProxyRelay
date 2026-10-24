package com.example.proxyrelay;

import android.app.*;
import android.content.*;
import android.os.*;
import java.text.SimpleDateFormat;
import java.util.*;

public class RelayService extends Service {
    public static volatile RelayEngine engine;
    public static volatile boolean running;
    public static volatile String status = "尚未启动";
    private static final ArrayDeque<String> logs = new ArrayDeque<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    public static synchronized void log(String message) {
        logs.addLast(new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date()) + "  " + message);
        while (logs.size() > 120) logs.removeFirst();
    }
    public static synchronized String logs() { return String.join("\n", logs); }
    public static synchronized void clearLogs() { logs.clear(); }
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public int onStartCommand(Intent intent, int flags, int id) {
        if (intent != null && "STOP".equals(intent.getAction())) { stopSelf(); return START_NOT_STICKY; }
        if (running) return START_NOT_STICKY;
        getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel("relay", "代理中继", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, RelayService.class).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE);
        startForeground(1, new Notification.Builder(this, "relay").setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setContentTitle("内网代理中继运行中").setContentText("点击查看状态和日志").setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, "停止中继", stop).build()).setOngoing(true).build());
        running = true; status = "正在启动…";
        SharedPreferences p = getSharedPreferences("config", MODE_PRIVATE);
        RelayEngine current = new RelayEngine(RelayService::log); engine = current;
        new Thread(() -> {
            try {
                current.start(p.getString("bind", "0.0.0.0"), p.getInt("port", 17892), p.getString("host", "192.168.10.3"), p.getInt("upstream", 7892), p.getInt("timeout", 10) * 1000);
                if (!current.isClosed()) {
                    status = "正在监听 · " + p.getString("bind", "0.0.0.0") + ":" + p.getInt("port", 17892);
                    log(status + " → " + p.getString("host", "192.168.10.3") + ":" + p.getInt("upstream", 7892));
                }
            } catch (Exception e) {
                status = "启动失败：" + e.getMessage(); log(status);
                handler.post(() -> { if (engine == current) stopSelf(); });
            }
        }, "relay-start").start();
        handler.postDelayed(monitor, 1500);
        return START_NOT_STICKY;
    }
    private final Runnable monitor = new Runnable() {
        public void run() {
            if (engine != null && engine.isClosed()) { stopSelf(); return; }
            handler.postDelayed(this, 1500);
        }
    };
    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (engine != null) engine.close();
        running = false;
        if (!status.startsWith("启动失败")) status = "已停止";
        log("服务已停止，连接已关闭"); stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy();
    }
}
