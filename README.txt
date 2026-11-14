BUILD THE APK
Upload this folder to GitHub, open the Actions tab, wait for "Build APK", download the Riya-APK artifact.
(Or open in Android Studio > Build > Build APK(s).)

FIRST RUN - tap the buttons in the app in order
1 Allow microphone, contacts, calls & notifications
2 Allow lock screen (Device admin)
3 Allow display over other apps
4 Allow background running (battery)  -> choose Allow
5 Allow Do Not Disturb access for Riya (this lets it mute the listening beep)
6 Enable Accessibility -> Riya -> On (only used to press Send in WhatsApp)
   (Android 13+: if it is greyed out, Settings > Apps > Riya > 3-dot menu > Allow restricted settings)
7 (Offline chat) In the app, 'Offline voice chat' card: tap DOWNLOAD (about 1.6 GB, Wi-Fi) once, or CHOOSE FILE if you already have the model file
8 Type the name (e.g. Janu) and tap START

COMMANDS (start with the name you set in the app, default Riya)
open <ANY app name>  (e.g. open Instagram, open Settings, open PhonePe)
lock screen | open camera
play <song> | play <song> on YouTube
call <name>   (if several match, Riya reads them numbered - just say the number, e.g. "three", no name needed)
send WhatsApp to <name> saying <message>
flashlight on / off
volume up / down / max / mute / 50
what's the time | what's the date
stop


NEW IN THIS VERSION
- Colourful screen with a live orb: Listening / Thinking / Speaking, plus what it heard and replied.
- Setup list shows a green tick for every step already done.
- Faster: one reused speech recogniser, instant restart, picks a contact the moment you say the number.
- Talks with you: after Riya speaks you can answer WITHOUT the name for 8 seconds.
- Small talk works offline (hello, how are you, jokes, battery, thanks, bye).
- Optional AI brain: paste an Anthropic API key in the app and Riya answers any question out loud.

OFFLINE VOICE CHAT
- Hearing: Vosk speech recognition, bundled in the APK (the GitHub build downloads the two speech models).
- Answers: Qwen2.5-1.5B running on the phone (MediaPipe). Say any question; no name needed (wake-word switch is OFF by default).
- Phone commands (open app, call, WhatsApp, flashlight...) still work the same way.
