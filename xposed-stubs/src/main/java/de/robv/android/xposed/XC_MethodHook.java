package de.robv.android.xposed;

import java.lang.reflect.Member;

/** 编译期桩：只声明模块代码会用到的成员 */
public abstract class XC_MethodHook {
    public XC_MethodHook() {}

    public XC_MethodHook(int priority) {}

    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {}

    protected void afterHookedMethod(MethodHookParam param) throws Throwable {}

    public static class MethodHookParam {
        public Member method;
        public Object thisObject;
        public Object[] args;

        public Object getResult() { return null; }

        public void setResult(Object result) {}

        public Throwable getThrowable() { return null; }

        public void setThrowable(Throwable throwable) {}

        public void cancel() {}

        public boolean isCanceled() { return false; }
    }

    public static class Unhook {
        public Member getHookedMethod() { return null; }

        public boolean isUnhooked() { return false; }

        public Object unhook() { return null; }
    }
}
