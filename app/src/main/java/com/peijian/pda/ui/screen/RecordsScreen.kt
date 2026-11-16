package com.peijian.pda.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.peijian.pda.data.entity.BoxStatus
import com.peijian.pda.importer.PackingImporter
import com.peijian.pda.logic.DateText
import com.peijian.pda.ui.AppViewModel
import com.peijian.pda.ui.EmptyHint

@Composable
fun RecordsScreen(vm: AppViewModel) {
    var tab by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            listOf("箱查询", "发货记录", "盘点记录").forEachIndexed { i, title ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(title, fontSize = 13.sp) })
            }
        }
        when (tab) {
            0 -> BoxSearchTab(vm)
            1 -> ShipmentRecordsTab(vm)
            else -> CheckRecordsTab(vm)
        }
    }

    val detail by vm.boxDetail.collectAsStateWithLifecycle()
    val box = detail?.box
    if (box != null) {
        AlertDialog(
            onDismissRequest = { vm.clearBoxDetail() },
            title = { Text(box.barcode, fontSize = 14.sp) },
            text = {
                Column {
                    Text("${box.customerCode} ${box.customerName}", fontSize = 12.sp)
                    Text("生产单号 ${box.orderNo} · 箱号 ${box.boxNo}", fontSize = 12.sp)
                    Text("打包日期 ${PackingImporter.formatDate(box.packDate)}", fontSize = 12.sp)
                    Text("尺寸 ${box.dimension} · 体积 ${box.volume ?: ""} · 毛重 ${box.grossWeight ?: ""}", fontSize = 11.sp)
                    Text(
                        if (box.status == BoxStatus.IN_STOCK) "状态：在库" else "状态：已发出 ${box.shippedAt?.let { DateText.dateTime(it) } ?: ""}",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (box.status == BoxStatus.IN_STOCK) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error
                    )
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    detail!!.items.forEach {
                        Text("${it.partCode}  ${it.partName}  ×${it.quantity}", fontSize = 12.sp)
                    }
                    if (detail!!.items.isEmpty()) Text("（箱内无配件明细）", fontSize = 12.sp)
                }
            },
            confirmButton = { TextButton(onClick = { vm.clearBoxDetail() }) { Text("关闭") } }
        )
    }
}

@Composable
private fun BoxSearchTab(vm: AppViewModel) {
    val query by vm.boxQuery.collectAsStateWithLifecycle()
    val filter by vm.boxFilter.collectAsStateWithLifecycle()
    val boxes by vm.boxes.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(12.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = { vm.setBoxQuery(it) },
                label = { Text("搜箱码 / 单号 / 箱号 / 配件编码 / 客户", fontSize = 12.sp) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                Modifier.padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilterChip(
                    selected = filter == "",
                    onClick = { vm.setBoxFilter("") },
                    label = { Text("全部", fontSize = 11.sp) }
                )
                FilterChip(
                    selected = filter == BoxStatus.IN_STOCK,
                    onClick = { vm.setBoxFilter(BoxStatus.IN_STOCK) },
                    label = { Text("在库", fontSize = 11.sp) }
                )
                FilterChip(
                    selected = filter == BoxStatus.SHIPPED,
                    onClick = { vm.setBoxFilter(BoxStatus.SHIPPED) },
                    label = { Text("已发出", fontSize = 11.sp) }
                )
            }
        }
        if (boxes.isEmpty()) {
            EmptyHint("没有匹配的箱")
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(boxes, key = { it.id }) { row ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable { vm.loadBoxDetail(row.id) }
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(row.barcode, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            Text(
                                if (row.status == BoxStatus.IN_STOCK) "在库" else "已发出",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (row.status == BoxStatus.IN_STOCK) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error
                            )
                        }
                        Text(
                            "${row.customerName.ifBlank { row.customerCode }} · ${PackingImporter.formatDate(row.packDate)}",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "${row.partSummary} · 共 ${row.totalQty} 件",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2
                        )
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun ShipmentRecordsTab(vm: AppViewModel) {
    val shipments by vm.shipments.collectAsStateWithLifecycle()
    if (shipments.isEmpty()) {
        EmptyHint("还没有发货记录")
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(shipments, key = { it.id }) { s ->
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text(s.docNo, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Text(
                            "${DateText.dateTime(s.createdAt)} · ${s.customerName.ifBlank { s.customerCode }}",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "${s.boxCount} 箱 · ${s.totalQty} 件 · " +
                                when {
                                    s.status == "OPEN" -> "进行中"
                                    s.syncedAt == null -> "已完成 · 未上传"
                                    else -> "已上传"
                                },
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (s.status == "FINISHED") {
                        OutlinedButton(onClick = { vm.exportShipment(s.id) }) { Text("导出", fontSize = 12.sp) }
                    }
                }
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun CheckRecordsTab(vm: AppViewModel) {
    val checks by vm.checks.collectAsStateWithLifecycle()
    if (checks.isEmpty()) {
        EmptyHint("还没有盘点记录")
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(checks, key = { it.id }) { c ->
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text(c.batchNo, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Text(
                            "${DateText.dateTime(c.startedAt)} · 扫描 ${c.scanCount} 条",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            when {
                                c.status == "OPEN" -> "进行中"
                                c.syncedAt == null -> "已结束 · 未上传"
                                else -> "已上传"
                            },
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (c.status == "FINISHED") {
                        OutlinedButton(onClick = { vm.exportStocktake(c.id) }) { Text("导出", fontSize = 12.sp) }
                    }
                }
            }
            HorizontalDivider()
        }
    }
}
