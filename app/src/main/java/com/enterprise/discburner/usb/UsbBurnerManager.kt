package com.enterprise.discburner.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.*
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * USB设备状态
 */
sealed class UsbDeviceState {
    object NoDevice : UsbDeviceState()
    object PermissionRequired : UsbDeviceState()
    object Connecting : UsbDeviceState()
    data class Connected(val deviceName: String, val interfaceId: Int) : UsbDeviceState()
    data class Error(val message: String) : UsbDeviceState()
}

/**
 * USB刻录机管理器
 * 负责设备发现、权限管理和连接管理
 */
class UsbBurnerManager(private val context: Context) {

    private val TAG = "UsbBurnerManager"
    private val ACTION_USB_PERMISSION = "com.enterprise.discburner.USB_PERMISSION"

    private val usbManager: UsbManager =
        context.getSystemService(Context.USB_SERVICE) as UsbManager

    private val _deviceState = MutableStateFlow<UsbDeviceState>(UsbDeviceState.NoDevice)
    val deviceState: StateFlow<UsbDeviceState> = _deviceState

    private var currentDevice: UsbDevice? = null
    private var currentConnection: UsbDeviceConnection? = null

    // USB广播接收器
    val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    device?.let { onDeviceAttached(it) }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    device?.let { onDeviceDetached(it) }
                }
                ACTION_USB_PERMISSION -> {
                    val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    device?.let { onPermissionResult(it, granted) }
                }
            }
        }
    }

    /**
     * 扫描已连接的刻录机
     * @return 找到的刻录机设备，如果没有则返回null
     */
    fun scanForBurner(): UsbDevice? {
        val deviceList = usbManager.deviceList
        Log.d(TAG, "扫描USB设备，找到 ${deviceList.size} 个设备")

        for ((name, device) in deviceList) {
            Log.d(TAG, "设备: $name, VID: ${device.vendorId}, PID: ${device.productId}, Class: ${device.deviceClass}")

            if (isMassStorageDevice(device)) {
                Log.i(TAG, "找到Mass Storage设备: $name")
                currentDevice = device
                return device
            }
        }

        return null
    }

    /**
     * 检查是否为Mass Storage设备（光盘刻录机）
     */
    private fun isMassStorageDevice(device: UsbDevice): Boolean {
        // 方法1: 检查设备类
        if (device.deviceClass == UsbConstants.USB_CLASS_MASS_STORAGE) {
            return true
        }

        // 方法2: 检查接口
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            if (intf.interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE) {
                // 子类0x06 = SCSI透明命令集（光盘刻录机使用）
                if (intf.interfaceSubclass == 0x06 || intf.interfaceSubclass == 0x02) {
                    return true
                }
            }
        }

        // 方法3: 检查已知刻录机VID
        val knownBurnerVids = setOf(
            0x13FD,  // Initio
            0x152E,  // JMicron
            0x057B,  // Y-E DATA
            0x0E8D,  // MediaTek
            0x1058,  // Western Digital (部分型号支持刻录)
            0x04E8,  // Samsung
            0x03F0,  // HP
            0x0BC2,  // Seagate
            0x059B,  // Iomega
            0x0409,  // NEC
            0x054C,  // Sony
            0x058F,  // Alcor Micro
            0x07AB,  // Freecom
            0x0BF6,  // Addonics
            0x14CD,  // Super Top
        )

        return device.vendorId in knownBurnerVids
    }

    /**
     * 请求USB权限
     */
    fun requestPermission(device: UsbDevice) {
        val permissionIntent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(ACTION_USB_PERMISSION),
            PendingIntent.FLAG_IMMUTABLE
        )
        usbManager.requestPermission(device, permissionIntent)
        _deviceState.value = UsbDeviceState.PermissionRequired
    }

    /**
     * 检查是否有权限
     */
    fun hasPermission(device: UsbDevice): Boolean {
        return usbManager.hasPermission(device)
    }

    /**
     * 连接设备
     */
    fun connectDevice(device: UsbDevice): Pair<UsbDeviceConnection, UsbInterface, UsbEndpoint, UsbEndpoint>? {
        if (!usbManager.hasPermission(device)) {
            _deviceState.value = UsbDeviceState.PermissionRequired
            return null
        }

        _deviceState.value = UsbDeviceState.Connecting

        // 查找Mass Storage接口
        var massStorageInterface: UsbInterface? = null
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            if (intf.interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE) {
                massStorageInterface = intf
                break
            }
        }

        if (massStorageInterface == null) {
            _deviceState.value = UsbDeviceState.Error("未找到Mass Storage接口")
            return null
        }

        // 打开连接
        val connection = usbManager.openDevice(device)
        if (connection == null) {
            _deviceState.value = UsbDeviceState.Error("无法打开USB设备")
            return null
        }

        // 声明接口
        if (!connection.claimInterface(massStorageInterface, true)) {
            connection.close()
            _deviceState.value = UsbDeviceState.Error("无法声明USB接口")
            return null
        }

        // 查找端点
        var endpointIn: UsbEndpoint? = null
        var endpointOut: UsbEndpoint? = null

        for (i in 0 until massStorageInterface.endpointCount) {
            val endpoint = massStorageInterface.getEndpoint(i)
            if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (endpoint.direction == UsbConstants.USB_DIR_IN) {
                    endpointIn = endpoint
                } else {
                    endpointOut = endpoint
                }
            }
        }

        if (endpointIn == null || endpointOut == null) {
            connection.releaseInterface(massStorageInterface)
            connection.close()
            _deviceState.value = UsbDeviceState.Error("未找到必要的USB端点")
            return null
        }

        currentConnection = connection
        _deviceState.value = UsbDeviceState.Connected(
            device.productName ?: "Unknown Device",
            massStorageInterface.id
        )

        return Pair(connection, massStorageInterface, endpointIn, endpointOut)
    }

    /**
     * 设备插入回调
     */
    private fun onDeviceAttached(device: UsbDevice) {
        Log.i(TAG, "USB设备插入: ${device.deviceName}")
        if (isMassStorageDevice(device)) {
            currentDevice = device
            // 通知UI设备已插入
        }
    }

    /**
     * 设备拔出回调
     */
    private fun onDeviceDetached(device: UsbDevice) {
        Log.i(TAG, "USB设备拔出: ${device.deviceName}")
        if (device == currentDevice) {
            currentConnection?.close()
            currentConnection = null
            currentDevice = null
            _deviceState.value = UsbDeviceState.NoDevice
        }
    }

    /**
     * 权限结果回调
     */
    private fun onPermissionResult(device: UsbDevice, granted: Boolean) {
        if (granted) {
            Log.i(TAG, "USB权限已授予")
            // 可以自动连接或通知UI
        } else {
            Log.w(TAG, "USB权限被拒绝")
            _deviceState.value = UsbDeviceState.Error("USB权限被拒绝")
        }
    }

    /**
     * 获取当前设备
     */
    fun getCurrentDevice(): UsbDevice? = currentDevice

    /**
     * 断开连接
     */
    fun disconnect() {
        currentConnection?.close()
        currentConnection = null
        currentDevice = null
        _deviceState.value = UsbDeviceState.NoDevice
    }
}
