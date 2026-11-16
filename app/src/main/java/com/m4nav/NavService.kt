package com.m4nav

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 常驻前台服务：BLE 写 + 倒计时状态机 + 可选本地 HTTP 入口（127.0.0.1:8766）。
 *
 * 两种输入都能用：
 *   1) NotifListener 直接抓高德通知（不装 Termux 也能跑）
 *   2) 本地 HTTP：POST 正文 {"text":"300米后右转","lat":..,"lon":..} 或纯 lat/lon 心跳
 */
class NavService : Service() {

    companion object {
        const val CHANNEL_ID = "m4nav"
        const val NOTIF_ID = 1
        const val HTTP_PORT = 8766
        const val COOLDOWN_MS = 2000L

        @Volatile
        var instance: NavService? = null
        var onLog: ((String) -> Unit)? = null
    }

    private lateinit var ble: BleEngine
    private lateinit var geo: GeoCountdown
    private lateinit var locationClient: FusedLocationProviderClient
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val queue = ConcurrentLinkedQueue<ByteArray>()
    private var pumping = false
    private var lastFrameText = ""
    private var lastSentAt = 0L
    private var lastLat: Double? = null
    private var lastLon: Double? = null
    private var localEnabled = true

    override fun onCreate() {
        super.onCreate()
        instance = this
        val ch = NotificationChannel(CHANNEL_ID, "M4 导航", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
        geo = GeoCountdown(50.0)
        ble = BleEngine(this) { msg -> log(msg) }
        startForeground(NOTIF_ID, buildNotification("M4 导航运行中"))
        startBle()
        startHttp()
    }

    private fun buildNotification(text: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("M4 导航")
        .setContentText(text)
        .setSmallIcon(android.R.drawable.stat_sys_speakerphone)
        .setOngoing(true)
        .build()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    fun startBle() {
        ble.start()
    }

    fun startLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            log("缺定位权限，无法开倒计时")
            return
        }
        locationClient = LocationServices.getFusedLocationProviderClient(this)
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000L)
            .setMinUpdateDistanceMeters(50f)
            .build()
        locationClient.requestLocationUpdates(req, callback, android.os.Looper.getMainLooper())
        log("定位心跳已启动（每 50 米）")
    }

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc: Location = result.lastLocation ?: return
            lastLat = loc.latitude
            lastLon = loc.longitude
            val remain = geo.onPosition(loc.latitude, loc.longitude) ?: return
            val pic = NavRender.render(geo.dir, remain.toInt())
            log("GEO 剩余 ${remain.toInt()}m -> $pic")
            enqueue(pic)
        }
    }

    /** 收到文本（通知或 HTTP）：解析 + 建锚点 + 立即推。 */
    fun handleText(raw: String) {
        if (raw.isBlank()) return
        val p = NavRender.parse(raw)
        if (p.meters != null && p.dir in listOf("L", "R", "U", "O")) {
            geo.onNotification(p.dir, p.meters, lastLat, lastLon)
        }
        val pic = NavRender.render(p.dir, p.meters)
        log("IN $raw -> ${p.dir}/${p.meters} -> $pic")
        enqueue(pic)
    }

    /** 纯位置心跳。 */
    fun handlePosition(lat: Double, lon: Double) {
        lastLat = lat
        lastLon = lon
        val remain = geo.onPosition(lat, lon) ?: return
        val pic = NavRender.render(geo.dir, remain.toInt())
        log("GEO 剩余 ${remain.toInt()}m -> $pic")
        enqueue(pic)
    }

    private fun enqueue(pic: String) {
        if (pic == lastFrameText && System.currentTimeMillis() - lastSentAt < 10000L) return
        val bytes = try {
            Frame.alert(pic)
        } catch (e: IllegalArgumentException) {
            log("跳过（超字节）：$pic")
            return
        }
        lastFrameText = pic
        queue.add(bytes)
        if (!pumping) {
            pumping = true
            scope.launch { pump() }
        }
    }

    private suspend fun pump() {
        while (queue.isNotEmpty()) {
            val now = System.currentTimeMillis()
            val wait = COOLDOWN_MS - (now - lastSentAt)
            if (wait > 0) delay(wait)
            val f = queue.poll() ?: break
            val ok = ble.write(f)
            lastSentAt = System.currentTimeMillis()
            log(if (ok) "TX ${Frame.hexs(f)}" else "TX 失败（未连上？）")
        }
        pumping = false
    }

    // ---------------- 本地 HTTP 入口（给 Termux / MacroDroid 用） ----------------

    private fun startHttp() {
        if (!localEnabled) return
        Thread {
            try {
                val server = ServerSocket(HTTP_PORT, 50, InetAddress.getByName("127.0.0.1"))
                log("HTTP 入口：http://127.0.0.1:$HTTP_PORT/nav")
                while (true) {
                    val s: Socket = server.accept()
                    Thread { serve(s) }.start()
                }
            } catch (e: Exception) {
                log("HTTP 启动失败：${e.message}")
            }
        }.start()
    }

    private fun serve(s: Socket) {
        try {
            val input = BufferedInputStream(s.getInputStream())
            var cl = 0
            var line = StringBuilder()
            // 读请求头，取 Content-Length
            while (true) {
                val b = input.read()
                if (b == -1) break
                if (b == '\n'.code) {
                    val l = line.toString()
                    line = StringBuilder()
                    if (l.isBlank()) break
                    if (l.startsWith("Content-Length:", ignoreCase = true)) {
                        cl = l.substringAfter(":").trim().toIntOrNull() ?: 0
                    }
                } else if (b != '\r'.code) {
                    line.append(b.toChar())
                }
            }
            var read = 0
            val body = StringBuilder()
            while (read < cl) {
                val b = input.read()
                if (b == -1) break
                body.append(b.toChar())
                read++
            }
            val raw = body.toString().trim()
            route(raw)
            s.getOutputStream().write(
                "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nConnection: close\r\n\r\n".toByteArray()
            )
            s.getOutputStream().write("{\"ok\":true}".toByteArray())
            s.getOutputStream().flush()
        } catch (e: Exception) {
            log("HTTP 处理异常：${e.message}")
        } finally {
            try { s.close() } catch (e: Exception) { }
        }
    }

    private fun route(raw: String) {
        if (raw.isBlank()) return
        val lat = pick(raw, listOf("lat", "latitude"))
        val lon = pick(raw, listOf("lon", "lng", "longitude"))
        val text = pickStr(raw, listOf("title", "text", "msg", "message"))
        if (text.isNotBlank()) {
            handleText(text)
        } else if (lat != null && lon != null) {
            handlePosition(lat, lon)
        } else if (raw.isNotBlank() && !raw.startsWith("{")) {
            handleText(raw)
        }
    }

    private fun pick(raw: String, keys: List<String>): Double? {
        for (k in keys) {
            val m = Regex("\"$k\"\\s*:\\s*\"?([-0-9.]+)").find(raw) ?: continue
            return m.groupValues[1].toDoubleOrNull()
        }
        return null
    }

    private fun pickStr(raw: String, keys: List<String>): String {
        for (k in keys) {
            val m = Regex("\"$k\"\\s*:\\s*\"([^\"]*)\"").find(raw) ?: continue
            if (m.groupValues[1].isNotBlank()) return m.groupValues[1]
        }
        return ""
    }

    private fun log(msg: String) {
        onLog?.invoke(msg)
    }

    override fun onDestroy() {
        try {
            locationClient.removeLocationUpdates(callback)
        } catch (e: Exception) { }
        ble.stop()
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
