package com.vivivy.reado

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.Wearable

object WearSyncHelper {
    private const val TAG = "WearSync"
    const val PATH_PHONE = "/reado/phone"
    const val PATH_WATCH = "/reado/gesture"
    const val CMD_OPEN = "OPEN"

    fun openWatchApp(context: Context) {
        Wearable.getNodeClient(context).connectedNodes
            .addOnSuccessListener { nodes ->
                if (nodes.isEmpty()) {
                    Log.w(TAG, "Tidak ada jam terhubung. Pastikan app Reado sudah terinstall di jam.")
                    return@addOnSuccessListener
                }
                val messageClient = Wearable.getMessageClient(context)
                nodes.forEach { node ->
                    Log.d(TAG, "Membuka app di jam: ${node.displayName} (${node.id})")
                    messageClient.sendMessage(
                        node.id,
                        PATH_PHONE,
                        CMD_OPEN.toByteArray()
                    ).addOnSuccessListener {
                        Log.d(TAG, "Perintah OPEN terkirim ke ${node.displayName}")
                    }.addOnFailureListener { e ->
                        Log.e(TAG, "Gagal kirim OPEN ke ${node.displayName}: ${e.message}")
                    }
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Gagal cek node jam: ${e.message}")
            }
    }
}
