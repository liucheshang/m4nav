package com.m4nav

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import java.util.UUID

/**
 * 连 M4 手环并写 0x2d01。
 *
 * 连接策略（和 Windows 端实测结论一致）：
 *   手环一被连上就停广播，所以必须“按服务 UUID 18d0 过滤扫描”拿到它，
 *   扫到之后立刻 connectGatt，再取 2d01 写。
 */
class BleEngine(private val ctx: Context, private val onState: (String) -> Unit) {

    companion object {
        val SERVICE: UUID = UUID.fromString("000018d0-0000-1000-8000-00805f9b34fb")
        val CHAR_WRITE: UUID = UUID.fromString("00002d01-0000-1000-8000-00805f9b34fb")
        val CHAR_NOTIFY: UUID = UUID.fromString("00002d00-0000-1000-8000-00805f9b34fb")
        const val DEVICE_NAME = "M4"
    }

    private var adapter: BluetoothAdapter? = null
    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var scanning = false
    private var lastError = ""

    val connected: Boolean
        get() = writeChar != null && gatt != null

    fun start() {
        if (!hasPerms()) {
            onState("缺权限：请先授予蓝牙/定位权限")
            return
        }
        val mgr = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        adapter = mgr?.adapter
        if (adapter == null || !adapter!!.isEnabled) {
            onState("蓝牙未开启")
            return
        }
        if (scanning) return
        scanning = true
        onState("扫描中…（手环需在边上、未连别的设备）")
        val filters = listOf(
            ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE)).build()
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        adapter!!.bluetoothLeScanner?.startScan(filters, settings, scanCallback)
    }

    private fun hasPerms(): Boolean {
        val need = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return need.all {
            ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.device.name ?: ""
            // 部分机型读 name 也要 BLUETOOTH_CONNECT，读不到就直接用扫到的第一个
            if (name.isNotEmpty() && name != DEVICE_NAME) return
            stopScan()
            onState("找到 ${if (name.isEmpty()) result.device.address else name}，连接中…")
            connect(result.device.address)
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            onState("扫描失败 code=$errorCode")
        }
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        try {
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (e: SecurityException) {
            lastError = e.message ?: ""
        }
    }

    private fun connect(address: String) {
        val dev = adapter?.getRemoteDevice(address) ?: return
        gatt?.close()
        gatt = dev.connectGatt(ctx, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                onState("已连上，取服务…")
                g.discoverServices()
            } else {
                writeChar = null
                onState("断开 status=$status")
                // 断开后自动重扫，保持常驻
                start()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val svc: BluetoothGattService? = g.getService(SERVICE)
            if (svc == null) {
                onState("没找到 18d0 服务（status=$status），重连")
                g.close()
                writeChar = null
                start()
                return
            }
            val w = svc.getCharacteristic(CHAR_WRITE)
            if (w == null) {
                onState("没找到 2d01 特征")
                return
            }
            writeChar = w
            onState("就绲：可推送")
        }
    }

    /** 写一帧；返回是否成功（不含固件是否弹窗）。 */
    fun write(bytes: ByteArray): Boolean {
        val g = gatt ?: return false
        val c = writeChar ?: return false
        return try {
            var ok = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ok = g.writeCharacteristic(c, bytes, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) ==
                        BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                @Suppress("DEPRECATION")
                c.value = bytes
                @Suppress("DEPRECATION")
                ok = g.writeCharacteristic(c)
            }
            ok
        } catch (e: SecurityException) {
            lastError = e.message ?: ""
            false
        }
    }

    fun stop() {
        stopScan()
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (e: SecurityException) {
            lastError = e.message ?: ""
        }
        gatt = null
        writeChar = null
        onState("已断开")
    }
}
