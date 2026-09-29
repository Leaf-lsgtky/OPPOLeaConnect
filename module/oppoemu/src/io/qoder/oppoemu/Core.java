package io.qoder.oppoemu;

import android.bluetooth.BluetoothDevice;
import de.robv.android.xposed.XC_MethodHook.MethodHookParam;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 让非 OPPO 手机用 OPPO/OnePlus 耳机的 LE Audio(LC3)。
 *
 * 四道门控（全部来自 out/java/xiaomi_bt 的反编译证据）：
 *   1) PhonePolicy.processActiveDeviceChanged:810-834 —— LE Audio 组活跃时把整组的 a2dp/hfp/asha 写成 FORBIDDEN
 *   2) A2dpService.connect:1258-1263 —— dual mode 开着时，CSIP 组成员的手机侧 A2DP 连接被拒并回写 FORBIDDEN
 *   3) LeAudioService 里 LeAudioGroupDescriptor.mAvailableContexts 在此 build 从无赋值 —— isGroupAvailableForStream()
 *      恒 false，CIG/CIS 永不建立
 *   4) 开盖瞬间 BR/EDR page scan 立即应答，经典先连上；耳机一旦有经典链路就不再发 LE 广播 —— LE 永远抢不到窗口
 *
 * 本模块不轮询也不设定时器：所有动作挂在系统自己的事件上（okToConnect / deviceConnected /
 * deviceDisconnected / native LeAudioStackEvent）。LE 优先的释放条件是"LE 状态机自己那一次
 * 30 秒尝试结束"（le_audio/LeAudioStateMachine.java:31 CONNECT_TIMEOUT），不是我数秒。
 */
public class Core implements de.robv.android.xposed.IXposedHookLoadPackage {

    private static final String TAG = "OppoEmu";
    private static final String BT_PKG = "com.android.bluetooth";
    private static final String BT_UID = "1002";

    private static final String CL_SM = "com.android.bluetooth.hfp.HeadsetStateMachine";
    private static final String CL_CONNECTED = "com.android.bluetooth.hfp.HeadsetStateMachine$Connected";
    private static final String CL_NI = "com.android.bluetooth.hfp.HeadsetNativeInterface";
    private static final String CL_AS = "com.android.bluetooth.btservice.AdapterService";
    private static final String CL_UTILS = "com.android.bluetooth.Utils";
    private static final String CL_A2DP = "com.android.bluetooth.a2dp.A2dpService";
    private static final String CL_HS = "com.android.bluetooth.hfp.HeadsetService";
    private static final String CL_LEA = "com.android.bluetooth.le_audio.LeAudioService";
    private static final String CFG_URI = "content://io.qoder.oppoemu.config";

    private static final int POLICY_FORBIDDEN = 0;
    private static final int POLICY_ALLOWED = 100;
    private static final int AT_OK = 1;

    /** BluetoothProfile 的 profile id：connectEnabledProfiles 里 A2DP=2、HFP=1 */
    private static final int PROFILE_A2DP = 2;
    private static final int PROFILE_HFP = 1;
    private static final int PROFILE_LE_AUDIO = 22;

    /** LeAudioStateMachine.getConnectionState() 只用 BluetoothProfile 那四个值（0/1/2/3） */
    private static final int LEA_DISCONNECTED = 0;
    private static final int LEA_CONNECTING = 1;
    private static final int LEA_CONNECTED = 2;
    private static final int LEA_DISCONNECTING = 3;

    private static Handler MAIN;
    private static Object sCtx;

    // 生效开关（provider 优先，其次 persist.oppoemu.*，最后默认值）
    private static boolean sAt = true;
    private static boolean sVdsp = true;
    private static boolean sLeFirst = true;
    private static boolean sGateHfp = false;
    private static boolean sPoke = true;
    private static boolean sAdoptCtx = true;
    private static int sOesfMask = 0x3F;
    private static String sVendorId = "1946";
    private static String sVdsf = "7";
    private static long sVdspDelay = 500L;

    // 目标成员
    private static Field sFieldDevice;
    private static Field sFieldNative;
    private static Method sMethodAtString;
    private static Method sMethodAtCode;
    private static Object sLeaRef;
    private static Object sA2dpRef;
    private static Object sHsRef;

    // LE 优先：纯状态驱动，一轮 = 耳机的一次来向，释放条件是 LE 状态机自己那 30 秒尝试结束
    private static final Set<String> sLePoked = new HashSet<String>();     // 本轮已经让 LE 试过
    private static final Set<String> sLeStarved = new HashSet<String>();   // 本轮 LE 没到手，经典放行
    private static final Set<String> sUserOffLea = new HashSet<String>();
    private static final Set<String> sUserOffA2dp = new HashSet<String>();
    private static final Set<String> sUserOffHfp = new HashSet<String>();
    private static final Set<String> sBuds = new HashSet<String>();
    private static final Set<String> sActed = new HashSet<String>();
    /** 当前音频面：addr -> "brd"（引导，经典允许） / "lea"（LE Audio 独占） */
    private static final Map<String, String> sPlane = new HashMap<String, String>();
    /** 地址 -> 我们挂上去的 BluetoothGatt（只为占住 hold-link，永不 close） */
    private static final Map<String, Object> sHeld = new HashMap<String, Object>();
    private static final Map<String, Integer> sBootTries = new HashMap<String, Integer>();
    private static final int MAX_BOOTSTRAP = 3;

    @Override
    public void handleLoadPackage(final de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam lpp) {
        if (!BT_PKG.equals(lpp.packageName)) {
            return;
        }
        MAIN = new Handler(Looper.getMainLooper());
        refreshConfig();
        log("load: " + configLine());

        bindMembers(lpp.classLoader);
        hookUnknownAt(lpp.classLoader);
        hookSlcConnected(lpp.classLoader);
        hookBlockForbid(lpp.classLoader);
        hookPolicyRepair(lpp.classLoader);
        hookClassicGate(lpp.classLoader);
        hookLeaEvents(lpp.classLoader);
        hookAdapterReady(lpp.classLoader);
    }

    // ---------------------------------------------------------------- 开关

