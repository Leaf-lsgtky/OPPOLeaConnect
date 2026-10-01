package de.robv.android.xposed.callbacks;

/** 编译期桩：真实类由 LSPosed 提供，字段/包名按设备上已验证的 dex 描述符书写 */
public abstract class XC_LoadPackage {
    public static class LoadPackageParam {
        public String packageName;
        public String processName;
        public ClassLoader classLoader;
        public android.content.pm.ApplicationInfo appInfo;
    }
}
