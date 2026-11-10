# Hey Google TV

تطبيق صغير لشاشة أندرويد تي في: بيسمع من الميكروفون الخارجي بمكتبة Porcupine على كلمة "Hey Google"،
ولما يسمعها بيفتح مساعد جوجل (بدل ما تدوس زرار الريموت).

## المطلوب قبل ما تبدأ

1. **AccessKey مجاني** من Picovoice Console (https://console.picovoice.ai). سجّل حساب وانسخ المفتاح.
2. **Android Studio** على الكمبيوتر (بيحمّل Gradle والـ SDK لوحده).
3. شاشة السكاى ورث متفعّل فيها **ADB over network** (Developer options).

## البناء

1. افتح مجلد المشروع `HeyGoogleTV` في Android Studio واستنى الـ Sync.
2. لو طلع خطأ إن نسخة `porcupine-android:3.0.2` مش موجودة، غيّر الرقم في `app/build.gradle.kts` لآخر نسخة من صفحة Porcupine Android.
3. Build ← Build APK(s). الملف بيطلع في `app/build/outputs/apk/debug/app-debug.apk`.

## التسطيب على الشاشة

```
adb connect IP_الشاشة:5555
adb install -r app-debug.apk
adb shell pm grant com.example.heygoogletv android.permission.RECORD_AUDIO
adb shell am start -n com.example.heygoogletv/.MainActivity --es access_key "ضع_المفتاح_هنا"
```

بعد كده التطبيق بيشتغل في الخلفية (هتشوف إشعار صغير)، وبيشتغل تلقائي كل ما الشاشة تفتح.

## عشان ميتقفلش في الخلفية

```
adb shell dumpsys deviceidle whitelist +com.example.heygoogletv
```

## تجربة وتشخيص

قول "Hey Google" وراقب اللوج:
```
adb logcat -s HeyGoogleTV
```
- `wake word detected` يعني الكلمة اتسمعت.
- `launched via ...` يعني الأسيستانت اتفتح بأنهي طريقة.
- `could not launch assistant` يعني الشاشة رفضت كل الطرق، ابعتلي اللوج.

## ملاحظات

- الكود لسه **متجرّبش على جهاز حقيقي**. أول تجربة ممكن تحتاج تعديل بسيط (خصوصاً طريقة فتح الأسيستانت).
- لو الأسيستانت بيفتح بس مبيسمعش، زوّد `RESUME_DELAY_MS` في `WakeService.kt`، أو لو بيرجع يسمع بدري أوي.
- لو بيفتح كتير بالغلط، قلّل `setSensitivity(0.7f)` لـ 0.5.
- الميكروفون بيفضل شغّال طول ما التطبيق شغّال، فالشاشة لازم تكون مفتوحة (مش standby).
