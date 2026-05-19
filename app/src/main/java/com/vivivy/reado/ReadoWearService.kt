package com.vivivy.reado

import android.content.Intent
import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

class ReadoWearService : WearableListenerService() {

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != "/reado/gesture") return

        val command = String(event.data)
        Log.d("ReadoWear", "Perintah diterima dari jam: $command")

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra(MainActivity.EXTRA_WATCH_COMMAND, command)
        }
        startActivity(intent)
    }
}
