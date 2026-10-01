package com.github.leaf.leaconnect.ui.data

/**
 * 设置项清单。key 必须和 Core.java 里 cfgBool/cfg/cfgInt 用的字符串一一对应，
 * 两边不同名等于这个开关不存在。列表顺序就是高级设置里的显示顺序。
 *
 * detail 是分节的：heading 在详情弹层里加粗显示，heading 为空表示该段无小标题。
 */
enum class OptionKind { SWITCH, TEXT }

enum class OptionGroup(val displayName: String) {
    LATENCY("延迟与性能"),
    CONNECTION("连接策略与调度"),
    STABILITY("链路保活与稳定性"),
    PROTOCOL("OPPO 私有协议兼容"),
}

data class Section(val heading: String = "", val body: String)

data class Option(
    val key: String,
    val title: String,
    val subtitle: String,
    val kind: OptionKind,
    val group: OptionGroup,
    val defaultBoolean: Boolean = true,
    val defaultText: String = "",
    val hint: String = "",
    val detail: List<Section>,
)

object Options {

    /** 总开关：不进高级设置，放在主页 */
    val master = Option(
        key = "enabled",
        title = "启用模块",
        subtitle = "",
        kind = OptionKind.SWITCH,
        group = OptionGroup.CONNECTION,
        detail = listOf(
            Section(
                "生效时机",
                "本项在蓝牙进程加载期读取（handleLoadPackage）。关闭后需重启蓝牙进程方可完全卸载钩子：" +
                        "开关一次蓝牙，或执行 adb shell su -c \"pkill -f com.android.bluetooth\"。"
            ),
            Section(
                "",
                "高级设置中的其余各项在运行期生效，蓝牙进程的配置缓存周期为 3 秒。"
            ),
        ),
    )

