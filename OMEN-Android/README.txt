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

V8: new gothic logo + wordmark; PHOTO button (ask the AI about a picture, uses a Groq vision model); IMAGE SEARCH ('show pictures of X', Wikimedia Commons);
 tougher mic loop (error handling, watchdog, more wake-word spellings incl. 'aman', smart wake); 'call X and put it on speaker'; fuzzy contact matching.

V9: NOTES SCANNER. Attach up to 5 photos (pages of handwritten/printed notes) with the photo button, then tap Read text / Summarize / Key points / Quiz me, or ask your own question.
 Uses a Groq vision model; if your key has none, it falls back to offline OCR (ML Kit) on the phone and analyses that text.

V9.1: versionCode now = GitHub run number so every build counts as a newer update; APK trimmed to arm64 + armv7 (smaller).

V10: references (source links) under web answers + Google link; read any website link you paste; image GENERATION ('create an image of ...', free Pollinations service) with Save to gallery;
 instant time/date/battery answers; permission checklist (SETUP > Check all permissions); title no longer overlaps the orb.

V10.1: fixed build error (missing DateFormat import) and text escapes.

V11: HEY OMEN from anywhere (floating glass panel with Omen's face over any app; says "What can I do for you, sir?"); needs SETUP > allow display over apps + AWAKEN OMEN.
 New voice/phone controls: flashlight, volume, media play/pause/next, notes, navigate, play on YouTube/Spotify, wifi/bluetooth settings, time/date/battery.
 Chat is now transparent glass (slider in SETUP) so Omen's face shows through. Background = black wind waves video, 1440x2560 (2K), looping; toggle in SETUP.
 After a phone restart a notification appears: tap it to awaken OMEN again.

V12: NEW LAYOUT (Claude-style). Menu (top-left) opens a side drawer: Chats, Notes, Share a file, Settings, Recents history, avatar and New chat.
 Awaken button is the round power button at the top-right. Chat box is a single card: + (photos, files, web search, spoken replies, create image), mode chip, mic (dictate), waveform/send.
 All settings are on their own Settings screen. Share any PDF, Word, PowerPoint, text or photo to OMEN from another app (Share > OMEN) or use + > Files.
 HEY OMEN: now uses an offline detector (Vosk). First time you awaken OMEN it offers a ~40 MB model download. Then say "Hey Omen" in any app.

V12.1: added 'Hey Women' / 'Hey Woman' as wake phrases (the phone often mishears Omen). Mic errors now retry quietly with back-off, switch language/offline mode automatically, and the offline detector retries instead of giving up.

V12.2: photo analysis finds a model that accepts images (tries several, remembers the one that works), retries on Groq rate limits, sends big multi-page notes one page at a time, and offline OCR now reads each photo twice (normal + contrast boosted). Errors now show both the vision error and the offline-reading error.

V13:
 HISTORY: menu > History opens a full screen with EVERY question, search, photo, document and command (time, type, your question and OMEN's answer),
  search box, type filters, tap to read/copy/delete/ask again. Recents in the menu shows the latest 40. Clearing the chat never deletes history.
 CAMERA: + > Camera (or say "camera search"). Take a photo, tap "Search this photo": OMEN says what it is, searches the web and lists references
  plus a Google Images link. The same photo can also be read as notes (Read text / Summarize / Key points / Quiz me).
 PHARMA RESEARCH: tap the mode chip (Chat > Web search > Pharma research), or say "full research on metformin", "pharma research ...", or just ask a drug/disease question.
  OMEN plans the search, pulls papers from PubMed, trials from ClinicalTrials.gov and the FDA drug label, then writes a structured brief
  (summary, mechanism, clinical evidence, dosing per label, adverse effects, interactions, regulatory status, gaps) with numbered references you can tap.

V14 PHARMA RESEARCH (all sources report whether they worked):
 Literature and regulatory: PubMed (real XML parsing, evidence-first search, retry queries, study type tags), ClinicalTrials.gov (broad search, phase/status/enrolment/sponsor/primary outcomes + posted results),
  openFDA label (explicit OR search, alternative drug names), DailyMed (second official label source + cross-check).
 Molecular tools (say these in any mode): "structure of aspirin" (PubChem data + 2D picture), "ADMET of metformin" (rule-based estimate, clearly labelled),
  "targets of imatinib" / "drug-target interaction imatinib" (ChEMBL mechanisms + measured potency), "dock metformin with AMPK" (docking PREPARATION: ligand SDF, best PDB structures, known potency, step-by-step; no fake scores),
  "screen aspirin, ibuprofen, naproxen" (drug-likeness ranking), "compounds active against EGFR" (potent compounds from ChEMBL).
 Source validation: every statement must cite a numbered source; uncited statements are tagged [unverified], invalid citation numbers become [?], and a citation check line is added.
 References show PMID / NCT / label dates, tappable links and the retrieval date. If every database fails you are told, and the answer is marked not source-validated.

V15 DASHBOARDS: menu > Dashboards opens a hub with 7 new screens + History + Settings.
 1 Pharma Research: live status of PubMed, ClinicalTrials.gov, openFDA, DailyMed, PubChem, ChEMBL; evidence chart, papers, trials, label, molecule, targets; button for the full cited AI brief.
 2 AI Drug Discovery: target > most potent ChEMBL compounds ranked by potency and drug-likeness > RCSB 3D structures > docking guide.
 3 Molecular Structure Viewer: 2D picture, rotating 3D model (needs internet for the 3D library), properties, rule-based ADMET.
 4 Drug Comparison: 2-4 drugs side by side with an AI summary written only from the table.
 5 Clinical Trial Analytics: phase, status, year, sponsor charts, enrolment, posted results, notable studies.
 6 Voice Control: status, live log, wake model download, switches, pitch/speed, mic test, command list.
 7 Image Gallery: generated images (with prompts), molecule pictures, photos; create, save, share, delete.

V16 (built from the uploaded OMEN-final-1 project): cooler UI.
 - Animated aurora background with floating stars behind Dashboards, History and Settings; glass cards; glowing title rule.
 - Dashboards hub redesigned: pulsing Omen face + colourful gradient tiles with press animation.
 - Chat: new bubbles slide in; the chat box glows blue when listening, violet when thinking, white when speaking; "Dashboards" added to the + menu.
 - The extra ResearchLabActivity in the upload was a simplified duplicate of Pharma Research, was not linked anywhere, and is replaced by the full dashboards, so it is not included.
