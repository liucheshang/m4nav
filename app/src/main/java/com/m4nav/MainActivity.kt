package com.m4nav

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var logView: TextView
    private lateinit var input: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 40, 24, 24)
        }

        status = TextView(this).apply {
            textSize = 15f
            text = "未启动"
        }
        root.addView(status)

        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row1.addView(button("授予权限") { askPerms() })
        row1.addView(button("开启服务") { startNav() })
        root.addView(row1)

        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row2.addView(button("通知使用权") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        })
        row2.addView(button("停止") { stopNav() })
        root.addView(row2)

        val row3 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row3.addView(button("定位心跳") { NavService.instance?.startLocation() })
        row3.addView(button("重连手环") { NavService.instance?.startBle() })
        root.addView(row3)

        input = EditText(this).apply {
            hint = "测试文本：300米后右转"
            setSingleLine(true)
        }
        root.addView(input)

        root.addView(button("推送这条") {
            val t = input.text.toString().trim()
            if (t.isNotEmpty()) NavService.instance?.handleText(t) ?: addLog("服务未启动")
        })

        logView = TextView(this).apply {
            textSize = 12f
            setTextIsSelectable(true)
        }
        root.addView(ScrollView(this).apply { addView(logView) })

        NavService.onLog = { msg -> runOnUiThread { addLog(msg) } }
        setContentView(root)
    }

    private fun button(text: String, onClick: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            setOnClickListener { onClick() }
        }

    private fun addLog(msg: String) {
        logView.append(msg + "\n")
        status.text = msg
    }

    private fun askPerms() {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            addLog("权限已齐")
        } else {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1001)
        }
    }

    private fun startNav() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED
        ) {
            addLog("请先点「授予权限」，否则前台服务起不来")
            askPerms()
            return
        }
        val i = Intent(this, NavService::class.java)
        ContextCompat.startForegroundService(this, i)
        addLog("服务已启动（可切后台，通知栏会显示）")
    }

    private fun stopNav() {
        startService(Intent(this, NavService::class.java).setAction("stop"))
        addLog("已请求停止")
    }
}
