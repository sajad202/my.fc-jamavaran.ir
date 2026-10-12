package com.app.myjamavaran

import android.app.Application
import com.google.firebase.messaging.FirebaseMessaging

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        try { FirebaseMessaging.getInstance().subscribeToTopic("all") } catch (_: Exception) {}
    }
}