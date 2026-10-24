package com.example.proxyrelay;

import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.net.*;
import java.util.*;

public class MainActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<EditText> fields = new ArrayList<>();
    private EditText host, upstream, bind, port, timeout;
    private TextView status, stats, logs;
    private Button start, save;
    private LinearLayout root, addresses;
    private int dp(int v) { return (int)(v * getResources().getDisplayMetrics().density); }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setBackgroundColor(Color.rgb(243,246,248));
        root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(dp(20), dp(20), dp(20), dp(24)); scroll.addView(root); setContentView(scroll);
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom()); return insets;
        });
        text(root, "NETWORK / TCP RELAY", 12, 0xFF147D78);
        text(root, "内网代理中继", 28, 0xFF152C38).setTypeface(null, Typeface.BOLD);
        text(root, "通过手机 VPN，把内网 SOCKS5 分享给热点设备", 14, 0xFF60747F);
        LinearLayout summary = card("运行状态");
        status = text(summary, "尚未启动", 18, 0xFF147D78); stats = text(summary, "", 14, 0xFF60747F);
        start = button(summary, "启动中继", () -> {
            if (!saveConfig()) return;
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1);
            try { startForegroundService(new Intent(this, RelayService.class)); start.setEnabled(false); }
            catch (Exception e) { toast("启动失败：" + e.getMessage()); }
        });
        button(summary, "停止中继", () -> stopService(new Intent(this, RelayService.class)));
        android.content.SharedPreferences p = getSharedPreferences("config", MODE_PRIVATE);
        LinearLayout config = card("中继配置");
        host = field(config, "上游 SOCKS5 · IPv4 地址", p.getString("host", "192.168.10.3"), false);
        upstream = field(config, "上游端口", "" + p.getInt("upstream", 7892), true);
        bind = field(config, "监听 IPv4 地址（0.0.0.0 表示全部接口）", p.getString("bind", "0.0.0.0"), false);
        port = field(config, "本机监听端口", "" + p.getInt("port", 17892), true);
        timeout = field(config, "上游连接超时 / 秒", "" + p.getInt("timeout", 10), true);
        save = button(config, "保存配置", () -> { if (saveConfig()) toast("配置已保存"); });
        text(config, "运行时配置锁定，停止后可修改。SOCKS5 账号密码请在笔记本填写，中继会原样透传。", 13, 0xFF60747F);
        LinearLayout network = card("笔记本连接信息");
        text(network, "代理类型：SOCKS5。选用手机热点对应的 IPv4 地址；通常与笔记本的默认网关一致，勿选 VPN 地址。", 14, 0xFF60747F);
        addresses = new LinearLayout(this); addresses.setOrientation(LinearLayout.VERTICAL); network.addView(addresses);
        button(network, "刷新接口地址", this::refreshAddresses); refreshAddresses();
        LinearLayout help = card("使用提示");
        text(help, "① 先连接手机 VPN，再开启系统热点。\n② VPN 应用分流中允许“内网代理中继”。\n③ 启动中继，笔记本连接热点并设置 SOCKS5。\n④ 内网域名使用代理端 DNS 解析。\n\n仅转发 TCP，不支持 UDP / ping。监听成功不代表内网已连通，也不保证 VPN 断线时阻止直连。监听全部接口时，仅向可信设备开放热点。后台易被关闭时，可在系统电池设置中允许本应用后台运行。", 14, 0xFF60747F);
        LinearLayout logCard = card("运行日志 · 最近 120 条");
        button(logCard, "复制日志", () -> copy(RelayService.logs()));
        button(logCard, "清空日志", RelayService::clearLogs);
        logs = text(logCard, "暂无日志", 12, 0xFF415A68); logs.setTypeface(Typeface.MONOSPACE); logs.setTextIsSelectable(true);
    }
    private LinearLayout card(String title) {
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(16), dp(16), dp(16), dp(16));
        GradientDrawable bg = new GradientDrawable(); bg.setColor(Color.WHITE); bg.setCornerRadius(dp(16)); box.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = dp(16); root.addView(box, lp);
        text(box, title, 17, 0xFF152C38).setTypeface(null, Typeface.BOLD); return box;
    }
    private TextView text(LinearLayout box, String value, int size, int color) {
        TextView v = new TextView(this); v.setText(value); v.setTextSize(size); v.setTextColor(color); v.setPadding(0, dp(6), 0, dp(6)); box.addView(v); return v;
    }
    private EditText field(LinearLayout box, String label, String value, boolean numeric) {
        text(box, label, 13, 0xFF60747F); EditText e = new EditText(this); e.setSingleLine(true);
        e.setInputType(numeric ? 2 : 1); e.setText(value); e.setTextSize(17); box.addView(e); fields.add(e); return e;
    }
    private Button button(LinearLayout box, String title, Runnable action) {
        Button b = new Button(this); b.setText(title); b.setAllCaps(false); box.addView(b); b.setOnClickListener(v -> action.run()); return b;
    }
    private boolean ipv4(String s) {
        String[] parts = s.split("\\.", -1); if (parts.length != 4) return false;
        for (String part : parts) { if (!part.matches("[0-9]{1,3}") || Integer.parseInt(part) > 255) return false; } return true;
    }
    private String value(EditText e) { return e.getText().toString().trim(); }
    private int number(EditText e, int min, int max) {
        int n = Integer.parseInt(value(e)); if (n < min || n > max) throw new IllegalArgumentException(); return n;
    }
    private boolean saveConfig() {
        if (RelayService.running) return false;
        try {
            if (!ipv4(value(host)) || !ipv4(value(bind)) || value(host).equals("0.0.0.0")) { toast("请填写有效的 IPv4 地址；上游不能是 0.0.0.0"); return false; }
            int a = number(upstream, 1, 65535), b = number(port, 1024, 65535), t = number(timeout, 1, 120);
            if (a == b) {
                boolean local = value(host).startsWith("127.");
                for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces()))
                    for (InetAddress address : Collections.list(ni.getInetAddresses())) local |= value(host).equals(address.getHostAddress());
                if (local) { toast("上游不能指向本机监听端口"); return false; }
            }
            getSharedPreferences("config", MODE_PRIVATE).edit().putString("host", value(host)).putString("bind", value(bind)).putInt("upstream", a).putInt("port", b).putInt("timeout", t).apply();
            refreshAddresses(); return true;
        } catch (Exception e) { toast("上游端口 1–65535；监听端口 1024–65535；超时 1–120 秒"); return false; }
    }
    private void refreshAddresses() {
        addresses.removeAllViews();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                        String endpoint = a.getHostAddress() + ":" + getSharedPreferences("config", MODE_PRIVATE).getInt("port",17892);
                        button(addresses, ni.getName() + "  ·  " + endpoint + "  复制", () -> copy(endpoint));
                    }
                }
            }
            if (addresses.getChildCount() == 0) text(addresses, "未发现 IPv4 地址，请开启热点后刷新。", 14, 0xFF60747F);
        } catch (Exception e) { text(addresses, "无法读取接口；请在笔记本查看热点网关 IP。", 14, 0xFF60747F); }
    }
    private void copy(String value) { getSystemService(android.content.ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("代理中继", value)); toast("已复制"); }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }
    private final Runnable refresh = new Runnable() {
        public void run() {
            status.setText(RelayService.status);
            RelayEngine e = RelayService.engine;
            stats.setText(e == null ? "连接 0  ·  ↑ 0 KB  ↓ 0 KB" : String.format(Locale.ROOT, "连接 %d  ·  ↑ %.1f KB  ↓ %.1f KB", e.active.get(), e.uploaded.get()/1024.0, e.downloaded.get()/1024.0));
            for (EditText f : fields) f.setEnabled(!RelayService.running);
            start.setEnabled(!RelayService.running); save.setEnabled(!RelayService.running);
            String s = RelayService.logs(); if (!s.contentEquals(logs.getText())) logs.setText(s.isEmpty() ? "暂无日志" : s);
            handler.postDelayed(this, 1000);
        }
    };
    @Override public void onResume() { super.onResume(); handler.post(refresh); }
    @Override public void onPause() { handler.removeCallbacks(refresh); super.onPause(); }
}
