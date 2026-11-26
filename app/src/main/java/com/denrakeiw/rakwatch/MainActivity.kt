package com.denrakeiw.rakwatch

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.RecognizerIntent
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class MainActivity : Activity() {

    companion object {
        private const val SPEECH_REQUEST_CODE = 1001
        private const val DEFAULT_BRIDGE_URL = "http://192.168.50.107:8765"
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val uiHandler = Handler(Looper.getMainLooper())

    private var bridgeUrl = DEFAULT_BRIDGE_URL
    private var activeTarget = "antigravity" // "antigravity" or "claude"
    private var lastRequestId = ""
    private var isPolling = false

    private lateinit var targetBadge: TextView
    private lateinit var statusText: TextView
    private lateinit var approveBtn: Button
    private lateinit var voiceBtn: Button
    private lateinit var stopBtn: Button

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!isPolling) return
            pollBridgeStatus()
            uiHandler.postDelayed(this, 1800L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        loadConfig()
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        isPolling = true
        uiHandler.post(pollRunnable)
    }

    override fun onPause() {
        super.onPause()
        isPolling = false
        uiHandler.removeCallbacks(pollRunnable)
    }

    private fun loadConfig() {
        try {
            val f = File("/sdcard/rakwatch.json")
            if (f.exists()) {
                val json = JSONObject(f.readText())
                val u = json.optString("bridgeUrl", "").trim()
                if (u.isNotEmpty()) bridgeUrl = u.trimEnd('/')
            }
        } catch (_: Exception) {}
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Color.BLACK)
            setPadding(24, 22, 24, 20)
        }

        // Top toggle pill: ANTIGRAVITY / CLAUDE
        targetBadge = TextView(this).apply {
            text = "● ANTIGRAVITY"
            setTextColor(Color.parseColor("#00F0FF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(18, 6, 18, 6)
            background = pillDrawable(Color.parseColor("#111B24"), Color.parseColor("#00F0FF"), 24f)
            setOnClickListener {
                activeTarget = if (activeTarget == "antigravity") "claude" else "antigravity"
                updateTargetBadge(online = true)
                vibrateTick()
            }
        }
        root.addView(targetBadge, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = 8 })

        // Status message above mechanical center pivot
        statusText = TextView(this).apply {
            text = "Connecting to PC..."
            setTextColor(Color.parseColor("#E0E6F0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            maxLines = 2
        }
        root.addView(statusText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = 14 })

        // HUGE APPROVE BUTTON
        approveBtn = Button(this).apply {
            text = "✓  APPROVE"
            setTextColor(Color.BLACK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = Typeface.DEFAULT_BOLD
            isAllCaps = false
            background = pillDrawable(Color.parseColor("#00E676"), Color.parseColor("#69F0AE"), 44f)
            setOnClickListener { sendApprove() }
        }
        root.addView(approveBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1.4f
        ).apply { bottomMargin = 12 })

        // Bottom row: VOICE + STOP
        val bottomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        voiceBtn = Button(this).apply {
            text = "🎙 VOICE"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            isAllCaps = false
            background = pillDrawable(Color.parseColor("#0D2B3E"), Color.parseColor("#00E5FF"), 32f)
            setOnClickListener { startVoiceInput() }
        }
        bottomRow.addView(voiceBtn, LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.MATCH_PARENT,
            1.15f
        ).apply { rightMargin = 8 })

        stopBtn = Button(this).apply {
            text = "✕ STOP"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            isAllCaps = false
            background = pillDrawable(Color.parseColor("#3B0D16"), Color.parseColor("#FF2E63"), 32f)
            setOnClickListener { sendReject() }
        }
        bottomRow.addView(stopBtn, LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.MATCH_PARENT,
            0.85f
        ))

        root.addView(bottomRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            0.85f
        ))

        setContentView(root)
    }

    private fun pillDrawable(fill: Int, stroke: Int, radius: Float): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            setStroke(3, stroke)
            cornerRadius = radius
        }

    private fun updateTargetBadge(online: Boolean) {
        val dot = if (online) "●" else "○"
        if (activeTarget == "claude") {
            targetBadge.text = "$dot CLAUDE CODE"
            targetBadge.setTextColor(Color.parseColor("#FFB74D"))
            targetBadge.background = pillDrawable(Color.parseColor("#24190D"), Color.parseColor("#FFB74D"), 24f)
        } else {
            targetBadge.text = "$dot ANTIGRAVITY"
            targetBadge.setTextColor(Color.parseColor("#00F0FF"))
            targetBadge.background = pillDrawable(Color.parseColor("#0E1D29"), Color.parseColor("#00F0FF"), 24f)
        }
    }

    // Hardware side buttons on LG Watch W7:
    // Upper side button (STEM_1) = APPROVE, Lower side button (STEM_2) = VOICE
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_STEM_1 -> {
                sendApprove()
                true
            }
            KeyEvent.KEYCODE_STEM_2 -> {
                startVoiceInput()
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun startVoiceInput() {
        vibrateTick()
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Sag deinen Befehl für ${activeTarget.uppercase()}...")
            }
            startActivityForResult(intent, SPEECH_REQUEST_CODE)
        } catch (e: Exception) {
            statusText.text = "Voice unavailable"
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == SPEECH_REQUEST_CODE && resultCode == RESULT_OK) {
            val results = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val spoken = results?.firstOrNull()?.trim().orEmpty()
            if (spoken.isNotEmpty()) {
                statusText.text = "» $spoken"
                sendPrompt(spoken)
            }
        }
    }

    private fun sendApprove() {
        vibrateTick()
        approveBtn.text = "APPROVED ✓"
        approveBtn.background = pillDrawable(Color.parseColor("#B9F6CA"), Color.WHITE, 44f)
        uiHandler.postDelayed({
            approveBtn.text = "✓  APPROVE"
            approveBtn.background = pillDrawable(Color.parseColor("#00E676"), Color.parseColor("#69F0AE"), 44f)
        }, 1200L)
        postJson("/api/approve", JSONObject().put("target", activeTarget).put("request_id", lastRequestId))
    }

    private fun sendReject() {
        vibrateAlert()
        statusText.text = "Sent STOP / REJECT"
        postJson("/api/reject", JSONObject().put("target", activeTarget).put("request_id", lastRequestId))
    }

    private fun sendPrompt(prompt: String) {
        postJson("/api/prompt", JSONObject().put("target", activeTarget).put("prompt", prompt))
    }

    private fun postJson(path: String, body: JSONObject) {
        executor.execute {
            try {
                val conn = (URL("$bridgeUrl$path").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 2500
                    readTimeout = 2500
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                conn.disconnect()
                uiHandler.post {
                    if (code in 200..299) {
                        updateTargetBadge(online = true)
                    } else {
                        statusText.text = "Bridge HTTP $code"
                    }
                }
            } catch (_: Exception) {
                uiHandler.post {
                    updateTargetBadge(online = false)
                    statusText.text = "PC Bridge offline"
                }
            }
        }
    }

    private fun pollBridgeStatus() {
        executor.execute {
            try {
                val conn = (URL("$bridgeUrl/api/status").openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 1800
                    readTimeout = 1800
                }
                if (conn.responseCode == 200) {
                    val text = conn.inputStream.bufferedReader().readText()
                    val json = JSONObject(text)
                    val state = json.optString("state", "idle")
                    val msg = json.optString("message", "Ready")
                    val reqId = json.optString("request_id", "")
                    val agent = json.optString("agent", "")
                    uiHandler.post {
                        if (agent == "claude" || agent == "antigravity") {
                            activeTarget = agent
                        }
                        updateTargetBadge(online = true)
                        statusText.text = msg
                        if (state == "pending" && reqId.isNotEmpty() && reqId != lastRequestId) {
                            lastRequestId = reqId
                            vibrateAlert()
                            approveBtn.text = "⚡ APPROVE NOW"
                            approveBtn.background = pillDrawable(Color.parseColor("#00FF87"), Color.WHITE, 44f)
                        } else if (state != "pending") {
                            approveBtn.text = "✓  APPROVE"
                            approveBtn.background = pillDrawable(Color.parseColor("#00E676"), Color.parseColor("#69F0AE"), 44f)
                        }
                    }
                }
                conn.disconnect()
            } catch (_: Exception) {
                uiHandler.post {
                    updateTargetBadge(online = false)
                    statusText.text = "Waiting for PC Bridge..."
                }
            }
        }
    }

    private fun vibrateTick() {
        try {
            val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            v.vibrate(VibrationEffect.createOneShot(45, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) {}
    }

    private fun vibrateAlert() {
        try {
            val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 120, 80, 160), -1))
        } catch (_: Exception) {}
    }
}
