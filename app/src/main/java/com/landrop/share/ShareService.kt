package com.landrop.share

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileInputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

class ShareService : Service() {

    companion object {
        const val PORT = 8080
        const val ACTION_START = "com.landrop.share.START"
        const val ACTION_STOP = "com.landrop.share.STOP"
        const val EXTRA_PATHS = "paths"
        const val EXTRA_NAMES = "names"
        private const val CHANNEL_ID = "landrop_share"
        private const val NOTIF_ID = 42

        fun getLocalIpAddress(context: Context): String {
            try {
                val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
                for (intf in interfaces) {
                    val addrs = Collections.list(intf.inetAddresses)
                    for (addr in addrs) {
                        if (!addr.isLoopbackAddress && addr is Inet4Address) {
                            val s = addr.hostAddress ?: continue
                            if (s.startsWith("192.168.") || s.startsWith("10.") || s.startsWith("172.")) {
                                return s
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
            try {
                val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                @Suppress("DEPRECATION")
                val ip = wm.connectionInfo.ipAddress
                if (ip != 0) {
                    return String.format(
                        "%d.%d.%d.%d",
                        ip and 0xff,
                        ip shr 8 and 0xff,
                        ip shr 16 and 0xff,
                        ip shr 24 and 0xff
                    )
                }
            } catch (_: Exception) {}
            return "0.0.0.0"
        }
    }

    private var server: FileServer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val paths = intent.getStringArrayListExtra(EXTRA_PATHS) ?: arrayListOf()
                val names = intent.getStringArrayListExtra(EXTRA_NAMES) ?: arrayListOf()
                startForeground(NOTIF_ID, buildNotification())
                startServer(paths, names)
            }
            ACTION_STOP -> {
                stopServer()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun startServer(paths: List<String>, names: List<String>) {
        stopServer()
        val files = paths.mapIndexed { i, p ->
            val name = names.getOrElse(i) { File(p).name }
            name to File(p)
        }.filter { it.second.exists() }

        server = FileServer(PORT, files)
        try {
            server?.start()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun stopServer() {
        try {
            server?.stop()
        } catch (_: Exception) {}
        server = null
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "LanDrop Sharing",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("LanDrop is sharing")
            .setContentText("Others can download from your link")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        stopServer()
        super.onDestroy()
    }

    private class FileServer(
        port: Int,
        private val files: List<Pair<String, File>>
    ) : NanoHTTPD(port) {

        override fun serve(session: IHTTPSession): Response {
            val uri = session.uri.trimEnd('/')
            return when {
                uri.isEmpty() || uri == "/" -> serveIndex()
                uri.startsWith("/download/") -> {
                    val name = uri.removePrefix("/download/")
                    val decoded = try {
                        java.net.URLDecoder.decode(name, "UTF-8")
                    } catch (_: Exception) { name }
                    val pair = files.find { it.first == decoded }
                    if (pair != null) serveFile(pair.first, pair.second)
                    else newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
                }
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
            }
        }

        private fun serveIndex(): Response {
            val sb = StringBuilder()
            sb.append("""
                <!DOCTYPE html>
                <html><head>
                <meta charset="utf-8"/>
                <meta name="viewport" content="width=device-width,initial-scale=1"/>
                <title>LanDrop</title>
                <style>
                  body{font-family:system-ui,sans-serif;background:#1A1A2E;color:#eee;margin:0;padding:20px}
                  h1{color:#6C5CE7;text-align:center}
                  .file{background:#252540;border-radius:12px;padding:16px;margin:10px 0;display:flex;justify-content:space-between;align-items:center}
                  a{background:#6C5CE7;color:#fff;padding:10px 18px;border-radius:8px;text-decoration:none;font-weight:600}
                  .size{color:#A0A0B0;font-size:13px;margin-top:4px}
                </style>
                </head><body>
                <h1>LanDrop</h1>
                <p style="text-align:center;color:#A0A0B0">Tap a file to download</p>
            """.trimIndent())

            if (files.isEmpty()) {
                sb.append("<p style='text-align:center'>No files shared</p>")
            } else {
                files.forEach { (name, file) ->
                    val size = formatSize(file.length())
                    val href = "/download/" + java.net.URLEncoder.encode(name, "UTF-8")
                    sb.append("""
                        <div class="file">
                          <div>
                            <div>$name</div>
                            <div class="size">$size</div>
                          </div>
                          <a href="$href">Download</a>
                        </div>
                    """.trimIndent())
                }
            }
            sb.append("</body></html>")
            return newFixedLengthResponse(Response.Status.OK, "text/html", sb.toString())
        }

        private fun serveFile(name: String, file: File): Response {
            val fis = FileInputStream(file)
            val res = newFixedLengthResponse(
                Response.Status.OK,
                "application/octet-stream",
                fis,
                file.length()
            )
            res.addHeader("Content-Disposition", "attachment; filename=\"$name\"")
            return res
        }

        private fun formatSize(bytes: Long): String {
            if (bytes < 1024) return "$bytes B"
            if (bytes < 1024 * 1024) return "%.1f KB".format(bytes / 1024.0)
            return "%.1f MB".format(bytes / (1024.0 * 1024.0))
        }
    }
}
