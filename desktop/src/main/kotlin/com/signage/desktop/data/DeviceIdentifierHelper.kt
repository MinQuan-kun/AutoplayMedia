package com.signage.desktop.data

import java.net.NetworkInterface
import java.util.Collections
import java.util.Locale
import java.util.UUID

/**
 * Trích xuất địa chỉ MAC thật của card mạng trên máy tính Windows (LAN / Wi-Fi).
 */
object DeviceIdentifierHelper {

    fun getMacAddress(): String {
        try {
            val allInterfaces = Collections.list(NetworkInterface.getNetworkInterfaces())

            // Sắp xếp ưu tiên: card mạng thật (Ethernet / Wi-Fi), bỏ qua các card ảo VPN/Loopback
            val sortedList = allInterfaces.filter { nif ->
                !nif.isLoopback && nif.hardwareAddress != null && nif.hardwareAddress.isNotEmpty()
            }.sortedBy { nif ->
                val name = nif.name.lowercase(Locale.ROOT)
                val display = nif.displayName.lowercase(Locale.ROOT)
                when {
                    display.contains("ethernet") || name.startsWith("eth") -> 0
                    display.contains("wi-fi") || display.contains("wireless") || name.startsWith("wlan") -> 1
                    display.contains("virtual") || display.contains("vmware") || display.contains("vbox") -> 3
                    else -> 2
                }
            }

            for (nif in sortedList) {
                val macBytes = nif.hardwareAddress ?: continue
                val res = StringBuilder()
                for (b in macBytes) {
                    res.append(String.format("%02X:", b))
                }
                if (res.isNotEmpty()) res.deleteCharAt(res.length - 1)
                val mac = res.toString().uppercase(Locale.ROOT)
                if (isValidMac(mac)) {
                    return mac
                }
            }
        } catch (_: Exception) {}

        // Dự phòng tạo mã UUID nếu không đọc được MAC
        val fallback = UUID.randomUUID().toString().replace("-", "").take(12).uppercase(Locale.ROOT)
        return fallback.chunked(2).joinToString(":")
    }

    private fun isValidMac(mac: String): Boolean {
        if (mac.isBlank()) return false
        if (mac == "00:00:00:00:00:00") return false
        val parts = mac.split(":")
        return parts.size == 6
    }
}
