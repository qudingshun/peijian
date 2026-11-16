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

    /** targetSdk 29 + legacy 存储：Android 10 及以下需要运行时授权；更高版本一般直接可用 */
    private fun askStorage() {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
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
}
