# OPPOLeaConnect

让 OPPO / OnePlus 的 LE Audio 耳机在小米 HyperOS 上使用 LE Audio（LC3），并修掉这一组合下若干连不上、连上又掉、延迟偏高的问题。

LSPosed 模块，作用域只有一个：`com.android.bluetooth`。

## 它解决什么

在 HyperOS 上接 OPPO 耳机（实测机型：小米 17 + Enco X3）会遇到一组互相纠缠的问题，本模块逐项处理：

| 现象 | 原因所在 | 对应开关 |
| --- | --- | --- |
| 开盖后总是先连成经典蓝牙，LC3 上不去 | 耳机一旦有经典链路就停发 LE 广播 | LE Audio 连接优先 |
| LE Audio 连上但没有声音 / CIG 不建立 | 组描述符的可用上下文从未被填充 | 补全可用音频上下文 |
| 几秒后只剩一只耳有声 | LE ACL 上没有 GATT 持有者时被装载 4 秒闲置定时器拆链 | 保持 GATT 链路持有 |
| 开关一次蓝牙就再也连不上，必须回盒重开盖 | 系统只发 LE 协商，超时后不再拨 BR/EDR | 蓝牙重启后补拨经典 |
| 手动断开后又被连回来 | 断开走的是 `disconnectAllEnabledProfiles`，不改连接策略 | 内置：识别用户主动断开后不再补拨、不占链路、拒绝来向 |
| 合盖后手机仍持续尝试连接 | LE 断开事件无法区分"耳机睡了"和"链路掉了" | 已移除该类补拨触发点 |
| LC3 延迟和 AAC 差不多 | Media 场景的候选配置里没有低延迟档 | 全局低延迟 |
| 耳机按 OPPO 私有协议询问对端机型 | 非 ColorOS 手机不回答厂商 AT | 应答厂商 AT 指令 / 下发 +VDSP |

所有动作都挂在系统自身的事件上（profile 状态、PhonePolicy 回连、LE Audio 连接与断开、native 栈事件），**没有轮询，也没有"等 N 秒"这类时间窗口判断**。

## 安装

1. 需要已 root 并安装 LSPosed（或同类框架）。
2. 在 [Releases](https://github.com/Leaf-lsgtky/OPPOLeaConnect/releases) 下载 APK 并安装。
3. 打开 LSPosed → 找到 OPPOLeaConnect → 启用，作用域勾选**蓝牙**（`com.android.bluetooth`）。
4. 重启蓝牙（开关一次即可），或重启手机。
5. 打开模块，首页状态卡显示"已生效"即表示蓝牙进程已经加载并读取过配置。

## 关于"全局低延迟"

HyperOS 的 LE Audio 配置表在 `/apex/com.android.bt/etc/bluetooth/le_audio/audio_set_scenarios.json`，Media 场景没有低延迟档位，默认协商到的传输延迟约 85ms + presentation delay 40ms，与 AAC 的 150–200ms 差距很小。打开这一项会引导协议栈按 Game 场景选取 7.5ms framing 的配置，代价是单耳码率从 124kbps 降到 60kbps，音质变化需要自己判断，因此**默认关闭**。

## 构建

```bash
# 需要 JDK 17+、Android SDK（platforms;android-37、build-tools）
cp keystore.properties.example keystore.properties   # 填入自己的签名信息
./gradlew :app:moduleApk
# 产物：app/build/outputs/apk/module/OPPOLeaConnect-v<version>.apk
```

模块元数据（`META-INF/xposed/`）必须在签名前注入 APK，因此 release 不走 AGP 自带签名：`:app:moduleApk` 会取未签名产物、注入元数据、`zipalign -p` 对齐，再用 `apksigner` 按 v1+v2+v3 签名。

Xposed API 桩类位于 `:xposed-stubs`，只作为 `compileOnly` 依赖 —— 它们一旦进入 `classes.dex`，LSPosed 会直接拒绝加载模块。

### CI

GitHub Actions（`.github/workflows/release.yml`）在推送 `v*` tag 或手动触发时构建并发布 Release。需要在仓库 secrets 中配置：

- `KEYSTORE_B64`：签名库的 base64
- `KEYSTORE_PASSWORD` / `KEY_PASSWORD` / `KEY_ALIAS`

签名材料不进入仓库，也不进入历史记录。

## 已知限制

- 只针对 HyperOS 的蓝牙实现验证过，其它 ROM 的类名与门控可能不同。
- "全局低延迟"目前只在 LE Audio 连接建立与组激活时置位，播放开始后系统可能把状态清回 Media，正在推流时的低延迟档位仍不稳定。
- 耳机固件侧的私有低延迟指令（`AT+LOWLATENCY`）本模块不下发，手机侧协商才是主因。

## 许可与致谢

- 许可证：GPL-3.0（见 `LICENSE`）
- UI 基于 [Miuix](https://github.com/compose-miuix-ui/miuix)（HyperOS 风格的 Compose 组件库）
- 界面结构与状态卡样式参考 [KernelSU](https://github.com/tiann/KernelSU) 与 [InstallerX-Revived](https://github.com/rosan565778380/InstallerX-Revived) 的 Miuix 实现
