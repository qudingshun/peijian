package com.peijian.pda.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.peijian.pda.ui.screen.AboutScreen
import com.peijian.pda.ui.screen.ExportScreen
import com.peijian.pda.ui.screen.HomeScreen
import com.peijian.pda.ui.screen.OutboundScreen
import com.peijian.pda.ui.screen.RecordsScreen
import com.peijian.pda.ui.screen.SettingsScreen
import com.peijian.pda.ui.screen.StocktakeScreen
import com.peijian.pda.ui.screen.SyncScreen

enum class Screen(val title: String) {
    HOME("配件管理"),
    OUTBOUND("扫码出库"),
    STOCKTAKE("库存盘点"),
    EXPORT("导出报表"),
    RECORDS("记录与查询"),
    SETTINGS("设置"),
    SYNC("数据同步"),
    ABOUT("关于/版权")
}

@Composable
fun AppRoot(vm: AppViewModel) {
    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
    val snackbar = remember { SnackbarHostState() }
    val current = rememberUpdatedState(screen)
    val busy by vm.busy.collectAsStateWithLifecycle()
    val busyText by vm.busyText.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { vm.loadOpenState() }

    val appContext = androidx.compose.ui.platform.LocalContext.current.applicationContext
    LaunchedEffect(Unit) {
        vm.events.collect { e ->
            snackbar.showSnackbar(e.text)
            if (e.restart) {
                kotlinx.coroutines.delay(1200)
                restartApp(appContext)
            }
        }
    }

    // 扫码枪广播：只把内容送进当前业务页
    LaunchedEffect(Unit) {
        vm.scanEvents.collect { code ->
            when (current.value) {
                Screen.OUTBOUND -> if (vm.openShipment.value != null) vm.scanOutbound(code)
                Screen.STOCKTAKE -> if (vm.openCheck.value != null) vm.scanStocktake(code)
                Screen.RECORDS -> vm.setBoxQuery(code)
                else -> Unit
            }
        }
    }

    Scaffold(
        topBar = {
            AppTopBar(
                title = screen.title,
                subtitle = when (screen) {
                    Screen.HOME -> "售后配件 · 出库 / 盘点 / 报表"
                    Screen.OUTBOUND -> vm.openShipment.value?.docNo ?: "扫描箱码登记发出"
                    Screen.STOCKTAKE -> vm.openCheck.value?.batchNo ?: "扫描箱码核对在库"
                    Screen.ABOUT -> "版本与版权信息"
                    else -> null
                },
                onBack = if (screen == Screen.SETTINGS || screen == Screen.SYNC || screen == Screen.ABOUT) ({ screen = Screen.HOME }) else null,
                actions = {
                    if (screen == Screen.HOME) {
                        IconButton(onClick = { screen = Screen.SETTINGS }) {
                            Icon(Icons.Filled.Settings, contentDescription = "设置")
                        }
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                BottomItem(Screen.HOME, Icons.Filled.Home, screen) { screen = it }
                BottomItem(Screen.OUTBOUND, Icons.Filled.QrCodeScanner, screen) { screen = it }
                BottomItem(Screen.STOCKTAKE, Icons.Filled.Checklist, screen) { screen = it }
                BottomItem(Screen.EXPORT, Icons.Filled.FileDownload, screen) { screen = it }
                BottomItem(Screen.RECORDS, Icons.Filled.History, screen) { screen = it }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (screen) {
                Screen.HOME -> HomeScreen(vm, onNavigate = { screen = it }, onSettings = { screen = Screen.SETTINGS })
                Screen.OUTBOUND -> OutboundScreen(vm)
                Screen.STOCKTAKE -> StocktakeScreen(vm)
                Screen.EXPORT -> ExportScreen(vm)
                Screen.RECORDS -> RecordsScreen(vm)
                Screen.SETTINGS -> SettingsScreen(vm)
                Screen.SYNC -> SyncScreen(vm)
                Screen.ABOUT -> AboutScreen(onBack = { screen = Screen.HOME })
            }

            if (busy) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color(0x66000000)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        Text(
                            busyText.ifBlank { "处理中…" },
                            color = Color.White,
                            fontWeight = FontWeight.Medium,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(top = 10.dp)
                        )
                    }
                }
            }
        }
    }
}

/** 恢复数据库后重启进程：Room 与 ViewModel 都持有旧库连接，必须整进程重建。 */
private fun restartApp(context: android.content.Context) {
    val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
    if (intent != null) {
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(intent)
    }
    android.os.Process.killProcess(android.os.Process.myPid())
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.BottomItem(
    item: Screen,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    current: Screen,
    onClick: (Screen) -> Unit
) {
    NavigationBarItem(
        selected = current == item,
        onClick = { onClick(item) },
        icon = { Icon(icon, contentDescription = item.title) },
        label = { Text(item.title, fontSize = 10.sp, maxLines = 1) }
    )
}
