package com.peijian.pda

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.peijian.pda.ui.AppRoot
import com.peijian.pda.ui.AppViewModel
import com.peijian.pda.ui.theme.PeijianTheme

class MainActivity : ComponentActivity() {

    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        askStorage()
        setContent {
            PeijianTheme {
                AppRoot(vm)
            }
        }
    }

    /**
     * targetSdk 29 + legacy 存储：Android 6 ~ 11 都需要运行时授予读写权限。
     *
     * 之前只在 API <= 29 时申请，导致 Android 11（API 30）真机永远拿不到授权，
     * 导出/备份静默退到 Android/data 专属目录（用户很难取文件）。API 31+ 分区存储
     * 已强制生效，legacy 写入不再可用，交给导出/备份的 fallback 目录处理。
     */
    private fun askStorage() {
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.R) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.READ_EXTERNAL_STORAGE
                ),
                1001
            )
        }
    }
}
