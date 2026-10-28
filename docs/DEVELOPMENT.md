# How Hey Claude was built

The goal was simple: say "Hey Claude" and talk to Claude, the way "Hey Google" works with Gemini. Getting there took a few detours. This page records them, because most of what's interesting about the project is in the problems.

## 1. Picking a wake word engine

The first plan was [Picovoice Porcupine](https://picovoice.ai/platform/porcupine/): it's accurate, battery friendly, and lets you create custom wake words in a web console. While researching, it turned out that Picovoice had told users its free tier would end on **June 30, 2026**, and the SDK refuses to start without a valid access key, even offline. It also needs network access to validate that key, which didn't fit an app meant to have no internet permission at all.

[openWakeWord](https://github.com/dscripka/openWakeWord) was the alternative: Apache-2.0 code, open models, and a training pipeline that works from synthetic speech. There is a small Kotlin port, but it's young and lightly tested, and the pipeline itself is simple. So the detection code was written from scratch, with ONNX Runtime as the only native dependency.

## 2. Re-implementing the pipeline in Kotlin

openWakeWord isn't one model but three:

1. a mel spectrogram model (audio → 32 mel bins every 10 ms),
2. Google's speech embedding model (76 mel frames → 96 numbers),
3. a small classifier per wake word (16 embeddings → a score between 0 and 1).

The streaming details matter. The mel model needs 480 samples of left context from the previous chunk, its output is rescaled (`x / 10 + 2`), and the mel buffer starts filled with ones rather than zeros. Get any of these slightly wrong and the scores drift.

`FeatureExtractor` and `WakeWordDetector` were kept free of Android and ONNX types, so they can be unit tested on the JVM with fake models. To check the real thing, a small harness ran the Kotlin pipeline against the official Python package on openWakeWord's test recordings, sending each model call to the same ONNX files from both sides. The scores matched exactly, chunk by chunk.

## 3. Living with Android's background rules

- On current Android versions, a foreground service that uses the microphone **can't be started from the background**. Listening has to be started by tapping a button, and again after every reboot.
- The service keeps running when the screen is off, but there's no reason to listen then: the phone can't open Claude on a locked screen. So the microphone is released on `SCREEN_OFF` and reopened on unlock.
- **Bug found on the phone:** after the screen had been off for a few minutes, listening sometimes didn't come back. `ACTION_USER_PRESENT` can arrive a moment before `KeyguardManager` reports the device as unlocked, and the single check right after the broadcast saw "still locked" and gave up. The fix polls briefly while the screen is on, and the UI asks the service to re-check whenever the app is opened.

## 4. Opening Claude in voice mode

This took the longest. Opening the Claude app is easy; opening it **already listening** is not.

What was tried, in order:

1. **`Intent.ACTION_ASSIST`** to the Claude package: opens the text chat with the keyboard up.
2. **`Intent.ACTION_VOICE_COMMAND`**: Claude doesn't handle it.
3. **Launcher shortcuts.** Long-pressing Claude's icon shows a "Voice chat" shortcut. Reading the app's manifest showed no static shortcuts, so the shortcut is created at runtime, and dynamic shortcuts are only visible to the launcher.
4. **The assistant overlay activity.** Claude exports `AssistantOverlayActivity` with filters for `ASSIST` and `VOICE_ASSIST`. Both open the text chat when started by another app.
5. **Deep links.** Claude handles `claude://` links. To find the voice one, the shortcut was tapped with logcat running: it fires `VIEW claude://new/...` with extras, but through `AppOnlyDeepLinkActivity`, which isn't exported. Android also redacts the shortcut's intent in `dumpsys shortcut`. Guessing paths (`claude://new/voice`, `?mode=voice`, ...) only gave "link couldn't be opened".
6. **The side key itself.** Holding the side key does open voice mode. Sending the same event from a computer with `adb shell input keyevent KEYCODE_ASSIST` did too, and that was the breakthrough.

A normal app can't send key events, though. Two options were left:

- An **accessibility service** that taps Claude's voice button. It works on any app, but it needs permission to read everything on screen, including the conversations themselves. That was a hard no.
- **[Shizuku](https://github.com/RikkaApps/Shizuku)**, which gives an app `adb shell` rights on demand, started through the phone's own wireless debugging.

Shizuku won. The privileged part of the app is a `UserService` with a single method that runs `input keyevent KEYCODE_ASSIST` and takes no arguments. When Shizuku isn't running, the app falls back to `ACTION_ASSIST` and Claude opens in text chat.

The same key also explains how other assistants could be supported later: the assistant key opens whatever app holds the `ASSISTANT` role, and that role can be switched with Shizuku.

## 5. Sharing the microphone with Claude

The first real conversation through the wake word worked, and the second didn't: Claude stopped hearing anything a few seconds in.

The cause was the cooldown. After a detection, the app released the microphone for 10 seconds and then reopened it, while Claude's voice mode was still listening. With two apps capturing at once, Claude ended up getting silence.

The fix uses `AudioManager.getActiveRecordingConfigurations()` and `AudioRecordingCallback`. Apps get an anonymized view of every active recording: no package name, but each one has a session id, so the app can tell its own recording apart from everyone else's. The rules are now:

- never reopen the mic while anybody else is recording;
- if somebody else starts recording while we listen, stop immediately;
- once the mic has been free for 4 seconds, resume (voice apps often release the mic briefly between turns).

## 6. Training "Hey Claude"

openWakeWord trains wake words from synthetic speech: thousands of clips from a Piper TTS voice, mixed with room reverb and background noise, against 2,000 hours of precomputed negative features.

The official Colab notebook no longer runs on current Colab (Python 3.12+, dead download links). An open [pull request](https://github.com/dscripka/openWakeWord/pull/359) modernizes it with an isolated Python 3.11 environment. Two more issues came up while using it:

- `torch` and `torchaudio` ended up in mismatched versions (`undefined symbol: torch_exception_get_what_without_backtrace`). Pinning both to 2.8.0 from the CUDA 12.6 index fixed it.
- The notebook's display name is `automatic_model_training_v2.ipynb` even though the file is `automatic_model_training.ipynb`. Harmless, but worth knowing before you trust a notebook from a link.

Before the 16 GB download, the pronunciation test showed that "Hey Claude" sounded right, but the Turkish word "yavrum" came out with a strong English accent, so that phrase was postponed until it can be trained with real recordings.

Training took about 35 minutes on a free T4. The model was then tested locally with two Piper voices it had never seen:

- **No false activations** on "Hey cloud", "Claudia", "Okay Google" or a sentence containing "Claude". "Hey Clara" spiked once, but the two-chunks-in-a-row rule rejected it.
- **Moderate recall** on synthetic speech: about half of the "Hey Claude" clips, and every "Hey clawd" clip.

On a real voice it works well with the threshold lowered to 20%. A retrain with more samples should allow a higher threshold.

## 7. Build issues along the way

- **Gradle and JDK 25.** Android Studio ships JetBrains Runtime 25, which Gradle 8.14 can't run on. The project moved to Gradle 9.
- **Compose and AGP.** Compose BOM `2026.09.00` brings Compose 1.12, which requires AGP 9.1 and compileSdk 37. The first builds pinned the BOM to `2026.06.01` to stay on AGP 8.13; the project has since moved to AGP 9.4, Kotlin 2.4, Gradle 9.8 and compileSdk 37. The flags in `gradle.properties` were added by Android Studio's AGP 9 upgrade assistant and keep the AGP 8 behaviour for things like the Kotlin plugin and the variant DSL.

## 8. Things that are deliberately not here

- **No internet permission**, stripped even if a library declares it.
- **No accessibility service** and no screen reading.
- **No audio storage.** Audio is processed in 80 ms chunks in memory and dropped.
- **No analytics or crash reporting.**
