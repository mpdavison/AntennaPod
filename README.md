# AntennaPod (AdSkip Fork)

> This is a **fork** of [AntennaPod](https://github.com/AntennaPod/AntennaPod) that adds AI-powered ad detection and automatic ad skipping.
> For the standard AntennaPod experience, documentation, and contribution guidelines, see the [official repository](https://github.com/AntennaPod/AntennaPod).

[![License: GPL v3](https://img.shields.io/github/license/AntennaPod/AntennaPod)](https://www.gnu.org/licenses/gpl-3.0)

---

## Why AdSkip?

**Ad-creep is enshittification.** Episodes keep getting more ads — pre-roll, mid-roll, post-roll — with no way to know what you're getting before you hit play. This fork pushes in the other direction.

**It's not just time. It's attention.** Your attention belongs to you. Nobody has a right to yank you out of a story or conversation to sell you something you didn't ask for.

**You're going to skip it anyway.** The only question is whether you mash the skip-forward button six times or the app handles it for you. Automating that means you keep your eyes on the road and hands on the wheel.

**Ad Martyr mode** is the compromise: every ad still plays, just at the end of the episode instead of scattered throughout. You hear the content uninterrupted. Creators still get their ads heard.

## AdSkip Features

This fork adds **automatic ad detection and skipping** to AntennaPod. When enabled, the app detects advertisement segments in downloaded podcast episodes using AI transcription and classification, then automatically skips past them during playback.

### How It Works

1. **Ad Detection** — After an episode is downloaded, a background worker splits the audio into chunks, transcribes them via a Whisper-compatible API, and classifies ad segments using an LLM chat API. The detected ad timestamps are saved to a local JSON file.

2. **Automatic Skipping** — During playback, the `AdSkipController` monitors the playback position against the detected ad segments. When playback enters an ad segment, it seamlessly seeks past it, plays a short beep, and offers an undo action.

3. **Nostr Integration** — Optionally share and discover ad timestamps via the Nostr relay network. Community-sourced timestamps can skip the AI pipeline entirely when a matching episode is found.

4. **Ad Martyr Mode** — Instead of skipping ads, collect all ad segments and play them back-to-back at the end of the episode.

5. **Per-Feed Control** — Enable, disable, or defer to the global setting on a per-podcast basis.

### Configuration

Configure ad detection in **Settings → Ad Detection**:

| Setting | Description |
|---------|-------------|
| **Enable ad detection** | Master switch to turn ad detection on/off globally |
| **Ad Martyr** | Play all ads at the end of the episode instead of skipping them |
| **Transcription (Whisper)** | Choose a provider profile for audio-to-text transcription (OpenAI, Groq, or custom endpoint) |
| **Classification (Chat)** | Choose a provider profile for the LLM that identifies ad segments |
| **Nostr Community Ad Timestamps** | Enable sharing and discovering ad timestamps via Nostr relays |

**Provider Profiles** let you configure API keys, base URLs, and model names for the transcription and classification steps. Supports OpenAI, Groq, or any compatible custom endpoint.

### Per-Feed Ad Detection

Each podcast feed can override the global ad detection setting. Open the feed settings for any podcast and choose **Ad detection → Enabled / Disabled / Global**.

### Undoing a Skip

When an ad is skipped, a toast appears with an **Undo** button. Tap it to reverse the skip for that session. You can also clear all ad timestamps for an episode from the episode menu.

### Import/Export

Ad detection settings — including provider profiles, active provider selections, and the enabled/martyr-mode toggles — can be exported to and imported from a standalone JSON file via the **Settings → Import/Export** screen.

---

## Building

You can build this fork just like the original AntennaPod — it's a standard Android project using Gradle. See the [official contribution guide](https://github.com/AntennaPod/AntennaPod/blob/develop/CONTRIBUTING.md) for details.

## License

AntennaPod is licensed under the GNU General Public License (GPL-3.0). See the [LICENSE](https://github.com/AntennaPod/AntennaPod/blob/develop/LICENSE) file.
This fork's modifications are documented in [CHANGES.md](CHANGES.md).
