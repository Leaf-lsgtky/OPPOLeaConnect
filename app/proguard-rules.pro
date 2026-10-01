# LSPosed 按 META-INF/xposed/java_init.list 里的类名 Class.forName 加载入口，
# 类名一旦被 R8 改名，模块就静默不加载 —— 这两条 keep 是必须的。
-keep class com.github.leaf.leaconnect.Core {
    <init>();
    public void handleLoadPackage(...);
}
-keep class com.github.leaf.leaconnect.ConfigProvider

# Core 全靠反射摸框架成员：Class.forName("com.android.bluetooth...")、
# getDeclaredField/getDeclaredMethod、以及 invokeByName 按名字找方法。
# 框架类不在 APK 里，R8 看不到它们，警告直接忽略即可。
-dontwarn de.robv.android.xposed.**
-dontwarn com.android.bluetooth.**
-dontwarn android.**
