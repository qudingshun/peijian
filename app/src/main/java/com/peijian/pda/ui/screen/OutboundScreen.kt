package com.peijian.pda.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
fun OutboundScreen(vm: AppViewModel) {
    val shipment by vm.openShipment.collectAsStateWithLifecycle()
    val boxes by vm.shipmentBoxes.collectAsStateWithLifecycle()
    val lastScan by vm.lastScan.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()

    var confirmFinish by remember { mutableStateOf(false) }
    var confirmCancel by remember { mutableStateOf(false) }
    var showPlan by remember { mutableStateOf(true) }

    if (shipment == null) {
        StartShipmentForm(vm)
    } else {
        val s = shipment!!
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
                        Text(s.docNo, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text(
                            s.customerName.ifBlank { s.customerCode }.takeIf { it.isNotBlank() } ?: "未填客户",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("${boxes.size} 箱", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Text("${boxes.sumOf { it.totalQty }} 件", fontSize = 11.sp)
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { confirmFinish = true }, modifier = Modifier.weight(1f)) {
                        Text("完成发货")
                    }
                    OutlinedButton(onClick = { confirmCancel = true }, modifier = Modifier.weight(1f)) {
                        Text("取消发货柜")
                    }
                }
            }

            // 预选箱（白名单）
            PlanSection(vm, expanded = showPlan, onToggle = { showPlan = !showPlan })

            ScanField(
                label = "扫描箱码（${settings.separator} 分隔）",
                onSubmit = { vm.scanOutbound(it) }
            )

            lastScan?.let { r ->
                val ok = r.status == PackingRepository.ScanStatus.OK
                val title = when (r.status) {
                    PackingRepository.ScanStatus.OK -> "上一箱：登记成功"
                    PackingRepository.ScanStatus.WRONG_CUSTOMER -> "报警：客户不符，未记录"
                    PackingRepository.ScanStatus.NOT_IN_PLAN -> "报警：不在预选范围，未记录"
                    else -> "上一箱：${r.message}"
                }
                SectionCard(title) {
                    if (r.box != null) {
                        Text(
                            r.box!!.barcode,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                        )
                        Text(
                            "${r.box!!.customerName.ifBlank { r.box!!.customerCode }} · ${r.box!!.orderNo} 箱${r.box!!.boxNo}",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (r.items.isNotEmpty()) {
                        HorizontalDivider(Modifier.padding(vertical = 6.dp))
                        r.items.forEach { item ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 1.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    "${item.partCode}  ${item.partName}",
                                    fontSize = 12.sp,
                                    modifier = Modifier.weight(1f)
                                )
                                Text("×${item.quantity}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }

            SectionCard("本次已扫 ${boxes.size} 箱") {
                if (boxes.isEmpty()) {
                    EmptyHint("还没有扫描。把扫码枪对准箱子标签即可。")
                } else {
                    boxes.forEach { row ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(row.barcode, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                Text(
                                    "${row.orderNo} 箱${row.boxNo} · ${row.itemSummary}",
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2
                                )
                            }
                            Text("${row.totalQty}", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            IconButton(onClick = { vm.removeShipmentBox(row.shipmentBoxId) }) {
                                Icon(Icons.Filled.Close, contentDescription = "撤回", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }

            Text(
                "操作员：${settings.operator.ifBlank { settings.deviceName }} · 建立于 ${DateText.dateTime(s.createdAt)}",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (confirmFinish) {
        AlertDialog(
            onDismissRequest = { confirmFinish = false },
            title = { Text("完成发货？") },
            text = { Text("共 ${boxes.size} 箱、${boxes.sumOf { it.totalQty }} 件。完成后箱状态变为「已发出」，历史出库单可在记录里导出。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmFinish = false
                    vm.finishShipment()
                }) { Text("完成") }
            },
            dismissButton = { TextButton(onClick = { confirmFinish = false }) { Text("再检查一下") } }
        )
    }
    if (confirmCancel) {
        AlertDialog(
            onDismissRequest = { confirmCancel = false },
            title = { Text("取消这个发货柜？") },
            text = { Text("已扫的 ${boxes.size} 箱会回到在库状态，本次扫描记录删除。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmCancel = false
                    vm.cancelShipment()
                }) { Text("取消发货柜") }
            },
            dismissButton = { TextButton(onClick = { confirmCancel = false }) { Text("返回") } }
        )
    }
}

/** 预选箱白名单：搜索在库箱并勾选；勾选后仅白名单内箱可扫 */
@Composable
private fun PlanSection(vm: AppViewModel, expanded: Boolean, onToggle: () -> Unit) {
    val candidates by vm.planCandidates.collectAsStateWithLifecycle()
    val selected by vm.planBoxes.collectAsStateWithLifecycle()
    val query by vm.planQuery.collectAsStateWithLifecycle()
    val selectedIds = remember(selected) { selected.map { it.boxId }.toHashSet() }

    SectionCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                "预选箱（本次发货范围）  已选 ${selected.size}",
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp
            )
            TextButton(onClick = onToggle) { Text(if (expanded) "收起" else "展开") }
        }
        Text(
            "从不发货的箱里提前勾选本次要发的箱；勾选后只认清单内的箱，扫错会报警且不记录。不勾选则不限制。",
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (expanded) {
            OutlinedTextField(
                value = query,
                onValueChange = { vm.setPlanQuery(it) },
                label = { Text("搜索箱码 / 单号 / 配件 / 客户", fontSize = 11.sp) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
            )
            if (selected.isNotEmpty()) {
                TextButton(onClick = { vm.clearPlan() }) { Text("清空已选（恢复不限制）") }
            }
            if (candidates.isEmpty()) {
                EmptyHint("没有匹配的在库箱。")
            } else {
                Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    candidates.forEach { row ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .background(
                                    if (row.id in selectedIds) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                                    else Color.Transparent,
                                    RoundedCornerShape(6.dp)
                                )
                                .padding(horizontal = 2.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = row.id in selectedIds,
                                onCheckedChange = { vm.togglePlanBox(row) }
                            )
                            Column(Modifier.weight(1f)) {
                                Text("${row.orderNo} 箱${row.boxNo}", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                Text(
                                    "${row.customerName.ifBlank { row.customerCode }} · ${row.partSummary} · ${row.totalQty}件",
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StartShipmentForm(vm: AppViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var docNo by remember { mutableStateOf("FH" + DateText.docStamp()) }
    var customerCode by remember { mutableStateOf("") }
    var customerName by remember { mutableStateOf("") }
    var nameResolved by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }

    // 输入客户编码后自动带出客户名称（数据源：已导入/同步的箱表）
    LaunchedEffect(customerCode) {
        val code = customerCode.trim()
        if (code.isEmpty()) {
            nameResolved = false
            return@LaunchedEffect
        }
        val resolved = vm.lookupCustomerName(code)
        if (resolved != null && customerCode.trim() == code) {
            customerName = resolved
            nameResolved = true
        } else {
            nameResolved = false
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SectionCard("新建发货柜") {
            OutlinedTextField(
                value = docNo,
                onValueChange = { docNo = it },
                label = { Text("发货柜号", fontSize = 12.sp) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = customerCode,
                onValueChange = { customerCode = it },
                label = { Text("客户编码（选填）", fontSize = 12.sp) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
            )
            OutlinedTextField(
                value = customerName,
                onValueChange = { customerName = it },
                label = {
                    Text(
                        if (nameResolved) "客户名称（已从数据源带出）" else "客户名称（选填，可按编码自动带出）",
                        fontSize = 12.sp
                    )
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
            )
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("备注（选填）", fontSize = 12.sp) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
            )
            Button(
                onClick = { vm.startShipment(docNo, customerCode, customerName, "", note) },
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
            ) { Text("建立发货柜并开始扫码") }
        }

        SectionCard("说明") {
            Text("• 一个发货柜 = 一次发货；扫箱时自动按箱内配件登记数量。", fontSize = 11.sp)
            Text("• 填了客户编码后，非该客户的箱扫码会报警且不记录。", fontSize = 11.sp)
            Text("• 可先在「预选箱」勾选本次要发的箱；勾选后只认清单内的箱。", fontSize = 11.sp)
            Text("• 同一箱不能重复扫；已发出的箱会提示并报警。", fontSize = 11.sp)
            Text("• 完成后可在「记录与查询 → 发货记录」导出出库明细。", fontSize = 11.sp)
            Text(
                "当前操作员：${settings.operator.ifBlank { settings.deviceName }}（在设置里改）",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
