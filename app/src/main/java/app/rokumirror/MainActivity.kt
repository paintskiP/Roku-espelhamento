package app.rokumirror

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.*

class MainActivity : Activity() {
    private lateinit var ip: EditText
    private lateinit var status: TextView

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val prefs = getSharedPreferences("p", Context.MODE_PRIVATE)
        ip = EditText(this).apply {
            hint = "IP da Roku (ex.: 192.168.0.15)"
            setText(prefs.getString("ip", ""))
        }
        status = TextView(this).apply { text = "Pronto."; setPadding(0, 32, 0, 0) }
        val start = Button(this).apply {
            text = "Iniciar espelhamento"
            setOnClickListener {
                prefs.edit().putString("ip", ip.text.toString().trim()).apply()
                val m = getSystemService(MediaProjectionManager::class.java)
                startActivityForResult(m.createScreenCaptureIntent(), 1)
            }
        }
        val stop = Button(this).apply {
            text = "Parar"
            setOnClickListener {
                stopService(Intent(this@MainActivity, MirrorService::class.java))
                status.text = "Parado."
            }
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
            addView(ip); addView(start); addView(stop); addView(status)
        }
        setContentView(root)
    }

    @Deprecated("simples")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        if (req == 1 && res == RESULT_OK && data != null) {
            startForegroundService(
                Intent(this, MirrorService::class.java)
                    .putExtra("code", res).putExtra("data", data)
                    .putExtra("ip", ip.text.toString().trim())
            )
            status.text = "Transmitindo... a Roku abre o vídeo em alguns segundos."
        } else status.text = "Permissão negada."
    }
}
