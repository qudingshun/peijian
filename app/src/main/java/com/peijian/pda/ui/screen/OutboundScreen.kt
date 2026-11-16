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
import androidx.compose.material3.HorizontalDivider
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
import com.peijian.pda.ui.InfoLine
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
                            listOfNotNull(
                                s.customerName.ifBlank { s.customerCode },
                                s.receiver.takeIf { it.isNotBlank() }?.let { "收货：$it" }
                            ).joinToString(" · ").ifBlank { "未填客户" },
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
                        Text("取消发货单")
                    }
                }
            }

            ScanField(
                label = "扫描箱码（${settings.separator} 分隔）",
                onSubmit = { vm.scanOutbound(it) }
            )

            lastScan?.let { r ->
                val ok = r.status == PackingRepository.ScanStatus.OK
                SectionCard(if (ok) "上一箱：登记成功" else "上一箱：${r.message}") {
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
            title = { Text("取消这张发货单？") },
            text = { Text("已扫的 ${boxes.size} 箱会回到在库状态，本次扫描记录删除。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmCancel = false
                    vm.cancelShipment()
                }) { Text("取消发货单") }
            },
            dismissButton = { TextButton(onClick = { confirmCancel = false }) { Text("返回") } }
        )
    }
}

@Composable
private fun StartShipmentForm(vm: AppViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var docNo by remember { mutableStateOf("FH" + DateText.docStamp()) }
    var customerCode by remember { mutableStateOf("") }
    var customerName by remember { mutableStateOf("") }
    var receiver by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SectionCard("新建发货单") {
            OutlinedTextField(
                value = docNo,
                onValueChange = { docNo = it },
                label = { Text("发货单号", fontSize = 12.sp) },
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
                label = { Text("客户名称 / 发往哪里（选填）", fontSize = 12.sp) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
            )
            OutlinedTextField(
                value = receiver,
                onValueChange = { receiver = it },
                label = { Text("收货人 / 物流单号（选填）", fontSize = 12.sp) },
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
                onClick = { vm.startShipment(docNo, customerCode, customerName, receiver, note) },
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
            ) { Text("建立发货单并开始扫码") }
        }

        SectionCard("说明") {
            Text("• 一张发货单 = 一次发货；扫箱时自动按箱内配件登记数量。", fontSize = 11.sp)
            Text("• 同一箱不能重复扫；已发出的箱会提示。", fontSize = 11.sp)
            Text("• 完成后可在「记录与查询 → 发货记录」导出出库明细。", fontSize = 11.sp)
            Text(
                "当前操作员：${settings.operator.ifBlank { settings.deviceName }}（在设置里改）",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
