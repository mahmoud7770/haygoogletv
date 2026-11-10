package com.example.heygoogletv

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast

/**
 * شاشة بسيطة: بتاخد الـ AccessKey من Picovoice (من ADB أو محفوظ قبل كده)،
 * وتطلب صلاحية الميكروفون لو ناقصة، وتشغّل الخدمة وتقفل.
 *
 * التشغيل من ADB:
 *   adb shell am start -n com.example.heygoogletv/.MainActivity --es access_key "YOUR_KEY"
 */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("cfg", MODE_PRIVATE)
        intent?.getStringExtra("access_key")?.takeIf { it.isNotBlank() }?.let {
            prefs.edit().putString("access_key", it.trim()).apply()
        }

        val tv = TextView(this).apply {
            textSize = 22f
            setPadding(80, 80, 80, 80)
        }
        setContentView(tv)

        if (prefs.getString("access_key", null).isNullOrBlank()) {
            tv.text = "مفيش AccessKey. شغّل الأمر ده من الكمبيوتر:\n\n" +
                "adb shell am start -n com.example.heygoogletv/.MainActivity --es access_key \"YOUR_KEY\""
            return
        }

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            tv.text = "محتاج صلاحية الميكروفون..."
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }

        startWake(tv)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        val tv = TextView(this).apply { textSize = 22f; setPadding(80, 80, 80, 80) }
        setContentView(tv)
        if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startWake(tv)
        } else {
            tv.text = "الصلاحية اترفضت. اديها من ADB:\n\n" +
                "adb shell pm grant com.example.heygoogletv android.permission.RECORD_AUDIO"
        }
    }

    private fun startWake(tv: TextView) {
        startForegroundService(Intent(this, WakeService::class.java))
        Toast.makeText(this, "Hey Google شغّال في الخلفية", Toast.LENGTH_LONG).show()
        tv.text = "تمام، الخدمة اشتغلت. قول \"Hey Google\"."
        tv.postDelayed({ finish() }, 2000)
    }
}
