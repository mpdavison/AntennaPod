# AntennaPod (AdSkip Fork)

> This is a **fork** of [AntennaPod](https://github.com/AntennaPod/AntennaPod) that adds AI-powered ad detection and automatic ad skipping.
> For the standard AntennaPod experience, documentation, and contribution guidelines, see the [official repository](https://github.com/AntennaPod/AntennaPod).

[![License: GPL v3](https://img.shields.io/github/license/AntennaPod/AntennaPod)](https://www.gnu.org/licenses/gpl-3.0)

---

## Why AdSkip?

**Podcast providers inject too many ads.** 15 minutes of ads in a 1-hour episode is CRAZY (looking at YOU, **iHeart**). This fork is a direct response to that.

**Attention is finite.** Your attention belongs to you. Nobody has a right to yank you out of a story or conversation to sell you something you didn't ask for.

**You're going to skip it anyway.** The only question is whether you mash the skip-forward button six times or the app handles it for you.

## What about the creators?

If the ad-driven model is the only revenue model that works, then the model stinks. If you believe that listening to the ads is the only way to support creators, then you can still do so with **Ad Martyr mode.** Every ad still plays, just at the end of the episode instead of scattered throughout. You hear the content uninterrupted. Creators still get their ads heard.

## AdSkip Features

This fork adds **automatic ad detection and skipping** to AntennaPod. When enabled, the app detects advertisement segments in downloaded podcast episodes using AI transcription and classification, then automatically skips past them during playback.

### How It Works

1. **Ad Detection** — After an episode is downloaded, a background worker splits the audio into chunks, transcribes them via a Whisper-compatible API, and classifies ad segments using an LLM chat API. The detected ad timestamps are saved to a local JSON file.

2. **Automatic Skipping** — During playback, the `AdSkipController` monitors the playback position against the detected ad segments. When playback enters an ad segment, it seamlessly seeks past it, plays a short beep, and offers an undo action.

3. **Nostr Integration** — Optionally share and discover ad timestamps anonymously via the Nostr relay network. Community-sourced timestamps can skip the AI pipeline entirely when a matching episode is found.

4. **Ad Martyr Mode** — Instead of skipping ads, collect all ad segments and play them back-to-back at the end of the episode.

5. **Per-Feed Control** — Enable, disable, or defer to the global setting on a per-podcast basis.

### Is it expensive?

No, but _it depends_&trade;. You can do transcription with your old GPU or use OpenAI. You can use Deepseek's LLMs for classification or one of the top-tier AI providers. The most expensive processing is the transcription. If you host that yourself then the cost will be your electricity plus whatever LLM you use for classification. The app is designed to be provider-agnostic, so you can choose combinations of providers that suit your budget and privacy needs.

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