    /** 顺序即显示顺序：全局低延迟第一 */
    val list = listOf(
        Option(
            key = "game_ctx",
            title = "全局低延迟",
            subtitle = "强制宣告游戏状态，取 7.5ms 低延迟档位",
            kind = OptionKind.SWITCH,
            group = OptionGroup.LATENCY,
            defaultBoolean = false,
            detail = listOf(
                Section(
                    "技术原理",
                    "LE Audio 的低延迟配置仅在系统判定前台为游戏时参与协商。唯一入口为 " +
                            "LeAudioService.processGameImportanceChange() → mNativeInterface.setInGame(true)，" +
                            "其门槛 isGameApplication(uid) 取自 PackageManager 的应用类目，非游戏类应用无法满足，" +
                            "故音频上下文长期停留在 MEDIA。"
                ),
                Section(
                    "系统行为",
                    "HyperOS 的配置表位于 /apex/com.android.bt/etc/bluetooth/le_audio/audio_set_scenarios.json，" +
                            "其中 Media 场景的 65 条候选不含 Low_Latency 档位，实际协商结果为 " +
                            "VND_QoS_Config_R13_L100（RTN 13 / MTL 100ms）：协议栈计算的传输延迟 84.69ms，" +
                            "叠加 presentation delay 40ms，上报 Audio HAL 的 peerDelayUs 为 124000，" +
                            "相对 AAC 的 150–200ms 无明显优势。Game 场景的 " +
                            "Two-OneChan-SnkAse-Lc3_48_1_Low_Latency 为 7.5ms framing / RTN 3 / MTL 8ms。" +
                            "本项在 codec configure 之前调用同一 native 接口，使协议栈按 Game 场景选取配置。"
                ),
                Section(
                    "权衡",
                    "单耳码率由 155 octets（124kbps）降至 75 octets（60kbps）。关闭时显式调用 " +
                            "setInGame(false) 复位：系统的游戏状态列表为空，不会自行回退。"
                ),
            ),
        ),
        Option(
            key = "le_first",
            title = "LE Audio 连接优先",
            subtitle = "阻止经典蓝牙抢占连接窗口，优先握手 LC3",
            kind = OptionKind.SWITCH,
            group = OptionGroup.CONNECTION,
            detail = listOf(
                Section(
                    "技术原理",
                    "开盖瞬间耳机的 BR/EDR page scan 立即应答，经典链路通常在数百毫秒内先行建立；" +
                            "而 Enco X3 一旦存在经典链路即停止发送 LE 广播，LE Audio 失去协商窗口。"
                ),
                Section(
                    "系统行为",
                    "在 A2dpService.okToConnect 与 HeadsetService.okToAcceptConnection 两个入口对来向请求作事件级拒绝，" +
                            "不修改持久化连接策略，并触发一次 LeAudioService.connect()。LE 建立后立即放行；" +
                            "若该次协商超时（LeAudioStateMachine CONNECT_TIMEOUT 30s）则本轮放行经典链路，" +
                            "直至下一次 LE 连接或断开。判定为事件驱动。"
                ),
            ),
        ),
        Option(
            key = "poke",
            title = "主动发起 LE 握手",
            subtitle = "在来向事件里直接触发一次连接，不等系统 30 秒轮询",
            kind = OptionKind.SWITCH,
            group = OptionGroup.CONNECTION,
            detail = listOf(
                Section(
                    "技术原理",
                    "在经典来向事件中调用一次 LeAudioService.connect()，不等待系统约 30 秒一轮的后台连接。" +
                            "仅在事件内执行单次调用，非轮询。"
                ),
            ),
        ),
        Option(
            key = "wake_classic",
            title = "蓝牙重启后补拨经典",
            subtitle = "修复开关蓝牙后不回连、必须回盒重开盖",
            kind = OptionKind.SWITCH,
            group = OptionGroup.CONNECTION,
            detail = listOf(
                Section(
                    "技术原理",
                    "开关蓝牙或蓝牙进程重启后，系统仅发起 LE Audio 协商，超时后不再有针对 BR/EDR 的拨号动作；" +
                            "而蓝牙设置中的「连接」（AdapterService.connectAllEnabledProfiles）可以正常连上，" +
                            "说明耳机可达，缺少的正是这一次经典拨号。"
                ),
                Section(
                    "实现",
                    "触发点为事件驱动：ProfileService 状态到达 12=ON，以及 PhonePolicy.autoConnect / " +
                            "autoConnectLeAudio。两处约束必须满足 —— 其一，AdapterService 在 " +
                            "profileServicesRunning() 为假时不执行任何 profile 连接，故需等待 profile 全部启动；" +
                            "其二，connectAllEnabledProfiles 对 LE-only 设备直接跳过，故仅对具备 BR/EDR 链路密钥的" +
                            "设备拨号，该地址由 HFP 的 AT 交互识别并持久化。"
                ),
            ),
        ),
        Option(
            key = "fix_policy",
            title = "修复连接策略锁死",
            subtitle = "纠正被 HyperOS 写成 FORBIDDEN 的 A2DP/HFP 策略",
            kind = OptionKind.SWITCH,
            group = OptionGroup.CONNECTION,
            detail = listOf(
                Section(
                    "技术原理",
                    "HyperOS 在解除配对时直接写存储将 a2dp/hfp 置为 FORBIDDEN，该路径不经过 profile 的 " +
                            "setConnectionPolicy，常规拦截无法感知。"
                ),
                Section(
                    "系统行为",
                    "策略落为 FORBIDDEN 后双向失效：来向 page 被 okToConnect 按策略拒绝，手动与自动的全量连接" +
                            "又被 getConnectionPolicy > 0 过滤器跳过。本项在来向请求与全量连接之前将其恢复为 ALLOWED；" +
                            "用户在系统设置中主动关闭的项不会被重新开启。"
                ),
            ),
        ),
        Option(
            key = "gate_hfp",
            title = "LE 优先时拦截 HFP",
            subtitle = "默认关闭；开启可能导致仅单耳出声",
            kind = OptionKind.SWITCH,
            group = OptionGroup.CONNECTION,
            defaultBoolean = false,
            detail = listOf(
                Section(
                    "警告",
                    "Enco X3 主耳依赖经典 HFP 与 AT 通道协调双耳同步，在 LE 优先阶段同时拒绝 HFP " +
                            "会造成单耳无声。建议保持默认关闭。"
                ),
            ),
        ),
        Option(
            key = "hold_gatt",
            title = "保持 GATT 链路持有",
            subtitle = "防止 4 秒闲置定时器拆链造成的单耳断联",
            kind = OptionKind.SWITCH,
            group = OptionGroup.STABILITY,
            detail = listOf(
                Section(
                    "技术原理",
                    "LE Audio 建立后，若该 LE ACL 上不存在任何 GATT 持有者，系统会为其装载 4000ms 闲置定时器并拆除链路。" +
                            "闲置时长取自 LCB/CCB 的最大 idle 值，存在持有者时 GATT 侧为 65535，因此不会装载。" +
                            "拆链发生时 EATT 通道与 CIS 仍在，日志记录为 All channels closed。"
                ),
                Section(
                    "实现",
                    "对每个 LE 地址发起一次 direct connectGatt(TRANSPORT_LE) 且不关闭。必须使用 direct：" +
                            "opportunistic 连接不进入 hold-link 表。用户在系统设置中主动断开时，本模块会执行 " +
                            "disconnect() 与 close() 释放持有者。"
                ),
                Section(
                    "权衡",
                    "该 LE 链路无法进入深度睡眠，功耗略有增加。"
                ),
            ),
        ),
        Option(
            key = "adopt_ctx",
            title = "补全可用音频上下文",
            subtitle = "填充 mAvailableContexts，否则 CIG/CIS 不建立",
            kind = OptionKind.SWITCH,
            group = OptionGroup.STABILITY,
            detail = listOf(
                Section(
                    "技术原理",
                    "该版本中 LeAudioGroupDescriptor.mAvailableContexts 从未由 native 事件赋值，" +
                            "导致 isGroupAvailableForStream() 恒为 false，CIG/CIS 无法建立。"
                ),
                Section(
                    "实现",
                    "取 native AUDIO_CONF_CHANGED 事件中的 available_contexts 回写至 Java 层描述符。"
                ),
            ),
        ),
        Option(
            key = "at",
            title = "应答厂商 AT 指令",
            subtitle = "按 ColorOS 口径回答 VDID / VDSF / OESF 询问",
            kind = OptionKind.SWITCH,
            group = OptionGroup.PROTOCOL,
            detail = listOf(
                Section(
                    "技术原理",
                    "耳机在每次回连时查询 AT+VDID=? / +VDSF= / +OESF=，用于判定对端是否为同厂手机。" +
                            "按 ColorOS 规范应答（+VDID: 1946、+VDSF: 7、+OESF=7,<值&掩码>）后耳机才启用完整功能集。"
                ),
            ),
        ),
        Option(
            key = "vdsp",
            title = "下发 +VDSP=1,1",
            subtitle = "SLC 建立后补一次原厂初始化",
            kind = OptionKind.SWITCH,
            group = OptionGroup.PROTOCOL,
            detail = listOf(
                Section(
                    "技术原理",
                    "在 HFP 服务级连接建立后下发 +VDSP=1,1，等价于 ColorOS 的 " +
                            "OplusLeAudioServiceExt.oplusHandleConnect。"
                ),
            ),
        ),
        Option(
            key = "vendor_id",
            title = "厂商标识 Vendor ID",
            subtitle = "应答 AT+VDID 的取值",
            kind = OptionKind.TEXT,
            group = OptionGroup.PROTOCOL,
            defaultText = "1946",
            hint = "1946",
            detail = listOf(
                Section(
                    "技术原理",
                    "OPPO 的 vendor id 为 1946（0x079A），耳机据此判定对端设备归属。"
                ),
            ),
        ),
        Option(
            key = "oesf_mask",
            title = "OESF 功能掩码",
            subtitle = "+OESF 回包的按位掩码",
            kind = OptionKind.TEXT,
            group = OptionGroup.PROTOCOL,
            defaultText = "0x3f",
            hint = "0x3f",
            detail = listOf(
                Section(
                    "技术原理",
                    "+OESF 用于协商手机侧支持的扩展特性，回包按位与一个掩码，" +
                            "可屏蔽使耳机进入 OPPO 私有分支的特性位。"
                ),
            ),
        ),
    )
}
