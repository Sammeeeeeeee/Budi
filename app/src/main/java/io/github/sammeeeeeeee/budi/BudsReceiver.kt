package io.github.sammeeeeeeee.budi

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Follows the buds while they're connected over classic Bluetooth. */
class BudsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return
        val transport = intent.getIntExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_BREDR)
        if (transport != BluetoothDevice.TRANSPORT_BREDR || !Buds.isBuds(device)) return
        if (Buds.samsungPlugins(context).isEmpty()) return
        when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> BudsService.connected(context)
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> BudsService.disconnected(context)
        }
    }
}
