OMEN: NATIVE ANDROID ASSISTANT (Kotlin, Groq brain)
====================================================
BUILD THE APK (no coding, free):
 1. github.com > New repository > upload everything inside this folder (include the hidden .github folder).
 2. Actions tab > "Build NEEL APK"/"Build OMEN APK" > Run workflow > wait 3-5 min.
 3. Open the finished run > download "OMEN-apk" > unzip > install app-debug.apk on your phone ("Install unknown apps").
 (Or use Android Studio: open folder > Build > Build APK.)

FIRST RUN: open OMEN > SETUP > paste your Groq key > "Save and test key" (shows which Groq model it connected to)
 > 1. allow Display over apps  > 2. (optional) enable "OMEN auto-send" in Accessibility > 3. battery: Unrestricted
 > AWAKEN OMEN > allow permissions.

COMMANDS (say "Omen" first; for 15 seconds after a reply you can skip it):
 "Omen, open WhatsApp"   (opens the real app, not a website)      "Omen, open Instagram / Camera / Settings"
 "Omen, set an alarm for 6:30 am"   "Omen, timer for 10 minutes"
 "Omen, call Rahul"   "Omen, WhatsApp Rahul saying I will be late"   "Omen, text Mom saying call me"
 "Omen, who won the last IPL?" (live web research)   "Omen, remember I wake up at 6"   "Omen, goodbye"
 Incoming call: OMEN announces the caller; say "answer" or "decline".

NOTES: Untested build (no Android SDK where it was written). The voice is a deep, slow, low-pitched text-to-speech voice in
Omen's style, not the actual Valorant voice recordings. Wake word variants are in OmenService.kt (val wake).

VS CODE / npm USERS
 package.json only wraps the Gradle build; "npm install" has nothing to download. Android libraries are fetched by Gradle.
 Needs on your computer: JDK 17, Android SDK (install Android Studio once), and Gradle 8.7 (gradle.org) in PATH.
 Create local.properties containing:  sdk.dir=C:\\Users\\YOU\\AppData\\Local\\Android\\Sdk   (Mac/Linux: path to your Android SDK)
 Then:  npm run setup   (once)   |   npm run build   -> app/build/outputs/apk/debug/app-debug.apk   |   npm run install:phone (USB debugging on)
 No computer setup wanted? Use the GitHub Actions build instead (see above).
