package com.englishstudy.app.data;

import android.content.Context;
import android.content.SharedPreferences;

/** 轻量持久化：全部键存在一个 SharedPreferences 里。 */
public final class Store {

    private static SharedPreferences sp;

    private Store() {
    }

    public static void init(Context c) {
        if (sp == null) {
            sp = c.getApplicationContext().getSharedPreferences("es_store", Context.MODE_PRIVATE);
        }
    }

    public static String get(String key, String def) {
        return sp.getString(key, def);
    }

    public static void put(String key, String value) {
        sp.edit().putString(key, value).apply();
    }

    public static void remove(String key) {
        sp.edit().remove(key).apply();
    }

    public static int getInt(String key, int def) {
        return sp.getInt(key, def);
    }

    public static void putInt(String key, int value) {
        sp.edit().putInt(key, value).apply();
    }

    public static long getLong(String key, long def) {
        return sp.getLong(key, def);
    }

    public static void putLong(String key, long value) {
        sp.edit().putLong(key, value).apply();
    }

    public static boolean getBool(String key, boolean def) {
        return sp.getBoolean(key, def);
    }

    public static void putBool(String key, boolean value) {
        sp.edit().putBoolean(key, value).apply();
    }
}
