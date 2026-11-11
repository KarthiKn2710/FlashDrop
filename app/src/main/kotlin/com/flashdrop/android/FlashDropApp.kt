package com.flashdrop.android

import android.app.Application
import com.flashdrop.service.TransferServiceStarter
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class FlashDropApp : Application() {
    @Inject lateinit var serviceStarter: TransferServiceStarter

    override fun onCreate() {
        super.onCreate()
        // The foreground service follows transfer state; it runs only while visible or transferring.
        serviceStarter.attach()
    }
}
