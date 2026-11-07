# Release notes

Newest first. When you publish a release on GitHub, paste that version's section as its
description. How to publish is in [Releasing a new version](../README.md#releasing-a-new-version).

## 0.20

What's new since 0.18:

- **Sound wave styles (0.19).** Choose how the live sound wave looks while you dictate: *Neon*
  (green bars and a red dot), *Classic orange*, *Line* (a smooth glowing wave) or *Minimal* (white
  bars, no background). Each one has a live preview in the app; tap one to use it.
- **Webhook, optional (0.20).** Give it a URL and it POSTs `{"event":"start"}` and
  `{"event":"stop"}` as you start and finish dictating. Another device can react, for example
  pausing music on your computer. Plain `http://` works for home or VPN addresses. Leave it empty
  to turn it off.

Requires Android 9 or newer.

## 0.18 and earlier

See the descriptions on [GitHub Releases](../../../releases).
