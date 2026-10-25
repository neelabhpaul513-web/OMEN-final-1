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

V2: chat box, web-search toggle (magnifier), voice on/off (speaker), clear chat. App is signed with a fixed key so later updates install over it. FIRST time: uninstall the old OMEN, then install this one.

V3 VOICE: tap the mic button (next to the chat box) to speak one command WITHOUT saying "Omen". The status line shows "HEARD: ..." so you can see what OMEN understood.
"Search for X", "look up X", "google X" always run a web search. SETUP has a switch to turn the wake word OFF (it then answers everything it hears).

V4: new logo; ALARMS/TIMERS now go into your phone's Clock app (needs SETUP step 1 "display over apps", or keep OMEN open on screen);
 smoother animation; round icon buttons; HISTORY button keeps every question you asked (CLEAR only clears the chat, not history).

V5: the mic button now SEARCHES THE WEB for anything that is not a phone command (open/alarm/call/message). Questions spoken to 'Omen' also search.

V6: new animated AWAKEN button (glow, morphs to red 'SEND ME BACK' with a green online dot); greeting no longer repeats in chat; softer orb glow.

V7: fixed the AWAKEN button body being see-through.
