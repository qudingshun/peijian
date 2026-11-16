package com.peijian.pda.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.peijian.pda.data.PackingRepository
import com.peijian.pda.logic.DateText
import com.peijian.pda.ui.AppViewModel
import com.peijian.pda.ui.EmptyHint
import com.peijian.pda.ui.ScanField
import com.peijian.pda.ui.SectionCard

@Composable
fun StocktakeScreen(vm: AppViewModel) {
    val check by vm.openCheck.collectAsStateWithLifecycle()
    val scans by vm.checkScans.collectAsStateWithLifecycle()
    val lastScan by vm.lastScan.collectAsStateWithLifecycle()
    val bookBoxes by vm.stockBoxCount.collectAsStateWithLifecycle(initialValue = 0)
    val settings by vm.settings.collectAsStateWithLifecycle()

    var confirmFinish by remember { mutableStateOf(false) }
    var confirmCancel by remember { mutableStateOf(false) }

    if (check == null) {
        StartCheckForm(vm, bookBoxes)
    } else {
        val c = check!!
        val matched = scans.count { it.expected }
        val abnormal = scans.count { !it.expected }
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SectionCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text(c.batchNo, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text(
                            "开始 ${DateText.dateTime(c.startedAt)} · ${c.operator}",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("$matched / $bookBoxes", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Text("已盘 / 应存箱", fontSize = 10.sp)
                    }
                }
                LinearProgressIndicator(
                    progress = { if (bookBoxes <= 0) 0f else matched.toFloat() / bookBoxes },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { confirmFinish = true }, modifier = Modifier.weight(1f)) { Text("结束盘点") }
                    OutlinedButton(onClick = { confirmCancel = true }, modifier = Modifier.weight(1f)) { Text("取消") }
                }
            }

            ScanField(label = "扫描箱码进行盘点", onSubmit = { vm.scanStocktake(it) })

            if (abnormal > 0) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Text(
                        "有 $abnormal 箱异常（系统无此箱或已发出），导出差异表时会单独列出",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            lastScan?.let { r ->
                SectionCard("上一枪") {
                    Text(
                        r.box?.barcode ?: r.code,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = if (r.status == PackingRepository.ScanStatus.OK) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                    Text(
                        when (r.status) {
                            PackingRepository.ScanStatus.OK -> "✓ 已登记"
                            PackingRepository.ScanStatus.DUPLICATE -> "✗ 这一箱已经盘过"
                            PackingRepository.ScanStatus.ALREADY_SHIPPED -> "⚠ ${r.message}"
                            PackingRepository.ScanStatus.NOT_FOUND -> "⚠ ${r.message}"
                            else -> r.message
                        },
                        fontSize = 12.sp
                    )
                    if (r.items.isNotEmpty()) {
                        Text(
                            r.items.joinToString("；") { "${it.partCode} ${it.partName} ×${it.quantity}" },
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            SectionCard("扫描记录 ${scans.size} 条") {
                if (scans.isEmpty()) {
                    EmptyHint("尚未扫描。按箱逐件扫，系统只核对「在库」的箱。")
                } else {
                    scans.forEach { s ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(s.barcode, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                Text(
                                    s.partSummary.ifBlank { "未匹配到箱" },
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2
                                )
                            }
                            Text(
                                if (s.expected) "在库" else "异常",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (s.expected) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error
                            )
                        }
                        HorizontalDivider()
                    }
                }
            }

            Text(
                "提示：盘点不会改变箱的在库状态，只记录「这一箱此刻在不在」。全盘结束后到「记录与查询 → 盘点记录」导出差异表。",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (confirmFinish) {
        AlertDialog(
            onDismissRequest = { confirmFinish = false },
            title = { Text("结束盘点？") },
            text = { Text("已扫 ${scans.count { it.expected }} 箱，系统应存 $bookBoxes 箱。结束后本批次不能再扫码，可导出差异表。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmFinish = false
                    vm.finishCheck()
                }) { Text("结束") }
            },
            dismissButton = { TextButton(onClick = { confirmFinish = false }) { Text("继续扫") } }
        )
    }
    if (confirmCancel) {
        AlertDialog(
            onDismissRequest = { confirmCancel = false },
            title = { Text("取消本次盘点？") },
            text = { Text("本批次已扫的 ${scans.size} 条记录会删除。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmCancel = false
                    vm.cancelCheck()
                }) { Text("取消盘点") }
            },
            dismissButton = { TextButton(onClick = { confirmCancel = false }) { Text("返回") } }
        )
    }
}

@Composable
private fun StartCheckForm(vm: AppViewModel, bookBoxes: Int) {
    var batchNo by remember { mutableStateOf("PD" + DateText.docStamp()) }
    var note by remember { mutableStateOf("") }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SectionCard("新建盘点批次") {
            OutlinedTextField(
                value = batchNo,
                onValueChange = { batchNo = it },
                label = { Text("盘点批次号", fontSize = 12.sp) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("备注（选填，如：月末全盘 / 某区域）", fontSize = 12.sp) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
            )
            Text(
                "系统当前应存 $bookBoxes 箱",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(top = 8.dp)
            )
            Button(
                onClick = { vm.startCheck(batchNo, note) },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) { Text("开始盘点") }
        }

        SectionCard("盘点规则") {
            Text("• 扫到「在库」的箱 → 实盘 +1；系统应存但没扫到的箱 → 盘亏。", fontSize = 11.sp)
            Text("• 扫到已发出 / 系统不存在的箱 → 记为异常，导出时单列。", fontSize = 11.sp)
            Text("• 差异按配件编码汇总数量，可直接交给财务对数。", fontSize = 11.sp)
            Text(
                "建议：盘点前先把已发出的箱清出库位，避免混扫。",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
