package de.robv.android.xposed;

import java.lang.reflect.Member;

/** 编译期桩，签名取自设备上正常工作的模块 dex（描述符已用 dexdump 核对） */
public final class XposedBridge {
    public static ClassLoader BOOTCLASSLOADER;

    public static XC_MethodHook.Unhook hookMethod(Member hookMethod, XC_MethodHook callback) {
        throw new UnsupportedOperationException("stub");
    }

    public static int getXposedVersion() {
        return 0;
    }

    public static void log(String text) {}

    public static void log(Throwable t) {}
}
