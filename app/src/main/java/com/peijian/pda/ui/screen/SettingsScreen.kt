package com.peijian.pda.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.peijian.pda.barcode.BarcodeRules
import com.peijian.pda.importer.PackingImporter
import com.peijian.pda.ui.AppViewModel
import com.peijian.pda.ui.SectionCard

@Composable
fun SettingsScreen(vm: AppViewModel) {
    val s by vm.settings.collectAsStateWithLifecycle()

    var url by remember(s.serverUrl) { mutableStateOf(s.serverUrl) }
    var token by remember(s.token) { mutableStateOf(s.token) }
    var operator by remember(s.operator) { mutableStateOf(s.operator) }
    var device by remember(s.deviceName) { mutableStateOf(s.deviceName) }
    var separator by remember(s.separator) { mutableStateOf(s.separator) }
    var policy by remember(s.duplicatePolicy) { mutableStateOf(s.duplicatePolicy) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SectionCard("操作员 / 设备") {
            OutlinedTextField(
                value = operator,
                onValueChange = { operator = it },
                label = { Text("操作员姓名或工号", fontSize = 12.sp) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = device,
                onValueChange = { device = it },
                label = { Text("本机名称（同步时显示）", fontSize = 12.sp) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
            )
            Text(
                "记录会带上操作员姓名（如：张三）和本机名称（如：一号机）",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        SectionCard("电脑端连接") {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("服务地址", fontSize = 12.sp) },
                placeholder = { Text("192.168.1.5:8000") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text("配对令牌", fontSize = 12.sp) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
            )
        }

        SectionCard("箱码格式") {
            Text(
                "客户编码 + 生产单号 + 箱号，用下面的分隔符连接。",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                Modifier.padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf("-", "/", "_", "").forEach { sep ->
                    FilterChip(
                        selected = separator == sep,
                        onClick = { separator = sep },
                        label = { Text(if (sep.isEmpty()) "无分隔" else "「$sep」", fontSize = 11.sp) }
                    )
                }
            }
            OutlinedTextField(
                value = separator,
                onValueChange = { separator = it },
                label = { Text("自定义分隔符（可空）", fontSize = 12.sp) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
            )
            Text(
                "示例：${BarcodeRules.build("W00305", "P108580", "1", separator)}",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 6.dp)
            )
            Text(
                "扫码时带不带分隔符都能识别（系统会忽略符号、只比对字母数字）。",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        SectionCard("重复箱号处理") {
            Text(
                "源表里偶尔出现同一「客户+单号+箱号」多行（重新打包 / 箱号复用）。",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            listOf(
                PackingImporter.DuplicatePolicy.MERGE to "合并数量（同箱同配件相加）",
                PackingImporter.DuplicatePolicy.LATEST to "以最后一次打包为准",
                PackingImporter.DuplicatePolicy.KEEP_ALL to "全部保留（自动加 -2/-3 后缀）"
            ).forEach { (value, label) ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.Start
                ) {
                    FilterChip(
                        selected = policy == value,
                        onClick = { policy = value },
                        label = { Text(label, fontSize = 11.sp) }
                    )
                }
            }
        }

        Button(
            onClick = { vm.saveSettings(url, token, operator, device, separator, policy) },
            modifier = Modifier.fillMaxWidth()
        ) { Text("保存设置") }

        SectionCard("关于") {
            Text("配件管理 PDA  v0.1.0", fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Text(
                "Android 手持 + 电脑端同步服务；数据格式参考「配件记录.xlsx / 打包记录」。",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "当前数据版本 rev=${s.lastRev}",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
