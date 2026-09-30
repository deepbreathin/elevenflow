# How ElevenFlow works

A technical tour for anyone reading or changing the code. The user guide is in the
[README](../README.md).

## The pipeline

```
 finger down                                                         finger up / second tap
     │                                                                         │
     ▼                                                                         ▼
 AudioRecord 16 kHz mono PCM ──50 ms chunks──►  Scribe (WebSocket)  ── commit ──►  committed_transcript(s)
     │                                          scribe_v2_realtime                        │
     │                                                                                    ▼
     └──► in-memory buffer ──► WAV in files/recordings/      join segments ──► clipboard + InsertService paste
                                        │                                                 │
                                        └── if live fails / is late ──► Batch (POST file) ─┘
                                                                                          │
                                                               not pasted? ──► "tap to paste" notification
```

1. **Touch down** starts a `Session` straight away. The microphone opens and the WebSocket starts
   connecting. The connection takes ~1–1.5 s to open, so chunks recorded in the meantime are
   queued and flushed as soon as it's ready. The connection time therefore never adds to the delay
   after you stop.
2. Each 50 ms chunk is base64-encoded and sent as an `input_audio_chunk` message. It's also
   appended to an in-memory buffer and fed to the level meter.
3. **Stop** sends the last chunk with `commit: true`. The server replies with `committed_transcript`,
   typically ~150–400 ms later.
4. The text goes to the clipboard, then `InsertService` pastes it into the focused field.
5. The buffered audio is written to a WAV, which is used for retries and kept in history.

## Files

