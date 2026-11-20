package com.peijian.pda.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.peijian.pda.logic.DateText
import com.peijian.pda.ui.AppViewModel
import com.peijian.pda.ui.Screen
import com.peijian.pda.ui.SectionCard

@Composable
fun HomeScreen(vm: AppViewModel, onNavigate: (Screen) -> Unit, onSettings: () -> Unit) {
    val stockBoxes by vm.stockBoxCount.collectAsStateWithLifecycle(initialValue = 0)
    val stockParts by vm.stockPartCount.collectAsStateWithLifecycle(initialValue = 0)
    val stockQty by vm.stockQty.collectAsStateWithLifecycle(initialValue = 0L)
    val shipped by vm.shippedBoxCount.collectAsStateWithLifecycle(initialValue = 0)
    val unsynced by vm.unsyncedShipments.collectAsStateWithLifecycle(initialValue = 0)
    val unsyncedChecks by vm.unsyncedChecks.collectAsStateWithLifecycle(initialValue = 0)
    val settings by vm.settings.collectAsStateWithLifecycle()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(Modifier.padding(14.dp)) {
                Text("在库配件", color = Color(0xFFD6E4FF), fontSize = 12.sp)
                Text(
                    "$stockBoxes 箱",
                    color = Color.White,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold
                )
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    HomeMiniStat("物料种类", "$stockParts")
                    HomeMiniStat("在库数量", "$stockQty")
                    HomeMiniStat("已发出箱", "$shipped")
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            QuickAction(Icons.Filled.QrCodeScanner, "扫码出库", "整箱发出登记", Modifier.weight(1f)) {
                onNavigate(Screen.OUTBOUND)
            }
            QuickAction(Icons.Filled.Checklist, "库存盘点", "扫箱核对在库", Modifier.weight(1f)) {
                onNavigate(Screen.STOCKTAKE)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            QuickAction(Icons.Filled.Sync, "数据同步", "从电脑拉新增", Modifier.weight(1f)) {
                onNavigate(Screen.SYNC)
            }
            QuickAction(Icons.Filled.FileDownload, "导出报表", "库存/出库/盘点", Modifier.weight(1f)) {
                onNavigate(Screen.EXPORT)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            QuickAction(Icons.Filled.Search, "记录查询", "发货/盘点/箱", Modifier.weight(1f)) {
                onNavigate(Screen.RECORDS)
            }
            QuickAction(Icons.Filled.Settings, "设置", "条码/操作员/服务器", Modifier.weight(1f)) {
                onSettings()
            }
        }

        SectionCard("同步状态") {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        if (settings.serverUrl.isBlank()) "未配置电脑端地址" else settings.serverUrl,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        if (settings.lastSyncAt > 0) "上次同步 ${DateText.dateTime(settings.lastSyncAt)}" else "还没有同步过",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "数据版本 ${settings.lastRev}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    if (unsynced + unsyncedChecks > 0) {
                        Text(
                            "待上传 ${unsynced + unsyncedChecks} 条",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(4.dp))
                        Button(
                            onClick = { vm.push() },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                        ) { Text("立即上传", fontSize = 12.sp) }
                    } else {
                        Text("记录已同步", fontSize = 12.sp, color = MaterialTheme.colorScheme.tertiary)
                    }
                }
            }
        }

        SectionCard("箱码规则") {
            Text(
                "标签内容 = 客户编码 + 生产单号 + 箱号",
                fontSize = 12.sp
            )
            Text(
                "例：W00305-P108580-1（分隔符可在设置里改）",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "扫码头扫到标签后，系统按箱号找到整箱配件并登记；盘点时同样按箱核对。",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        SectionCard("使用顺序") {
            listOf(
                "1. 电脑更新「配件记录.xlsx」→ 首页「数据同步」拉新增（或直接导入 Excel）",
                "2. 打包时打印箱码，贴在对应箱子外侧",
                "3. 发货：填发货柜号/客户 → 可选预选箱 → 扫箱码 → 完成后上传电脑",
                "4. 盘点：建批次 → 逐箱扫码 → 结束并导出差异表",
                "5. 报表：库存清单（打包记录格式）+ 物料汇总（按配件编码合计）"
            ).forEach {
                Text(it, fontSize = 11.sp, modifier = Modifier.padding(vertical = 2.dp))
            }
        }
    }
}

@Composable
private fun HomeMiniStat(label: String, value: String) {
    Column(Modifier.padding(top = 8.dp)) {
        Text(value, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text(label, color = Color(0xFFBBDEFB), fontSize = 10.sp)
    }
}

@Composable
private fun QuickAction(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            Icon(icon, contentDescription = title, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
            Spacer(Modifier.height(8.dp))
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
