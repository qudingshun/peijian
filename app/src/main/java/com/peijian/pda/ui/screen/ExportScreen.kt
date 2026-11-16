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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.peijian.pda.export.Exporter
import com.peijian.pda.logic.DateText
import com.peijian.pda.ui.AppViewModel
import com.peijian.pda.ui.EmptyHint
import com.peijian.pda.ui.SectionCard

@Composable
fun ExportScreen(vm: AppViewModel, modifier: Modifier = Modifier) {
    val shipments by vm.shipments.collectAsStateWithLifecycle()
    val checks by vm.checks.collectAsStateWithLifecycle()
    val app = androidx.compose.ui.platform.LocalContext.current

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SectionCard("库存报表（一键导出）") {
            Text(
                "按当前「在库」的箱生成：\n• 库存清单：与配件记录.xlsx 的打包记录同样的 17 列\n• 物料汇总：按配件编码合计数量 / 箱数",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                onClick = { vm.exportInventory() },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) { Text("导出库存清单 + 物料汇总") }
        }

        SectionCard("出库明细（按发货单）") {
            val finished = shipments.filter { it.status == "FINISHED" }
            if (finished.isEmpty()) {
                EmptyHint("还没有已完成的发货单")
            } else {
                finished.take(30).forEach { s ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(s.docNo, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            Text(
                                "${DateText.dateTime(s.createdAt)} · ${s.boxCount} 箱 · ${s.totalQty} 件" +
                                    if (s.syncedAt == null) " · 未上传" else "",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        OutlinedButton(onClick = { vm.exportShipment(s.id) }) { Text("导出", fontSize = 12.sp) }
                    }
                    HorizontalDivider()
                }
            }
        }

        SectionCard("盘点差异（按批次）") {
            val finished = checks.filter { it.status == "FINISHED" }
            if (finished.isEmpty()) {
                EmptyHint("还没有已结束的盘点批次")
            } else {
                finished.take(30).forEach { c ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(c.batchNo, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            Text(
                                "${DateText.dateTime(c.startedAt)} · 扫描 ${c.scanCount} 条" +
                                    if (c.syncedAt == null) " · 未上传" else "",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        OutlinedButton(onClick = { vm.exportStocktake(c.id) }) { Text("导出", fontSize = 12.sp) }
                    }
                    HorizontalDivider()
                }
            }
        }

        SectionCard("文件在哪里") {
            Text(
                "默认写到手机存储：${Exporter.exportDir(app).absolutePath}",
                fontSize = 11.sp
            )
            Text(
                "导出后会自动弹出「发送」面板，可发到微信 / 企业微信 / U 盘；也可以直接用文件管理器打开该目录。",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "如果手机 ROM 不让写 /sdcard 根目录，会自动改写到 App 专属目录（路径会在提示里显示）。",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
