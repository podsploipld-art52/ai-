package com.aiagent.android.device

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.util.DisplayMetrics
import android.view.WindowManager

/**
 * Collects a snapshot of device information for the `device_info` tool.
 *
 * Everything here is best-effort and read-only.
 */
object DeviceInfo {

    fun gather(context: Context): String {
        val sb = StringBuilder()
        sb.append("=== Устройство ===\n")
        sb.append("Производитель: ").append(Build.MANUFACTURER).append('\n')
        sb.append("Бренд: ").append(Build.BRAND).append('\n')
        sb.append("Модель: ").append(Build.MODEL).append('\n')
        sb.append("Устройство: ").append(Build.DEVICE).append('\n')
        sb.append("Продукт: ").append(Build.PRODUCT).append('\n')
        sb.append("Hardware: ").append(Build.HARDWARE).append('\n')
        sb.append("Board: ").append(Build.BOARD).append('\n')
        sb.append("Поддерживаемые ABI: ").append(Build.SUPPORTED_ABIS.joinToString(", ")).append('\n')
        sb.append("Bootloader: ").append(Build.BOOTLOADER).append('\n')
        sb.append("Build ID: ").append(Build.ID).append('\n')
        sb.append("Build display: ").append(Build.DISPLAY).append('\n')

        sb.append("\n=== ОС ===\n")
        sb.append("Android: ").append(Build.VERSION.RELEASE)
            .append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n")
        sb.append("Codename: ").append(Build.VERSION.CODENAME).append('\n')
        sb.append("Patch: ").append(Build.VERSION.SECURITY_PATCH).append('\n')
        sb.append("Incremental: ").append(Build.VERSION.INCREMENTAL).append('\n')
        sb.append("Fingerprint: ").append(Build.FINGERPRINT).append('\n')

        sb.append("\n=== Экран ===\n")
        runCatching {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(metrics)
            sb.append("Разрешение: ").append(metrics.widthPixels).append('x').append(metrics.heightPixels).append('\n')
            sb.append("DPI: ").append(metrics.densityDpi).append(" (").append(metrics.density).append(")\n")
            sb.append("Refresh rate: ").append(@Suppress("DEPRECATION") wm.defaultDisplay.refreshRate).append(" Hz\n")
        }

        sb.append("\n=== ОЗУ ===\n")
        runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            sb.append("Всего: ").append(humanBytes(mi.totalMem)).append('\n')
            sb.append("Доступно: ").append(humanBytes(mi.availMem)).append('\n')
            sb.append("Low-memory: ").append(mi.lowMemory).append('\n')
            sb.append("Threshold: ").append(humanBytes(mi.threshold)).append('\n')
        }

        sb.append("\n=== Хранилище ===\n")
        runCatching {
            val internal = StatFs(Environment.getDataDirectory().path)
            val total = internal.blockCountLong * internal.blockSizeLong
            val free = internal.availableBlocksLong * internal.blockSizeLong
            sb.append("Internal: ").append(humanBytes(free)).append(" свободно из ").append(humanBytes(total)).append('\n')
        }
        runCatching {
            val ext = Environment.getExternalStorageDirectory()
            val sf = StatFs(ext.path)
            val total = sf.blockCountLong * sf.blockSizeLong
            val free = sf.availableBlocksLong * sf.blockSizeLong
            sb.append("External: ").append(humanBytes(free)).append(" свободно из ").append(humanBytes(total)).append('\n')
        }

        sb.append("\n=== Батарея ===\n")
        runCatching {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            sb.append("Заряд: ").append(bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)).append("%\n")
            val statusFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val battery: Intent? = context.registerReceiver(null, statusFilter)
            val plugged = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
            val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val temp = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
            val voltage = battery?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
            val pluggedText = when (plugged) {
                BatteryManager.BATTERY_PLUGGED_AC -> "AC"
                BatteryManager.BATTERY_PLUGGED_USB -> "USB"
                BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
                0 -> "не подключено"
                else -> plugged.toString()
            }
            sb.append("Источник: ").append(pluggedText).append('\n')
            sb.append("Статус: ").append(batteryStatusName(status)).append('\n')
            if (temp > 0) sb.append("Температура: ").append(temp / 10.0).append(" °C\n")
            if (voltage > 0) sb.append("Напряжение: ").append(voltage).append(" mV\n")
        }

        sb.append("\n=== Сеть ===\n")
        runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val net = cm.activeNetwork
            val caps = net?.let { cm.getNetworkCapabilities(it) }
            if (caps == null) {
                sb.append("Нет активного соединения.\n")
            } else {
                val transport = when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Сотовая сеть"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "Bluetooth"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
                    else -> "другое"
                }
                sb.append("Транспорт: ").append(transport).append('\n')
                sb.append("Скорость down: ").append(caps.linkDownstreamBandwidthKbps).append(" kbps\n")
                sb.append("Скорость up: ").append(caps.linkUpstreamBandwidthKbps).append(" kbps\n")
                sb.append("Validated: ")
                    .append(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)).append('\n')
                sb.append("Интернет: ")
                    .append(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)).append('\n')
            }
        }

        sb.append("\n=== Возможности ===\n")
        val pm = context.packageManager
        val features = listOf(
            PackageManager.FEATURE_TELEPHONY to "Телефония",
            PackageManager.FEATURE_CAMERA_ANY to "Камера",
            PackageManager.FEATURE_BLUETOOTH to "Bluetooth",
            PackageManager.FEATURE_BLUETOOTH_LE to "BLE",
            PackageManager.FEATURE_NFC to "NFC",
            PackageManager.FEATURE_FINGERPRINT to "Отпечаток",
            PackageManager.FEATURE_LOCATION_GPS to "GPS",
            PackageManager.FEATURE_SENSOR_ACCELEROMETER to "Акселерометр",
            PackageManager.FEATURE_SENSOR_GYROSCOPE to "Гироскоп",
            PackageManager.FEATURE_VULKAN_HARDWARE_VERSION to "Vulkan",
        )
        for ((feature, label) in features) {
            sb.append(label).append(": ")
                .append(if (pm.hasSystemFeature(feature)) "да" else "нет").append('\n')
        }

        return sb.toString()
    }

    private fun humanBytes(b: Long): String {
        if (b < 1024) return "$b B"
        val units = arrayOf("B", "KiB", "MiB", "GiB", "TiB")
        var v = b.toDouble()
        var u = 0
        while (v >= 1024.0 && u < units.size - 1) { v /= 1024.0; u++ }
        return String.format("%.2f %s", v, units[u])
    }

    private fun batteryStatusName(status: Int): String = when (status) {
        BatteryManager.BATTERY_STATUS_CHARGING -> "заряжается"
        BatteryManager.BATTERY_STATUS_DISCHARGING -> "разряжается"
        BatteryManager.BATTERY_STATUS_FULL -> "полная"
        BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "не заряжается"
        BatteryManager.BATTERY_STATUS_UNKNOWN -> "неизвестно"
        else -> "?$status"
    }
}
