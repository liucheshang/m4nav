package com.m4nav

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.PI

/**
 * 通知给锚点（"3 0 0 米后右转"），定位心跳把距离递减出来。
 * 高德只在 500/300/100/50 这类档位刷新，靠这个类补出平滑倒计时。
 */
class GeoCountdown(private val step: Double = 50.0) {

    var dir: String = "?"
        private set
    private var anchorRemain: Double? = null
    private var anchorLat: Double? = null
    private var anchorLon: Double? = null
    private var lastShown: Double? = null

    fun onNotification(direction: String, meters: Int, lat: Double?, lon: Double?) {
        dir = direction
        anchorRemain = meters.toDouble()
        anchorLat = lat
        anchorLon = lon
        lastShown = meters.toDouble()
    }

    /** 返回应刷新的剩余距离；null 表示不值得推（变化不足 step 或 GPS 抖动回跳）。 */
    fun onPosition(lat: Double, lon: Double): Double? {
        val aLat = anchorLat ?: return null
        val aLon = anchorLon ?: return null
        val base = anchorRemain ?: return null
        val traveled = haversine(aLat, aLon, lat, lon)
        var remain = base - traveled
        if (remain <= 0) {
            if (lastShown == 0.0) return null
            lastShown = 0.0
            return 0.0
        }
        remain = round(remain / 5.0) * 5.0
        val shown = lastShown
        if (shown != null) {
            if (abs(shown - remain) < step) return null
            if (remain > shown + 5) return null      // 只往小走，防回跳
        }
        lastShown = remain
        return remain
    }

    fun clear() {
        dir = "?"
        anchorRemain = null
        anchorLat = null
        anchorLon = null
        lastShown = null
    }

    companion object {
        fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = 6371000.0
            val p1 = lat1 * PI / 180.0
            val p2 = lat2 * PI / 180.0
            val dp = p2 - p1
            val dl = (lon2 - lon1) * PI / 180.0
            val a = sin(dp / 2) * sin(dp / 2) +
                    cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
            return 2 * r * asin(min(1.0, sqrt(a)))
        }
    }
}
