OMEN DESKTOP (Windows, Mac, Linux) with ADMIN PANEL
===================================================
RUN IT NOW (needs Node.js from nodejs.org):
  1. Open this folder in VS Code > Terminal:  npm install
  2. npm start
  3. Click the gear (top right) = ADMIN PANEL > AI and models > paste your Groq key > Test key.

GET A WINDOWS INSTALLER (OMEN-Setup.exe):
  A) npm run dist      -> installer appears in the "dist" folder
  B) No setup on your PC: make a free PUBLIC GitHub repo, upload everything in this folder (include the hidden .github folder;
     if it is skipped, add the file .github/workflows/build-desktop.yml by hand), open Actions, wait for the green tick, then:
     https://github.com/YOUR-NAME/YOUR-REPO/releases/latest/download/OMEN-Setup.exe

WHERE IS THE ADMIN PANEL?  Three ways: the gear button (top right), "Admin panel" in the side menu, or the tray icon > Admin panel.
  Tabs: Overview, AI and models, Voice, Appearance, Behaviour, Usage and limits, Logs, Data and backup, Security (PIN), About.

USING OMEN
  - Press Ctrl+Shift+Space from ANY app to talk (speech is turned into text by Groq Whisper). Press it again to stop.
  - Type, drop photos/documents on the window, paste screenshots, or use + for photos, files, web search, spoken replies, image creation.
  - Photos of notes: Read text / Summarize / Key points / Quiz me. PDF, Word, PowerPoint and text files are supported (scanned PDFs: add as photos).
  - "open YouTube", "timer 10 minutes", "alarm at 6:30 am", "note buy milk", "show pictures of tigers", "create an image of a dragon",
    paste a link to have it read, ask anything for a web answer with references.
  - Closing the window keeps OMEN in the tray (needed for timers and the hotkey). Quit from the tray icon.

LIMITS: the always-listening "Hey Omen" is on the phone app only; on desktop you talk with the hotkey or the mic button.
Untested build (written without being able to run Electron here). If something breaks, open Admin panel > Logs and send the text.
