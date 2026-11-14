package com.rama.riya

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.SearchManager
import android.app.Service
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.BatteryManager
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject
import android.provider.Settings
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.provider.ContactsContract
import android.provider.MediaStore
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.telecom.TelecomManager
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RiyaService : Service() {

    data class Contact(val name: String, val number: String)

    companion object {
        // Shown live on the main screen: stopped | listening | thinking | speaking
        @Volatile var state = "stopped"
        @Volatile var lastHeard = ""
        @Volatile var lastReply = ""
        @Volatile var lastError = ""
        @Volatile var lastHint = ""
        @Volatile var listener: (() -> Unit)? = null
    }

    private fun setState(st: String) { state = st; notifyUi() }
    private fun notifyUi() { handler.post { listener?.invoke() } }
    private fun prefs() = getSharedPreferences("riya", Context.MODE_PRIVATE)

    // Used when a contact number has no country code (change if you are not in India)
    private val defaultCountryCode = "91"

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var speaking = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var torchOn = false
    private val handler = Handler(Looper.getMainLooper())
    private var running = true

    // When Riya asks "whom?", the next sentence is the answer (no wake word needed)
    private var pendingContacts: List<Contact>? = null
    private var pendingAction: ((Contact) -> Unit)? = null
    private val clearPending = Runnable { pendingContacts = null; pendingAction = null; pendingMisses = 0 }
    private var pendingMisses = 0

    // Speech bookkeeping: only the latest utterance may end "speaking", plus a watchdog
    // so a lost TTS callback can never leave Riya deaf.
    private var utteranceCounter = 0
    private var currentUtterance: String? = null
    private val speakWatchdog = Runnable { speaking = false; setState("listening"); resumeAfterSpeech() }

    private var overlayView: View? = null

    private var afterSpeech: (() -> Unit)? = null    // runs when the current sentence finishes

    private var useVosk = false
    private var ears: VoskEars? = null
    private var localLlm: LocalLlm? = null

    private var listening = false
    private var thinking = false
    private var conversationUntil = 0L          // after Riya talks, you can answer without the wake word
    private var currentFollowUp = false
    private var pendingFreeText: ((String) -> Unit)? = null
    private var freeUntil = 0L
    private val io = Executors.newSingleThreadExecutor()
    private val aiHistory = ArrayList<Pair<String, String>>()
    private var appCache: List<AppEntry> = emptyList()
    private var appCacheAt = 0L

    private lateinit var wakeRegex: Regex
    private var assistantName = "riya"

    private fun loadWakeName() {
        assistantName = getSharedPreferences("riya", Context.MODE_PRIVATE)
            .getString("name", "riya")!!.trim().lowercase().ifEmpty { "riya" }
        val variants = when (assistantName) {
            "riya" -> listOf("riya", "ria", "reya", "rhea", "riyah", "riyaa", "reeya", "rea", "reah", "riyan")
            "janu" -> listOf("janu", "jaanu", "januu", "jhanu", "janoo", "zanu")
            "ram" -> listOf("ram", "raam", "rum", "rom", "ramm", "rahm")
            else -> listOf(assistantName)
        }
        wakeRegex = Regex("\\b(" + variants.joinToString("|") { Regex.escape(it) } + ")\\b")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        loadWakeName()
        val channelId = "riya_channel"
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(channelId, "Riya", NotificationManager.IMPORTANCE_LOW))
        val notification = Notification.Builder(this, channelId)
            .setContentTitle("$assistantName is listening")
            .setContentText("Say \"$assistantName\" followed by a command")
            .setSmallIcon(R.drawable.ic_notification)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1, notification)
        }

        // Keep the CPU awake so Riya keeps listening when the screen is off / locked
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "riya:listen").apply { acquire() }

        addOverlay()
        muteBeeps()

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val lr = tts?.setLanguage(Locale("en", "IN"))
                if (lr == TextToSpeech.LANG_MISSING_DATA || lr == TextToSpeech.LANG_NOT_SUPPORTED) tts?.setLanguage(Locale.US)
                tts?.setSpeechRate(1.12f)
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onError(utteranceId: String?) = finishSpeaking(utteranceId)
                    override fun onStop(utteranceId: String?, interrupted: Boolean) = finishSpeaking(utteranceId)
                    override fun onDone(utteranceId: String?) = finishSpeaking(utteranceId)
                })
                ttsReady = true
                say("Hi, I am $assistantName. I am listening.")
            }
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            lastError = "No speech recognition on this phone. Install or update the Google app."
            notifyUi()
        }
        io.execute { installedApps() }   // warm the app list so "open ..." is instant

        localLlm = LocalLlm(this)
        if (LocalLlm.installed(this)) {
            io.execute {
                val err = localLlm?.load()
                if (err != null) { lastError = err; notifyUi() }
            }
        }
        useVosk = prefs().getBoolean("offline_ears", true)
        if (useVosk) startOfflineEars() else startListening()
    }

    // ---------- Offline hearing (Vosk) ----------

    private fun startOfflineEars() {
        val model = prefs().getString("vosk_model", "en-in") ?: "en-in"
        lastError = "Loading offline hearing…"
        notifyUi()
        val e = VoskEars(
            this, model,
            onReady = {
                handler.post {
                    if (!running) return@post
                    lastError = ""
                    notifyUi()
                    startListening()
                }
            },
            onPartial = { p ->
                handler.post { if (running && !speaking && !thinking) { lastHeard = p; notifyUi() } }
            },
            onText = { t ->
                handler.post {
                    if (running && !speaking && !thinking) {
                        lastHeard = t
                        notifyUi()
                        handleSpeech(listOf(t))
                    }
                }
            },
            onFail = { msg ->
                handler.post {
                    if (!running) return@post
                    lastError = "Offline hearing failed ($msg). Using the Google recogniser instead."
                    useVosk = false
                    ears?.stop()
                    ears = null
                    notifyUi()
                    startListening()
                }
            }
        )
        ears = e
        e.start()
    }

    // The speech recogniser plays a "ding" each time it starts listening. Mute the system and
    // notification streams while Riya runs (restored when Riya stops).
    private val mutedStreams = ArrayList<Int>()

    private fun muteBeeps() {
        val am = getSystemService(AudioManager::class.java)
        for (st in intArrayOf(AudioManager.STREAM_NOTIFICATION, AudioManager.STREAM_SYSTEM)) {
            try {
                if (!am.isStreamMute(st)) {
                    am.adjustStreamVolume(st, AudioManager.ADJUST_MUTE, 0)
                    mutedStreams.add(st)
                }
            } catch (e: Exception) { /* needs Do Not Disturb access on some phones */ }
        }
    }

    private fun unmuteBeeps() {
        val am = getSystemService(AudioManager::class.java)
        for (st in mutedStreams) {
            try { am.adjustStreamVolume(st, AudioManager.ADJUST_UNMUTE, 0) } catch (e: Exception) { }
        }
        mutedStreams.clear()
    }

    // A 1px invisible overlay lets Android allow starting apps/calls from this background service.
    private fun addOverlay() {
        try {
            if (!Settings.canDrawOverlays(this) || overlayView != null) return
            val wm = getSystemService(WindowManager::class.java)
            val v = View(this)
            val lp = WindowManager.LayoutParams(
                1, 1,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.TOP or Gravity.START }
            wm.addView(v, lp)
            overlayView = v
        } catch (e: Exception) { /* optional helper */ }
    }

    // ---------- Listening (one reused recognizer = much faster restarts) ----------

    private var recLang = "en-IN"
    private var recognizerIntent: Intent = buildRecognizerIntent()

    private fun buildRecognizerIntent(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, recLang)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 900L)
        }

    private fun errorName(e: Int) = when (e) {
        1 -> "network timeout"; 2 -> "network error"; 3 -> "audio problem"; 4 -> "server error"
        5 -> "client error"; 8 -> "recogniser busy"; 9 -> "microphone permission missing"
        10 -> "too many requests"; 11 -> "server disconnected"
        12 -> "language not supported"; 13 -> "language not available"
        else -> "error $e"
    }

    private val listenWatchdog = Runnable { listening = false; resetRecognizer(); startListening() }

    private val recListener = object : RecognitionListener {
        override fun onResults(results: Bundle?) {
            listening = false
            handler.removeCallbacks(listenWatchdog)
            val heard = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.map { it.lowercase().trim() }?.filter { it.isNotEmpty() } ?: emptyList()
            if (heard.isNotEmpty()) {
                pendingMisses = 0
                lastHeard = heard[0]
                notifyUi()
                handleSpeech(heard)
            }
            restart()
        }

        override fun onError(error: Int) {
            listening = false
            handler.removeCallbacks(listenWatchdog)
            val soft = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
            if (!soft) {
                lastError = "Mic problem: " + errorName(error)
                notifyUi()
                if ((error == 12 || error == 13) && recLang != "en-US") {
                    recLang = "en-US"
                    recognizerIntent = buildRecognizerIntent()
                }
                resetRecognizer()
            }
            if (pendingContacts != null && soft) {
                pendingMisses++
                if (pendingMisses >= 3) {
                    pendingMisses = 0
                    say("I did not hear you. Please say the number, like one, two or three.")
                }
            }
            restart(if (soft) 60L else 300L)
        }

        override fun onReadyForSpeech(params: Bundle?) {
            if (lastError.isNotEmpty()) { lastError = ""; notifyUi() }
            if (!thinking && !speaking) setState("listening")
        }

        // Fastest path: while choosing a contact, react the moment a clear number is heard
        override fun onPartialResults(partialResults: Bundle?) {
            val pending = pendingContacts ?: return
            val p = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.map { it.lowercase().trim() }?.filter { it.isNotEmpty() } ?: return
            if (p.any { parseChoice(it, pending.size, strictOnly = true) != null }) {
                listening = false
                handler.removeCallbacks(listenWatchdog)
                recognizer?.cancel()
                lastHeard = p[0]; notifyUi()
                handleChoice(p, pending)
                restart()
            }
        }

        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun resetRecognizer() {
        recognizer?.destroy()
        recognizer = null
        listening = false
    }

    private fun startListening() {
        if (!running || speaking) return
        if (useVosk) {
            ears?.resume()
            if (!thinking) setState("listening")
            return
        }
        if (listening) return
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this).also { it.setRecognitionListener(recListener) }
        }
        listening = true
        handler.removeCallbacks(listenWatchdog)
        handler.postDelayed(listenWatchdog, 20000)
        recognizer?.startListening(recognizerIntent)
    }

    private fun restart(delay: Long = 60) {
        if (running && !speaking) handler.postDelayed({ startListening() }, if (pendingContacts != null) 0L else delay)
    }

    // ---------- Speaking ----------

    private fun say(msg: String, followUp: Boolean = false) {
        lastReply = msg
        if (!ttsReady) {
            if (followUp) conversationUntil = System.currentTimeMillis() + 8000
            notifyUi()
            val h = afterSpeech; afterSpeech = null
            h?.invoke()
            return
        }
        speaking = true
        setState("speaking")
        ears?.pause()
        recognizer?.cancel()
        listening = false
        handler.removeCallbacks(listenWatchdog)
        val id = "riya_" + (++utteranceCounter)
        currentUtterance = id
        currentFollowUp = followUp
        handler.removeCallbacks(speakWatchdog)
        val r = tts?.speak(msg, TextToSpeech.QUEUE_FLUSH, null, id)
        if (r != TextToSpeech.SUCCESS) {
            speaking = false
            setState("listening")
            val h = afterSpeech; afterSpeech = null
            if (h != null) h() else restart()
        } else {
            handler.postDelayed(speakWatchdog, 4000L + msg.length * 90L)
        }
    }

    private fun finishSpeaking(id: String?) {
        handler.post {
            if (id != currentUtterance) return@post
            handler.removeCallbacks(speakWatchdog)
            speaking = false
            conversationUntil = if (currentFollowUp) System.currentTimeMillis() + 8000 else 0L
            if (pendingContacts != null) armPending()
            setState("listening")
            resumeAfterSpeech()
        }
    }

    private fun resumeAfterSpeech() {
        val h = afterSpeech
        afterSpeech = null
        if (h != null) h() else startListening()
    }

    private fun armPending() {
        handler.removeCallbacks(clearPending)
        handler.postDelayed(clearPending, 30000)
    }

    // ---------- Command handling ----------

    private fun handleSpeech(heard: List<String>) {
        val pending = pendingContacts
        if (pending != null) {
            handleChoice(heard, pending)
            return
        }
        val now = System.currentTimeMillis()
        val free = pendingFreeText
        if (free != null) {
            pendingFreeText = null
            if (now < freeUntil) { free(heard[0]); return }
        }
        for (text in heard) {
            val match = wakeRegex.find(text) ?: continue
            val after = text.substring(match.range.last + 1).trim()
            val before = text.substring(0, match.range.first).trim()
            conversationUntil = 0
            handle(if (after.isNotEmpty()) after else before)   // "Riya call Vinod" and "call Vinod Riya"
            return
        }
        // No wake word, but Riya just spoke to you: treat this as the reply
        if (now < conversationUntil) { conversationUntil = 0; handle(heard[0]); return }
        // Wake word turned off in the app: every sentence is a command
        if (!prefs().getBoolean("wake_required", false)) {
            val t = heard[0]
            val noise = setOf("the", "a", "uh", "huh", "hmm", "um", "oh", "ah", "eh", "and", "but")
            if (t.length >= 3 && t !in noise) handle(t, false)
            return
        }
        // Heard you, but there was no name: tell the user instead of staying silent
        lastHint = "Heard \u201c${heard[0]}\u201d. Start with \u201c$assistantName\u201d, e.g. \u201c$assistantName, open Instagram\u201d."
        notifyUi()
    }

    private fun handle(raw: String, addressed: Boolean = true) {
        lastHint = ""
        var c = raw.trim()
        val filler = Regex("^(please|can you|could you|would you|will you|hey|ok|okay|just|now)\\b[ ,]*")
        var prev: String
        do { prev = c; c = c.replace(filler, "").trim() } while (c != prev)
        if (c.isEmpty()) {
            val o = prefs().getString("owner", "").orEmpty().trim()
            say(if (o.isEmpty()) "I am listening. Tell me." else "$o, I am listening. Tell me.", followUp = true)
            return
        }

        val openRe = Regex("^(open|launch|start|run)\\s+(?:the\\s+)?(.+)$")

        when {
            c.startsWith("stop") || c.startsWith("exit") -> stopSelf()
            Regex("^(call|phone|dial)\\b").containsMatchIn(c) -> {
                val name = c.replace(Regex("^(call|phone|dial)( to)?"), "").trim()
                if (name.isEmpty()) {
                    pendingFreeText = { n ->
                        val nm = n.replace(Regex("^(call|phone|dial)( to)?"), "").trim()
                        if (nm.isNotEmpty()) pickContact(nm) { dial(it) }
                    }
                    freeUntil = System.currentTimeMillis() + 15000
                    say("Whom should I call?")
                } else pickContact(name) { dial(it) }
            }
            openRe.containsMatchIn(c) -> {
                val target = openRe.find(c)!!.groupValues[2].replace(Regex("\\s+app$"), "").trim()
                if ("camera" in target) openCamera() else openApp(target)
            }
            c.startsWith("play") -> {
                val m = Regex("^play (.+?)(?: on (youtube|spotify))?$").find(c)
                val song = m?.groupValues?.get(1) ?: c.replace("play", "").trim()
                val platform = m?.groupValues?.get(2).orEmpty()
                if (platform == "youtube") youtube(song) else playSpotify(song)
            }
            "whatsapp" in c || c.startsWith("message") || c.startsWith("send message") -> whatsapp(c)
            Regex("\\b(unlock|un lock)\\b").containsMatchIn(c) ->
                say("Sorry, I can no longer unlock the phone. Please unlock it yourself.", true)
            Regex("\\block\\b").containsMatchIn(c) && "unlock" !in c -> lockScreen()
            "flashlight" in c || "torch" in c || "flash light" in c -> flashlight(c)
            "volume" in c || "mute" in c || "louder" in c -> volume(c)
            Regex("\\b(time|clock)\\b").containsMatchIn(c) ->
                say("It is " + SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date()), true)
            Regex("\\b(date|today)\\b").containsMatchIn(c) ->
                say("Today is " + SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(Date()), true)
            "camera" in c -> openCamera()
            else -> {
                val talk = smallTalk(c)
                val hasKey = prefs().getString("ai_key", "").orEmpty().isNotBlank()
                if (talk != null) say(talk, true)
                else if (addressed && c.split(" ").size <= 3 && openApp(c, quiet = true)) { /* opened */ }
                else if (LocalLlm.installed(this)) {
                    if (localLlm?.isLoaded() == true) askLocal(c)
                    else if (hasKey) askAi(c)
                    else say("My offline brain is still loading. Please ask again in a few seconds.", true)
                }
                else if (hasKey) askAi(c)
                else if (addressed) say("Sorry, I did not understand. Install the offline brain in the app so I can answer anything.", true)
            }
        }
    }

    // ---------- Chat: offline small talk + optional AI brain ----------

    private val jokes = listOf(
        "Why did the phone go to school? To get smarter!",
        "Why was the computer cold? It left its Windows open.",
        "I told my phone a joke, but it did not laugh. It just buffered.",
        "Why do programmers prefer dark mode? Because light attracts bugs."
    )

    private fun smallTalk(c: String): String? {
        fun has(vararg w: String) = w.any { Regex("\\b" + it + "\\b").containsMatchIn(c) }
        val short = c.split(" ").size <= 3
        return when {
            short && has("hi", "hii", "hello", "hey", "namaste") -> "Hello! How can I help you?"
            "how are you" in c -> "I am doing great, thanks for asking! What can I do for you?"
            "your name" in c || "who are you" in c -> "I am $assistantName, your voice assistant."
            has("thanks", "thank you") -> "You are welcome!"
            "good morning" in c -> "Good morning! Have a wonderful day."
            "good night" in c -> "Good night! Sleep well."
            "joke" in c -> jokes.random()
            "i love you" in c -> "Aww, that is sweet! I am always here to help."
            has("bye", "goodbye") -> "Bye! Call me anytime."
            "battery" in c -> {
                val bm = getSystemService(BatteryManager::class.java)
                "Battery is at " + bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) + " percent"
            }
            "what can you do" in c || c == "help" ->
                "I can open any app, call your contacts, send WhatsApp messages, play songs, control the flashlight and volume, lock your screen, and chat with you."
            else -> null
        }
    }

    private fun askLocal(q: String) {
        thinking = true
        ears?.pause()
        setState("thinking")
        val past = aiHistory.takeLast(6).toList()
        io.execute {
            val reply = try { localLlm?.ask(assistantName, past, q) } catch (e: Throwable) { null }
            handler.post {
                thinking = false
                if (!running) return@post
                if (reply.isNullOrBlank()) {
                    say("Sorry, I could not think of an answer.", true)
                } else {
                    aiHistory.add("user" to q)
                    aiHistory.add("assistant" to reply)
                    while (aiHistory.size > 12) aiHistory.removeAt(0)
                    say(reply, true)
                }
            }
        }
    }

    private fun askAi(q: String) {
        val key = prefs().getString("ai_key", "").orEmpty().trim()
        thinking = true
        setState("thinking")
        val past = aiHistory.takeLast(8).toList()
        io.execute {
            val reply = try { callClaude(key, past, q) } catch (e: Exception) { null }
            handler.post {
                thinking = false
                if (!running) return@post
                if (reply.isNullOrBlank()) {
                    say("Sorry, I could not reach my brain. Please check your internet and key.", true)
                } else {
                    aiHistory.add("user" to q)
                    aiHistory.add("assistant" to reply)
                    while (aiHistory.size > 20) aiHistory.removeAt(0)
                    say(reply, true)
                }
            }
        }
    }

    private fun callClaude(key: String, past: List<Pair<String, String>>, q: String): String? {
        val msgs = JSONArray()
        for ((role, text) in past) msgs.put(JSONObject().put("role", role).put("content", text))
        msgs.put(JSONObject().put("role", "user").put("content", q))
        val body = JSONObject()
            .put("model", "claude-haiku-5-5")
            .put("max_tokens", 150)
            .put("system", "You are $assistantName, a friendly voice assistant on the user's phone. " +
                "Reply in one or two short spoken sentences. No markdown, no lists, no emojis.")
            .put("messages", msgs)
        val conn = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 8000
            conn.readTimeout = 20000
            conn.doOutput = true
            conn.setRequestProperty("content-type", "application/json")
            conn.setRequestProperty("x-api-key", key)
            conn.setRequestProperty("anthropic-version", "2023-06-01")
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            if (conn.responseCode != 200) return null
            val txt = conn.inputStream.bufferedReader().use { it.readText() }
            return JSONObject(txt).getJSONArray("content").getJSONObject(0).getString("text").trim()
        } finally {
            conn.disconnect()
        }
    }

    // ---------- Open ANY app by name ----------

    private data class AppEntry(val label: String, val norm: String, val pkg: String)

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    private val appAliases = mapOf(
        "insta" to "instagram", "fb" to "facebook", "gpay" to "googlepay", "googlepay" to "gpay",
        "yt" to "youtube", "maps" to "maps", "gmail" to "gmail", "mail" to "gmail",
        "settings" to "settings", "setting" to "settings", "whatsapp" to "whatsapp",
        "chrome" to "chrome", "browser" to "chrome", "play store" to "playstore",
        "playstore" to "playstore", "photos" to "photos", "gallery" to "gallery",
        "calculator" to "calculator", "phonepe" to "phonepe", "paytm" to "paytm",
        "telegram" to "telegram", "snapchat" to "snapchat", "snap" to "snapchat",
        "twitter" to "x", "insta gram" to "instagram", "what's app" to "whatsapp"
    )

    @Synchronized
    private fun installedApps(): List<AppEntry> {
        val now = System.currentTimeMillis()
        if (appCache.isNotEmpty() && now - appCacheAt < 60000) return appCache
        appCache = loadInstalledApps()
        appCacheAt = now
        return appCache
    }

    private fun loadInstalledApps(): List<AppEntry> {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(i, 0)
            .map {
                val label = it.loadLabel(packageManager).toString()
                AppEntry(label, norm(label), it.activityInfo.packageName)
            }
            .filter { it.pkg != packageName && it.norm.isNotEmpty() }
            .distinctBy { it.pkg }
    }

    private fun editDistance(a: String, b: String): Int {
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]; dp[0] = i
            for (j in 1..b.length) {
                val tmp = dp[j]
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return dp[b.length]
    }

    private fun findApp(spoken: String): AppEntry? {
        val apps = installedApps()
        val rawTarget = norm(spoken)
        if (rawTarget.isEmpty()) return null
        val targets = listOfNotNull(rawTarget, appAliases[spoken.trim()], appAliases[rawTarget]).distinct()

        for (t in targets) {
            apps.firstOrNull { it.norm == t }?.let { return it }
        }
        for (t in targets) {
            apps.filter { it.norm.startsWith(t) }.minByOrNull { it.norm.length }?.let { return it }
        }
        for (t in targets) {
            if (t.length >= 3) apps.filter { it.norm.contains(t) }.minByOrNull { it.norm.length }?.let { return it }
        }
        // speech-recognition slips ("whats up" / "spotifi"): closest spelling wins
        val t = rawTarget
        val best = apps.map { it to editDistance(t, it.norm) }.minByOrNull { it.second }
        if (best != null) {
            val limit = maxOf(1, maxOf(t.length, best.first.norm.length) / 4)
            if (best.second <= limit) return best.first
        }
        return null
    }

    private fun openApp(spoken: String, quiet: Boolean = false): Boolean {
        val app = findApp(spoken)
        if (app == null) {
            if (!quiet) say("I could not find an app named $spoken")
            return false
        }
        val intent = packageManager.getLaunchIntentForPackage(app.pkg)
        if (intent != null && launch(intent)) {
            say("Opening ${app.label}")
            return true
        }
        if (!quiet) say("I could not open ${app.label}")
        return false
    }

    // ---------- Contacts: search + ask whom ----------

    private fun queryContacts(like: String): List<Contact> {
        val result = LinkedHashMap<String, Contact>()
        contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%$like%"),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
        )?.use { cur ->
            while (cur.moveToNext()) {
                val n = cur.getString(0) ?: continue
                val num = cur.getString(1) ?: continue
                val key = num.filter { it.isDigit() }.takeLast(10)
                if (key.isNotEmpty() && !result.containsKey(key)) result[key] = Contact(n, num)
            }
        }
        return result.values.toList()
    }

    private fun findContacts(name: String): List<Contact> {
        var r = queryContacts(name)
        if (r.isEmpty()) {
            // "vinod kumar" not saved that way? try the individual words
            for (w in name.split(" ").filter { it.length >= 3 }.sortedByDescending { it.length }) {
                r = queryContacts(w)
                if (r.isNotEmpty()) break
            }
        }
        return r
    }

    private fun pickContact(name: String, action: (Contact) -> Unit) {
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            say("Please allow contacts permission in the Riya app"); return
        }
        val list = findContacts(name)
        when {
            list.isEmpty() -> say("I could not find $name in your contacts")
            list.size == 1 -> action(list[0])
            else -> {
                val shown = list.take(6)
                pendingContacts = shown
                pendingAction = action
                pendingMisses = 0
                armPending()
                val options = shown.mapIndexed { i, ct -> "${i + 1}, ${labelFor(ct, shown)}" }.joinToString(". ")
                say("I found ${list.size} contacts. $options. Say the number.")
            }
        }
    }

    private fun labelFor(c: Contact, all: List<Contact>): String =
        if (all.count { it.name == c.name } > 1)
            "${c.name}, number ending ${c.number.filter { it.isDigit() }.takeLast(2)}"
        else c.name

    // Clear number words first; sound-alikes only if nothing clear was heard
    private val strictNumbers = mapOf(
        "one" to 1, "first" to 1, "two" to 2, "second" to 2, "three" to 3, "third" to 3,
        "four" to 4, "fourth" to 4, "five" to 5, "fifth" to 5, "six" to 6, "sixth" to 6,
        "seven" to 7, "eight" to 8, "nine" to 9
    )
    private val soundAlikes = mapOf(
        "won" to 1, "wan" to 1, "to" to 2, "too" to 2, "tu" to 2, "tree" to 3, "free" to 3, "tri" to 3, "thri" to 3,
        "for" to 4, "fore" to 4, "far" to 4, "fife" to 5, "hive" to 5, "sex" to 6, "ate" to 8
    )

    private fun parseChoice(text: String, size: Int, strictOnly: Boolean = false): Int? {
        val tokens = text.split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
        if ("last" in tokens) return size
        for (t in tokens) {
            val digits = Regex("^(\\d+)(st|nd|rd|th)?$").find(t)?.groupValues?.get(1)?.toIntOrNull()
            val n = digits ?: strictNumbers[t]
            if (n != null && n in 1..size) return n
        }
        if (strictOnly) return null
        for (t in tokens) {
            val n = soundAlikes[t]
            if (n != null && n in 1..size) return n
        }
        return null
    }

    private val choiceStopWords = setOf("call", "the", "please", "number", "want", "i", "to", "for", "a", "and", "of", "that", "this", "one", "ok", "okay")

    private fun handleChoice(heard: List<String>, list: List<Contact>) {
        if (heard.any { Regex("\\b(cancel|nobody|none|never mind|stop)\\b").containsMatchIn(it) }) {
            clearPending.run(); handler.removeCallbacks(clearPending)
            say("Okay, cancelled")
            return
        }
        var chosen: Contact? = null
        for (text in heard) {
            val idx = parseChoice(text, list.size)
            if (idx != null) { chosen = list[idx - 1]; break }
        }
        if (chosen == null) {
            for (text in heard) {
                val words = text.split(Regex("[^a-z0-9]+")).filter { it.length >= 2 && it !in choiceStopWords }
                if (words.isEmpty()) continue
                val matches = list.filter { c -> words.all { c.name.lowercase().contains(it) } }
                if (matches.size == 1) { chosen = matches[0]; break }
            }
        }
        if (chosen != null) {
            val action = pendingAction
            clearPending.run(); handler.removeCallbacks(clearPending)
            action?.invoke(chosen)
        } else {
            armPending()
            say("Sorry, say the number, like one, two or three")
        }
    }

    // ---------- Call ----------

    private fun dial(c: Contact) {
        say("Calling ${c.name}")
        val uri = Uri.fromParts("tel", c.number, null)
        if (checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
            try {
                // TelecomManager places the call even when the phone is locked
                getSystemService(TelecomManager::class.java).placeCall(uri, Bundle())
                return
            } catch (e: Exception) { /* fall through */ }
            if (launch(Intent(Intent.ACTION_CALL, uri))) return
        }
        launch(Intent(Intent.ACTION_DIAL, uri))
    }

    // ---------- WhatsApp ----------

    private fun whatsapp(c: String) {
        val cleaned = c.replace(Regex("^(send )?(a )?(whatsapp )?(message )?(on whatsapp )?(to )?"), "").trim()
        val m = Regex("^(.+?) (?:saying|that|message|as) (.+)$").find(cleaned)
        if (m == null) {
            say("Say it like: Riya send WhatsApp to Vinod saying hello")
            return
        }
        val name = m.groupValues[1].trim()
        val text = m.groupValues[2].trim()
        pickContact(name) { contact ->
            var digits = contact.number.filter { it.isDigit() }
            if (digits.length == 10) digits = defaultCountryCode + digits
            say("Sending WhatsApp to ${contact.name}")
            RiyaAccessibility.arm()
            val url = "https://wa.me/$digits?text=" + Uri.encode(text)
            launch(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }

    // ---------- Flashlight ----------

    private fun flashlight(c: String) {
        val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
        if (id == null) { say("No flashlight found"); return }
        torchOn = if ("off" in c) false else if ("on" in c) true else !torchOn
        try {
            cm.setTorchMode(id, torchOn)
            say(if (torchOn) "Flashlight on" else "Flashlight off")
        } catch (e: Exception) { say("Could not control the flashlight") }
    }

    // ---------- Volume ----------

    private fun volume(c: String) {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val stream = AudioManager.STREAM_MUSIC
        val percent = Regex("(\\d{1,3})").find(c)?.groupValues?.get(1)?.toIntOrNull()
        when {
            "unmute" in c -> am.setStreamVolume(stream, max / 2, AudioManager.FLAG_SHOW_UI)
            "mute" in c -> am.setStreamVolume(stream, 0, AudioManager.FLAG_SHOW_UI)
            percent != null -> am.setStreamVolume(stream, (percent.coerceIn(0, 100) * max / 100), AudioManager.FLAG_SHOW_UI)
            "max" in c || "full" in c -> am.setStreamVolume(stream, max, AudioManager.FLAG_SHOW_UI)
            "up" in c || "increase" in c || "raise" in c || "louder" in c || "high" in c -> {
                repeat(2) { am.adjustStreamVolume(stream, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI) }
            }
            "down" in c || "decrease" in c || "lower" in c || "low" in c || "reduce" in c -> {
                repeat(2) { am.adjustStreamVolume(stream, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI) }
            }
            else -> { say("Say volume up, down, mute, or a number"); return }
        }
        say("Volume " + (am.getStreamVolume(stream) * 100 / max) + " percent")
    }

    // ---------- Other actions ----------

    private fun launch(intent: Intent): Boolean = try {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        true
    } catch (e: Exception) {
        false
    }

    private fun lockScreen() {
        val dpm = getSystemService(DevicePolicyManager::class.java)
        if (dpm.isAdminActive(ComponentName(this, AdminReceiver::class.java))) {
            dpm.lockNow()
        } else say("Enable Device admin in the Riya app first")
    }

    private fun openCamera() {
        if (!launch(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE))) {
            if (!launch(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))) say("Camera not found")
        }
    }

    private fun youtube(query: String?) {
        val url = if (query.isNullOrBlank()) "https://www.youtube.com"
        else "https://www.youtube.com/results?search_query=" + Uri.encode(query)
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage("com.google.android.youtube")
        if (!launch(i)) launch(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    private fun openSpotify() {
        val i = packageManager.getLaunchIntentForPackage("com.spotify.music")
        if (i == null || !launch(i)) say("Spotify is not installed")
    }

    private fun playSpotify(song: String) {
        val i = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
            setPackage("com.spotify.music")
            putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            putExtra(SearchManager.QUERY, song)
        }
        if (!launch(i)) {
            if (!launch(Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:" + Uri.encode(song))))) {
                say("Spotify is not installed")
            }
        }
    }

    override fun onDestroy() {
        running = false
        state = "stopped"
        handler.removeCallbacksAndMessages(null)
        listener?.invoke()
        io.shutdownNow()
        try { overlayView?.let { getSystemService(WindowManager::class.java).removeView(it) } } catch (e: Exception) {}
        ears?.stop()
        recognizer?.destroy()
        tts?.shutdown()
        wakeLock?.let { if (it.isHeld) it.release() }
        unmuteBeeps()
        Thread { localLlm?.close() }.start()
        super.onDestroy()
    }
}
