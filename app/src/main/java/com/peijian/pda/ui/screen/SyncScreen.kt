package com.peijian.pda.ui.screen

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.peijian.pda.logic.DateText
import com.peijian.pda.ui.AppViewModel
import com.peijian.pda.ui.ScanField
import com.peijian.pda.ui.SectionCard

@Composable
fun SyncScreen(vm: AppViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val allBoxes by vm.allBoxCount.collectAsStateWithLifecycle(initialValue = 0)
    val stockBoxes by vm.stockBoxCount.collectAsStateWithLifecycle(initialValue = 0)
    val unsynced by vm.unsyncedShipments.collectAsStateWithLifecycle(initialValue = 0)
    val unsyncedChecks by vm.unsyncedChecks.collectAsStateWithLifecycle(initialValue = 0)

    var url by remember(settings.serverUrl) { mutableStateOf(settings.serverUrl) }
    var token by remember(settings.token) { mutableStateOf(settings.token) }
    var tokenVisible by remember { mutableStateOf(false) }
    val localIp = remember { localIpAddress() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importXlsx(uri)
    }
    val backupPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.prepareRestore(uri)
    }
    val pending by vm.pendingRestore.collectAsStateWithLifecycle()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SectionCard("电脑端同步服务") {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("电脑地址", fontSize = 12.sp) },
                placeholder = { Text("192.168.1.5:8000") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                "配对令牌（忽略大小写）",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                placeholder = { Text("扫配对码自动填入，或看电脑端首页", fontSize = 11.sp) },
                singleLine = true,
                visualTransformation = if (tokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { tokenVisible = !tokenVisible }) {
                        Icon(
                            if (tokenVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = if (tokenVisible) "隐藏令牌" else "显示令牌"
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        vm.saveSettings(url, token, settings.operator, settings.deviceName, settings.separator, settings.duplicatePolicy)
                        vm.testConnection()
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("保存并测试") }
                OutlinedButton(
                    onClick = { vm.pull() },
                    modifier = Modifier.weight(1f)
                ) { Text("拉取新增") }
            }
            OutlinedButton(
                onClick = { vm.reconcile() },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
            ) { Text("一键对账（修复差箱/状态）") }
        }

        SectionCard("扫码配对") {
            Text(
                "电脑端打开同步服务网页 → 显示配对二维码 → 用扫码枪扫屏幕",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            ScanField(label = "配对二维码内容 / IP:端口#令牌", onSubmit = { vm.applyPairing(it) })
            Text(
                "配对成功后自动填入上面的地址和令牌，再点「保存并测试」。",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        SectionCard("导入 Excel（无网络时）") {
            Text(
                "把电脑上的「配件记录.xlsx」拷到 PDA（或用微信/共享文件夹打开），直接选文件导入。",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                onClick = { picker.launch(arrayOf("*/*")) },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) { Text("选择 配件记录.xlsx") }
        }

        SectionCard("上传本机记录") {
            Text(
                "发货单 ${unsynced} 张、盘点 ${unsyncedChecks} 批待上传。",
                fontSize = 12.sp
            )
            Button(
                onClick = { vm.push() },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) { Text("立即上传到电脑") }
        }

        SectionCard("数据库备份与恢复") {
            Text(
                "备份会把全库（含未上传单据）和同步进度一起打包成 zip，可发到电脑 / 微信保存。换机或重装后用它恢复，避免丢单据。",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = { vm.backupDatabase() }, modifier = Modifier.weight(1f)) { Text("立即备份") }
                OutlinedButton(
                    onClick = { backupPicker.launch(arrayOf("application/zip", "*/*")) },
                    modifier = Modifier.weight(1f)
                ) { Text("从备份恢复") }
            }
            Text(
                "备份目录：${com.peijian.pda.backup.DbBackup.backupDir(androidx.compose.ui.platform.LocalContext.current).absolutePath}",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        SectionCard("数据状态") {
            InfoRow("已导入箱数", "$allBoxes")
            InfoRow("在库箱数", "$stockBoxes")
            InfoRow("数据版本（rev）", "${settings.lastRev}")
            InfoRow("上次同步", if (settings.lastSyncAt > 0) DateText.dateTime(settings.lastSyncAt) else "—")
            InfoRow("本机名称", settings.deviceName)
            InfoRow("本机 WiFi IP", localIp)
        }

        SectionCard("电脑端怎么开服务") {
            Text("1. 把 pc-server 文件夹复制到电脑（如 E:\\peijian\\pc-server）", fontSize = 11.sp)
            Text("2. 双击「启动同步服务.bat」", fontSize = 11.sp)
            Text("3. 首次运行会自动在 data 目录生成令牌并显示配对二维码", fontSize = 11.sp)
            Text("4. PDA 与本机连同一个 WiFi / 路由器（关闭 AP 隔离）", fontSize = 11.sp)
            Text(
                "5. 两边 IP 要在同一网段：PDA 见上方「本机 WiFi IP」，电脑用 ipconfig 查",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.error
            )
            Text(
                "详见仓库 docs/04-部署与使用.md",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium
            )
        }
    }

    val restore = pending
    if (restore != null) {
        AlertDialog(
            onDismissRequest = { vm.cancelRestore() },
            title = { Text("确认恢复数据库？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("此操作会覆盖当前全部数据，恢复后应用自动重启。", fontSize = 13.sp)
                    com.peijian.pda.ui.InfoLine("备份箱数", "${restore.manifest.boxCount}")
                    com.peijian.pda.ui.InfoLine("数据库版本", "${restore.manifest.dbVersion}")
                    com.peijian.pda.ui.InfoLine("未上传发货单", "${restore.manifest.unsyncedShipments}")
                    com.peijian.pda.ui.InfoLine("未上传盘点", "${restore.manifest.unsyncedChecks}")
                    com.peijian.pda.ui.InfoLine(
                        "创建时间",
                        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                            .format(java.util.Date(restore.manifest.createdAt))
                    )
                    Text("恢复前会自动为当前库另存一份备份。", fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.confirmRestore() }) { Text("确认恢复") }
            },
            dismissButton = {
                TextButton(onClick = { vm.cancelRestore() }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    com.peijian.pda.ui.InfoLine(label, value)
}

/** 取本机 WLAN 的 IPv4，便于和电脑 IP 对比是否同网段（排查连不上的首要原因）。 */
private fun localIpAddress(): String = runCatching {
    val ifaces = java.net.NetworkInterface.getNetworkInterfaces()
    while (ifaces.hasMoreElements()) {
        val iface = ifaces.nextElement()
        if (!iface.isUp || iface.isLoopback || iface.isVirtual) continue
        val addrs = iface.inetAddresses
        while (addrs.hasMoreElements()) {
            val a = addrs.nextElement()
            if (a is java.net.Inet4Address && !a.isLoopbackAddress && !a.isLinkLocalAddress) {
                return a.hostAddress ?: "—"
            }
        }
    }
    "—"
}.getOrDefault("—")
