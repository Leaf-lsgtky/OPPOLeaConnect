package com.github.leaf.leaconnect.ui.screen

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.ui.unit.sp
import com.github.leaf.leaconnect.ui.data.ConfigStore
import com.github.leaf.leaconnect.ui.data.Options
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

const val REPO_URL = "https://github.com/Leaf-lsgtky/OPPOLeaConnect"

@Composable
fun HomeScreen(store: ConfigStore, onAdvanced: () -> Unit) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val scrollBehavior = MiuixScrollBehavior()

    var masterOn by remember { mutableStateOf(store.boolean(Options.master.key, true)) }
    var hb by remember { mutableStateOf(store.heartbeat()) }
    var version by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        version = try {
            val i = context.packageManager.getPackageInfo(context.packageName, 0)
            "${i.versionName} (${i.longVersionCode})"
        } catch (t: Throwable) {
            "?"
        }
        while (true) {
            hb = store.heartbeat()
            masterOn = store.boolean(Options.master.key, true)
            delay(1200L)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = "OPPOLeaConnect",
                largeTitle = "OPPOLeaConnect",
                scrollBehavior = scrollBehavior,
            )
        }
    ) { insets ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .scrollEndHaptic(),
            contentPadding = PaddingValues(top = insets.calculateTopPadding(), bottom = 24.dp),
        ) {
            item {
                StatusBlock(
                    masterOn = masterOn,
                    heartbeat = hb,
                    version = version,
                )
            }

            item {
                Card(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp)
                ) {
                    SwitchPreference(
                        checked = masterOn,
                        onCheckedChange = {
                            masterOn = it
                            store.set(Options.master.key, if (it) "true" else "false")
                        },
                        title = Options.master.title,
                    )
                    ArrowPreference(title = "高级设置", onClick = onAdvanced)
                }
            }

            item { SmallTitle(text = "关于") }

            item {
                Card(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp)
                ) {
                    ArrowPreference(
                        title = "项目地址",
                        summary = REPO_URL.removePrefix("https://"),
                        onClick = { uriHandler.openUri(REPO_URL) },
                    )
                }
            }
        }
    }
}

/**
 * 状态区：一张大卡 + 两张小数据卡，结构照搬 InstallerX 的 MiuixStatusGrid 和
 * KernelSU 的 StatusCard —— 大卡里是"状态词 / 说明行 / 装饰水印 / 底部小字"，
 * 下面并排两张"标签 + 大数字"的小卡。
 *
 * 状态判据不是"我以为我 hook 上了"，而是蓝牙进程真的来 provider 读过配置：
 * 那次跨进程调用就在 hook 生效的必经路径上，没加载就一定不会发生。
 * 好状态用固定绿（Miuix 没有 success 色板，KernelSU 也是自己写死的十六进制），
 * 其余一律中性容器色，不涂红 —— "耳机没戴在耳朵上"不该看起来像出错。
 */
@Composable
private fun StatusBlock(masterOn: Boolean, heartbeat: ConfigStore.Heartbeat, version: String) {
    // 心跳只能证明"蓝牙进程读过配置"，而 Core 的配置读取是事件驱动的（3 秒缓存只在被事件
    // 触发时才刷新），所以心跳变旧是常态，不代表模块掉了。判据因此只看"有过心跳没有"：
    // 从没读过 = 没被加载进进程；读过 = 已生效，并把最近一次的时间摆在副标题。
    val age = heartbeat.ageMs
    val alive = masterOn && age != null
    val level = when {
        !masterOn -> Level.WARN
        alive -> Level.OK
        else -> Level.ERROR
    }
    val title = when {
        !masterOn -> "未启用"
        alive -> "已生效"
        else -> "未生效"
    }
    val subtitle = when {
        !masterOn -> "总开关是关的"
        age == null -> "未加载至进程"
        else -> "${agoText(age)}前读过配置"
    }
    val (container, content) = levelColors(level)
    val accent = levelAccent(level)

    // 逐行照 KernelSU manager/.../home/HomeMiuix.kt 的 StatusCard：
    // 水印是 Box(fillMaxSize).offset(27,31) 里 contentAlignment=BottomEnd 的 110dp Icon；
    // 正文是 Box(fillMaxSize).padding(16,14) + TopStart 的 Column{22sp SemiBold / 1dp / 15sp Medium}；
    // 左下角那条是它放 "LKM"/"GKI" 徽章的位置：padding(16,10) + BottomStart + 16sp Medium。
    // 文字一律不写死颜色，跟着 Card 的 contentColor 走。
    // KernelSU 外面那层 Row(height = IntrinsicSize.Min) 不是装饰：它让 110dp 的水印
    // 参与测量，卡片才被撑到能同时放下顶部两行和左下角那行；省掉就会叠字。
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .padding(horizontal = 12.dp)
            .padding(top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.defaultColors(color = container, contentColor = content),
    ) {
        Box {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .offset(27.dp, 31.dp),
                contentAlignment = Alignment.BottomEnd,
            ) {
                Icon(
                    modifier = Modifier.size(110.dp),
                    imageVector = if (level == Level.OK) Icons.Rounded.CheckCircleOutline
                    else Icons.Rounded.ErrorOutline,
                    contentDescription = null,
                    tint = accent,
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp, 14.dp),
                contentAlignment = Alignment.TopStart,
            ) {
                Column {
                    Text(
                        text = title,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = content,
                    )
                    Spacer(Modifier.height(1.dp))
                    Text(
                        text = subtitle,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = content,
                    )
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp, 10.dp),
                contentAlignment = Alignment.BottomStart,
            ) {
                Text(
                    text = "$version · LE Audio",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = content,
                )
            }
        }
    }
    }
}

