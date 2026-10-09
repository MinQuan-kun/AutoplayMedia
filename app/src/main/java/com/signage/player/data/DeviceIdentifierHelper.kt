package com.signage.player.data

import android.content.Context
import android.provider.Settings
import android.util.Log
import java.net.NetworkInterface
import java.util.Collections
import java.util.Locale
import java.util.UUID

/**
 * ==============================================================================
 * BỘ HỖ TRỢ ĐỌC ĐỊA CHỈ PHẦN CỨNG (MAC ADDRESS & DEVICE ID)
 * ==============================================================================
 * Tương thích từ Android 4.3 (Jelly Bean) đến Android 14/15 mới nhất:
 * 1. Ưu tiên 1: Đọc MAC của cổng mạng LAN có dây (eth0) - chuẩn trên TV & Bo mạch nhúng
 * 2. Ưu tiên 2: Đọc MAC của cổng Wi-Fi (wlan0)
 * 3. Ưu tiên 3: WifiManager.connectionInfo (hoạt động tốt trên Android 4.3 - 5.1)
 * 4. Dự phòng: Mã định danh phần cứng ANDROID_ID
 */
object DeviceIdentifierHelper {

    private const val TAG = "DeviceIdentifierHelper"

    /**
     * Lấy địa chỉ MAC thật của thiết bị để dùng làm định danh cố định.
     */
    fun getMacAddress(context: Context): String {
        // Cách 1: Quét NetworkInterface (Hỗ trợ cả Android cũ và mới, đặc biệt mạng LAN eth0)
        try {
            val allInterfaces = Collections.list(NetworkInterface.getNetworkInterfaces())

            // Sắp xếp ưu tiên: eth0 (mạng dây), sau đó đến wlan0 (Wi-Fi)
            val sortedList = allInterfaces.sortedWith(Comparator { o1, o2 ->
                val name1 = o1.name.lowercase(Locale.ROOT)
                val name2 = o2.name.lowercase(Locale.ROOT)
                when {
                    name1.startsWith("eth") -> -1
                    name2.startsWith("eth") -> 1
                    name1.startsWith("wlan") -> -1
                    name2.startsWith("wlan") -> 1
                    else -> 0
                }
            })

            for (nif in sortedList) {
                val macBytes = nif.hardwareAddress ?: continue
                val res = StringBuilder()
                for (b in macBytes) {
                    res.append(String.format("%02X:", b))
                }
                if (res.isNotEmpty()) res.deleteCharAt(res.length - 1)
                val mac = res.toString().uppercase(Locale.ROOT)
                if (isValidMac(mac)) {
                    Log.d(TAG, "Đã đọc thành công MAC từ card mạng [${nif.name}]: $mac")
                    return mac
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Lỗi khi quét NetworkInterface: ${e.message}")
        }

        // Cách 2: Định danh phần cứng chính thức của Google (ANDROID_ID)
        try {
            val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            if (!androidId.isNullOrBlank() && androidId != "9774d56d682e549c") {
                return "ID_" + androidId.uppercase(Locale.ROOT)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Lỗi khi đọc Android ID: ${e.message}")
        }

        // Cách 4: Tạo UUID ngẫu nhiên nếu hệ điều hành bảo mật tối đa
        return "Signage_" + UUID.randomUUID().toString().replace("-", "").take(8).uppercase(Locale.ROOT)
    }

    private fun isValidMac(mac: String): Boolean {
        if (mac.isBlank()) return false
        if (mac == "02:00:00:00:00:00") return false
        if (mac == "00:00:00:00:00:00") return false
        val parts = mac.split(":")
        return parts.size == 6
    }
}
