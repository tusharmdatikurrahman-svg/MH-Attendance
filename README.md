# MH Attendance — Android App (WebView shell)

এই প্রজেক্টটি আপনার Laravel সাইট **https://mhpattendancesystem.top** কে
Android অ্যাপ হিসেবে চালায়। ডেটা, লগইন, পারমিশন — সব কিছু আগের মতোই
সার্ভার থেকে আসে; অ্যাপ আলাদা কোনো ডেটাবেজ রাখে না।

## যা যা করা হয়েছে
- পুরো ওয়েবসাইট একটি WebView-তে লোড হয় (আপনার responsive CSS অনুযায়ী মোবাইল লেআউট এমনিতেই কাজ করে)
- নিচে Bottom Navigation (Dashboard / Attendance / Staff / Reports / Menu) — ইউজারের পারমিশন অনুযায়ী স্বয়ংক্রিয়ভাবে দেখায়/লুকায়
- PDF ডাউনলোড, Payslip, Salary/Monthly Report, Database Backup — সব ডাউনলোড হয়ে ফোনের ডিফল্ট ভিউয়ারে খোলে
- `window.print()` (Payslip পেজে ব্যবহৃত) Android-এর নিজস্ব Print/Save-as-PDF ডায়ালগে কাজ করে
- সেশন টাইম-আউট হলে (419 error) স্বয়ংক্রিয়ভাবে Login পেজে পাঠায়
- Pull-to-refresh, লোডিং প্রোগ্রেস বার, Offline হলে "No connection" স্ক্রিন ও Retry বাটন
- আপনার লোগো দিয়ে তৈরি অ্যাপ আইকন ও স্প্ল্যাশ স্ক্রিন
- ব্যাক বাটন: সাইটের ভেতরে গেলে আগের পেজে যায়, Dashboard-এ থাকলে দুইবার চাপলে অ্যাপ বন্ধ হয়

## APK কীভাবে বানাবেন (৩টি উপায়)

### উপায় ১ — সবচেয়ে সহজ: GitHub Actions (কোনো ইনস্টল লাগবে না)
1. এই পুরো ফোল্ডারটি একটি নতুন GitHub রিপোতে আপলোড করুন
2. GitHub-এ রিপোর "Actions" ট্যাবে যান → "Build APK" workflow **Run workflow** চাপুন
3. ২-৩ মিনিট পর নিচে "Artifacts" থেকে `MH-Attendance-APK` ডাউনলোড করুন — এতে `app-debug.apk` থাকবে
4. এই APK ফোনে পাঠিয়ে ইনস্টল করুন (Settings → "Install unknown apps" অনুমতি দিতে হতে পারে)

### উপায় ২ — Android Studio (নিজে বিল্ড/রিলিজ সাইন করতে চাইলে)
1. [Android Studio](https://developer.android.com/studio) ইনস্টল করুন
2. `File → Open` করে এই ফোল্ডার (MHPAttendanceApp) সিলেক্ট করুন — Gradle sync হতে দিন
3. `Build → Generate Signed Bundle / APK` দিয়ে Play Store-এর জন্য সাইন করা APK/AAB বানাতে পারবেন
4. টেস্টের জন্য সরাসরি `Run ▶` চাপলেও ফোনে/এমুলেটরে চলবে

### উপায় ৩ — কমান্ড লাইনে (যদি লোকাল মেশিনে Android SDK + Gradle থাকে)
```
./gradlew assembleDebug
```
(প্রথমবার gradlew না থাকলে Android Studio দিয়ে একবার প্রজেক্ট ওপেন করলেই wrapper ফাইল তৈরি হয়ে যাবে)

## সাইটের ঠিকানা পরিবর্তন করতে চাইলে
`app/src/main/java/com/modernhospital/attendance/MainActivity.kt` ফাইলের একদম উপরে:
```kotlin
private const val BASE_URL = "https://mhpattendancesystem.top"
private const val HOST = "mhpattendancesystem.top"
```
এই দুই লাইন পরিবর্তন করলেই হবে।

## Play Store-এ দিতে চাইলে
- `applicationId` (এখন `com.modernhospital.attendance`) এবং app icon/নাম পরিবর্তন করতে পারেন `app/build.gradle.kts` ও `res/values/strings.xml`-এ
- Release build-এর জন্য একটি keystore বানিয়ে সাইন করতে হবে (Android Studio এই কাজে সাহায্য করবে)