private fun agoText(ms: Long): String = when {
    ms < 1000 -> "不到 1 秒"
    ms < 60_000 -> "${ms / 1000} 秒"
    ms < 3_600_000 -> "${ms / 60_000} 分钟"
    else -> "${ms / 3_600_000} 小时"
}

/** 状态分档：绿=已生效，黄=用户自己关掉的，红=该在却没在 */
private enum class Level { OK, WARN, ERROR }

/**
 * 颜色只用参考项目里已有的那几套：KernelSU 的 StatusCard 用固定绿表示 working，
 * WarningCard 用 tertiaryContainer / errorContainer 两档，非动态色时分别落到
 * 0xFFFFF0DB·0xFFF5A623（提示）和 0xFFF8E2E2·0xFFF72727（错误）。
 * 这里不另起炉灶，原样搬过来。
 */
@Composable
private fun levelColors(level: Level): Pair<Color, Color> {
    val cs = MiuixTheme.colorScheme
    if (MiuixTheme.isDynamicColor) {
        return when (level) {
            Level.OK -> cs.secondaryContainer to cs.onSecondaryContainer
            Level.WARN -> cs.tertiaryContainer to cs.onTertiaryContainer
            Level.ERROR -> cs.errorContainer to cs.onErrorContainer
        }
    }
    val dark = isSystemInDarkTheme()
    val container = when (level) {
        Level.OK -> if (dark) Color(0xFF1A3825) else Color(0xFFDFFAE4)
        Level.WARN -> if (dark) Color(0xFF3E2F1B) else Color(0xFFFFF0DB)
        Level.ERROR -> if (dark) Color(0xFF310808) else Color(0xFFF8E2E2)
    }
    // 正文一律是容器对应的近黑色：KernelSU 的 working 卡只传 color，contentColor 走默认
    // onSurfaceContainer；0xFFF5A623 / 0xFFF72727 那两枚是 WarningCard 自己的文字色，
    // 状态大卡不用。
    val content = cs.onSurfaceContainer
    return container to content
}

/**
 * 水印的 tint 是状态强调色，和正文不是一回事 —— KernelSU 的 working 卡里 Icon 用
 * 0xFF36D167（动态色下是 primary.copy(0.8f)），而 Text 不写颜色、跟着卡片默认的
 * onSurfaceContainer 走，所以看起来是"图标有色、字是黑的"。
 * 黄/红两档的强调色取 KernelSU WarningCard 里那两枚文字色 0xFFF5A623 / 0xFFF72727。
 */
@Composable
private fun levelAccent(level: Level): Color {
    val cs = MiuixTheme.colorScheme
    if (MiuixTheme.isDynamicColor) {
        return when (level) {
            Level.OK -> cs.primary.copy(alpha = 0.8f)
            Level.WARN -> cs.onTertiaryContainer
            Level.ERROR -> cs.onErrorContainer
        }
    }
    return when (level) {
        Level.OK -> Color(0xFF36D167)
        Level.WARN -> Color(0xFFF5A623)
        Level.ERROR -> Color(0xFFF72727)
    }
}

