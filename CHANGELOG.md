# Changelog

## 0.3.1

- Doesn't reopen the microphone while another app (such as Claude's voice mode) is recording, and steps aside immediately if one starts. Fixes Claude not hearing you after the first reply.

## 0.3.0

- Bundled the "Hey Claude" wake word model.

## 0.2.1

- Fixed listening sometimes not resuming after the screen had been locked for a few minutes.

## 0.2.0

- Opens Claude in voice mode by pressing the assistant key through Shizuku.
- Falls back to Claude's text chat when Shizuku isn't available.

## 0.1.0

- First version: on-device wake word detection with openWakeWord models in Kotlin, foreground service, pause on screen lock.