    private static void refreshConfig() {
        sAt = cfgBool("at", true);
        sVdsp = cfgBool("vdsp", true);
        sLeFirst = cfgBool("le_first", true);
        sGateHfp = cfgBool("gate_hfp", false);
        sPoke = cfgBool("poke", true);
        sAdoptCtx = cfgBool("adopt_ctx", true);
        sOesfMask = cfgInt("oesf_mask", 0x3F);
        sVendorId = cfg("vendor_id", "1946");
        sVdsf = cfg("vdsf", "7");
        sVdspDelay = (long) cfgInt("vdsp_delay", 500);
    }

    private static String configLine() {
        return "le_first=" + sLeFirst + " gate_hfp=" + sGateHfp
                + " poke=" + sPoke + " adopt_ctx=" + sAdoptCtx + " at=" + sAt + " vdsp=" + sVdsp
                + " oesf_mask=0x" + Integer.toHexString(sOesfMask);
    }

    // ---------------------------------------------------------------- 反射绑定

    private static void bindMembers(ClassLoader cl) {
        try {
            Class<?> sm = Class.forName(CL_SM, false, cl);
            sFieldDevice = sm.getDeclaredField("mDevice");
            sFieldNative = sm.getDeclaredField("mNativeInterface");
            sFieldDevice.setAccessible(true);
            sFieldNative.setAccessible(true);
            Class<?> ni = Class.forName(CL_NI, false, cl);
            sMethodAtString = ni.getDeclaredMethod("atResponseString",
                    BluetoothDevice.class, String.class);
            sMethodAtCode = ni.getDeclaredMethod("atResponseCode",
                    BluetoothDevice.class, int.class, int.class);
            sMethodAtString.setAccessible(true);
            sMethodAtCode.setAccessible(true);
            log("bind ok: HeadsetStateMachine.mDevice/mNativeInterface, "
                    + "HeadsetNativeInterface.atResponseString/atResponseCode");
        } catch (Throwable t) {
            log("bind 失败: " + t);
        }
    }

    private static Method findMethod(ClassLoader cl, String className, String name, Class<?>... types) {
        try {
            Class<?> c = Class.forName(className, false, cl);
            while (c != null && c != Object.class) {
                try {
                    Method m = c.getDeclaredMethod(name, types);
                    m.setAccessible(true);
                    return m;
                } catch (NoSuchMethodException ignored) {
                }
                c = c.getSuperclass();
            }
        } catch (Throwable t) {
            log("findMethod " + className + "." + name + " 异常: " + t);
        }
        return null;
    }

    /**
     * 这里**不再**碰 Utils.isDualModeAudioEnabled()。
     * 钉成 true 曾经是"共存"思路的拐杖（免掉 PhonePolicy 拆经典），代价是
     * A2dpService.java:1260-1266 / HeadsetService.java:1032-1038 的 CSIP 拒绝分支永远成立 ——
     * 手机端所有主动 connect() 必被拒，还把刚建好的经典 ACL 因"没有 profile 通道"闲置拆掉
     * （实测 04:40:22.824 -> 04:40:23.167、04:48:05.102 -> 04:48:05.602）。
     * 现在的模型是"经典只用于引导，翻面之后就让位给 LE Audio"，见 plane_flip。
     */

    // ------------------------------------------------- 1) OPPO 私有厂商 AT 通道

    private void hookUnknownAt(ClassLoader cl) {
        final Method m = findMethod(cl, CL_SM, "processUnknownAt",
                String.class, BluetoothDevice.class);
        if (m == null) {
            log("hook 跳过: 没有 HeadsetStateMachine.processUnknownAt(String, BluetoothDevice)");
            return;
        }
        de.robv.android.xposed.XposedBridge.hookMethod(m, new Hook() {
            @Override
            protected void before(MethodHookParam p) {
                try {
                    if (!cfgBool("at", true)) {
                        return;
                    }
                    String at = normalize((String) p.args[0]);
                    if (at == null || at.isEmpty()) {
                        return;
                    }
                    BluetoothDevice dev = (BluetoothDevice) p.args[1];
                    String reply = buildReply(at);
                    log("AT 收到: " + at + " from " + dev);
                    if (reply == null || sMethodAtString == null || sMethodAtCode == null) {
                        return;
                    }
                    Object ni = sFieldNative.get(p.thisObject);
                    if (ni == null) {
                        return;
                    }
                    sMethodAtString.invoke(ni, dev, reply);
                    sMethodAtCode.invoke(ni, dev, AT_OK, 0);
                    log("答 " + at + " -> " + reply + " (+OK)");
                    p.setResult(null);
                } catch (Throwable t) {
                    log("processUnknownAt 异常: " + t);
                }
            }
        });
        log("hook ok: HeadsetStateMachine.processUnknownAt");
    }

    private static String buildReply(String at) {
        if (at.startsWith("+VDID")) {
            return "+VDID: " + sVendorId;
        }
        if (at.startsWith("+VDSF")) {
            return "+VDSF: " + sVdsf;
        }
        if (at.startsWith("+OESF")) {
            int[] kv = twoInts(at);
            return kv == null ? "+OESF=0,0" : "+OESF=" + kv[0] + "," + (kv[1] & sOesfMask);
        }
        if (at.startsWith("+VDSP")) {
            return "+VDSP=1,1";
        }
        return null;
    }

