package io.github.sammeeeeeeee.budi

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Starts Budi when Galaxy Buds connect over classic Bluetooth and stops it when they go. */
class BudsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return
        val transport = intent.getIntExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_BREDR)
        if (transport != BluetoothDevice.TRANSPORT_BREDR || !Buds.isBuds(device)) return
        when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> if (Buds.samsungManager(context) == null) BudsService.start(context, device)
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> BudsService.stop(context)
        }
    }
}
