package com.github.leaf.leaconnect;

import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.SharedPreferences;
import de.robv.android.xposed.XC_MethodHook.MethodHookParam;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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

    private static final String TAG = "OPPOLeaConnect";
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
    private static final String CL_LEA_NI = "com.android.bluetooth.le_audio.LeAudioNativeInterface";
    private static final String CL_REMOTE = "com.android.bluetooth.btservice.RemoteDevices";
    private static final String CFG_URI = "content://com.github.leaf.leaconnect.config";

    private static final int POLICY_FORBIDDEN = 0;
    private static final int POLICY_ALLOWED = 100;
    private static final int AT_OK = 1;

    /** BluetoothProfile 的 profile id：connectEnabledProfiles 里 A2DP=2、HFP=1 */
    private static final int PROFILE_A2DP = 2;
    private static final int PROFILE_HFP = 1;

    /** LeAudioStateMachine.getConnectionState() 只用 BluetoothProfile 那四个值（0/1/2/3） */
    private static final int LEA_DISCONNECTED = 0;
    private static final int LEA_CONNECTING = 1;
    private static final int LEA_CONNECTED = 2;
    private static final int LEA_DISCONNECTING = 3;

    private static Handler MAIN;
    private static Object sCtx;
    private static PeerOwnership sPeers;

    // 生效开关（provider 优先，其次 persist.oppoemu.*，最后默认值）
    private static boolean sAt = true;
    private static boolean sVdsp = true;
    private static boolean sLeFirst = true;
    private static boolean sGateHfp = false;
    private static boolean sPoke = true;
    private static boolean sAdoptCtx = true;
    /** 我们替系统把"游戏在场"置起来了 —— 关掉开关时要靠它决定是否需要还原 */
    private static boolean sGameForced = false;
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
    /** 用户在本进程里主动断开过的耳机（按组记地址）：不再补拨、不占链路、不放行来向 */
    private static final Set<String> sUserDisc = ConcurrentHashMap.newKeySet();
    /** 有 BR/EDR 链路密钥的那只（经典腿只能拨它）—— 落盘，开关蓝牙后新进程也认得 */
    private static String sClassicAddr;
    private static final Set<String> sActed = new HashSet<String>();
    private static final HandoffState sHandoff = new HandoffState();
    private static final ThreadLocal<Boolean> sModuleCall = new ThreadLocal<Boolean>();
    private static final Set<String> sLeAclConnected = ConcurrentHashMap.newKeySet();
    private static final Set<String> sDiscoveryRetried = ConcurrentHashMap.newKeySet();
    /** cfgContext() 的缓存；Boolean.FALSE 表示"拿不到"，别再反复试 */
    private static Object sAppCtx;
    /** 只持有已连接的 LE Audio 链路；断开或整组让位时关闭。 */
    private static final Map<String, Object> sHeld = new ConcurrentHashMap<String, Object>();

    @Override
    public void handleLoadPackage(final de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam lpp) {
        if (!BT_PKG.equals(lpp.packageName)) {
            return;
        }
        if (!cfgBool("enabled", true)) {
            log("总开关关闭：本次不挂任何 hook");
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
        hookHandoff(lpp.classLoader);
        hookReconnectEvents(lpp.classLoader);
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
     * 现在的模型：LE Audio 与经典同时可拨，谁先上来由栈自己定，模块只保住策略不被写死。
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
                    // HFP 的 AT 只可能走 BR/EDR：见到 AT 就认它那只地址是经典腿
                    rememberClassic(dev.getAddress());
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
                    final Object sm = outerOf(p.thisObject);
                    if (sm == null) {
                        return;
                    }
                    final Object raw = sFieldDevice.get(sm);
                    if (!(raw instanceof BluetoothDevice)) {
                        return;
                    }
                    final BluetoothDevice bud = (BluetoothDevice) raw;
                    if (!cfgBool("vdsp", true) || sMethodAtString == null) {
                        return;
                    }
                    final Object ni = sFieldNative.get(sm);
                    if (ni == null) {
                        return;
                    }
                    MAIN.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            if (isPaused(bud)) return;
                            try {
                                sMethodAtString.invoke(ni, bud, "+VDSP=1,1");
                                log("已下发 +VDSP=1,1（HFP SLC 之后）");
                            } catch (Throwable t) {
                                log("下发 VDSP 失败: " + t);
                            }
                            // ColorOS 的时序：+VDSP 是"把耳机的音频面翻到 LE Audio"的命令，
                            // 翻完就该让经典让位（OplusLeAudioServiceExt.java:328-334 发命令、
                            // OplusPhonePolicyExtImpl.java:299 disconnectAcl(BREDR)）。
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
                    boolean userAction = isExplicitRequest();
                    if (userAction) {
                        // 外部（设置界面点击）= 用户意图，放行并记住，别再自作主张改回去
                        remember(sUserOffLea, tag.contains("le_audio"), policy, "LE Audio", dev, uid);
                        remember(sUserOffA2dp, tag.contains(".a2dp."), policy, "媒体音频(a2dp)", dev, uid);
                        remember(sUserOffHfp, tag.contains(".hfp."), policy, "通话音频(hfp)", dev, uid);
                        if (policy == POLICY_ALLOWED) resumeGroup(dev, "用户打开音频配置");
                        return;
                    }
                    if (policy != POLICY_FORBIDDEN) {
                        return;
                    }
                    if (tag.contains("le_audio") && sUserOffLea.contains(dev.getAddress())) {
                        return;   // 用户要关，不拦
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
            if (isUserDisc(dev)) {
                refuse(p, dev, which, "用户在设置里主动断开过");
                return;
            }
            if (isYielded(dev)) {
                refuse(p, dev, which, "整组已让位，等待用户重新连接");
                return;
            }
            // 用户连接入口先恢复整组权限；让位后晚到的系统去向请求同样必须拒绝。
            if (p.args.length > 1 && Boolean.TRUE.equals(p.args[1])) return;
            repairClassicPolicy(dev, which + " 来向");
            if (!cfgBool("le_first", true) || !wantLea(dev)) {
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

    /**
     * 补拨的统一入口：把事件里的地址换成那只经典设备，然后走一次全量连接
     * （等价于用户在设置里点"连接"）。
     *
     * 只由事件驱动，没有任何定时器/间隔窗口：调用点只有两处，都在"手机自己刚启动"那一侧 ——
     *   - 每个 ProfileService 报到 12=ON（onProfileServiceStateChanged）；
     *   - PhonePolicy.autoConnect() / autoConnectLeAudio(dev)（系统自己发起回连的那一刻）。
     * 每个蓝牙启动周期最多补拨一次；整组让位后不再补拨。尝试额度与连接成功状态分别记录。
     * 判死/断开那两类事件以前也挂在这里，v6.18 删了：合盖后它们会连着几次把经典往关死的盒子里 page。
     *
     * 为什么要换成经典地址：LE 断开事件经常落在纯 LE 的 46:3B 上，而 page 只对主耳那个
     * 经典地址有用；更关键的是 HyperOS 在 btservice/AdapterService.java:2409-2411 对
     * LE-only 设备直接 "skip connectAllSupportedProfiles" 返回 —— 拨到纯 LE 那只等于没拨。
     */
    private static void connectNow(BluetoothDevice dev, String why, boolean oncePerCycle) {
        if (!cfgBool("wake_classic", true) || sCtx == null || dev == null) {
            return;
        }
        BluetoothDevice target = dev;
        BluetoothDevice classic = findBondedClassicBud();
        if (classic != null && !classic.getAddress().equals(dev.getAddress())) {
            target = classic;
        } else if (classic == null && !isBudNameless(dev)) {
            log(why + "：认不出经典那只，放弃补拨（事件里的地址是 " + dev + "）");
            return;
        }
        if (a2dpConnected(target) || hfpConnected(target) || leaState(target) == LEA_CONNECTED) {
            return;
        }
        if (isUserDisc(target) || isUserDisc(dev) || isYielded(target) || isYielded(dev)) {
            log(why + "：整组已暂停，不补拨");
            return;
        }
        // AdapterService.java:2397-2401：profile 服务没起齐时 connectAllEnabledProfiles
        // 只打一条 "Not all profile services running" 就 return 1，一个 profile 都不会拨。
        // 开机那一串 profile-ON 事件会连着来，所以这里直接跳过、等下一个事件，不用延时。
        if (Boolean.FALSE.equals(invokeByName(sCtx, "profileServicesRunning"))) {
            log(why + "：profile 服务还没起齐，等下一个 profile-ON 事件再拨");
            return;
        }
        if (oncePerCycle && !sHandoff.takeStartupAttempt(target.getAddress())) {
            return;
        }
        repairClassicPolicy(target, why);
        log(why + "：补一次全量连接（等价于设置里点连接）: " + target
                + (target == dev ? "" : "（由 " + dev + " 映射）"));
        Boolean previous = sModuleCall.get();
        sModuleCall.set(Boolean.TRUE);
        try {
            invokeByName(sCtx, "connectAllEnabledProfiles", target);
        } finally {
            sModuleCall.set(previous);
        }
        if (sPeers != null) sPeers.request(target.getAddress(), false);
    }

    /** 纯 LE 的那只耳没有经典链路，补拨要落到主耳那个经典地址上 */
    private static BluetoothDevice findBondedClassicBud() {
        if (sCtx == null) {
            return null;
        }
        try {
            Object bonded = invokeByName(sCtx, "getBondedDevices");
            if (!(bonded instanceof java.util.Collection)) {
                return null;
            }
            BluetoothDevice guess = null;
            for (Object o : (java.util.Collection) bonded) {
                if (!(o instanceof BluetoothDevice)) {
                    continue;
                }
                BluetoothDevice d = (BluetoothDevice) o;
                if (sClassicAddr != null && sClassicAddr.equals(d.getAddress())) {
                    return d;
                }
                if (d.getType() == BluetoothDevice.DEVICE_TYPE_LE) {
                    continue;
                }
                if (guess == null && isBudNameless(d)) {
                    guess = d;
                }
            }
            return guess;
        } catch (Throwable t) {
            log("findBondedClassicBud 异常: " + t);
        }
        return null;
    }

    /**
     * 和 isBud 同一套判定，但启动期用：那时 dev.getName() 还是 null，
     * 名字要改从栈自己的存储里拿（AdapterService.getRemoteName），再退到 getAlias。
     * 实测 v6.13 在 onCreate 里"配对列表 3 个，认出耳机 0 个"，
     * 连 holdGatt 都要等到第一次事件才挂得上 —— 而那几秒正是 4000ms 闲置拆链的窗口。
     */
    private static boolean isBudNameless(BluetoothDevice dev) {
        String addr = dev.getAddress();
        if (sBuds.contains(addr)) {
            return true;
        }
        return budNameHit(dev) != null;
    }

    /** 命中就返回用到的名字来源，没命中返回 null（顺便记住地址，后面就不用再查） */
    private static String budNameHit(BluetoothDevice dev) {
        try {
            String name = dev.getName();
            String via = "getName";
            if (name == null && sCtx != null) {
                name = (String) invokeByName(sCtx, "getRemoteName", dev);
                via = "getRemoteName";
            }
            if (name == null) {
                name = (String) invokeByName(dev, "getAlias");
                via = "getAlias";
            }
            if (name == null) {
                return null;
            }
            String n = name.toUpperCase();
            if (n.contains("OPPO") || n.contains("ONEPLUS") || n.contains("ENCO")) {
                rememberBud(dev.getAddress());
                return via;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * 耳机地址必须落盘：开关蓝牙走的是每次全新的 com.android.bluetooth 进程，
     * 而 sBuds 只在内存里。实测 19:26:24 那一轮，开机瞬间 getName/getRemoteName/getAlias
     * 三个来源全拿不到名字，于是配对列表里一个耳机都认不出来，补拨一次都没发出去；
     * 同一版 19:18 那一轮名字缓存还热，就连上了 —— 完全看运气。
     */
    private static void loadBuds() {
        if (!(sCtx instanceof Context)) {
            return;
        }
        try {
            SharedPreferences sp = ((Context) sCtx).getSharedPreferences("oppoemu_buds", Context.MODE_PRIVATE);
            Set<String> all = sp.getStringSet("all", null);
            if (all != null) {
                sBuds.addAll(all);
            }
            sClassicAddr = sp.getString("classic", null);
            log("已加载耳机地址 " + sBuds + "，经典那只=" + sClassicAddr);
        } catch (Throwable t) {
            log("loadBuds 异常: " + t);
        }
    }

    private static void saveBuds() {
        if (!(sCtx instanceof Context)) {
            return;
        }
        try {
            ((Context) sCtx).getSharedPreferences("oppoemu_buds", Context.MODE_PRIVATE)
                    .edit().putStringSet("all", new HashSet<String>(sBuds))
                    .putString("classic", sClassicAddr).apply();
        } catch (Throwable t) {
            log("saveBuds 异常: " + t);
        }
    }

    private static void rememberBud(String addr) {
        if (addr == null || sBuds.contains(addr)) {
            return;
        }
        sBuds.add(addr);
        log("记录耳机地址: " + addr);
        saveBuds();
    }

    /**
     * 经典腿只认这一个地址。判据是事件而不是类型：HFP 的 AT 交互只可能发生在 BR/EDR 上
     * （纯 LE 的 46:3B 走不到 processUnknownAt），所以只要见过它发 AT，它就是经典那只。
     * BluetoothDevice.getType() 在这个时刻不可信 —— 实测补拨时对 46:3B 也没映射成功，
     * 说明那时它报的不是 DEVICE_TYPE_LE，于是全量连接拨到了纯 LE 的耳上，
     * 被 AdapterService.java:2409-2411 的 "skip connectAllSupportedProfiles for LE-only device" 吃掉。
     */
    private static void rememberClassic(String addr) {
        if (addr == null || addr.equals(sClassicAddr)) {
            return;
        }
        sClassicAddr = addr;
        log("经典腿地址: " + addr);
        saveBuds();
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
            int gid = toInt(invokeByName(leaSvc(), "getActiveGroupId"), -1);
            if (gid < 0) {
                // 组刚连上但还没被标成 active 时 getActiveGroupId() 是 -1（实测 08:20:48
                // 就是这样跳过过，结果 Active config 一直 Not set、CIG 不建）——
                // 退而用成员自己的 groupId 查，等 GROUP_STATUS_ACTIVE 事件再试一次
                for (String a : sBuds) {
                    Object d = sCtx == null ? null : invokeByName(sCtx, "getRemoteDevice", a);
                    if (d instanceof BluetoothDevice) {
                        gid = toInt(invokeByName(leaSvc(), "getGroupId", d), -1);
                        if (gid >= 0) {
                            break;
                        }
                    }
                }
            }
            if (gid < 0) {
                log("拿不到组号，跳过 setActiveDevice");
                return;
            }
            Object lead = invokeByName(leaSvc(), "getConnectedGroupLeadDevice", Integer.valueOf(gid));
            if (!(lead instanceof BluetoothDevice)) {
                log("组 " + gid + " 里拿不到 lead，跳过 setActiveDevice");
                return;
            }
            if (isPaused((BluetoothDevice) lead)) return;
            log("setActiveDevice(组的 lead/主耳): " + lead);
            invokeByName(leaSvc(), "setActiveDevice", lead);
        } catch (Throwable t) {
            log("setActiveLead 异常: " + t);
        }
    }

    /**
     * LE Audio 的低延迟档只有"手机认为前台是游戏"时才拿得到。全进程唯一的入口是
     * LeAudioService.processGameImportanceChange() -> mNativeInterface.setInGame(true)
     * （le_audio/LeAudioService.java:4104-4142），而它的门槛 isGameApplication(uid)
     * 用的是 PackageManager 给的 App 类目（:3183）—— 音乐类 App 不是游戏，于是
     * context 一直停在 MEDIA；而 HyperOS 的
     * /apex/com.android.bt/etc/bluetooth/le_audio/audio_set_scenarios.json 里
     * Media 场景 65 条候选没有一条 Low_Latency，实测只能拿到
     * VND_QoS_Config_R13_L100（RTN13 / MTL100ms，栈自己算出的传输延迟 84.69ms，
     * 加上 presentation delay 40ms => 手机上报 Audio HAL 的 peerDelayUs=124000），
     * 与 AAC 的 150-200ms 只差几十毫秒，所以听着"和 AAC 差不多"。
     * Game 场景里的 Two-OneChan-SnkAse-Lc3_48_1_Low_Latency 才是 7.5ms framing /
     * RTN3 / MTL8ms。这里走同一个 native 入口把"游戏在场"置起来，让栈按 Game 场景挑 CC。
     * 关掉时必须显式还原：我们是直接调 native 的，系统的游戏跟踪列表始终为空，
     * 它自己不会再调 setInGame(false)。
     */
    private static void applyGameCtx(String why) {
        Object lea = leaSvc();
        if (lea == null) {
            return;
        }
        boolean on = cfgBool("game_ctx", false);
        if (!on && !sGameForced) {
            return;
        }
        Object ni = objVal(lea, "mNativeInterface");
        if (ni == null || pickMethod(ni.getClass(), "setInGame", new Object[]{Boolean.TRUE}) == null) {
            log("game_ctx 跳过: " + (ni == null ? "拿不到 LeAudioService.mNativeInterface"
                    : ni.getClass().getName() + " 里没有 setInGame(boolean)"));
            return;
        }
        try {
            int gid = toInt(invokeByName(lea, "getActiveGroupId"), -1);
            if (on) {
                Object sup = invokeByName(lea, "getGroupSupportGameContext", Integer.valueOf(gid));
                invokeByName(ni, "setInGame", Boolean.TRUE);
                sGameForced = true;
                log(why + "：setInGame(true) 组=" + gid + " 支持 GAME context=" + sup
                        + "（不置它只能在 Media 场景里挑，那边没有 Low_Latency）");
            } else {
                invokeByName(ni, "setInGame", Boolean.FALSE);
                sGameForced = false;
                log(why + "：setInGame(false) 交还给系统自己的游戏跟踪");
            }
        } catch (Throwable t) {
            log("game_ctx 异常: " + t);
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
                        groupDevices(dev);
                        sHandoff.connected(dev.getAddress());
                        if (isYielded(dev) || isUserDisc(dev)) {
                            stopGroup(dev, "让位后收到延迟连接事件");
                            return;
                        }
                        holdGatt(dev);
                        if (sPeers != null) sPeers.request(dev.getAddress(), false);
                        completeGroup(dev);
                        // 必须赶在流起来之前：CC/QoS 是在 codec configure 时按场景表挑的，
                        // 等组 ACTIVE（type=2）再置就晚了一整轮
                        applyGameCtx("LE Audio 连接");
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
                        sGameForced = false;
                        // 不在这里重新拦经典：否则 LE 一断就把经典饿死，形成"断开后不回连"。
                        // 也不在这里补拨：LE 断开对手机来说分不清"耳机睡了（合盖）"和
                        // "链路意外掉了"，实测合盖后 22:52:09/22:52:26/22:59:12 三次
                        // "LE 那一次尝试失败"补拨就是隔着关死的盒子 page。开关蓝牙那一轮
                        // 由 profile-ON 事件和 PhonePolicy.autoConnect 两处覆盖（22:49:30、
                        // 22:51:55 两次都是它们先拨中的），这里删掉不影响那个修复。
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
                @Override protected void before(MethodHookParam p) {
                    Object e = p.args[0];
                    Object raw = objVal(e, "device");
                    if (intVal(e, "type") == 1 && intVal(e, "valueInt1") == LEA_CONNECTED
                            && raw instanceof BluetoothDevice && isBud((BluetoothDevice) raw)) {
                        BluetoothDevice dev = (BluetoothDevice) raw;
                        groupDevices(dev);
                        sHandoff.connected(dev.getAddress());
                    }
                }

                @Override
                protected void after(MethodHookParam p) {
                    try {
                        Object e = p.args[0];
                        int type = intVal(e, "type");
                        Object raw = objVal(e, "device");
                        if (type == 1 && intVal(e, "valueInt1") == LEA_CONNECTED
                                && raw instanceof BluetoothDevice && isPaused((BluetoothDevice) raw)) {
                            stopGroup((BluetoothDevice) raw, "让位后 native 意外重新连接");
                            return;
                        }
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
                        if (type == 2 && intVal(e, "valueInt2") == 1) {
                            // 组变 ACTIVE 是"lead 可查了"的时刻；deviceConnected 里那次可能早了一拍
                            setActiveLead();
                            applyGameCtx("组 ACTIVE");
                        }
                        if (type == 1 && intVal(e, "valueInt1") == 0) {
                            Object dev = objVal(e, "device");
                            if (dev instanceof BluetoothDevice && isBud((BluetoothDevice) dev)) {
                                log("LE Audio 断开（native 事件）: " + dev);
                                endRound((BluetoothDevice) dev);
                                String address = ((BluetoothDevice) dev).getAddress();
                                if (!sLeAclConnected.contains(address)) releaseHeld(address);
                                else retryServiceDiscovery((BluetoothDevice) dev);
                                final BluetoothDevice failed = (BluetoothDevice) dev;
                                MAIN.post(new Runnable() {
                                    @Override public void run() { completeGroup(failed); }
                                });
                                sGameForced = false;
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

    /**
     * 两个"系统自己准备回连"的时刻，加上一个"profile 服务起好了"的时刻，全部转成补拨。
     * 全是事件，没有 postDelayed：以前用 600ms 延时等 profileServicesRunning，是定时器；
     * 现在改成等 onProfileServiceStateChanged(LE_AUDIO, 12) 这个事件本身。
     */
    private void hookReconnectEvents(ClassLoader cl) {
        Class<?> as;
        try {
            as = Class.forName(CL_AS, false, cl);
        } catch (Throwable t) {
            log("hook 跳过: 加载 AdapterService 失败 " + t);
            return;
        }
        // HyperOS 把 ProfileService 挪到了 com.android.bluetooth.profile 包，
        // 所以不写死类名，直接从方法表拿签名
        Method ps = null;
        for (Method c : as.getDeclaredMethods()) {
            if (c.getName().equals("onProfileServiceStateChanged") && c.getParameterTypes().length == 2) {
                ps = c;
                break;
            }
        }
        if (ps != null) {
            de.robv.android.xposed.XposedBridge.hookMethod(ps, new Hook() {
                @Override
                protected void after(MethodHookParam p) {
                    // AdapterService.java:4125-4133 只是把消息丢给 mHandler，
                    // mRunningProfiles 要等 mHandler 处理（:352）才加上这一个 profile。
                    // 所以这里 post 一次：排在同一条主线程队列的后面，等它把这一个记完账再看状态。
                    // 不判 profileId —— 开机那一串 ON 事件里谁都不知道哪个是最后一个，
                    // 每个都试一次，profileServicesRunning() 之前的一律空转，第一个通过的就赢。
                    if (!(p.args[1] instanceof Integer) || ((Integer) p.args[1]).intValue() != 12) {
                        return;
                    }
                    final String why = "profile " + invokeByName(p.args[0], "getProfileId") + " 起好";
                    MAIN.post(new Runnable() {
                        @Override
                        public void run() {
                            BluetoothDevice classic = findBondedClassicBud();
                            if (classic != null) {
                                if (sPeers != null) sPeers.start();
                                connectNow(classic, why, true);
                            }
                        }
                    });
                }
            });
            log("hook ok: AdapterService.onProfileServiceStateChanged");
        } else {
            log("hook 跳过: AdapterService.onProfileServiceStateChanged");
        }

        hookPolicy(cl, "autoConnect", null, "PhonePolicy.autoConnect", false);
        hookPolicy(cl, "autoConnectLeAudio", BluetoothDevice.class, "PhonePolicy.autoConnectLeAudio", true);
    }

    /** 保留正常双耳补连；只有确认整组让位后才阻止系统回连。 */
    private void hookPolicy(ClassLoader cl, String name, Class<?> argType, String tag, final boolean withArg) {
        try {
            Class<?> pp = Class.forName("com.android.bluetooth.btservice.PhonePolicy", false, cl);
            Method m = argType == null ? pp.getDeclaredMethod(name) : pp.getDeclaredMethod(name, argType);
            de.robv.android.xposed.XposedBridge.hookMethod(m, new Hook() {
                @Override
                protected void after(MethodHookParam p) {
                    if (withArg) {
                        return;
                    }
                    BluetoothDevice classic = findBondedClassicBud();
                    if (classic != null) {
                        connectNow(classic, tag, true);
                    }
                }

                @Override
                protected void before(MethodHookParam p) {
                    if (!withArg) {
                        return;
                    }
                    for (int i = p.args.length - 1; i >= 0; i--) {
                        if (p.args[i] instanceof BluetoothDevice) {
                            BluetoothDevice dev = (BluetoothDevice) p.args[i];
                            if (!isBudNameless(dev)) {
                                continue;
                            }
                            if (isYielded(dev) || isUserDisc(dev)) {
                                p.setResult(null);
                                log(tag + "：整组已暂停，跳过系统自动回连: " + dev);
                                return;
                            }
                            connectNow(dev, tag, true);
                            return;
                        }
                    }
                }
            });
            log("hook ok: " + tag);
        } catch (Throwable t) {
            log("hook 跳过: " + tag + " —— " + t);
        }
    }

    private void hookHandoff(ClassLoader cl) {
        Method acl = findMethod(cl, CL_REMOTE, "aclStateChangeCallback", int.class, byte[].class,
                int.class, int.class, int.class, int.class, int.class);
        if (acl != null) {
            de.robv.android.xposed.XposedBridge.hookMethod(acl, new Hook() {
                @Override protected void before(MethodHookParam p) {
                    int transport = toInt(p.args[3], -1);
                    if (toInt(p.args[0], -1) != 0 || (transport != 1 && transport != 2)) return;
                    Object raw = invokeByName(p.thisObject, "getDevice", p.args[1]);
                    if (!(raw instanceof BluetoothDevice) || !isBud((BluetoothDevice) raw)) return;
                    final BluetoothDevice dev = (BluetoothDevice) raw;
                    groupDevices(dev);
                    int state = toInt(p.args[4], -1);
                    if (state == 0 && transport == 2) {
                        sLeAclConnected.add(dev.getAddress());
                        MAIN.post(new Runnable() {
                            @Override public void run() {
                                if (isPaused(dev)) stopGroup(dev, "让位后 LE ACL 意外重新建立");
                                else holdGatt(dev);
                            }
                        });
                        return;
                    }
                    if (state == 1 && transport == 2) {
                        sLeAclConnected.remove(dev.getAddress());
                        releaseHeld(dev.getAddress());
                    }
                    if (sHandoff.remoteDisconnect(dev.getAddress(), transport, state, toInt(p.args[5], -1))) {
                        log("ACL 被远端主动终止(HCI 0x13, transport=" + transport + ")，整组让位: "
                                + sHandoff.membersOf(dev.getAddress()));
                        stopGroup(dev, "远端主动断开");
                    }
                }
            });
            log("hook ok: RemoteDevices.aclStateChangeCallback 整组让位");
        } else {
            log("hook 跳过: RemoteDevices.aclStateChangeCallback，无法识别远端接管");
        }

        guardLeConnection(cl, CL_LEA, "connect", true);
        guardLeConnection(cl, CL_LEA, "okToConnect", false);
        guardLeConnection(cl, CL_LEA_NI, "connectLeAudio", false);
        Method enable = findMethod(cl, CL_LEA_NI, "setEnableState", BluetoothDevice.class, boolean.class);
        if (enable != null) {
            de.robv.android.xposed.XposedBridge.hookMethod(enable, new Hook() {
                @Override protected void before(MethodHookParam p) {
                    if (Boolean.TRUE.equals(p.args[1]) && p.args[0] instanceof BluetoothDevice
                            && isPaused((BluetoothDevice) p.args[0])) {
                        p.setResult(Boolean.FALSE);
                        log("拦 native 重新启用，整组已暂停: " + p.args[0]);
                    }
                }
            });
            log("hook ok: LeAudioNativeInterface.setEnableState 保护让位状态");
        }
    }

    private void guardLeConnection(ClassLoader cl, String className, String name, final boolean userEntry) {
        Method method = findMethod(cl, className, name, BluetoothDevice.class);
        if (method == null) {
            log("hook 跳过: " + shortName(className) + "." + name);
            return;
        }
        de.robv.android.xposed.XposedBridge.hookMethod(method, new Hook() {
            @Override protected void before(MethodHookParam p) {
                if (!(p.args[0] instanceof BluetoothDevice)) return;
                BluetoothDevice dev = (BluetoothDevice) p.args[0];
                if (!isBud(dev)) return;
                if (userEntry && isExplicitRequest()) resumeGroup(dev, "用户主动连接 LE Audio");
                if (isPaused(dev)) {
                    p.setResult(Boolean.FALSE);
                    log("拦 " + name + "，整组已暂停: " + dev);
                }
            }
        });
        log("hook ok: " + shortName(className) + "." + name + " 保护让位状态");
    }

    private static boolean isExplicitRequest() {
        if (Boolean.TRUE.equals(sModuleCall.get())
                || BT_UID.equals(String.valueOf(android.os.Binder.getCallingUid()))) return false;
        String caller = whoCalled();
        return !caller.contains("PhonePolicy") && !caller.contains("Service.connect:");
    }

    private static ArrayList<BluetoothDevice> groupDevices(BluetoothDevice dev) {
        Map<String, BluetoothDevice> members = new HashMap<String, BluetoothDevice>();
        members.put(dev.getAddress(), dev);
        Object lea = leaSvc();
        int groupId = toInt(invokeByName(lea, "getGroupId", dev), -1);
        Object group = groupId < 0 ? null : invokeByName(lea, "getGroupDevices", Integer.valueOf(groupId));
        if (group instanceof Collection) {
            for (Object raw : (Collection<?>) group) {
                if (raw instanceof BluetoothDevice) {
                    BluetoothDevice member = (BluetoothDevice) raw;
                    members.put(member.getAddress(), member);
                    rememberBud(member.getAddress());
                }
            }
        }
        Object classic = invokeByName(sCtx, "findBrDevice", dev.getAddress());
        if (classic instanceof BluetoothDevice) {
            BluetoothDevice br = (BluetoothDevice) classic;
            members.put(br.getAddress(), br);
        }
        sHandoff.registerGroup(members.keySet());
        for (String address : sHandoff.membersOf(dev.getAddress())) {
            if (members.containsKey(address)) continue;
            Object raw = invokeByName(sCtx, "getRemoteDevice", address);
            if (raw instanceof BluetoothDevice) members.put(address, (BluetoothDevice) raw);
        }
        return new ArrayList<BluetoothDevice>(members.values());
    }

    private static boolean isYielded(BluetoothDevice dev) {
        return dev != null && sHandoff.isYielded(dev.getAddress());
    }

    private static boolean isPaused(BluetoothDevice dev) {
        return isYielded(dev) || isUserDisc(dev);
    }

    private static void retryServiceDiscovery(final BluetoothDevice dev) {
        final String address = dev.getAddress();
        if (isPaused(dev) || sHandoff.hasEstablished(address) || !sHeld.containsKey(address)
                || !sDiscoveryRetried.add(address)) return;
        MAIN.post(new Runnable() {
            @Override public void run() {
                if (isPaused(dev) || !sLeAclConnected.contains(address) || leaState(dev) == LEA_CONNECTED) return;
                Object gatt = sHeld.get(address);
                Object refreshed = invokeByName(gatt, "refresh");
                log("LE Audio 服务发现失败但 ACL 仍在线，刷新 GATT 缓存一次: " + dev + " refreshed=" + refreshed);
                if (Boolean.TRUE.equals(refreshed)) invokeByName(leaSvc(), "connect", dev);
            }
        });
    }

    private static void completeGroup(BluetoothDevice dev) {
        if (isPaused(dev)) return;
        ArrayList<BluetoothDevice> members = groupDevices(dev);
        boolean connectedPeer = false;
        for (BluetoothDevice member : members) connectedPeer |= leaState(member) == LEA_CONNECTED;
        if (!connectedPeer) return;
        for (BluetoothDevice member : members) {
            int state = leaState(member);
            if (isPaused(member) || !wantLea(member) || state == LEA_CONNECTED || state == LEA_DISCONNECTING) continue;
            if (sHandoff.takeCompletionAttempt(member.getAddress())) {
                // Connecting 状态机吞掉重复 CONNECT；直接升级 native 的后台连接请求。
                Object result = state == LEA_CONNECTING
                        ? invokeByName(objVal(leaSvc(), "mNativeInterface"), "connectLeAudio", member)
                        : invokeByName(leaSvc(), "connect", member);
                log("补齐 LE Audio 组，直接连接缺失成员一次: " + member + " result=" + result);
            }
        }
    }

    private static void stopGroup(final BluetoothDevice dev, final String why) {
        final ArrayList<BluetoothDevice> members = groupDevices(dev);
        if (sPeers != null) sPeers.withdraw(dev.getAddress());
        MAIN.post(new Runnable() {
            @Override public void run() {
                if (!isPaused(dev)) return;
                Object lea = leaSvc();
                Object ni = objVal(lea, "mNativeInterface");
                for (BluetoothDevice member : members) {
                    Object disabled = invokeByName(ni, "setEnableState", member, Boolean.FALSE);
                    // Disconnected 状态也要调用 native，取消 CONNECTING_AUTOCONNECT 的挂起请求。
                    invokeByName(ni, "disconnectLeAudio", member);
                    invokeByName(lea, "disconnect", member);
                    releaseHeld(member.getAddress());
                    sActed.remove(member.getAddress());
                    endRound(member);
                    invokeByName(sCtx, "disconnectAllEnabledProfiles", member, Integer.valueOf(0));
                    log(why + "：停止整组成员 native 回连并释放链路: " + member
                            + " disabled=" + disabled);
                }
            }
        });
    }

    private static void resumeGroup(BluetoothDevice dev, String why) {
        ArrayList<BluetoothDevice> members = groupDevices(dev);
        if (sPeers != null) sPeers.request(dev.getAddress(), true);
        boolean changed = sHandoff.resume(dev.getAddress());
        for (BluetoothDevice member : members) {
            changed |= sUserDisc.remove(member.getAddress());
            if (leaState(member) == LEA_CONNECTED) sHandoff.connected(member.getAddress());
        }
        if (!changed) return;
        Object ni = objVal(leaSvc(), "mNativeInterface");
        for (BluetoothDevice member : members) {
            if (wantLea(member)) invokeByName(ni, "setEnableState", member, Boolean.TRUE);
        }
        log(why + "：恢复整组连接权限: " + sHandoff.membersOf(dev.getAddress()));
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
                    invalidateConfig();
                    refreshConfig();
                    sHandoff.reset();
                    sLeAclConnected.clear();
                    sDiscoveryRetried.clear();
                    clearUserDisc("蓝牙进程重启");
                    loadBuds();
                    if (sPeers != null) sPeers.stop();
                    sPeers = new PeerOwnership(MAIN, new PeerOwnership.Listener() {
                        @Override public void log(String message) { Core.log(message); }
                        @Override public void yield(String address) {
                            Object raw = invokeByName(sCtx, "getRemoteDevice", address);
                            if (!(raw instanceof BluetoothDevice)) return;
                            BluetoothDevice dev = (BluetoothDevice) raw;
                            groupDevices(dev);
                            sHandoff.yieldGroup(address);
                            stopGroup(dev, "设备间独占接管");
                        }
                    });
                    for (String address : new ArrayList<String>(sBuds)) sPeers.register(address);
                    log("AdapterService 就绪：" + configLine());
                    // GATT 持有者只能在目标耳机的 LE ACL 实际建立后挂载。启动阶段预先对所有已配对
                    // 耳机 connectGatt 会抢占耳机的 LE ACL，导致其它手机无法接管连接。
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
                    int uid = android.os.Binder.getCallingUid();
                    BluetoothDevice dev = (BluetoothDevice) arg0;
                    if (isExplicitRequest()) {
                        resumeGroup(dev, "用户主动发起全量连接(uid=" + uid + ")");
                    } else if (isYielded(dev) || isUserDisc(dev)) {
                        p.setResult(Integer.valueOf(0));
                        log("拦系统自动全量连接，整组已暂停: " + dev);
                        return;
                    }
                    repairClassicPolicy((BluetoothDevice) arg0, "全量连接");
                }
            }
        });
        log("hook ok: AdapterService.connectAllEnabledProfiles 修经典策略");

        Method md = findMethod(cl, CL_AS, "disconnectAllEnabledProfiles",
                BluetoothDevice.class, int.class);
        if (md == null) {
            log("hook 跳过: AdapterService.disconnectAllEnabledProfiles");
            return;
        }
        de.robv.android.xposed.XposedBridge.hookMethod(md, new Hook() {
            @Override
            protected void before(MethodHookParam p) {
                Object arg0 = p.args[0];
                if (!(arg0 instanceof BluetoothDevice) || !isBud((BluetoothDevice) arg0)) {
                    return;
                }
                int uid = android.os.Binder.getCallingUid();
                if (BT_UID.equals(String.valueOf(uid))) {
                    return;
                }
                markUserDisc((BluetoothDevice) arg0, "设置里点断开(uid=" + uid + ")");
            }
        });
        log("hook ok: AdapterService.disconnectAllEnabledProfiles 记用户主动断开");
    }

    /**
     * 用户在设置里点"断开"之后，手机就不该再把这对耳拨回来。
     *
     * 实测这条和"关低功耗音频"是两条完全不同的路：断开走
     * btservice/AdapterServiceBinder.disconnectAllEnabledProfiles（19:59:21.220，
     * uid/pid=1000/28479 packageName=com.android.settings），经
     * CachedBluetoothDevice.disconnect 下来，**不碰 setConnectionPolicy**，所以
     * sUserOffLea/sUserOffA2dp 那三个"用户在设置里关的"标记一个都不会被点亮 ——
     * 于是我们的补拨、hold_gatt、来向放行全都照常工作，把用户那一下断开当成链路意外掉了。
     * 栈自己也认为这是"别再自动连"：native 在同一毫秒就打了
     * system/bta/le_audio/client.cc:3031 "Disconnect: Removing autoconnect flag for group_id 1"。
     *
     * 更糟的是它还会自激：我们 45ms 后把经典拨起来，HyperOS 一见 A2DP/HFP 在线就把 LE 拆掉
     * （"processProfileStateChanged: A2DP/HFP connected disconnect lea"），LE 一断我们又拨经典，
     * 19:59:21~41 那 20 秒里两只耳在 CONNECTED/CONNECTING 之间翻转了七八轮。
     *
     * 标记按组记：断开请求只落在 46:3B 上，而补拨用的是经典那只 6E:5A，只记一个地址等于没记。
     * 清除时机：用户在设置里再点一次"连接"（同一个 uid!=1002 的判据），或蓝牙进程重启。
     */
    private static void markUserDisc(BluetoothDevice dev, String why) {
        groupDevices(dev);
        sUserDisc.addAll(sHandoff.membersOf(dev.getAddress()));
        log(why + "：本进程内不再补拨/占链路/放行来向，地址=" + sUserDisc);
        stopGroup(dev, why);
    }

    private static void clearUserDisc(String why) {
        if (sUserDisc.isEmpty()) {
            return;
        }
        log(why + "：清除主动断开标记 " + sUserDisc);
        sUserDisc.clear();
    }

    private static boolean isUserDisc(BluetoothDevice dev) {
        return !sUserDisc.isEmpty() && dev != null && sUserDisc.contains(dev.getAddress());
    }

    private static void repairClassicPolicy(BluetoothDevice dev, String why) {
        if (!cfgBool("fix_policy", true) || sCtx == null) {
            return;
        }
        fixOne(dev, PROFILE_A2DP, sUserOffA2dp, "a2dp", why);
        fixOne(dev, PROFILE_HFP, sUserOffHfp, "hfp", why);
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
                + " wantLea=" + lea + " 用户关LEA=" + sUserOffLea.contains(addr)
                + " 用户主动断开=" + isUserDisc(dev));
        if (!on || sCtx == null || !lea || isPaused(dev)
                || (!sLeAclConnected.contains(addr) && leaState(dev) != LEA_CONNECTED)) {
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

    /**
     * 但用户主动断开时这枚扳机必须扣：这个 GATT 客户端唯一的用处就是把
     * gatt_update_app_hold_link_status 的名额占住（LE 链路建好后 4 秒的闲置拆链就是被它顶掉的），
     * 用户点了断开还占着，等于我们替他把链路又摁回去。disconnect+close 之后名额才真正释放。
     */
    private static void releaseHeld(String addr) {
        Object g = sHeld.remove(addr);
        if (g == null) {
            return;
        }
        try {
            invokeByName(g, "disconnect");
            invokeByName(g, "close");
            log("已释放 GATT 持有者: " + addr);
        } catch (Throwable t) {
            log("释放 GATT 持有者异常: " + t);
        }
    }

    /** 断开时必须注销 GATT 客户端，不能丢掉引用后让 native 继续持有。 */
    private static class Holder extends android.bluetooth.BluetoothGattCallback {
        @Override public void onConnectionStateChange(android.bluetooth.BluetoothGatt gatt, int status, int state) {
            if (state != LEA_DISCONNECTED) return;
            String address = gatt.getDevice().getAddress();
            if (sHeld.remove(address, gatt)) log("GATT 已断开，关闭持有者: " + address);
            gatt.close();
        }
    }

    private static Object sCb;

    // ---------------------------------------------------------------- 判定工具

    private static boolean isBud(BluetoothDevice dev) {
        if (dev == null) {
            return false;
        }
        String addr = dev.getAddress();
        if (sBuds.contains(addr)) {
            return true;
        }
        if (budNameHit(dev) != null) {
            return true;
        }
        return false;
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

    private static synchronized void invalidateConfig() { sCfgCache.clear(); }

    private static synchronized String cfg(String key, String def) {
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
            Object ctx = cfgContext();
            if (ctx != null) {
                Object cr = invokeByName(ctx, "getContentResolver");
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

    /**
     * 读配置用的 Context。AdapterService 还没起来时（handleLoadPackage 那一刻 sCtx 是 null）
     * 退到目标进程自己的 Application：不加这一层，总开关和一切在加载期做的 cfgBool 判断
     * 只能看见 persist.oppoemu.*，UI 里的设置要到蓝牙进程重启以后才生效。
     */
    private static Object cfgContext() {
        if (sCtx != null) {
            return sCtx;
        }
        if (sAppCtx == null) {
            try {
                sAppCtx = Class.forName("android.app.ActivityThread")
                        .getMethod("currentApplication").invoke(null);
            } catch (Throwable t) {
                log("拿不到 Application 上下文，配置只能退回 persist.oppoemu.*: " + t);
                sAppCtx = Boolean.FALSE;
            }
        }
        return sAppCtx == Boolean.FALSE ? null : sAppCtx;
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
