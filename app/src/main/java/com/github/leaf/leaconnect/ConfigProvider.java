package com.github.leaf.leaconnect;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Binder;
import android.os.Process;
import android.os.SystemClock;

/**
 * 模块 UI 与蓝牙进程之间的通道：普通应用写不了 persist.* 属性，
 * 所以开关存在这里，被 hook 的 com.android.bluetooth 通过 call() 读。
 *
 * 顺手把这里当成"模块到底加载没加载"的唯一可靠证据源：蓝牙进程每 3 秒最多读一次配置，
 * 每一次跨进程的 get 都记一笔心跳。UI 因此可以区分三种状态 ——
 * 从没收到过心跳 = 模块没被加载（LSPosed 里没启用，或作用域不含蓝牙）；
 * 心跳很旧 = 加载过但蓝牙进程被杀了；心跳新鲜 = 真的在生效。
 * 比自己探测进程/反射猜可靠，因为这正是 hook 生效路径上必然发生的一次调用。
 */
public class ConfigProvider extends ContentProvider {

    public static final String AUTHORITY = "com.github.leaf.leaconnect.config";
    private static final String PREFS = "cfg";
    private static final String HB_AT = "_hb_at";
    private static final String HB_UID = "_hb_uid";
    private static final String HB_N = "_hb_n";

    @Override
    public boolean onCreate() {
        return true;
    }

    private SharedPreferences prefs() {
        return getContext().getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE);
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Bundle out = new Bundle();
        if ("get".equals(method)) {
            int uid = Binder.getCallingUid();
            long now = SystemClock.elapsedRealtime();
            if (uid != Process.myUid() && arg != null && !arg.startsWith("_")
                    && now - prefs().getLong(HB_AT, -1L) > 1000L) {
                // 只有别人（=被 hook 的进程）来读才算心跳；UI 自己读不是。
                // 限流 1 秒一次：Core 每个 3 秒窗口要读十几个键，别把写盘变成心跳本身。
                SharedPreferences.Editor e = prefs().edit();
                e.putLong(HB_AT, now);
                e.putInt(HB_UID, uid);
                e.putInt(HB_N, prefs().getInt(HB_N, 0) + 1);
                e.apply();
            }
            out.putString("value", prefs().getString(arg, null));
            return out;
        }
        if ("set".equals(method)) {
            String v = extras == null ? null : extras.getString("value");
            SharedPreferences.Editor e = prefs().edit();
            if (v == null) {
                e.remove(arg);
            } else {
                e.putString(arg, v);
            }
            e.apply();
            return out;
        }
        if ("keys".equals(method)) {
            out.putString("list", String.join(",", prefs().getAll().keySet()));
            return out;
        }
        if ("reset".equals(method)) {
            // 恢复默认 = 把用户动过的键全部擦掉，Core 那边自然回落到各自默认值
            SharedPreferences.Editor e = prefs().edit();
            for (String k : prefs().getAll().keySet()) {
                if (!k.startsWith("_")) {
                    e.remove(k);
                }
            }
            e.apply();
            return out;
        }
        if ("state".equals(method)) {
            SharedPreferences p = prefs();
            out.putLong("hb_at", p.getLong(HB_AT, -1L));
            out.putInt("hb_uid", p.getInt(HB_UID, -1));
            out.putInt("hb_n", p.getInt(HB_N, 0));
            out.putLong("now", SystemClock.elapsedRealtime());
            return out;
        }
        return out;
    }

    @Override
    public Cursor query(Uri uri, String[] p, String s, String[] sa, String so) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String s, String[] sa) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues v, String s, String[] sa) {
        return 0;
    }
}