    private void hookSlcConnected(ClassLoader cl) {
        final Method m = findMethod(cl, CL_CONNECTED, "enter");
        if (m == null) {
            log("hook 跳过: 没有 HeadsetStateMachine$Connected.enter()");
            return;
        }
        de.robv.android.xposed.XposedBridge.hookMethod(m, new Hook() {
            @Override
            protected void after(MethodHookParam p) {
                try {
                    if (!cfgBool("vdsp", true)) {
                        return;
                    }
                    final Object sm = outerOf(p.thisObject);
                    if (sm == null || sMethodAtString == null) {
                        return;
                    }
                    final Object ni = sFieldNative.get(sm);
                    final Object raw = sFieldDevice.get(sm);
                    if (ni == null || !(raw instanceof BluetoothDevice)) {
                        return;
                    }
                    final BluetoothDevice bud = (BluetoothDevice) raw;
                    MAIN.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                sMethodAtString.invoke(ni, bud, "+VDSP=1,1");
                                log("已下发 +VDSP=1,1（HFP SLC 之后）");
                            } catch (Throwable t) {
                                log("下发 VDSP 失败: " + t);
                            }
                            // ColorOS 的时序：+VDSP 是"把耳机的音频面翻到 LE Audio"的命令，
                            // 翻完就该让经典让位（OplusLeAudioServiceExt.java:328-334 发命令、
                            // OplusPhonePolicyExtImpl.java:299 disconnectAcl(BREDR)）。
                            flipToLea(bud);
                        }
                    }, sVdspDelay);
                } catch (Throwable t) {
                    log("Connected.enter 异常: " + t);
                }
            }
        });
        log("hook ok: HeadsetStateMachine$Connected.enter");
    }

    // ------------------------------------------- 2) 拦掉系统内部的 FORBIDDEN 回写

    private void hookBlockForbid(ClassLoader cl) {
        blockForbidIn(cl, CL_A2DP);
        blockForbidIn(cl, CL_HS);
        blockForbidIn(cl, CL_LEA);
    }

    private void blockForbidIn(ClassLoader cl, String className) {
        final String tag = className;
        Method m = findMethod(cl, className, "setConnectionPolicy",
                BluetoothDevice.class, int.class);
        if (m == null) {
            log("hook 跳过: " + className + " 没有 setConnectionPolicy(dev,int)");
            return;
        }
        de.robv.android.xposed.XposedBridge.hookMethod(m, new Hook() {
            @Override
            protected void before(MethodHookParam p) {
                try {
                    BluetoothDevice dev = (BluetoothDevice) p.args[0];
                    if (dev == null || !isBud(dev)) {
                        return;
                    }
                    int policy = toInt(p.args[1], POLICY_ALLOWED);
                    int uid = android.os.Binder.getCallingUid();
                    String who = whoCalled();
                    // uid 不可靠：HyperOS 把设置的 binder 帧一路带在 connect() 调用链上，
                    // CSIP 门（HeadsetService.java:1032-1038 / A2dpService.java:1260-1266）的回写
                    // 也会被算成 uid=1000。真判据是栈：栈里有 profile 自己的 connect() 或 PhonePolicy
                    // 就是系统自动回写，只有纯 binder 入口才是用户在设置里点的。
                    boolean userAction = !BT_UID.equals(String.valueOf(uid))
                            && !who.contains("Service.connect:") && !who.contains("PhonePolicy");
                    if (userAction) {
                        // 外部（设置界面点击）= 用户意图，放行并记住，别再自作主张改回去
                        remember(sUserOffLea, tag.contains("le_audio"), policy, "LE Audio", dev, uid);
                        remember(sUserOffA2dp, tag.contains(".a2dp."), policy, "媒体音频(a2dp)", dev, uid);
                        remember(sUserOffHfp, tag.contains(".hfp."), policy, "通话音频(hfp)", dev, uid);
                        return;
                    }
                    if (policy != POLICY_FORBIDDEN) {
                        return;
                    }
                    if (tag.contains("le_audio") && sUserOffLea.contains(dev.getAddress())) {
                        return;   // 用户要关，不拦
                    }
                    if (!tag.contains("le_audio") && cfgBool("plane_flip", false)) {
                        // 互斥模式下"禁经典"是我们要的稳态，交给系统自己写
                        return;
                    }
                    // 不能改成 ALLOWED 再放行：内部 ALLOWED 写入会 connect()，
                    // connect() 又回写 FORBIDDEN —— 死循环。吞掉即可。
                    p.setResult(Boolean.TRUE);
                    log("吞掉 FORBIDDEN 回写: " + shortName(tag) + " " + dev
                            + " uid=" + uid + " 来自 " + who);
                } catch (Throwable t) {
                    log("blockForbid 异常: " + t);
                }
            }
        });
        log("hook ok: " + shortName(tag) + ".setConnectionPolicy 拦内部 FORBIDDEN");
    }

    /** 外部写入代表用户意图：关掉就记住，模块绝不替他打开 */
    private static void remember(Set<String> off, boolean mine, int policy, String name,
                                 BluetoothDevice dev, int uid) {
        if (!mine) {
            return;
        }
        if (policy == POLICY_FORBIDDEN) {
            off.add(dev.getAddress());
            log("用户在设置里关闭 " + name + "(uid=" + uid + "): " + dev);
        } else if (policy == POLICY_ALLOWED) {
            off.remove(dev.getAddress());
            log("用户在设置里打开 " + name + "(uid=" + uid + "): " + dev);
        }
    }

    // ------------------------------- 4) 事件驱动的 LE 优先窗口（经典来向直接被拒）

    private void hookClassicGate(ClassLoader cl) {
        Method a2 = findMethod(cl, CL_A2DP, "okToConnect", BluetoothDevice.class, boolean.class);
        if (a2 != null) {
            de.robv.android.xposed.XposedBridge.hookMethod(a2, new Hook() {
                @Override
                protected void before(MethodHookParam p) {
                    gate(p, "A2DP", p.args[0]);
                }
            });
            log("hook ok: A2dpService.okToConnect");
        } else {
            log("hook 跳过: A2dpService.okToConnect");
        }
        Method hs = findMethod(cl, CL_HS, "okToAcceptConnection", BluetoothDevice.class, boolean.class);
        if (hs != null) {
            de.robv.android.xposed.XposedBridge.hookMethod(hs, new Hook() {
                @Override
                protected void before(MethodHookParam p) {
                    gate(p, "HFP", p.args[0]);
                }
            });
            log("hook ok: HeadsetService.okToAcceptConnection");
        } else {
            log("hook 跳过: HeadsetService.okToAcceptConnection");
        }
    }

    /**
     * 经典想来接的时候：本轮先给 LE 一次机会（推 LeAudioService.connect() 并拒掉这次经典）；
     * LE 正在尝试（状态机 state=1，它自己会在 30 秒后超时回 0）就继续拒；LE 这一次没到手就放行经典，
     * 并在本轮（到下一次 LE 连上/断开为止）不再拦。全程只在事件里判断，不设定时器、不主动拨经典。
     */
    private void gate(MethodHookParam p, String which, Object arg0) {
        try {
            if (!(arg0 instanceof BluetoothDevice)) {
                return;
            }
            BluetoothDevice dev = (BluetoothDevice) arg0;
            if (!isBud(dev)) {
                return;
            }
            repairClassicPolicy(dev, which + " 来向");
            if (cfgBool("plane_flip", false)
                    || !cfgBool("le_first", true) || !wantLea(dev)) {
                return;
            }
            if (which.equals("HFP") && !cfgBool("gate_hfp", true)) {
                return;
            }
            String addr = dev.getAddress();
            int st = leaState(dev);
            if (st == LEA_CONNECTED || st == LEA_DISCONNECTING || sLeStarved.contains(addr)) {
                return;
            }
            if (st == LEA_CONNECTING) {
                if (a2dpConnected(dev) || hfpConnected(dev)) {
                    sLeStarved.add(addr);
                    log("经典已经在线，LE 还挂着 state=1 -> 本轮不再拒经典: " + dev);
                    return;
                }
                refuse(p, dev, which, "LE 正在尝试(state=1)");
                return;
            }
            if (sLePoked.contains(addr)) {
                sLeStarved.add(addr);
                log("LE 那一次尝试没到手(state=" + st + ")，本轮放行经典: " + dev);
                return;
            }
            if (!cfgBool("poke", true) || leaSvc() == null) {
                return;
            }
            sLePoked.add(addr);
            refuse(p, dev, which, "窗口先让给 LE");
            log("推 LE Audio 一次 connect(): " + dev);
            invokeByName(leaSvc(), "connect", dev);
        } catch (Throwable t) {
            log("gate 异常: " + t);
        }
    }

    /** 事件级拒绝：不改落盘策略，避免和系统自己的自愈逻辑互相打架 */
    private static void refuse(MethodHookParam p, BluetoothDevice dev, String which, String why) {
        p.setResult(Boolean.FALSE);
        log("拒绝 " + which + "（" + why + "）: " + dev);
    }

    /** 一轮结束：LE 到手或断开，下一轮重新给 LE 机会 */
    private static void endRound(BluetoothDevice dev) {
        String addr = dev.getAddress();
        sLePoked.remove(addr);
        sLeStarved.remove(addr);
    }


    /** 只有 LE Audio 组两只都到位才设活跃设备，并且要设成组的 lead（主耳） */
    private static void setActiveLead() {
        if (leaSvc() == null) {
            return;
        }
        try {
            Object all = invokeByName(leaSvc(), "allLeAudioDevicesConnected");
            if (!Boolean.TRUE.equals(all)) {
                log("组还没配齐（allLeAudioDevicesConnected=" + all + "），先不 setActiveDevice");
                return;
            }
            Object gid = invokeByName(leaSvc(), "getActiveGroupId");
            Object lead = gid == null ? null : invokeByName(leaSvc(), "getConnectedGroupLeadDevice", gid);
            if (!(lead instanceof BluetoothDevice)) {
                log("拿不到组的 lead，跳过 setActiveDevice");
                return;
            }
            log("setActiveDevice(组的 lead/主耳): " + lead);
            invokeByName(leaSvc(), "setActiveDevice", lead);
        } catch (Throwable t) {
            log("setActiveLead 异常: " + t);
        }
    }

    // ------------------------------------ 3) LE Audio 事件：连上/断开/native 上下文

    private void hookLeaEvents(ClassLoader cl) {
        Method dc = findMethod(cl, CL_LEA, "deviceConnected", BluetoothDevice.class);
        if (dc != null) {
            de.robv.android.xposed.XposedBridge.hookMethod(dc, new Hook() {
                @Override
                protected void after(MethodHookParam p) {
                    if (p.args[0] instanceof BluetoothDevice && isBud((BluetoothDevice) p.args[0])) {
                        BluetoothDevice dev = (BluetoothDevice) p.args[0];
                        log("LE Audio 已连接（deviceConnected）: " + dev);
                        endRound(dev);
                        holdGatt(dev);
                        if (allLeaUp()) {
                            sBootTries.remove(dev.getAddress());   // 两只都到位 = 新会话，配额重置
                        }
                        if (cfgBool("adopt_ctx", true) && sAdoptedMask != 0) {
                            adoptGroupContexts(dev);
                        }
                        if (sActed.add(dev.getAddress())) {
                            MAIN.postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                    setActiveLead();
                                }
                            }, 1500L);
                        }
                    }
                }
            });
            log("hook ok: LeAudioService.deviceConnected");
        }
        Method dd = findMethod(cl, CL_LEA, "deviceDisconnected", BluetoothDevice.class, boolean.class);
        if (dd != null) {
            de.robv.android.xposed.XposedBridge.hookMethod(dd, new Hook() {
                @Override
                protected void after(MethodHookParam p) {
                    if (p.args[0] instanceof BluetoothDevice && isBud((BluetoothDevice) p.args[0])) {
                        BluetoothDevice dev = (BluetoothDevice) p.args[0];
                        sActed.remove(dev.getAddress());
                        endRound(dev);
                        // 不在这里重新拦经典：否则 LE 一断就把经典饿死，形成"断开后不回连"
                        log("LE Audio 断开: " + dev + "，下一轮重新给 LE 机会");
                    }
                }
            });
            log("hook ok: LeAudioService.deviceDisconnected");
        }
        hookStackEvents(cl);
    }

    private static int sAdoptedMask = 0;
    private static String sLastEvt = "";

    private void hookStackEvents(ClassLoader cl) {
        try {
            Class<?> evt = Class.forName("com.android.bluetooth.le_audio.LeAudioStackEvent", false, cl);
            Method m = findMethod(cl, CL_LEA, "messageFromNative", evt);
            if (m == null) {
                log("hook 跳过: LeAudioService.messageFromNative");
                return;
            }
            de.robv.android.xposed.XposedBridge.hookMethod(m, new Hook() {
                @Override
                protected void after(MethodHookParam p) {
                    try {
                        Object e = p.args[0];
                        int type = intVal(e, "type");
                        if (type != 1 && type != 2 && type != 4 && type != 13) {
                            return;
                        }
                        String line = "LEA evt type=" + type + " v1=" + intVal(e, "valueInt1")
                                + " v2=" + intVal(e, "valueInt2") + " v5=0x"
                                + Integer.toHexString(intVal(e, "valueInt5")) + " " + oneline(e);
                        if (!line.equals(sLastEvt)) {
                            sLastEvt = line;
                            log(line);
                        }
                        if (type == 4 && intVal(e, "valueInt5") != 0) {
                            sAdoptedMask = intVal(e, "valueInt5");
                            Object dev = objVal(e, "device");
                            adoptGroupContexts(null);
                            if (dev instanceof BluetoothDevice && cfgBool("adopt_ctx", true)) {
                                log("收到 native available_contexts=0x"
                                        + Integer.toHexString(sAdoptedMask) + "，补组");
                                setActiveLead();
                            }
                        }
                        // 掉线必须挂在这个 native 事件上：LeAudioService.deviceDisconnected(dev,boolean)
                        // 在"LE ACL 被栈闲置拆掉"这条路径上不会被调用（实测 05:09:10.339 只有
                        // type=1 v1=0，没有 deviceDisconnected），挂它会漏掉正要补拨的那次。
                        if (type == 1 && intVal(e, "valueInt1") == 0) {
                            Object dev = objVal(e, "device");
                            if (dev instanceof BluetoothDevice && isBud((BluetoothDevice) dev)) {
                                log("LE Audio 断开（native 事件）: " + dev);
                                endRound((BluetoothDevice) dev);
                                forgetGatt(((BluetoothDevice) dev).getAddress());
                                maybeFallBackToBrd(((BluetoothDevice) dev).getAddress(), "native");
                                    }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
            log("hook ok: LeAudioService.messageFromNative");
        } catch (Throwable t) {
            log("hook 失败 messageFromNative: " + t);
        }
    }

    /**
     * 组的 mAvailableContexts 在这个 build 里从没被 native 事件填过（isGroupAvailableForStream
     * 恒 false -> setActiveGroupWithDevice 直接 return -> 不建 CIG/CIS），用事件里的
     * available_contexts 补上，并把 allowed 掩码同步下发给 native。
     */
    private static void adoptGroupContexts(BluetoothDevice dev) {
        if (!cfgBool("adopt_ctx", true) || leaSvc() == null) {
            return;
        }
        try {
            int gid = toInt(invokeByName(leaSvc(), "getActiveGroupId"), -1);
            if (gid < 0) {
                gid = dev == null ? -1 : toInt(invokeByName(leaSvc(), "getGroupId", dev), -1);
            }
            if (gid < 0) {
                return;
            }
            Object gd = invokeByName(leaSvc(), "getGroupDescriptor", gid);
            if (gd == null) {
                return;
            }
            Integer cur = intBox(gd, "mAvailableContexts");
            if (cur != null && cur.intValue() != 0) {
                return;
            }
            int mask = sAdoptedMask != 0 ? sAdoptedMask : 0x4F;
            setIntField(gd, "mAvailableContexts", mask);
            invokeByName(leaSvc(), "setGroupAllowedContextMask", gid, mask, 0xFFF);
            log("补组可用 context: gid=" + gid + " -> 0x" + Integer.toHexString(mask)
                    + " forStream=" + invokeByName(leaSvc(), "isGroupAvailableForStream", gid));
        } catch (Throwable t) {
            log("adoptGroupContexts 异常: " + t);
        }
    }

    // ------------------------------------------------------ AdapterService 起点

    private void hookAdapterReady(ClassLoader cl) {
        Method m = findMethod(cl, CL_AS, "onCreate");
        if (m == null) {
            log("hook 跳过: AdapterService 没有 onCreate()");
            return;
        }
        de.robv.android.xposed.XposedBridge.hookMethod(m, new Hook() {
            @Override
            protected void after(MethodHookParam p) {
                try {
                    Object as = p.thisObject;
                    sCtx = as;
                    sA2dpRef = unwrapOptional(invokeByName(as, "getA2dpService"));
                    sHsRef = unwrapOptional(invokeByName(as, "getHeadsetService"));
                    sLeaRef = unwrapOptional(invokeByName(as, "getLeAudioService"));
                    refreshConfig();
                    log("AdapterService 就绪：" + configLine());
                    // 提前给已配对的耳机注册 interop：闲置定时器是在 LE 链路建好后 4 秒内装的，
                    // 等到第一次事件再认就晚了
                    Object bonded = invokeByName(as, "getBondedDevices");
                    if (bonded instanceof java.util.Collection) {
                        for (Object o : (java.util.Collection) bonded) {
                            if (o instanceof BluetoothDevice && isBud((BluetoothDevice) o)) {
                                holdGatt((BluetoothDevice) o);
                            }
                        }
                    }
                } catch (Throwable t) {
                    log("onCreate 收尾异常: " + t);
                }
            }
        });
        log("hook ok: AdapterService.onCreate");
    }

    /**
     * 解除配对时 HyperOS 直接写存储把经典禁掉（btservice/AdapterService.java:5208-5211，
     * removeBond -> setProfileConnectionPolicy(dev, profileId, 0)），这条路不经过
     * A2dpService/HeadsetService.setConnectionPolicy，我的拦 FORBIDDEN hook 看不见它。
     * 落成 FORBIDDEN 之后两边都是死的：来向 page 被 okToConnect 按策略拒
     * （a2dp/A2dpService.java:2572-2574），手动/自动全量连接又被
     * btservice/AdapterService.java:1252 的 getConnectionPolicy>0 过滤器整条跳过 ——
     * 就是"开盖不连接、手动连也连不上"。这里只补这一处，且只用同一个存储 sink，
     * 不走 profile 的 setConnectionPolicy(100)：那个会顺带 connect()，而主动 connect 会被
     * A2dpService.java:1260-1266 的 CSIP 门拒掉并回写 FORBIDDEN，白折腾。
     */
    private void hookPolicyRepair(ClassLoader cl) {
        Method m = findMethod(cl, CL_AS, "connectAllEnabledProfiles", BluetoothDevice.class);
        if (m == null) {
            log("hook 跳过: AdapterService.connectAllEnabledProfiles");
            return;
        }
        de.robv.android.xposed.XposedBridge.hookMethod(m, new Hook() {
            @Override
            protected void before(MethodHookParam p) {
                Object arg0 = p.args[0];
                if (arg0 instanceof BluetoothDevice && isBud((BluetoothDevice) arg0)) {
                    repairClassicPolicy((BluetoothDevice) arg0, "全量连接");
                }
            }
        });
        log("hook ok: AdapterService.connectAllEnabledProfiles 修经典策略");
    }

    private static void repairClassicPolicy(BluetoothDevice dev, String why) {
        if (!cfgBool("fix_policy", true) || sCtx == null) {
            return;
        }
        String addr = dev.getAddress();
        if (!cfgBool("plane_flip", false) || sUserOffLea.contains(addr)) {
            // 保底/用户自己关了 LE Audio：经典该是允许的，只做"修被写死的策略"
            fixOne(dev, PROFILE_A2DP, sUserOffA2dp, "a2dp", why);
            fixOne(dev, PROFILE_HFP, sUserOffHfp, "hfp", why);
            return;
        }
        if (sPlane.get(addr) != null) {
            return;                       // 引导面已立过（或已在 LE 面）
        }
        Integer done = sBootTries.get(addr);
        int tried = done == null ? 0 : done.intValue();
        if (tried >= MAX_BOOTSTRAP) {
            log(addr + " 引导已试满 " + MAX_BOOTSTRAP + " 次，不再自动翻面（可关 plane_flip 走 AAC）");
            return;
        }
        sBootTries.put(addr, Integer.valueOf(tried + 1));
        sPlane.put(addr, "brd");
        setPolicy(dev, PROFILE_LE_AUDIO, POLICY_FORBIDDEN);
        setPolicy(dev, PROFILE_A2DP, POLICY_ALLOWED);
        setPolicy(dev, PROFILE_HFP, POLICY_ALLOWED);
        log("立引导面（第 " + (tried + 1) + " 次）：a2dp/hfp=ALLOWED、le_audio=FORBIDDEN，"
                + "显式拨经典去拿 HFP SLC（" + why + "）: " + dev);
        invokeByName(sHsRef, "connect", dev);
        invokeByName(sA2dpRef, "connect", dev);
    }

    /** 只写存储，不调 profile 的 setConnectionPolicy —— 后者自带 connect/disconnect 副作用 */
    private static void setPolicy(BluetoothDevice dev, int profileId, int policy) {
        invokeByName(sCtx, "setProfileConnectionPolicy", dev,
                Integer.valueOf(profileId), Integer.valueOf(policy));
    }

    /**
     * ColorOS 的时序：HFP SLC 起来 -> 发 +VDSP=1,1 把耳机的音频面翻到 LE Audio
     * （OplusLeAudioServiceExt.java:328-334 / OplusBluetoothATCommandExtension.java:480-490）
     * -> 经典让位（OplusPhonePolicyExtImpl.java:299 disconnectAcl(BREDR)）-> 逐成员连 LE。
     */
    private static void flipToLea(BluetoothDevice dev) {
        if (!cfgBool("plane_flip", false) || sCtx == null) {
            return;
        }
        String addr = dev.getAddress();
        if ("lea".equals(sPlane.get(addr))) {
            return;
        }
        sPlane.put(addr, "lea");
        setPolicy(dev, PROFILE_LE_AUDIO, POLICY_ALLOWED);
        setPolicy(dev, PROFILE_A2DP, POLICY_FORBIDDEN);
        setPolicy(dev, PROFILE_HFP, POLICY_FORBIDDEN);
        invokeByName(sA2dpRef, "disconnect", dev);
        invokeByName(sHsRef, "disconnect", dev);
        log("翻面给 LE Audio：le_audio=ALLOWED、a2dp/hfp=FORBIDDEN，经典 profile 已断: " + dev);
        connectAllLeaMembers(dev);
    }

    /** 组内成员逐个连 —— HyperOS 没有 ColorOS 的 pairPeerDeviceIfNeed，得自己拉 */
    private static void connectAllLeaMembers(BluetoothDevice why) {
        if (leaSvc() == null) {
            return;
        }
        for (String addr : sBuds) {
            Object d = invokeByName(sCtx, "getRemoteDevice", addr);
            if (!(d instanceof BluetoothDevice)) {
                continue;
            }
            BluetoothDevice peer = (BluetoothDevice) d;
            int st = leaState(peer);
            if (st == LEA_CONNECTED || st == LEA_CONNECTING) {
                continue;
            }
            log("连 LE Audio 成员: " + peer + "（因 " + why + "）");
            invokeByName(leaSvc(), "connect", peer);
        }
    }

    /** LE 面整个没了 -> 撤掉面标记，下一次来向/全量连接会重新立引导面 */
    private static void maybeFallBackToBrd(String addr, String why) {
        if (!"lea".equals(sPlane.get(addr))) {
            return;
        }
        for (String a : sBuds) {
            Object d = sCtx == null ? null : invokeByName(sCtx, "getRemoteDevice", a);
            if (d instanceof BluetoothDevice && leaState((BluetoothDevice) d) != LEA_DISCONNECTED) {
                return;                      // 还有成员在 LE 面上，别撤
            }
        }
        sPlane.remove(addr);
        log("LE 面没了（" + why + "），退回引导面等下一次连接: " + addr);
    }

    private static void fixOne(BluetoothDevice dev, int profileId, Set<String> userOff,
                               String name, String why) {
        Object boxed = Integer.valueOf(profileId);
        Object got = invokeByName(sCtx, "getProfileConnectionPolicy", dev, boxed);
        if (!(got instanceof Integer)) {
            log("修 " + name + " 策略失败：AdapterService.getProfileConnectionPolicy 调不通，"
                    + "下次事件再试: " + dev);
            return;                       // 没读成功就不登记，别让反射失败冒充"已修好"
        }
        int cur = ((Integer) got).intValue();
        if (cur != POLICY_FORBIDDEN) {
            log(name + " 策略=" + cur + "，不用修（" + why + "）: " + dev);
            return;
        }
        if (userOff.contains(dev.getAddress())) {
            log(name + " 是用户在设置里关的，保持不动: " + dev);
            return;
        }
        invokeByName(sCtx, "setProfileConnectionPolicy", dev, boxed, Integer.valueOf(POLICY_ALLOWED));
        log("修 " + name + " 策略 FORBIDDEN->ALLOWED（" + why + "），现在="
                + invokeByName(sCtx, "getProfileConnectionPolicy", dev, boxed) + ": " + dev);
    }

    /**
     * 在这条 LE ACL 上挂一个"持有者"，让栈不去装那个 4000ms 闲置拆链定时器。
     * 依据（都是本机一手证据）：
     *   - HyperOS native 源码路径 packages/modules/MiuiBluetooth/system/stack/l2cap/l2c_utils.cc 里的
     *     l2cu_no_dynamic_ccbs（Ghidra 反编译 @00c31c38）取的是"LCB 与所有 CCB 里最大的 idle 秒数"，
     *     碰到 0xffff 就不装表；而持有者存在时 GATT 侧正是 idle_timeout=65535（日志
     *     "disable link idle timer"）。
     *   - 有持有者时的日志原文：is_add=false, but some app is still using the ACL link.
     *     ACL holders gatt_if: le_audio (55)（06:06 前一轮 46:3b 靠这个活着）。
     *   - 必须 direct connect：opportunistic 不进 hold-link 表
     *     （bas/BatteryStateMachine.java:378 用的就是 opportunistic=true，所以它挡不住拆链）。
     * ColorOS 同一层做法：BluetoothApp 的 LeAudioService.java:3579-3580 在 deviceConnected 里
     * 对每只耳 dev.connectGatt(ctx,false,cb,TRANSPORT_LE)，到 deviceDisconnected 才关。
     */
    private static void holdGatt(BluetoothDevice dev) {
        String addr = dev.getAddress();
        if (sHeld.containsKey(addr)) {
            return;
        }
        boolean on = cfgBool("hold_gatt", true);
        boolean lea = wantLea(dev);
        log("holdGatt 检查 " + addr + "：开关=" + on + " sCtx=" + (sCtx != null)
                + " wantLea=" + lea + " 用户关LEA=" + sUserOffLea.contains(addr));
        if (!on || sCtx == null || !lea) {
            return;
        }
        try {
            if (sCb == null) {
                sCb = new Holder();
            }
            Method m = dev.getClass().getMethod("connectGatt", android.content.Context.class,
                    boolean.class, android.bluetooth.BluetoothGattCallback.class, int.class);
            Object g = m.invoke(dev, sCtx, Boolean.FALSE, sCb, Integer.valueOf(2));
            if (g == null) {
                log("挂持有者失败：connectGatt 返回 null: " + addr);
                return;
            }
            sHeld.put(addr, g);
            log("已挂 GATT 持有者（direct、TRANSPORT_LE、不 close）: " + addr);
        } catch (Throwable t) {
            log("挂 GATT 持有者异常: " + t);
        }
    }

    /** 链路没了就把持有者忘掉，下一次连上重新挂 —— 不主动 close，close 本身就是扳机 */
    private static void forgetGatt(String addr) {
        sHeld.remove(addr);
    }

    /** 空回调：这个客户端唯一的用途就是占住 gatt_update_app_hold_link_status 的名额 */
    private static class Holder extends android.bluetooth.BluetoothGattCallback {
    }

    private static Object sCb;

    // ---------------------------------------------------------------- 判定工具

    private static boolean isBud(BluetoothDevice dev) {
        String addr = dev.getAddress();
        if (sBuds.contains(addr)) {
            return true;
        }
        try {
            String name = dev.getName();
            if (name == null) {
                return false;
            }
            String n = name.toUpperCase();
            boolean hit = n.contains("OPPO") || n.contains("ONEPLUS") || n.contains("ENCODAIR");
            if (hit) {
                sBuds.add(addr);
                holdGatt(dev);
            }
            return hit;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 用户是否想要 LE Audio：策略允许 且 没在设置里手动关掉 */
    /** LE Audio 服务实例。AdapterService.onCreate 时它常常还没起来
     *  （getLeAudioService() 返回 Optional.empty），所以每次用之前补取一次。 */
    private static Object leaSvc() {
        if (sLeaRef == null && sCtx != null) {
            sLeaRef = unwrapOptional(invokeByName(sCtx, "getLeAudioService"));
            if (sLeaRef != null) {
                log("补取到 LeAudioService 实例");
            }
        }
        return sLeaRef;
    }

    private static boolean wantLea(BluetoothDevice dev) {
        if (sUserOffLea.contains(dev.getAddress()) || leaSvc() == null) {
            return false;
        }
        return toInt(invokeByName(leaSvc(), "getConnectionPolicy", dev), POLICY_ALLOWED)
                != POLICY_FORBIDDEN;
    }

    private static boolean allLeaUp() {
        int up = 0;
        for (String a : sBuds) {
            Object d = sCtx == null ? null : invokeByName(sCtx, "getRemoteDevice", a);
            if (d instanceof BluetoothDevice && leaState((BluetoothDevice) d) == LEA_CONNECTED) {
                up++;
            }
        }
        return up >= 2;
    }

    private static int leaState(BluetoothDevice dev) {
        return leaSvc() == null ? LEA_DISCONNECTED
                : toInt(invokeByName(leaSvc(), "getConnectionState", dev), LEA_DISCONNECTED);
    }

    private static boolean profileConnected(Object svc, BluetoothDevice dev) {
        return svc != null && toInt(invokeByName(svc, "getConnectionState", dev), 0) == 2;
    }

    private static boolean a2dpConnected(BluetoothDevice dev) {
        return profileConnected(sA2dpRef, dev);
    }

    private static boolean hfpConnected(BluetoothDevice dev) {
        return profileConnected(sHsRef, dev);
    }

    private static Object unwrapOptional(Object o) {
        if (o == null) {
            return null;
        }
        if ("java.util.Optional".equals(o.getClass().getName())) {
            if (Boolean.TRUE.equals(invokeByName(o, "isPresent"))) {
                return invokeByName(o, "get");
            }
            return null;
        }
        return o;
    }

    private static final Map<String, Method> sMethodCache = new HashMap<String, Method>();

    private static Object invokeByName(Object target, String name, Object... args) {
        if (target == null) {
            return null;
        }
        try {
            String key = target.getClass().getName() + "#" + name + "/" + args.length;
            Method m = sMethodCache.get(key);
            if (m == null) {
                m = pickMethod(target.getClass(), name, args);
                if (m == null) {
                    return null;
                }
                m.setAccessible(true);
                sMethodCache.put(key, m);
            }
            return m.invoke(target, args);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Method pickMethod(Class<?> start, String name, Object[] args) {
        Class<?> c = start;
        while (c != null && c != Object.class) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterTypes().length == args.length
                        && assignable(m.getParameterTypes(), args)) {
                    return m;
                }
            }
            c = c.getSuperclass();
        }
        return null;
    }

    private static boolean assignable(Class<?>[] types, Object[] args) {
        for (int i = 0; i < types.length; i++) {
            Object a = args[i];
            if (a == null) {
                if (types[i].isPrimitive()) {
                    return false;
                }
                continue;
            }
            if (types[i].isPrimitive()) {
                if (!boxed(types[i]).isInstance(a)) {
                    return false;
                }
            } else if (!types[i].isInstance(a)) {
                return false;
            }
        }
        return true;
    }

    private static Class<?> boxed(Class<?> p) {
        if (p == int.class) {
            return Integer.class;
        }
        if (p == boolean.class) {
            return Boolean.class;
        }
        if (p == long.class) {
            return Long.class;
        }
        return p;
    }

    private static Object outerOf(Object state) {
        Class<?> c = state.getClass();
        while (c != null && c != Object.class) {
            if (CL_SM.equals(c.getName())) {
                return state;
            }
            try {
                Field f = c.getDeclaredField("this$0");
                f.setAccessible(true);
                Object o = f.get(state);
                if (o != null) {
                    return o;
                }
            } catch (Throwable ignored) {
            }
            c = c.getSuperclass();
        }
        return null;
    }

    private static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim().toUpperCase().replace(" ", "");
        return s.startsWith("AT") ? s.substring(2) : s;
    }

    private static int[] twoInts(String at) {
        try {
            int i = at.indexOf('=');
            if (i < 0) {
                return null;
            }
            String[] parts = at.substring(i + 1).split(",");
            if (parts.length < 2) {
                return null;
            }
            return new int[]{Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim())};
        } catch (Exception e) {
            return null;
        }
    }

    private static int toInt(Object o, int def) {
        return o instanceof Integer ? ((Integer) o).intValue() : def;
    }

    private static Integer intBox(Object o, String name) {
        try {
            Field f = o.getClass().getDeclaredField(name);
            f.setAccessible(true);
            Object v = f.get(o);
            return v instanceof Integer ? (Integer) v : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static int intVal(Object o, String name) {
        Integer v = o == null ? null : intBox(o, name);
        return v == null ? -1 : v.intValue();
    }

    private static Object objVal(Object o, String name) {
        try {
            Field f = o.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.get(o);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void setIntField(Object o, String name, int v) throws Exception {
        Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(o, Integer.valueOf(v));
    }

    private static String oneline(Object o) {
        if (o == null) {
            return "null";
        }
        String t = String.valueOf(o).replace((char) 10, ' ');
        return t.length() > 170 ? t.substring(0, 170) : t;
    }

    private static String shortName(String cls) {
        int i = cls.lastIndexOf('.');
        return i < 0 ? cls : cls.substring(i + 1);
    }

    private static String whoCalled() {
        StackTraceElement[] st = new Throwable().getStackTrace();
        StringBuilder sb = new StringBuilder();
        for (int i = 3; i < st.length && i < 14; i++) {
            sb.append(shortName(st[i].getClassName())).append('.').append(st[i].getMethodName())
                    .append(':').append(st[i].getLineNumber()).append(" <- ");
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- 配置读取

    private static final Map<String, String> sCfgCache = new HashMap<String, String>();
    private static long sCfgAt = 0;

    private static String cfg(String key, String def) {
        try {
            long now = android.os.SystemClock.uptimeMillis();
            if (now - sCfgAt > 3000L) {
                sCfgCache.clear();
                sCfgAt = now;
            }
            String cached = sCfgCache.get(key);
            if (cached != null) {
                return cached;
            }
            String v = null;
            if (sCtx != null) {
                Object cr = invokeByName(sCtx, "getContentResolver");
                if (cr != null) {
                    Bundle b = (Bundle) invokeByName(cr, "call",
                            android.net.Uri.parse(CFG_URI), "get", key, new Bundle());
                    if (b != null) {
                        v = b.getString("value");
                    }
                }
            }
            if (v == null || v.isEmpty()) {
                v = prop("persist.oppoemu." + key, null);
            }
            if (v == null || v.isEmpty()) {
                v = def;
            }
            sCfgCache.put(key, v);
            return v;
        } catch (Throwable t) {
            return def;
        }
    }

    private static boolean cfgBool(String key, boolean def) {
        String v = cfg(key, def ? "true" : "false");
        return "true".equalsIgnoreCase(v) || "1".equals(v);
    }

    private static int cfgInt(String key, int def) {
        String v = cfg(key, null);
        if (v == null) {
            return def;
        }
        try {
            return v.startsWith("0x") ? Integer.parseInt(v.substring(2), 16)
                    : Integer.parseInt(v.trim());
        } catch (Throwable t) {
            return def;
        }
    }

    private static Class<?> SP;

    private static String prop(String key, String def) {
        try {
            if (SP == null) {
                SP = Class.forName("android.os.SystemProperties");
            }
            Method m = SP.getMethod("get", String.class, String.class);
            String v = (String) m.invoke(null, key, def);
            return v == null || v.isEmpty() ? def : v;
        } catch (Throwable t) {
            return def;
        }
    }

    private static void log(String s) {
        de.robv.android.xposed.XposedBridge.log("[" + TAG + "] " + s);
        try {
            android.util.Log.i(TAG, s);
        } catch (Throwable ignored) {
        }
    }

    /** XposedBridge 回调的公共父类，避免两处匿名类重复写样板 */
    private abstract static class Hook extends de.robv.android.xposed.XC_MethodHook {
        @Override
        protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
            before(p);
        }

        @Override
        protected void afterHookedMethod(MethodHookParam p) throws Throwable {
            after(p);
        }

        protected void before(MethodHookParam p) throws Throwable {
        }

        protected void after(MethodHookParam p) throws Throwable {
        }
    }
}
