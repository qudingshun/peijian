package com.peijian.pda.ui.screen

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.peijian.pda.BuildConfig
import com.peijian.pda.R
import com.peijian.pda.ui.AppViewModel
import com.peijian.pda.ui.Screen

/**
 * 首页：版式参照 saoma 项目首页——顶部惠而浦 LOGO + 应用名/版本，
 * 主入口为全宽大按钮（数据看板 / 扫码出库 / 库存盘点 / 记录查询），
 * 次级入口为描边按钮（数据同步 / 导出报表 / 设置），版权行固定在页面最下方。
 */
@Composable
fun HomeScreen(vm: AppViewModel, onNavigate: (Screen) -> Unit, onSettings: () -> Unit) {
    val stockBoxes by vm.stockBoxCount.collectAsStateWithLifecycle(initialValue = 0)
    val settings by vm.settings.collectAsStateWithLifecycle()

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 12.dp)
    ) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 透明底 LOGO：白色圆角底衬，与 saoma 首页同款修饰
            Image(
                painter = painterResource(R.drawable.logo),
                contentDescription = "惠而浦 Whirlpool",
                modifier = Modifier
                    .width(270.dp)
                    .background(Color.White, RoundedCornerShape(16.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                contentScale = ContentScale.Fit
            )
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.app_name), fontSize = 40.sp, fontWeight = FontWeight.Bold)
            Text(
                "v${BuildConfig.VERSION_NAME}",
                fontSize = 16.sp,
                color = Color.Gray
            )
            Spacer(Modifier.height(18.dp))

            // 主入口
            HomeMainButton("数据看板") { onNavigate(Screen.DASHBOARD) }
            Spacer(Modifier.height(12.dp))
            HomeMainButton("扫码出库") { onNavigate(Screen.OUTBOUND) }
            Spacer(Modifier.height(12.dp))
            HomeMainButton("库存盘点") { onNavigate(Screen.STOCKTAKE) }
            Spacer(Modifier.height(12.dp))
            HomeMainButton("记录查询") { onNavigate(Screen.RECORDS) }

            Spacer(Modifier.height(18.dp))

            // 次级入口
            HomeSubButton("数据同步") { onNavigate(Screen.SYNC) }
            Spacer(Modifier.height(10.dp))
            HomeSubButton("导出报表") { onNavigate(Screen.EXPORT) }
            Spacer(Modifier.height(10.dp))
            HomeSubButton("设置") { onSettings() }

            Spacer(Modifier.height(10.dp))
            Text(
                "本机在库 $stockBoxes 箱 · 数据版本 ${settings.lastRev}",
                fontSize = 13.sp,
                color = Color.Gray
            )
        }

        // 版权行固定在首页最下方，不随内容滚动
        Column(
            Modifier
                .align(Alignment.CenterHorizontally)
                .padding(top = 8.dp, bottom = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "© 2026 qudingshun · 版权所有",
                fontSize = 11.sp,
                color = Color(0xFF9E9E9E)
            )
            Text(
                "配件管理 PDA · 内部作业工具",
                fontSize = 10.sp,
                color = Color(0xFFBDBDBD),
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

@Composable
private fun HomeMainButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(80.dp)
    ) {
        Text(text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun HomeSubButton(text: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
    ) {
        Text(text, fontSize = 18.sp)
    }
}
