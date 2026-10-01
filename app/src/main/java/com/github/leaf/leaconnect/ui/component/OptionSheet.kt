package com.github.leaf.leaconnect.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.leaf.leaconnect.ui.data.Option
import com.github.leaf.leaconnect.ui.data.OptionKind
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 选项详情弹层。
 *
 * 顶部那张 Card 就是列表里的那一行本身（同一个 BasicComponent + Switch），所以弹层里
 * 改开关和在外面改是同一个控件；弹层自身不再重复标题，避免和这一行的标题撞两次。
 * 正文里不套 Card —— OverlayBottomSheet 已有 24.dp 横向 insideMargin，再包一层等于凭空
 * 多一圈圆角。底部留 28.dp 白，避免最后一段文字压在系统手势条上。
 */
@Composable
fun OptionSheet(
    option: Option,
    show: Boolean,
    value: String?,
    onValueChange: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    OverlayBottomSheet(
        show = show,
        startAction = {
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = MiuixIcons.Close,
                    contentDescription = "关闭",
                    tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                )
            }
        },
        onDismissRequest = onDismiss,
        insideMargin = DpSize(24.dp, 0.dp),
        // 默认 true 会把弹层挂到最外层 Scaffold 的 popup 层；NavDisplay 下每个页面各有
        // 自己的 Scaffold，挂在 root 上就等于挂在一个不存在的层里，点了没有任何反应。
        renderInRootScaffold = false,
    ) {
        Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                // 弹层底色是 colorScheme.background（浅色下近白），卡片再用默认的
                // surfaceContainer 就看不出来；surfaceContainerHigh 是参考项目里
                // KernelSU 也在用的那档灰。
                colors = CardDefaults.defaultColors(
                    color = MiuixTheme.colorScheme.surfaceContainerHigh,
                ),
            ) {
                if (option.kind == OptionKind.SWITCH) {
                    val checked = rawToBool(value, option.defaultBoolean)
                    BasicComponent(
                        title = option.title,
                        summary = option.subtitle,
                        endActions = {
                            Switch(
                                checked = checked,
                                onCheckedChange = { on ->
                                    onValueChange(if (on) "true" else "false")
                                },
                            )
                        },
                    )
                } else {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = option.subtitle,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        Spacer(Modifier.height(8.dp))
                        TextField(
                            value = value ?: "",
                            onValueChange = { onValueChange(if (it.isEmpty()) null else it) },
                            label = option.hint,
                            singleLine = true,
                            insideMargin = DpSize(0.dp, 12.dp),
                        )
                    }
                }
            }

            option.detail.forEach { sec ->
                Spacer(Modifier.height(16.dp))
                if (sec.heading.isNotEmpty()) {
                    Text(
                        text = sec.heading,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MiuixTheme.colorScheme.onBackground,
                    )
                    Spacer(Modifier.height(4.dp))
                }
                Text(
                    text = sec.body,
                    fontSize = 14.sp,
                    color = MiuixTheme.colorScheme.onBackground,
                )
            }

            Spacer(Modifier.height(28.dp))
        }
    }
}

private fun rawToBool(raw: String?, def: Boolean): Boolean =
    if (raw == null) def else "true".equals(raw, true) || raw == "1"
