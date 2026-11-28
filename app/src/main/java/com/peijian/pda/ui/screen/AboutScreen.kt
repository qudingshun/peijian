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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.peijian.pda.BuildConfig
import com.peijian.pda.R
import com.peijian.pda.ui.SectionCard

/**
 * 关于/版权页：版式参照 saoma 项目首页——居中品牌区 + 全宽主按钮 + 贴底版权行。
 * Logo 为本项目原创图案，只沿用「深色字 + 琥珀色环绕线」的视觉语言，不使用第三方商标。
 */
@Composable
fun AboutScreen(onBack: () -> Unit) {
    var showInfo by remember { mutableStateOf(false) }

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
            // 透明底 logo：套白色圆角底衬，与 saoma 首页同款修饰
            Image(
                painter = painterResource(R.drawable.logo),
                contentDescription = "配件管理 Logo",
                modifier = Modifier
                    .width(270.dp)
                    .background(Color.White, RoundedCornerShape(16.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                contentScale = ContentScale.Fit
            )
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.app_name), fontSize = 40.sp, fontWeight = FontWeight.Bold)
            Text(
                "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                fontSize = 16.sp,
                color = Color.Gray
            )
            Spacer(Modifier.height(18.dp))

            Button(
                onClick = onBack,
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(88.dp)
            ) {
                Text("返回首页", fontSize = 30.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = { showInfo = !showInfo },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
            ) {
                Text(if (showInfo) "收起应用说明" else "应用说明", fontSize = 20.sp)
            }

            if (showInfo) {
                Spacer(Modifier.height(12.dp))
                SectionCard("配件管理 PDA") {
                    InfoRow("版本", "${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）")
                    InfoRow("包名", "com.peijian.pda")
                    InfoRow("用途", "整箱扫码出库、扫箱盘点、库存清单与物料汇总导出")
                    InfoRow("同步", "电脑端 FastAPI 服务 + WiFi 增量拉取，也可在本机导入 Excel")
                    InfoRow("项目地址", "github.com/qudingshun/peijian")
                }
            }

            Spacer(Modifier.height(10.dp))
            Text(
                "电脑端启动：pc-server\\启动同步服务.bat",
                fontSize = 13.sp,
                color = Color.Gray
            )
            Text(
                "详细操作见「交付包」内的操作说明书（Word / PDF）",
                fontSize = 13.sp,
                color = Color(0xFF1976D2),
                modifier = Modifier.padding(top = 6.dp)
            )
        }

        // 版权行固定在页面最下方，不随内容滚动
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
private fun InfoRow(label: String, value: String) {
    Column(Modifier.padding(vertical = 2.dp)) {
        Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
