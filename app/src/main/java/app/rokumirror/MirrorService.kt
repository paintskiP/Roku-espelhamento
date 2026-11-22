package app.rokumirror

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Toast
import java.io.ByteArrayOutputStream
import java.net.*
import java.util.Locale

/** Captura a tela, codifica em H.264, serve como HLS ao vivo e manda a Roku tocar. */
class MirrorService : Service() {
    private class Seg(val n: Int, val data: ByteArray, val dur: Double)

    private val segs = ArrayList<Seg>()
    private var proj: MediaProjection? = null
    private var vd: VirtualDisplay? = null
    private var enc: MediaCodec? = null
    private var srv: ServerSocket? = null
    @Volatile private var running = false
    private val port = 8081

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, flags: Int, id: Int): Int {
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel("m", "Espelhamento", NotificationManager.IMPORTANCE_LOW))
        startForeground(
            1,
            Notification.Builder(this, "m").setContentTitle("Espelhando para a Roku")
                .setSmallIcon(android.R.drawable.ic_media_play).build(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )
        if (i == null) { stopSelf(); return START_NOT_STICKY }
        val data = i.getParcelableExtra<Intent>("data")
        if (data == null) { stopSelf(); return START_NOT_STICKY }
        try {
            start(i.getStringExtra("ip") ?: "", i.getIntExtra("code", 0), data)
        } catch (e: Exception) {
            toast("Erro: ${e.message}")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun start(roku: String, code: Int, data: Intent) {
        stopAll()
        running = true
        val dm = resources.displayMetrics
        val k = minOf(1f, 1280f / maxOf(dm.widthPixels, dm.heightPixels))
        val w = (dm.widthPixels * k).toInt() and -2
        val h = (dm.heightPixels * k).toInt() and -2
        val f = MediaFormat.createVideoFormat("video/avc", w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 3_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000L) // tela parada continua gerando quadros
        }
        val e = MediaCodec.createEncoderByType("video/avc")
        e.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surf = e.createInputSurface()
        e.start()
        enc = e
        val p = getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data)
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, null)
        proj = p
        vd = p.createVirtualDisplay("roku", w, h, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surf, null, null)
        val s = ServerSocket(port)
        srv = s
        Thread { try { while (running) { val c = s.accept(); Thread { handle(c) }.start() } } catch (_: Exception) {} }.start()
        Thread { encodeLoop(e) }.start()
        Thread { launchRoku(roku) }.start()
    }

    private fun encodeLoop(e: MediaCodec) {
        val ts = Ts()
        val info = MediaCodec.BufferInfo()
        val aud = byteArrayOf(0, 0, 0, 1, 9, 0xF0.toByte())
        var cfg = ByteArray(0)
        var cur = ByteArrayOutputStream()
        var base = -1L
        var start = 0L
        var n = 0
        try {
            while (running) {
                val i = e.dequeueOutputBuffer(info, 100_000)
                if (i < 0) continue
                val d = ByteArray(info.size)
                e.getOutputBuffer(i)!!.apply { position(info.offset); get(d) }
                e.releaseOutputBuffer(i, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) { cfg = d; continue }
                if (base < 0) base = info.presentationTimeUs
                val pts = (info.presentationTimeUs - base) * 9 / 100 + 90000
                val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                if (key) {
                    if (cur.size() > 0) {
                        val seg = Seg(n++, cur.toByteArray(), (pts - start) / 90000.0)
                        synchronized(segs) {
                            segs.add(seg)
                            while (segs.size > 6) segs.removeAt(0)
                        }
                        cur = ByteArrayOutputStream()
                    }
                    cur.write(ts.header())
                    start = pts
                }
                if (cur.size() == 0) continue
                cur.write(ts.frame(aud + (if (key) cfg else ByteArray(0)) + d, pts, key))
            }
        } catch (_: Exception) {}
    }

    private fun playlist(): String = synchronized(segs) {
        val sb = StringBuilder("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:2\n")
        sb.append("#EXT-X-MEDIA-SEQUENCE:${segs.first().n}\n")
        for (s in segs) sb.append(String.format(Locale.US, "#EXTINF:%.3f,\ns%d.ts\n", s.dur, s.n))
        sb.toString()
    }

    private fun handle(c: Socket) {
        try {
            val r = c.getInputStream().bufferedReader()
            val path = r.readLine().split(" ")[1]
            while (true) { val l = r.readLine(); if (l.isNullOrEmpty()) break }
            val o = c.getOutputStream()
            val body: ByteArray
            val type: String
            if (path.startsWith("/live")) {
                body = playlist().toByteArray()
                type = "application/vnd.apple.mpegurl"
            } else {
                val n = path.removePrefix("/s").removeSuffix(".ts").toIntOrNull()
                val d = synchronized(segs) { segs.firstOrNull { it.n == n }?.data }
                if (d == null) {
                    o.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    return
                }
                body = d
                type = "video/MP2T"
            }
            o.write("HTTP/1.1 200 OK\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n".toByteArray())
            o.write(body)
            o.flush()
        } catch (_: Exception) {
        } finally {
            try { c.close() } catch (_: Exception) {}
        }
    }

    private fun launchRoku(roku: String) {
        try {
            while (running) {
                val ready = synchronized(segs) { segs.size } >= 3
                if (ready) break
                Thread.sleep(200)
            }
            if (!running) return
            val url = "http://${phoneIp()}:$port/live.m3u8"
            val q = "t=v&u=${URLEncoder.encode(url, "UTF-8")}&k=(null)&videoName=Espelho&videoFormat=hls"
            val c = URL("http://$roku:8060/input/15985?$q").openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.doOutput = true
            c.setFixedLengthStreamingMode(0)
            c.connectTimeout = 4000
            c.readTimeout = 4000
            c.outputStream.close()
            val code = c.responseCode
            toast(if (code in 200..299) "Enviado para a Roku" else "A Roku respondeu $code")
        } catch (e: Exception) {
            toast("Não consegui falar com a Roku: ${e.message}")
        }
    }

    private fun phoneIp(): String =
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.name.startsWith("wlan") }
            .flatMap { it.inetAddresses.asSequence() }
            .firstOrNull { it is Inet4Address }?.hostAddress ?: "127.0.0.1"

    private fun toast(s: String) {
        Handler(Looper.getMainLooper()).post { Toast.makeText(this, s, Toast.LENGTH_LONG).show() }
    }

    private fun stopAll() {
        running = false
        try { srv?.close() } catch (_: Exception) {}
        try { vd?.release() } catch (_: Exception) {}
        try { enc?.stop(); enc?.release() } catch (_: Exception) {}
        val p = proj
        proj = null; srv = null; vd = null; enc = null
        try { p?.stop() } catch (_: Exception) {}
        synchronized(segs) { segs.clear() }
    }

    override fun onDestroy() { stopAll() }
}
