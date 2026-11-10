package com.example.heygoogletv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream
import kotlin.math.abs
import kotlin.math.max

/**
 * خدمة السمع لكلمة "Ok Google" بـ Vosk.
 *
 * الفرق عن النسخة القديمة:
 *  1) بدل SpeechService (اللي thread بتاعها كان بيموت بصمت لو القراءة من الميكروفون فشلت)
 *     بنقرأ من AudioRecord بنفسنا، ولو القراءة وقفت أو الصوت بقى أصفار بنعيد فتح الميكروفون.
 *  2) watchdog بيتأكد كل ثواني إن حلقة السمع عايشة وبتتحرك، ولو لأ بيشغّلها من جديد.
 *  3) WakeLock عشان المعالج ميناموش.
 *  4) أما الأسيستنت يفتح، بنقفل Vosk ونحرر الميكروفون خالص، وأما الأسيستنت يقفل الميكروفون
 *     بنرجّع Vosk تاني.
 */
class WakeService : Service() {

    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var model: Model? = null

    // كل الحالات دي بتتلمس من الـ main thread بس
    private var running = false
    private var loop: ListenLoop? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var assistantMode = false
    private var launchedAt = 0L
    private var sawRecording = false
    private var quietSince = 0L
    private var lastSourcesLog = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "heygoogletv:wake").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        showNotification("بحمّل نموذج الصوت...")
        if (!running) {
            running = true
            Thread { loadModelAndStart() }.start()
            handler.postDelayed(watchdog, WATCHDOG_MS)
        }
        return START_STICKY
    }

    // ───────────────────────── إشعار ─────────────────────────

    private fun showNotification(text: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Hey Google", NotificationManager.IMPORTANCE_LOW)
        )
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle("Hey Google TV")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
        startForeground(1, n)
    }

    // ───────────────────────── تحميل النموذج ─────────────────────────

    private fun loadModelAndStart() {
        try {
            val dir = File(filesDir, MODEL_NAME)
            if (!File(dir, "am/final.mdl").exists()) {
                download()
            }
            model = Model(dir.absolutePath)
            handler.post { startListening() }
        } catch (e: Exception) {
            Log.e(TAG, "model load failed: ${e.message}", e)
            File(filesDir, MODEL_NAME).deleteRecursively()
            handler.postDelayed({
                if (running) Thread { loadModelAndStart() }.start()
            }, 30_000)
        }
    }

    private fun download() {
        Log.i(TAG, "downloading model")
        val tmp = File(cacheDir, "model.zip")
        val conn = URL(MODEL_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }

        File(filesDir, MODEL_NAME).deleteRecursively()
        val base = filesDir.canonicalPath
        ZipInputStream(tmp.inputStream().buffered()).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                val out = File(filesDir, e.name)
                if (!out.canonicalPath.startsWith(base)) throw SecurityException("bad zip entry")
                if (e.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zis.copyTo(it) }
                }
                zis.closeEntry()
                e = zis.nextEntry
            }
        }
        tmp.delete()
        Log.i(TAG, "model ready")
    }

    // ───────────────────────── السمع ─────────────────────────

    private fun startListening() {
        if (!running || assistantMode) return
        val m = model ?: return
        loop?.abort()
        val l = ListenLoop(m)
        loop = l
        l.start()
        showNotification("بسمع كلمة التنبيه...")
        Log.i(TAG, "listening")
    }

    /** Watchdog: لو حلقة السمع ماتت أو اتعلقت، نشغّلها من جديد. */
    private val watchdog = object : Runnable {
        override fun run() {
            if (!running) return
            if (!assistantMode && model != null) {
                val l = loop
                val stalled = l != null &&
                    SystemClock.elapsedRealtime() - l.lastBeat > STALL_MS
                if (l == null || !l.isAlive || stalled) {
                    Log.w(TAG, "watchdog: listen loop dead/stalled -> restart")
                    startListening()
                }
            }
            handler.postDelayed(this, WATCHDOG_MS)
        }
    }

    private inner class ListenLoop(private val m: Model) : Thread("hey-google-listen") {
        @Volatile
        var lastBeat = SystemClock.elapsedRealtime()

        @Volatile
        private var stop = false

        @Volatile
        private var current: AudioRecord? = null

        fun abort() {
            stop = true
            try {
                current?.stop() // بيفك أي read معلّق
            } catch (_: Exception) {
            }
        }

        override fun run() {
            var rec: Recognizer? = null
            var ar: AudioRecord? = null
            var woke = false
            try {
                rec = Recognizer(m, SAMPLE_RATE.toFloat(), GRAMMAR)
                val buf = ShortArray(CHUNK)
                var openedAt = 0L
                var lastSound = 0L
                var lastData = 0L

                while (!stop) {
                    lastBeat = SystemClock.elapsedRealtime()

                    if (ar == null) {
                        ar = openRecord()
                        current = ar
                        if (ar == null) {
                            sleepQuiet(2000)
                            continue
                        }
                        rec.reset()
                        val t0 = SystemClock.elapsedRealtime()
                        openedAt = t0; lastSound = t0; lastData = t0
                        Log.i(TAG, "mic opened")
                    }

                    val n = ar.read(buf, 0, buf.size)
                    val t = SystemClock.elapsedRealtime()

                    if (n > 0) {
                        lastData = t
                        var peak = 0
                        for (i in 0 until n) {
                            val a = abs(buf[i].toInt())
                            if (a > peak) peak = a
                        }
                        if (peak > 0) lastSound = t

                        val fin = rec.acceptWaveForm(buf, n)
                        val json = if (fin) rec.result else rec.partialResult
                        if (hasWake(json)) {
                            woke = true
                            break
                        }
                    } else {
                        sleepQuiet(50) // خطأ أو صفر: متدورش بسرعة
                    }

                    // الميكروفون ساكت/مقفول من النظام؟ افتحه من جديد
                    val reason = when {
                        t - lastData > NO_DATA_MS -> "no data"
                        t - lastSound > ALL_ZERO_MS -> "audio is all zeros (mic silenced?)"
                        t - openedAt > RECYCLE_MS -> "periodic recycle"
                        else -> null
                    }
                    if (reason != null) {
                        Log.w(TAG, "reopening mic: $reason")
                        closeRecord(ar)
                        ar = null
                        current = null
                        sleepQuiet(300)
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "listen loop crashed: ${t.message}", t)
            } finally {
                // الميكروفون لازم يتحرر قبل ما نفتح الأسيستنت
                closeRecord(ar)
                current = null
                try {
                    rec?.close()
                } catch (_: Throwable) {
                }
            }
            if (woke) {
                val me = this
                handler.post { if (loop === me) onWake() }
            }
        }
    }

    private fun openRecord(): AudioRecord? {
        val min = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (min <= 0) return null
        val size = max(min * 2, SAMPLE_RATE) // بالبايت
        for (src in intArrayOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC)) {
            var a: AudioRecord? = null
            try {
                a = AudioRecord(
                    src, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, size
                )
                if (a.state == AudioRecord.STATE_INITIALIZED) {
                    a.startRecording()
                    if (a.recordingState == AudioRecord.RECORDSTATE_RECORDING) return a
                }
            } catch (e: Exception) {
                Log.w(TAG, "openRecord src=$src failed: ${e.message}")
            }
            closeRecord(a)
        }
        return null
    }

    private fun closeRecord(a: AudioRecord?) {
        if (a == null) return
        try {
            a.stop()
        } catch (_: Exception) {
        }
        try {
            a.release()
        } catch (_: Exception) {
        }
    }

    private fun sleepQuiet(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
        }
    }

    private fun hasWake(json: String?): Boolean {
        if (json.isNullOrBlank()) return false
        return try {
            val o = JSONObject(json)
            (o.optString("partial", "") + " " + o.optString("text", "")).contains("google")
        } catch (_: Exception) {
            false
        }
    }

    // ───────────────────────── الأسيستنت ─────────────────────────

    /** اتسمعت الكلمة. الميكروفون اتحرر خلاص (الحلقة قفلته في finally). */
    private fun onWake() {
        if (assistantMode) return
        assistantMode = true
        loop = null
        Log.i(TAG, "wake phrase detected -> vosk stopped, launching assistant")
        showNotification("الأسيستنت شغّال... السمع واقف")

        launchedAt = SystemClock.elapsedRealtime()
        sawRecording = false
        quietSince = 0L
        lastSourcesLog = ""
        launchAssistant()
        handler.postDelayed(assistantPoll, POLL_MS)
    }

    /**
     * بنراقب هل في تطبيق تاني (الأسيستنت) بيسجّل من الميكروفون:
     *  - أما نشوفه بدأ يسجّل ثم يسكت [ASSISTANT_END_DEBOUNCE_MS] ← الأسيستنت خلص، نرجّع Vosk.
     *  - لو ما بدأش يسجّل خالص في [ASSISTANT_START_WAIT_MS] ← فشل يفتح، نرجّع Vosk.
     *  - حد أقصى [ASSISTANT_MAX_MS] أمان.
     */
    private val assistantPoll = object : Runnable {
        override fun run() {
            if (!running || !assistantMode) return
            val now = SystemClock.elapsedRealtime()
            val elapsed = now - launchedAt

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                if (elapsed >= LEGACY_RESUME_MS) resumeListening("legacy timer")
                else handler.postDelayed(this, POLL_MS)
                return
            }

            if (otherAppRecording()) {
                sawRecording = true
                quietSince = 0L
            } else if (quietSince == 0L) {
                quietSince = now
            }

            val quietFor = if (quietSince == 0L) 0L else now - quietSince
            when {
                sawRecording && quietFor >= ASSISTANT_END_DEBOUNCE_MS ->
                    resumeListening("assistant released the mic")
                !sawRecording && elapsed >= ASSISTANT_START_WAIT_MS ->
                    resumeListening("assistant never opened the mic")
                elapsed >= ASSISTANT_MAX_MS ->
                    resumeListening("max assistant time")
                else -> handler.postDelayed(this, POLL_MS)
            }
        }
    }

    /** في تطبيق تاني بيسجّل دلوقتي؟ (احنا مقفلين الميكروفون وقت الاستدعاء ده.) */
    private fun otherAppRecording(): Boolean {
        return try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val sources = am.activeRecordingConfigurations.map { it.clientAudioSource }
            val log = sources.joinToString(",")
            if (log != lastSourcesLog) {
                lastSourcesLog = log
                Log.i(TAG, "active recording sources: [$log]")
            }
            sources.any { it !in IGNORED_SOURCES }
        } catch (e: Exception) {
            Log.w(TAG, "activeRecordingConfigurations failed: ${e.message}")
            false
        }
    }

    private fun resumeListening(why: String) {
        Log.i(TAG, "resume vosk: $why")
        handler.removeCallbacks(assistantPoll)
        handler.postDelayed({
            assistantMode = false
            startListening()
        }, RESUME_GRACE_MS)
    }

    private fun launchAssistant() {
        val candidates = listOf(
            Intent(Intent.ACTION_ASSIST),
            Intent(Intent.ACTION_ASSIST).setPackage("com.google.android.katniss"),
            Intent(Intent.ACTION_VOICE_COMMAND),
        )
        for (i in candidates) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                startActivity(i)
                Log.i(TAG, "launched via ${i.action} pkg=${i.`package`}")
                return
            } catch (e: ActivityNotFoundException) {
                Log.w(TAG, "no handler for ${i.action}")
            } catch (e: SecurityException) {
                Log.w(TAG, "blocked ${i.action}: ${e.message}")
            }
        }
        Log.e(TAG, "could not launch assistant")
    }

    // ───────────────────────── إنهاء ─────────────────────────

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        loop?.abort()
        loop = null
        try {
            model?.close()
        } catch (_: Exception) {
        }
        model = null
        try {
            wakeLock?.takeIf { it.isHeld }?.release()
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "HeyGoogleTV"
        private const val CHANNEL = "wake"

        private const val MODEL_NAME = "vosk-model-small-en-us-0.15"
        private const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
        private const val GRAMMAR = "[\"hey google\", \"ok google\", \"okay google\", \"[unk]\"]"

        private const val SAMPLE_RATE = 16000
        private const val CHUNK = 3200 // 0.2 ثانية

        // watchdog وإعادة فتح الميكروفون
        private const val WATCHDOG_MS = 10_000L
        private const val STALL_MS = 15_000L
        private const val NO_DATA_MS = 3_000L
        private const val ALL_ZERO_MS = 20_000L
        private const val RECYCLE_MS = 15 * 60_000L

        // مراقبة الأسيستنت
        private const val POLL_MS = 500L
        private const val ASSISTANT_START_WAIT_MS = 8_000L
        private const val ASSISTANT_END_DEBOUNCE_MS = 3_000L
        private const val ASSISTANT_MAX_MS = 60_000L
        private const val RESUME_GRACE_MS = 1_000L
        private const val LEGACY_RESUME_MS = 15_000L // أندرويد أقدم من 7

        // مصادر تسجيل نظامية (hotword / radio / echo) مش بتعني إن الأسيستنت سامع
        private val IGNORED_SOURCES = setOf(1999, 1998, 1997)
    }
}