| File | Role |
|---|---|
| `FlowService.kt` | Foreground service (type *microphone*). Owns the bubble and meter overlay windows, touch handling (tap vs hold vs drag), position memory, and the `Session` class that runs one dictation from start to finish. |
| `Scribe.kt` | Realtime WebSocket client for `wss://api.elevenlabs.io/v1/speech-to-text/realtime`. Queues audio until the socket opens and reports partial, committed and error messages. |
| `Batch.kt` | Whole-file transcription (`POST /v1/speech-to-text`, `scribe_v2`). The retry path. |
| `InsertService.kt` | Accessibility service. Finds the focused editable field (across windows), pastes (falling back to set-text), treats placeholder text as empty, and reports whether a keyboard is on screen. |
| `Nudge.kt` / `NudgeReceiver.kt` | Heads-up notifications for "ready — tap to paste", "not transcribed — tap to retry" and "bubble off / mic blocked — tap to fix", plus what happens when you tap them. |
| `History.kt` / `Recordings.kt` | Last 20 dictations (text + WAV path + ok flag) in SharedPreferences; WAV writing and pruning. |
| `Settings.kt` | Every user setting and its default, in one place. The service re-reads them live. |
| `MainActivity.kt` | Setup checklist, API key field, settings, and recent dictations with retry. |
| `TestReceiver.kt` + `src/debug/AndroidManifest.xml` | Debug-only hook that feeds a WAV file in place of the microphone (see [Testing](#testing)). |
| `art/make_bubble.py` | Generates the idle and recording bubble images from `art/robot.webp`. |

## ElevenLabs findings (measured, not assumed)

- **Latency.** From commit to `committed_transcript` was 167 ms on a Mac test and 200–400 ms from
  the Android emulator. The WebSocket handshake is ~1.5 s, which the queueing hides.
- **Long speech is split into segments.** Even with `commit_strategy=manual`, the realtime
  endpoint commits by itself roughly every **38 s of audio**. Each segment arrives as its own
  `committed_transcript` while you're still talking. An earlier version kept only the final one,
  so long dictations came back as just the tail end. `Session` now keeps every segment and joins them.
- **Replaying audio over the realtime socket is capped near 2× real time.** Sending faster gets the
  socket closed with `queue_overflow`. At 4× the server silently dropped most of the audio. So
  retries don't replay over the socket; they upload the saved WAV to the file endpoint instead.
  A 75 s recording takes ~3 s there, complete.
- **Custom words** (`keyterms`) must be sent as repeated query parameters (`&keyterms=a&keyterms=b`).
  A JSON array is rejected. They fixed names noticeably in realtime tests. The file endpoint
  accepts them too, with less visible effect.
- **Silence.** An empty commit returns an empty transcript, or an `insufficient_audio_activity`
  error. Both are treated as "heard nothing": no paste, and the recording is deleted.

## Android findings

- **Placeholder text.** WhatsApp and Telegram report their grey placeholder ("Message") as the field's
  text without setting `isShowingHintText`. Taken at face value, dictation came out as
  "Message hello…". `InsertService.realText()` ignores text that matches the field's hint or a
  common placeholder while the cursor is at the start, and insertion uses the app's own paste action
  first, so the app places the text at its real cursor.
- **Background microphone.** Android 14 refuses to start a microphone foreground service from the
  background. That includes Android restarting the service itself after killing it, and the
  accessibility service being re-bound at boot. The exceptions that matter here are a visible
  activity and **a tap on the app's notification**. So:
  - Opening the app restarts the bubble if it should be on (`wantOn`).
  - If a background start is refused, the "tap to turn it on" notification appears. Its tap starts
    the service with microphone access.
  - If a restart succeeded but Android is quietly withholding the microphone, `AudioRecord` returns
    exact zeros. A real microphone always has some noise, so exact zeros mean the mic is blocked and
    trigger the "mic blocked — tap" notification. That tap re-claims the microphone type.
- **Notification grouping.** Android automatically groups an app's notifications. The "tap to paste"
  nudge folded under the always-on notification needed two taps (the first only expanded the group).
  Each kind of notification now has its own group.
- **Foldables.** The position is saved relative to the screen: distance from the nearer side edge
  when near one, a fraction of the width otherwise, and a fraction of the height. It's re-applied in
  `onConfigurationChanged`, so a bubble on the right edge of the cover screen stays on the right edge
  of the unfolded screen.
- **Keyboard-only mode** watches for an input-method window through the accessibility service
  (`TYPE_INPUT_METHOD`). While hidden, the overlay is also made not touchable, so it can't swallow
  taps.

## Testing

Debug builds include a receiver that plays a 16 kHz mono 16-bit WAV into the bubble in real time,
as if it were the microphone, then stops. Only adb can trigger it: it's protected by the `DUMP`
permission, which normal apps can't hold, and it only exists in debug builds.

```bash
# 16 kHz mono test audio (macOS)
say -o test.wav --data-format=LEI16@16000 "Remind me to call the garage tomorrow morning."
adb push test.wav /data/local/tmp/test.wav && adb shell chmod 644 /data/local/tmp/test.wav

# with the bubble running and a text box focused:
adb shell am broadcast -n app.elevenflow/.TestReceiver --es wav /data/local/tmp/test.wav
```

Scenarios checked on an Android 14 emulator (Pixel 3a image):

| Scenario | Result |
|---|---|
| Short phrase | Pasted ~210 ms after the end |
| 75 s of varied speech | Complete text (1,238 chars) pasted ~400 ms after the end |
| Network cut 20 s in, stays off | Recording saved, partial text in clipboard; retry after reconnecting → full text in ~4 s |
| Network drops for 15 s mid-recording, recovers | Full text via the saved recording, 2.9 s after the end |
| Pure silence | Nothing pasted, recording deleted, "heard nothing" |
| No text box focused | "Dictation ready — tap to paste"; tapping it with a box focused pasted the text |
| Process killed (`kill -9`) | Bubble back in ~2 s |

Tips: toggle the network with `adb shell cmd connectivity airplane-mode enable|disable`, and read a
field with `adb shell uiautomator dump`. `adb shell am crash` is a poor stand-in for a real kill,
because it shows an error dialog and keeps the process alive. Two crashes within a minute make
Android stop restarting the app until it's opened again.
