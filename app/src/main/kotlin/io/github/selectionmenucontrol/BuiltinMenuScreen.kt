package io.github.selectionmenucontrol

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

private data class BuiltinAction(val name: String, val flag: Int, val hint: String)

private val builtinActions = listOf(
    BuiltinAction("复制", BuiltinMenuRules.COPY, "系统文字菜单"),
    BuiltinAction("剪切", BuiltinMenuRules.CUT, "可编辑文本"),
    BuiltinAction("粘贴", BuiltinMenuRules.PASTE, "输入框中的操作"),
    BuiltinAction("全选", BuiltinMenuRules.SELECT_ALL, "选中文字"),
    BuiltinAction("分享", BuiltinMenuRules.SHARE, "系统分享文本"),
    BuiltinAction("纯文本粘贴", BuiltinMenuRules.PASTE_PLAIN, "不保留格式"),
    BuiltinAction("撤销", BuiltinMenuRules.UNDO, "应用支持时显示"),
    BuiltinAction("重做", BuiltinMenuRules.REDO, "应用支持时显示"),
)

@Composable
internal fun BuiltinMenuScreen(activity: MainActivity, onBack: () -> Unit) {
    var hidden by remember { mutableIntStateOf(BuiltinRuleStore.read(activity)) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier.fillMaxSize()
            .background(MiuixTheme.colorScheme.background)
            .statusBarsPadding().navigationBarsPadding(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Image(
                    painterResource(R.drawable.ic_back),
                    contentDescription = "返回",
                    modifier = Modifier.size(24.dp),
                )
            }
            Text(
                "系统文字操作",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        Text(
            "关闭开关 = 隐藏该操作。默认全部显示。",
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onBackgroundVariant,
        )
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item {
                Card(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(
                        "仅在 LSPosed 作用域内的原生 TextView / EditText 菜单上尝试生效。",
                        modifier = Modifier.padding(16.dp),
                        style = MiuixTheme.textStyles.body2,
                    )
                    Text(
                        "网页、Compose、应用自定义菜单暂不保证兼容。请勿同时启用其他文本菜单过滤模块。",
                        modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 14.dp),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onBackgroundVariant,
                    )
                }
            }
            items(builtinActions, key = { it.flag }) { entry ->
                Card(modifier = Modifier.padding(horizontal = 16.dp, vertical = 3.dp)) {
                    SwitchPreference(
                        checked = (hidden and entry.flag) == 0,
                        onCheckedChange = { shown ->
                            if (!saving) {
                                val updated = if (shown) hidden and entry.flag.inv()
                                    else hidden or entry.flag
                                if (updated != hidden) {
                                    saving = true
                                    scope.launch {
                                        val success = withContext(Dispatchers.IO) {
                                            BuiltinRuleStore.save(updated)
                                        }
                                        if (success) hidden = updated
                                        else Toast.makeText(
                                            activity, "保存失败，请检查 Root 授权", Toast.LENGTH_SHORT
                                        ).show()
                                        saving = false
                                    }
                                }
                            }
                        },
                        title = entry.name,
                        summary = entry.hint,
                    )
                }
            }
            item {
                Text(
                    "规则保存在 Settings.Global。重新打开文字菜单后检查结果；规则刷新可能延迟几秒。",
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                )
            }
        }
    }
}
