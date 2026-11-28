package com.peijian.pda.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.peijian.pda.logic.DateText
import com.peijian.pda.ui.AppViewModel
import com.peijian.pda.ui.InfoLine
import com.peijian.pda.ui.SectionCard

/** 数据看板：本机库存概览、同步状态与箱码规则。 */
@Composable
fun DashboardScreen(vm: AppViewModel) {
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
                    MiniStat("物料种类", "$stockParts")
                    MiniStat("在库数量", "$stockQty")
                    MiniStat("已发出箱", "$shipped")
                }
            }
        }

        SectionCard("同步状态") {
            InfoLine(
                "电脑端地址",
                settings.serverUrl.ifBlank { "未配置" }
            )
            InfoLine(
                "上次同步",
                if (settings.lastSyncAt > 0) DateText.dateTime(settings.lastSyncAt) else "还没有同步过"
            )
            InfoLine("数据版本", "${settings.lastRev}")
            InfoLine(
                "待上传",
                if (unsynced + unsyncedChecks > 0) "${unsynced + unsyncedChecks} 条" else "已全部同步"
            )
        }

        SectionCard("箱码规则") {
            Text("标签内容 = 客户编码 + 生产单号 + 箱码序号", fontSize = 12.sp)
            Text(
                "例：W00305-P108580-1（分隔符可在设置里改）",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "扫码后按箱登记发出或核对在库；带不带分隔符均可识别。",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        SectionCard("使用顺序") {
            listOf(
                "1. 电脑更新「配件记录.xlsx」→ 数据同步拉新增（或本机直接导入 Excel）",
                "2. 打包时打印箱码，贴在对应箱子外侧",
                "3. 扫码出库：填写发货柜/客户 → 可选预选箱 → 扫箱 → 上传",
                "4. 库存盘点：建批次 → 逐箱扫码 → 结束并导出差异表",
                "5. 导出报表：库存清单（打包记录格式）+ 物料汇总"
            ).forEach {
                Text(it, fontSize = 11.sp, modifier = Modifier.padding(vertical = 2.dp))
            }
        }
    }
}

@Composable
private fun MiniStat(label: String, value: String) {
    Column(Modifier.padding(top = 8.dp)) {
        Text(value, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text(label, color = Color(0xFFBBDEFB), fontSize = 10.sp)
    }
}
