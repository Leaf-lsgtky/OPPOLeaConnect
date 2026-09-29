package io.qoder.oppoemu;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

/**
 * 模块 UI 与蓝牙进程之间的通道：普通应用写不了 persist.* 属性，
 * 所以开关存在这里，被 hook 的 com.android.bluetooth 通过 call() 读。
 */
public class ConfigProvider extends ContentProvider {

    public static final String AUTHORITY = "io.qoder.oppoemu.config";
    private static final String PREFS = "cfg";

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
