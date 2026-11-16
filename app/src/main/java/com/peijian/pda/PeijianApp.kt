package com.peijian.pda

import android.app.Application
import com.peijian.pda.data.AppDatabase
import com.peijian.pda.scan.ScannerManager
import com.peijian.pda.sync.Prefs

class PeijianApp : Application() {

    lateinit var db: AppDatabase
        private set
    lateinit var prefs: Prefs
        private set
    lateinit var scanner: ScannerManager
        private set

    override fun onCreate() {
        super.onCreate()
        db = AppDatabase.getInstance(this)
        prefs = Prefs(this)
        scanner = ScannerManager(this)
        scanner.register()
    }
}
