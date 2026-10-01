package com.github.leaf.leaconnect.ui.screen

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.leaf.leaconnect.ui.component.OptionSheet
import com.github.leaf.leaconnect.ui.data.ConfigStore
import com.github.leaf.leaconnect.ui.data.Option
import com.github.leaf.leaconnect.ui.data.OptionGroup
import com.github.leaf.leaconnect.ui.data.OptionKind
import com.github.leaf.leaconnect.ui.data.Options
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import androidx.compose.ui.input.nestedscroll.nestedScroll

/** 分组和顺序都取自 Options.list：那里排第一的就是这里显示的第一 */
private val GROUPS: List<Pair<OptionGroup, List<Option>>> =
    Options.list.groupBy { it.group }.toList()

@Composable
fun AdvancedScreen(store: ConfigStore, onBack: () -> Unit) {
    val context = LocalContext.current
    var values by remember {
        mutableStateOf(Options.list.associate { it.key to store.get(it.key) })
    }
    var sheet by remember { mutableStateOf<Option?>(null) }
    var held by remember { mutableStateOf<Option?>(null) }
    // 收起动画期间 sheet 已经是 null，内容还留着上一个，避免弹层中途空掉
    LaunchedEffect(sheet) { sheet?.let { held = it } }

    fun write(opt: Option, v: String?) {
        store.set(opt.key, v)
        values = values + (opt.key to v)
    }

    val scrollBehavior = MiuixScrollBehavior()
    Scaffold(
        topBar = {
            TopAppBar(
                title = "高级设置",
                largeTitle = "高级设置",
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = MiuixIcons.Back,
                            contentDescription = "返回",
                            tint = MiuixTheme.colorScheme.onSurface,
                        )
                    }
                },
            )
        }
    ) { insets ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .scrollEndHaptic(),
            contentPadding = PaddingValues(top = insets.calculateTopPadding(), bottom = 12.dp),
        ) {
            GROUPS.forEach { (group, opts) ->
                item { SmallTitle(text = group.displayName) }
                item {
                    Card(
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 12.dp)
                    ) {
                        opts.forEach { opt ->
                            OptionRow(
                                option = opt,
                                raw = values[opt.key],
                                onToggle = { on -> write(opt, if (on) "true" else "false") },
                                onOpen = { sheet = opt },
                            )
                        }
                    }
                }
            }

            item {
                Button(
                    onClick = {
                        store.reset()
                        values = Options.list.associate { it.key to store.get(it.key) }
                        Toast.makeText(context, "已恢复默认", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                ) {
                    Text("全部恢复默认")
                }
            }
        }

        // Overlay* 组件由 Scaffold 的 popupHost 渲染，必须写在 content 里面；
        // 写成 Scaffold 的兄弟节点会静默不显示。
        val target = sheet ?: held
        if (target != null) {
            OptionSheet(
                option = target,
                show = sheet != null,
                value = values[target.key],
                onValueChange = { write(target, it) },
                onDismiss = { sheet = null },
            )
        }
    }
}

@Composable
private fun OptionRow(option: Option, raw: String?, onToggle: (Boolean) -> Unit, onOpen: () -> Unit) {
    if (option.kind == OptionKind.SWITCH) {
        val checked = raw?.let { "true".equals(it, true) || it == "1" } ?: option.defaultBoolean
        BasicComponent(
            title = option.title,
            summary = option.subtitle,
            onClick = onOpen,
            endActions = { Switch(checked = checked, onCheckedChange = onToggle) },
        )
    } else {
        BasicComponent(
            title = option.title,
            summary = option.subtitle + "（当前 " +
                    (raw?.takeIf { it.isNotEmpty() } ?: option.defaultText) + "）",
            onClick = onOpen,
        )
    }
}
