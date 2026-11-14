package com.rama.riya

import android.Manifest
import android.animation.ValueAnimator
import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : Activity() {

    private class Step(val title: String, val sub: String, val done: () -> Boolean, val action: () -> Unit)

    // Palette
    private val bgTop = Color.parseColor("#0F0C29")
    private val bgMid = Color.parseColor("#1B1646")
    private val bgBottom = Color.parseColor("#2E1A5E")
    private val purple = Color.parseColor("#8B6CFF")
    private val pink = Color.parseColor("#FF5C93")
    private val teal = Color.parseColor("#2DE2C0")
    private val amber = Color.parseColor("#FFC04D")
    private val red = Color.parseColor("#FF5470")
    private val white = Color.WHITE
    private val soft = Color.parseColor("#B9B3E6")

    private lateinit var prefs: SharedPreferences
    private lateinit var titleView: TextView
    private lateinit var nameBox: EditText
    private lateinit var keyBox: EditText
    private lateinit var ownerBox: EditText
    private lateinit var errorText: TextView
    private lateinit var glow: View
    private lateinit var orb: View
    private lateinit var statusText: TextView
    private lateinit var heardText: TextView
    private lateinit var replyText: TextView
    private lateinit var brainStatus: TextView
    private lateinit var brainProgress: TextView
    private lateinit var urlBox: EditText
    @Volatile private var downloading = false
    private val modelUrlDefault =
        "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv1280.task"
    private val statusViews = ArrayList<Pair<TextView, Step>>()
    private var pulse: ValueAnimator? = null
    private var shownState = ""

    // ---------- helpers ----------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun oval(color: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }

    private fun gradOval(c1: Int, c2: Int) = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(c1, c2)).apply {
        shape = GradientDrawable.OVAL
    }

    private fun ripple(bg: GradientDrawable) = RippleDrawable(ColorStateList.valueOf(Color.parseColor("#44FFFFFF")), bg, null)

    private fun label(t: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = t
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun params(top: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(top) }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(Color.parseColor("#1AFFFFFF"), 20, Color.parseColor("#26FFFFFF"))
        setPadding(dp(18), dp(14), dp(18), dp(14))
        layoutParams = params(14)
    }

    private fun input(hint: String, value: String, password: Boolean = false) = EditText(this).apply {
        this.hint = hint
        setText(value)
        setSingleLine()
        textSize = 16f
        setTextColor(white)
        setHintTextColor(Color.parseColor("#80FFFFFF"))
        background = rounded(Color.parseColor("#22FFFFFF"), 14)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        if (password) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        layoutParams = params(8)
    }

    private fun bigButton(text: String, c1: Int, c2: Int, outlineOnly: Boolean, weight: Float, onClick: () -> Unit): TextView {
        val bg = if (outlineOnly) rounded(Color.parseColor("#14FFFFFF"), 16, c1)
        else GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(c1, c2)).apply { cornerRadius = dp(16).toFloat() }
        return TextView(this).apply {
            this.text = text
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (outlineOnly) c1 else white)
            gravity = Gravity.CENTER
            background = ripple(bg)
            isClickable = true
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(0, dp(54), weight).apply { marginStart = dp(4); marginEnd = dp(4) }
        }
    }

    // ---------- permission / setup state ----------
    private fun granted(p: String) = checkSelfPermission(p) == android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun accessibilityOn(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        return enabled.contains(packageName)
    }

    private fun steps(): List<Step> = listOf(
        Step("Microphone, contacts, calls", "Needed to hear you and call people", {
            granted(Manifest.permission.RECORD_AUDIO) && granted(Manifest.permission.READ_CONTACTS) &&
                granted(Manifest.permission.CALL_PHONE) &&
                (Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS))
        }) {
            val perms = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE)
            if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
            requestPermissions(perms.toTypedArray(), 1)
        },
        Step("Lock screen", "Device admin, lets me lock your phone", {
            getSystemService(DevicePolicyManager::class.java).isAdminActive(ComponentName(this, AdminReceiver::class.java))
        }) {
            startActivity(Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, ComponentName(this@MainActivity, AdminReceiver::class.java))
                putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Needed so Riya can lock your screen.")
            })
        },
        Step("Display over other apps", "Lets me open apps and calls instantly", { Settings.canDrawOverlays(this) }) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        },
        Step("Run in background", "Keeps me listening with screen off", {
            (getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)
        }) {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        },
        Step("Silence the listening beep", "Do Not Disturb access, lets me mute the ding", {
            getSystemService(android.app.NotificationManager::class.java).isNotificationPolicyAccessGranted
        }) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
        },
        Step("WhatsApp auto-send", "Accessibility: Riya, On (only needed to press Send in WhatsApp)", { accessibilityOn() }) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    )

    private fun stepRow(index: Int, step: Step): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
            isClickable = true
            setOnClickListener { step.action() }
        }
        val badge = TextView(this).apply {
            text = "$index"
            gravity = Gravity.CENTER
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(white)
            background = oval(purple)
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28))
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12) }
            addView(label(step.title, 15f, white, true))
            addView(label(step.sub, 12f, soft))
        }
        val status = TextView(this).apply { textSize = 13f; typeface = Typeface.DEFAULT_BOLD }
        row.addView(badge)
        row.addView(col)
        row.addView(status)
        statusViews.add(status to step)
        return row
    }

    private fun refreshSetup() {
        if (::brainStatus.isInitialized) {
            val f = LocalLlm.file(this)
            brainStatus.text = if (LocalLlm.installed(this)) "Offline brain: ✓ installed (${f.length() / 1_000_000} MB)"
            else "Offline brain: not installed yet"
        }
        for ((tv, step) in statusViews) {
            val ok = try { step.done() } catch (e: Exception) { false }
            tv.text = if (ok) "✓ Done" else "Tap"
            tv.setTextColor(if (ok) teal else amber)
        }
    }

    // ---------- offline brain: download / choose / delete ----------
    private fun downloadBrain() {
        if (downloading) return
        val url = urlBox.text.toString().trim()
        if (!url.startsWith("https://")) {
            Toast.makeText(this, "Enter a valid https download link", Toast.LENGTH_LONG).show()
            return
        }
        if (filesDir.usableSpace < 2_300_000_000L) {
            Toast.makeText(this, "Not enough free storage (need about 2.3 GB)", Toast.LENGTH_LONG).show()
            return
        }
        downloading = true
        brainProgress.text = "Starting download… keep this screen open"
        Thread {
            var msg: String
            val out = LocalLlm.file(this)
            val part = File(out.parentFile, "model.part")
            try {
                out.parentFile?.mkdirs()
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 30000
                conn.instanceFollowRedirects = true
                if (conn.responseCode != 200) throw IOException("server said HTTP " + conn.responseCode)
                val total = conn.contentLengthLong
                conn.inputStream.use { input ->
                    FileOutputStream(part).use { os ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var last = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            os.write(buf, 0, n)
                            done += n
                            val now = System.currentTimeMillis()
                            if (now - last > 500) {
                                last = now
                                val pct = if (total > 0) " (" + (done * 100 / total) + "%)" else ""
                                runOnUiThread { brainProgress.text = "Downloading… " + (done / 1_000_000) + " MB" + pct }
                            }
                        }
                    }
                }
                if (out.exists()) out.delete()
                if (!part.renameTo(out)) throw IOException("could not save the file")
                msg = "✓ Brain downloaded. Tap START."
            } catch (e: Exception) {
                part.delete()
                msg = "Download failed: " + (e.message ?: e.javaClass.simpleName) + ". You can also use CHOOSE FILE."
            }
            runOnUiThread { brainProgress.text = msg; downloading = false; refreshSetup() }
        }.start()
    }

    private fun pickBrain() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(i, 77)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 77 || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        if (downloading) return
        downloading = true
        brainProgress.text = "Copying the file…"
        Thread {
            var msg: String
            try {
                val out = LocalLlm.file(this)
                out.parentFile?.mkdirs()
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(out).use { os ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var last = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            os.write(buf, 0, n)
                            done += n
                            val now = System.currentTimeMillis()
                            if (now - last > 500) {
                                last = now
                                runOnUiThread { brainProgress.text = "Copying… " + (done / 1_000_000) + " MB" }
                            }
                        }
                    }
                } ?: throw IOException("could not open the file")
                msg = "✓ Brain installed. Tap START."
            } catch (e: Exception) {
                msg = "Copy failed: " + (e.message ?: e.javaClass.simpleName)
            }
            runOnUiThread { brainProgress.text = msg; downloading = false; refreshSetup() }
        }.start()
    }

    // ---------- live assistant state (orb) ----------
    private fun applyState() {
        val st = RiyaService.state
        val text: String
        val c1: Int
        val c2: Int
        val amp: Float
        val dur: Long
        when (st) {
            "listening" -> { text = "Listening…"; c1 = teal; c2 = purple; amp = 0.10f; dur = 1300 }
            "speaking" -> { text = "Speaking…"; c1 = pink; c2 = purple; amp = 0.18f; dur = 450 }
            "thinking" -> { text = "Thinking…"; c1 = amber; c2 = pink; amp = 0.14f; dur = 700 }
            else -> { text = "Tap START to wake me up"; c1 = Color.parseColor("#6B6890"); c2 = Color.parseColor("#3D3A63"); amp = 0f; dur = 1000 }
        }
        statusText.text = text
        orb.background = gradOval(c1, c2)
        glow.background = oval(c1)
        heardText.text = if (RiyaService.lastHeard.isEmpty()) "" else "You: " + RiyaService.lastHeard
        replyText.text = if (RiyaService.lastReply.isEmpty()) "" else titleView.text.toString() + ": " + RiyaService.lastReply
        errorText.text = if (RiyaService.lastError.isNotEmpty()) RiyaService.lastError else RiyaService.lastHint

        if (st != shownState) {
            shownState = st
            pulse?.cancel()
            glow.scaleX = 1f; glow.scaleY = 1f; orb.scaleX = 1f; orb.scaleY = 1f
            glow.alpha = if (amp > 0f) 0.35f else 0.12f
            if (amp > 0f) {
                pulse = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = dur
                    repeatMode = ValueAnimator.REVERSE
                    repeatCount = ValueAnimator.INFINITE
                    interpolator = AccelerateDecelerateInterpolator()
                    addUpdateListener {
                        val v = it.animatedValue as Float
                        glow.scaleX = 1f + amp * 2f * v
                        glow.scaleY = 1f + amp * 2f * v
                        orb.scaleX = 1f + amp * 0.5f * v
                        orb.scaleY = 1f + amp * 0.5f * v
                    }
                    start()
                }
            }
        }
    }

    // ---------- start / stop ----------
    private fun startRiya() {
        if (!granted(Manifest.permission.RECORD_AUDIO)) {
            Toast.makeText(this, "Allow the microphone first (step 1)", Toast.LENGTH_LONG).show()
            return
        }
        val name = nameBox.text.toString().trim().ifEmpty { "Riya" }
        prefs.edit().putString("name", name).putString("owner", ownerBox.text.toString().trim())
            .putString("ai_key", keyBox.text.toString().trim()).apply()
        titleView.text = name
        val i = Intent(this, RiyaService::class.java)
        stopService(i)               // restart so the new name is used
        startForegroundService(i)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("riya", Context.MODE_PRIVATE)
        window.statusBarColor = bgTop
        window.navigationBarColor = bgBottom

        val name = prefs.getString("name", "Riya") ?: "Riya"
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(32))
        }

        // Header
        titleView = label(name, 36f, white, true).apply { gravity = Gravity.CENTER }
        content.addView(titleView, params())
        content.addView(label("Your voice assistant", 14f, soft).apply { gravity = Gravity.CENTER }, params(2))

        // Orb
        val orbBox = FrameLayout(this)
        glow = View(this)
        orb = View(this)
        val mic = ImageView(this).apply { setImageResource(R.drawable.ic_notification) }
        orbBox.addView(glow, FrameLayout.LayoutParams(dp(130), dp(130), Gravity.CENTER))
        orbBox.addView(orb, FrameLayout.LayoutParams(dp(130), dp(130), Gravity.CENTER))
        orbBox.addView(mic, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER))
        content.addView(orbBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(230)).apply { topMargin = dp(8) })

        statusText = label("", 20f, white, true).apply { gravity = Gravity.CENTER }
        heardText = label("", 14f, soft).apply { gravity = Gravity.CENTER }
        replyText = label("", 15f, teal).apply { gravity = Gravity.CENTER }
        errorText = label("", 13f, amber).apply { gravity = Gravity.CENTER }
        content.addView(statusText, params())
        content.addView(heardText, params(10))
        content.addView(replyText, params(4))
        content.addView(errorText, params(4))

        // Start / Stop
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(bigButton("▶  START", teal, purple, false, 1.3f) { startRiya() })
        buttons.addView(bigButton("■  STOP", red, red, true, 1f) {
            stopService(Intent(this, RiyaService::class.java))
        })
        content.addView(buttons, params(18))

        // Name
        val nameCard = card()
        nameCard.addView(label("Assistant name", 15f, white, true))
        nameCard.addView(label("Say this name before every command", 12f, soft))
        nameBox = input("e.g. Riya, Jarvis, Maya", name)
        nameCard.addView(nameBox)
        nameCard.addView(label("Your name (I call you this)", 12f, soft).apply { setPadding(0, dp(10), 0, 0) })
        ownerBox = input("e.g. Ram", prefs.getString("owner", "Ram") ?: "Ram")
        nameCard.addView(ownerBox)
        val wakeSwitch = android.widget.Switch(this).apply {
            text = "Say the name before commands"
            setTextColor(white)
            textSize = 14f
            isChecked = prefs.getBoolean("wake_required", false)
            setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean("wake_required", on).apply() }
            layoutParams = params(12)
        }
        nameCard.addView(wakeSwitch)
        nameCard.addView(label("Turn off to react to every sentence (noisy rooms may trigger it)", 12f, soft))
        content.addView(nameCard)

        // AI key
        val aiCard = card()
        aiCard.addView(label("AI brain (optional)", 15f, white, true))
        aiCard.addView(label("Paste an Anthropic API key so I can chat and answer any question. Leave empty to use offline chat.", 12f, soft))
        keyBox = input("API key", prefs.getString("ai_key", "") ?: "", password = true)
        aiCard.addView(keyBox)
        content.addView(aiCard)

        // Offline voice chat
        val brain = card()
        brain.addView(label("Offline voice chat (free)", 17f, white, true))
        brain.addView(label("Hearing uses Vosk and answers come from a small AI model that runs on this phone. No internet needed after the one-time download.", 12f, soft))
        val earsSwitch = android.widget.Switch(this).apply {
            text = "Offline hearing"
            setTextColor(white)
            textSize = 14f
            isChecked = prefs.getBoolean("offline_ears", true)
            setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean("offline_ears", on).apply() }
            layoutParams = params(10)
        }
        brain.addView(earsSwitch)
        val indianSwitch = android.widget.Switch(this).apply {
            text = "Indian-English hearing model"
            setTextColor(white)
            textSize = 14f
            isChecked = prefs.getString("vosk_model", "en-in") == "en-in"
            setOnCheckedChangeListener { _, on -> prefs.edit().putString("vosk_model", if (on) "en-in" else "en-us").apply() }
            layoutParams = params(8)
        }
        brain.addView(indianSwitch)
        brainStatus = label("", 14f, teal).apply { setPadding(0, dp(12), 0, 0) }
        brain.addView(brainStatus)
        urlBox = input("Brain download link", modelUrlDefault)
        brain.addView(urlBox)
        brainProgress = label("", 13f, amber).apply { setPadding(0, dp(6), 0, 0) }
        brain.addView(brainProgress)
        val bb = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bb.addView(bigButton("⬇  DOWNLOAD", teal, purple, false, 1.6f) { downloadBrain() })
        bb.addView(bigButton("DELETE", red, red, true, 1f) {
            LocalLlm.file(this).delete()
            brainProgress.text = ""
            refreshSetup()
        })
        brain.addView(bb, params(10))
        brain.addView(bigButton("CHOOSE FILE FROM PHONE", purple, purple, true, 1f) { pickBrain() }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(54)).apply { topMargin = dp(8) })
        brain.addView(label("About 1.6 GB. Use Wi-Fi. Changes apply when you tap START.", 12f, soft).apply { setPadding(0, dp(8), 0, 0) })
        content.addView(brain)

        // Setup
        val setup = card()
        setup.addView(label("Setup", 17f, white, true))
        steps().forEachIndexed { i, st -> setup.addView(stepRow(i + 1, st)) }
        content.addView(setup)

        // Commands
        val cmds = card()
        cmds.addView(label("Try saying", 17f, white, true))
        listOf(
            "🚀  “$name, open Instagram”",
            "📞  “$name, call Vinod”",
            "💬  “$name, send WhatsApp to Amma saying hello”",
            "🎵  “$name, play Kesariya”",
            "🔦  “$name, flashlight on”",
            "🔊  “$name, volume 60”",
            "🔒  “$name, lock screen”",
            "😄  “$name, tell me a joke”",
            "🧠  “Why is the sky blue?”  (with the offline brain installed)"
        ).forEach { cmds.addView(label(it, 14f, soft).apply { setPadding(0, dp(7), 0, 0) }) }
        content.addView(cmds)

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(bgTop, bgMid, bgBottom))
            addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        refreshSetup()
        shownState = ""
        applyState()
        RiyaService.listener = { runOnUiThread { applyState() } }
    }

    override fun onPause() {
        RiyaService.listener = null
        pulse?.cancel()
        shownState = ""
        super.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshSetup()
    }
}
