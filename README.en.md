# MultiSuperPlayer

A local audio/video player for Android, focused on its **subtitle/lyrics pipeline** and **subtitle translation**.

- Language: Kotlin + Jetpack Compose (Material 3)
- Playback engine: AndroidX Media3 (ExoPlayer) + the NextLib FFmpeg software-decoding extension
- Minimum: Android 8.0 (API 26)
- Current version: **0.8.0-alpha.1** (pre-release)
- License: [GPL-3.0](LICENSE)

Release notes: [docs/release-notes](docs/release-notes/)

中文文档：[README.md](README.md)

---

## 1. Features

### 1.1 Playback

- **Format coverage.** Container formats are handled by Media3's extractors; when the platform lacks a
  decoder for the codec, playback automatically falls back to the FFmpeg software decoders bundled with
  NextLib. A "force software decoding" switch is provided for the rare file a hardware decoder misjudges.
- **Four aspect modes**: fit / crop / stretch / original. Changing it from the player is a **temporary
  override** and is not written back to settings.
- **Gestures**: vertical drag on the left half adjusts brightness, on the right half adjusts volume,
  horizontal drag seeks, double-tap on either side seeks ±10 s.
- **Speed**: 10 steps from 0.25x to 4x, plus a separate "long-press speed" you can trigger by holding the screen.
- **A-B repeat** with three states (set A, set B, clear).
- **Resume position** for both audio and video. Reaching 95% counts as "finished", so the next
  play starts from the beginning (the record is **kept**, not deleted); playback shorter than
  15 seconds records "it was played" but no position.
- **Two separate switches**: "Remember position" decides whether a position is **written**;
  "Record recent plays" decides whether the list **gains new entries**. All four combinations
  are meaningful (they are two independent choices, not two names for one bit):

  | Remember position | Record recent plays | Result |
  | --- | --- | --- |
  | on | on | Default: positions remembered, everything played shows up |
  | on | off | Positions still recorded (resuming is unaffected), so something you watched halfway still leaves a resume record; the position-less "it was played" entries are no longer created, and existing entries stop having their timestamp refreshed |
  | off | on | Always starts from the beginning, yet **you can still see what you have watched** (no position is written at all) |
  | off | off | Nothing is recorded |

  The third row works because "record that it was played" and "write a position" are two
  different operations (`markPlayed` keeps the existing position and only pushes the timestamp);
  one shared write could never express it.
- **Fullscreen, landscape, screen lock** to avoid accidental touches.
- **Landscape audio gets a layout of its own**: artwork and track info on the left, lyrics on the
  right. When the window is wide enough (≥ 711dp) the feature chips and the transport controls move
  into the left column under the artwork, leaving the whole right column to the lyrics (only the
  progress bar stays); narrower windows (split screen, small older devices) fall back to the
  controls pinned under the lyrics. Audio has no picture to protect, so the
  constraint is the opposite of video — the controls are **permanent**, not a fading overlay, which
  also means there is no "it faded out and can never be brought back" (landscape audio has no gesture
  layer, so a faded overlay really is gone). When there are no lyrics that column says which case it
  is — loading / turned off / none found / failed — and offers a way to pick a subtitle file.
- **Portrait keeps room for the lyrics**: every other band on the audio page (artwork / title /
  progress bar / feature chips / transport controls / session chips) is **fixed-height**, so only the
  lyrics take what is left — on a short screen the lyrics are what gets squeezed. The rule is now
  written as "**who gives way**": the artwork shrinks first (max 112dp; below 32dp it is not drawn at
  all, and the gap above it goes with it), the lyrics keep a 200dp floor, and once the lyrics viewport
  drops below 260dp its own padding tightens from 24dp to 16dp / 8dp (about half a line back) — the
  font size never changes.
- **A visible way out**: a labelled *Collapse* button at the top-left of the portrait player —
  an exit you can see, instead of having to guess the system back gesture (landscape keeps its
  own *Exit fullscreen* arrow and gets no second button). Collapsing pops the player page only:
  playback continues and the mini player takes over the bottom bar.
- **Audio track selection**: when a file carries more than one audio track, an extra *Audio track* chip
  shows up under the controls (labelled with the current track's name/language) and its panel lets you
  switch track by track or go back to *Auto*. The choice applies to **this file only** — another file goes
  back to automatic, otherwise the previous file's pick would follow the next one with nothing on screen
  saying it had been changed by hand (the same reason manual subtitle picks are not remembered).
- **Also**: shuffle / repeat all / repeat one, previous / next, volume and brightness indicators,
  a playback service and notification controls.

### 1.2 Lyrics and subtitles

- **Parsers**: SRT / WebVTT / ASS / SSA / LRC / Enhanced LRC (word-by-word) / TTML (DFXP, SMPTE-TT) /
  VobSub / PGS. Unsupported input is never guessed at silently — the registry reports readable
  diagnostics such as "detected as X but the content looks more like Y".
- **Discovery and matching**: sibling subtitles are found by **source-specific** means and then scored
  by filename similarity, language tag and `forced` flag; ties are broken by stable rules.
  - library entries: through the relative path recorded by the system media library;
  - entries inside a granted SAF tree: the siblings are enumerated by the SAF provider (so media the
    media library cannot see at all is covered);
  - entries opened through the built-in file browser: the directory is **listed straight off the disk**
    (including `Download/` roots and `.lrc` files that are not in any index).

  The route is chosen by the **source itself**, never by "which field happens to be populated" — keying
  on a field would route browser entries down the media-library path and silently find nothing.
- **Encoding fallback**: non-UTF-8 subtitles are tried against a list of candidate encodings, and the
  parse warnings state which one was used. Chinese falls back to GB18030; Japanese files, which are
  commonly **Shift-JIS (CP932)**, get a separate decision — and it is **not** "whichever candidate comes
  first wins": the GB18030 reading is used as the baseline, and Shift-JIS is only adopted when its text
  looks **more Japanese** (more kana and ideographs, fewer halfwidth katakana and private-use
  characters). Otherwise both are double-byte encodings and whoever is tried first wins, so GBK Chinese
  subtitles would be stolen by the Japanese candidate. Known trade-off: Japanese that contains **no kana
  at all, only ideographs**, still lands on GB18030 (both encodings decode it, so there is nothing to
  choose between them).
- **Display modes**: hidden / original only / translation only / bilingual.
- **Never blank.** If you pick "translation only" and the subtitle has no translation, the app falls back
  to showing the original **and says why**, instead of rendering nothing and making you think subtitles
  are broken.
- **Manual edits**: any translated line can be corrected by hand. Corrections are persisted separately and
  survive re-translation.
- **Pick a subtitle file yourself**: the subtitle panel has a *Choose subtitle file…* button that opens the
  system file picker for any location. That candidate scores **full marks** — the user pointed at it, so
  there is nothing to guess about "is the name similar", and it sorts ahead of anything auto-discovery
  found. A manual pick is deliberately **not remembered**: switching media goes back to auto-selection,
  because otherwise last item's pick would follow the next one, which looks exactly like subtitles being
  attached to the wrong media — and nothing on screen would say a manual pick was ever in effect.
- **Subtitles that ship inside the file**: **subtitle tracks muxed into the container** are fed into **our
  own subtitle layer**, so display modes, bilingual mode, translation and the timeline offset treat them
  exactly like sibling files. `PlayerView`, however, carries a `SubtitleView` of its own that keeps drawing
  the same cues from `onCues` once `setPlayer()` has run, so the player screen sets that layer to `GONE`:
  otherwise an embedded track shows up as **two** copies on screen (offset from each other, i.e. a ghost
  image) and the "hidden" display mode cannot hide it either. Sibling subtitle files never go through
  Media3's parsers, so they are unaffected. They are listed in the same panel as the file candidates, with
  language, format and default/forced flags. The format is read from `Format.codecs`, not
  from `sampleMimeType`: Media3 reports every text track it extracts from a container as
  `application/x-media3-cues` and keeps the real format (`application/x-subrip` and friends) in `codecs`.
  Judging by `sampleMimeType` classifies them as unknown and the whole track then **vanishes** from the
  panel — while the top line still says `Auto-selected "zh"`, i.e. the two halves contradict each other.
- **Subtitle timeline offset**: ±0.1 s and ±0.5 s steps that accumulate, plus a *Reset* button. A positive
  value means the subtitle appears **later** (if it shows up before the sound, tune positive). The offset
  only shifts the timeline as a whole; it never changes how long a line stays on screen.
- **Subtitle rate (proportional correction)**: five presets (0.96x / 0.98x / 1.00x / 1.02x / 1.04x) plus
  fine adjustments of ±0.01 / ±0.10, covering 0.90x–1.10x. It is **not** the same thing as the timeline
  offset: that one is a **shift** (every line moves by the same amount for the whole file), this one is a
  **scale** — use it when the drift grows towards the end (a source whose frame rate does not match its
  subtitles). Fixing the beginning with a shift is guaranteed to break the ending; only a rate can fix
  that kind of drift. Like the offset, it applies to this playback only: switching files returns it to 1.00x.
- **Embedded subtitle tracks can be pre-read in full**: the text track inside the container is read
  end-to-end in the background, so the app ends up with the **whole** cue table instead of "whatever has
  played so far". Subtitles are therefore there the moment you open a file, and the rate has a complete
  table to work from. When the pre-read cannot be done (network stream, bitmap subtitles, unrecognised
  container) it **silently falls back** to the streaming table — pre-reading is the better path, not the
  only one. The panel says which state it is in: `Pre-reading subtitles…` while it runs, and
  `Could not pre-read subtitles, rate adjustment may be off` if it failed.
- **Subtitle style**: presets for text size (Small / Normal / Large / Huge), line spacing (Tight / Normal /
  Loose), outline (None / Thin / Normal / Thick) and bottom margin (Edge / Normal / Raised / High). Both the
  **subtitle panel on the player** and the **"Subtitles & translation" settings page** can edit them; the
  two entry points share one setting, changes apply immediately and are remembered globally, and *Reset
  subtitle style* puts all four back at once. The reset runs in a single transaction rather than four
  consecutive writes: if one of those failed you would be left half default and half custom with nothing on
  screen saying which half did not make it. Presets rather than continuous sliders, because no tick on a
  slider has any claim to being "the default", nobody dares to drag one arbitrarily, and afterwards a stored
  number ("1.37× text size") cannot be explained. **The default presets resolve to exactly the numbers
  v0.5.15 hard-coded** (16sp text size / 24sp line height / no outline / 12dp bottom margin), so nobody sees
  their subtitles suddenly change after upgrading — a unit test guards this.

### 1.3 Subtitle translation

- **Any OpenAI-compatible endpoint**: fill in base URL, model name and API key. Presets for common
  providers are included, and a fully custom configuration is supported.
  The **Ollama preset points at Tencent's translation-specialised `demonbyron/HY-MT1.5-1.8B`** (33
  languages): subtitle translation needs no world knowledge, only line-by-line fidelity, and handing the
  job to a general 8B model makes the user pay 5x the VRAM and 5x the waiting time for none of it.
- **15 target languages**: Simplified Chinese / Traditional Chinese / English / Japanese / Korean /
  Russian / Spanish / French / German / Portuguese / Italian / Arabic / Thai / Vietnamese / Indonesian.
  Not 33 and not 5 — an optional list is not the model's capability ceiling, and every language costs 3
  translated UI strings plus 1 prompt example, so this is the intersection of "frequently used" and
  "easy to verify". The example follows the language: French demonstrates `Où vas-tu ?` (French keeps a
  space before `?`), Arabic demonstrates `؟`, Thai demonstrates "no full stop and no spaces".
- **It can also run entirely offline**: the provider list has an extra entry, *Local (runs on this
device)*, where the model runs on the phone and the subtitle text never leaves it (see section 1.9).
- **Batching and caching** with context lines and a glossary. Results are cached by a hash of
  (content, target language, model), so identical content is never paid for twice.
- **Glossary**: names and proper nouns are replaced with placeholders before the request and restored
  afterwards, so the model cannot silently rewrite them.
- **The example in the prompt follows the target language**: the "input / output" sample is generated for
  the language being translated into (translate to Japanese and it demonstrates Japanese, including "two
  speakers on one line keep their line breaks") instead of always demonstrating Chinese. Models copy the
  example: a Chinese example makes them answer in Chinese reliably — no error, no parse failure, just the
  wrong language, which looks like the model being bad at its job. The list of target languages and the
  examples are bound one-to-one at compile time (add a language without its example and the build fails).
- **Diagnosable failures.** Failures are classified into categories that each require a *different*
  remedy — not configured / unauthorized / quota exhausted / rate limited / rejected / server error /
  network / bad response / empty completion / truncated. The UI offers an action you can actually take,
  and the raw provider message is available on demand.
- **Retry only the failed lines** — a failed batch does not re-translate the lines that already succeeded.
- **Export** to SRT or ASS, translation-only or bilingual, with the target language code in the filename.

### 1.4 Theme

- **Base theme**: follow system / light / dark / pure black (OLED).
- **Color source**: system dynamic color (Android 12+), artwork color extraction, or a fixed accent
  (6 presets).
- The difference between dark and pure black only shows up on a real device: pure black is `#000000`,
  which is what OLED power saving and contrast actually need.
- The theme swaps the **entire tree**, not just the widgets the app draws itself: `MspTheme` sits at the
  outermost level and distributes both the base color scheme and the accent, so `Scaffold`, dialogs,
  dropdown menus and scroll containers never leak a white background.

### 1.5 Localization

- **Follow system / Simplified Chinese / Traditional Chinese / English**, switchable in-app under
  Settings → Appearance → Language.
- Simplified Chinese is the resource fallback language (it lives in `values/`); the other two have their
  own `values-en/` and `values-b+zh+Hant/`. An unsupported system language falls back to Simplified Chinese.
- On Android 13+ these languages are registered with the system, so the OS-level per-app language picker
  works too — the two can't drift apart.

### 1.6 Media library and playlists

- **Five tabs**: Library / Browse / Recent / Playlists / Settings. "Now playing" is a **real page**
  rather than a tab, so Back returns to the list you came from instead of exiting the app.
- **A mini player** sits above the navigation bar (hidden on the player page): tap it to return to the
  player, tap pause to pause in place, and use the previous / next buttons next to it to change track
  **in place without leaving the current page**. Its progress line is the **only** widget that subscribes to
  playback position (200 ms), so the surrounding lists do not redraw every second. Those two buttons are
  **never greyed out**: the playback state carries no "is there a next item" flag, and guessing wrong
  (greyed out but it works, or enabled but nothing happens) is worse than always-tappable — at the end of
  the queue skipping simply stays where it is, with no side effect.
- **View options**: list / grid; 6 sort orders (title asc/desc, newest, oldest, longest, largest);
  4 groupings (none / artist / album / folder). Group headers always sort by title and do not jitter
  with the sort order; entries with no artist/album/folder fall into an "unknown" group that always
  sorts last.
- **Multi-select**: long-press to enter selection mode, then select all / clear / add to playlist / play.
  Actions follow the **order you tapped the items in**: "add to playlist" appends in that order and
  "play" starts from the first one tapped, so tapping in reverse really does give you a reversed queue.
  Duplicates are skipped and the app **says how many it skipped** ("added 10, 1 was already in the
  list") instead of quietly adding 9. The **built-in file browser uses the same multi-select**
  (section 1.7); both pages share one action bar.
- **SAF folder browsing**: grant a folder through the system picker and the grant is persisted. Its
  contents are **merged** with the system media library and de-duplicated by relative path + file name +
  size, so the same file never appears twice. SAF entries are prefixed with `saf:` and cannot collide
  with MediaStore's numeric ids. The scan has four guard rails (max depth 8, max 20 000 entries,
  audio/video extensions only, no directory entered twice) so a malformed tree cannot stall it.
- **Subtitles inside SAF folders are still found**: when playing inside a granted tree the sibling
  subtitle files are enumerated and scored — so media inside a `.nomedia` folder, which the system media
  library cannot see at all, does not lose its subtitles either.
- **Recent**: built from the resume records (position + timestamp), up to 50 entries; opening one resumes
  from where you stopped rather than from the beginning.
- **No position still means it was played**: an item you opened for a few seconds keeps a position of 0
  but **stays in the list**, rendered as "Played </time> - no position kept" rather than "00:00".
  "Not worth resuming" and "never played" are two different things; earlier versions collapsed both
  into one branch, so short clips could never appear on the Recent page.
- **Can be turned off in Settings**: turning it off stops recording new items and **does not delete**
  what is already recorded (otherwise the switch would be an irreversible delete, while all the user
  meant was "stop recording"). With it off the Recent page says the switch is off instead of pretending
  the list is empty.
- **Rows can be deleted one by one, and the whole list can be cleared.** The ✕ on the right of a row
  removes that entry; the sweep icon in the top bar clears everything (behind a confirmation dialog).
  **Deleting a row also forgets the resume position it holds**, because the row *is* the resume record.
  A snackbar offers **Undo**, which writes the record back **with its original timestamp**, so the row
  returns to the same place in the list. The clear-all copy states both halves at once: resume
  positions are forgotten (playback starts from the beginning next time) and **files in the library
  are untouched** — saying what is lost and what is not is the only way this is not read as "deletes
  my files".
- **Playlists** store an id key plus a display snapshot (title / artist / duration). If the library
  temporarily cannot find a file, the entry **does not vanish**; the list marks it as currently absent
  while keeping its position in the queue — "the library can't find it" is not the same as "it can't be
  played". Limits: 100 playlists, 5 000 items each, 80-character names; hitting a limit refuses the
  action and says why. An entry restored from a playlist derives its source from the id prefix
  (browser entries carry `file:`), so a file played *from a playlist* still finds its sibling
  subtitles — otherwise it would be looked up as media-library media and hit the "cannot tell which
  folder this file is in" wall again. "Is this entry still there?" is decided **per source** too:
  the library is consulted first, and only when that misses *and* the id carries the `file:` prefix
  (added from the browser) is the absolute path it recorded checked against the file system. A
  library-only check mislabels **every** browser entry as "file is gone", because the library does
  not index those paths in the first place. A failed file-system probe always counts as "gone" —
  better to over-label one row than to claim a deleted file still plays.
- **Playlists can be reordered by dragging**: both the list of playlists and the items inside one are
  reordered by **long-pressing a whole row**. The order is saved (it survives killing and reopening the
  app). A playlist list's order is stored separately as a string of ids and **not written into the
  playlists themselves**, so the storage format is unchanged and upgrading needs no migration; playlists
  missing from that order fall back to creation time, which puts **a new playlist at the end**. Dragging
  does **not auto-scroll**: when a list is longer than one screen, scroll near the target first.

### 1.7 Built-in file browser

`ACTION_OPEN_DOCUMENT` only lets the user pick files in locations that have already been granted, and
places like Download or the root of a storage card can never be granted that way. So besides SAF trees,
the Browse tab has a **built-in file browser** that lists directories itself instead of going through the
system picker.

- **Two kinds of source side by side**: `Internal storage` and `SD card` (these need an "all files
  access" permission), plus every granted SAF tree. Each source carries a **status line**; when the
  permission is missing it says "not enabled, tap to open system settings" instead of a bare failure.
- **The permission is explicit and optional**: "all files access" (`MANAGE_EXTERNAL_STORAGE`) is **off by
  default** and is **never requested on first launch**. It lives on the Settings **Permissions** page,
  which lists all four things the app can ask for (media read / all files access / notifications /
  Bluetooth) together with a **state** (granted / partially granted / not enabled / denied / unsupported
  on this device) and an **action** — request it in place when the system still shows a dialog, jump to
  the system page when that is the only way, and show **"Open settings" rather than a dead "Request"
  button** once the system has stopped showing dialogs. A collapsible note below lists the six
  permissions granted at install time that the app itself cannot change (network, foreground service,
  and so on), so it is visible what the app asks for in total.
- **One request at first launch**: the first launch after install asks for **notifications** and **media
  read** (music & audio, photos & videos) in turn; "all files access" and Bluetooth are **not** part of
  that batch. It never nags again — the system would not show the dialogs anyway once the user has
  declined. Returning from a system settings page **refreshes the state automatically** instead of keeping
  a stale conclusion.
- **Crumbs record the route taken, not a recomputed path**: walking into a SAF folder and back follows
  one chain in one tree, so no path-splicing rule can land you somewhere that does not exist.
- **4 sort orders** inside a directory (name asc/desc, newest, oldest) plus a show-hidden-files switch.
  **Directories always come before files**, regardless of the selected order.
- **5 000 entries per directory** maximum: beyond that the listing is truncated, and it **says so**
  instead of quietly returning fewer rows.
- **Tapping a playable file** builds the queue from **every playable entry in the current directory**
  (not the whole library) and starts at the one you tapped.
- **Multi-select**: long-press any row to enter selection mode, then select all / clear / add to playlist /
  play (the same action bar the library uses). Only **playable files** can be selected: directories and
  subtitle files are still listed, but their checkbox is disabled — they never enter the queue, so
  selecting them would have nothing to act on. In selection mode a tap **toggles** instead of descending,
  and Back leaves selection mode first rather than going up a directory. Entering another directory
  clears the selection, because what is selected is "these files in *this* directory".
- **Tapping a subtitle file** (`.srt` / `.ass` / `.ssa` / `.vtt` / `.lrc` / `.ttml` / `.dfxp` and friends)
  does **not** queue it for playback — it is attached as an **external subtitle to the video you are
  currently playing**. That is the intended entry point for "pick a subtitle by hand for this video".
  A hand-picked subtitle **does not depend on the directory scan**, so it attaches even where automatic
  discovery has no way to run.
- **Nothing is written to the media library**: browsing is browsing. Files opened this way do not enter
  the library or the Recent list.

> Directory rows show the **original name with its extension** (`Movie.zh-CN.ass` shows as
> `Movie.zh-CN.ass`), because the point of this list is finding one specific file, and the extension and
> language tag are exactly the information you need for that.

**Sibling subtitles are still discovered automatically**: media opened here has its own directory
**listed straight off the disk** (never through the system media library — the whole reason this source
exists is "places the media library cannot see"), so a `Download/` root, a `.nomedia` folder and media
on the SD card all find their `.srt` / `.ass` / `.lrc` counterparts. This needs the "all files access"
permission: **when the directory cannot be read the app says "cannot read this folder", never "this
folder has no subtitles"** — the two need opposite fixes (grant a permission vs. rename a file).

### 1.8 On-device offline speech recognition (advanced goal)

No network, no upload: the audio track is turned into subtitles using the phone's own compute, and the
resulting file goes straight into the subtitle sheet, **already selected**.

- **Three models to choose from**: Chinese offline (Paraformer small, about 78.1 MB), Japanese offline
  (ReazonSpeech, about 169.0 MB) and bilingual streaming (Zipformer, about 189.8 MB). Size and intended use
  are part of each option, and **only the selected model is downloaded** — the others are not fetched
  "while we are at it". The Japanese one is a **Japanese-only** whole-segment model: it cannot recognize
  Chinese or English at all, and in exchange it is more accurate on Japanese than the streaming model. That
  trade-off is written on the option itself instead of being left for the user to discover.
- **Download source is editable**: the default is the Chinese mirror `hf-mirror.com`; entering
  `https://huggingface.co` switches to the official source, and **clearing the field goes back to the
  default**. A wrong-looking address is flagged and explained, but never blocks the *Download* button —
  only the network stack knows whether a mirror is alive, and a format check cannot tell.
- **Per-file sha256 verification**: a mismatch is treated as "verification failed" and the partially
  downloaded file is deleted, so there is never a half-finished model that "looks downloaded but cannot
  be loaded".
- **Models can be deleted on their own**: deleting clears just that model's files and
  **never touches subtitles you already generated** (that is what the confirmation says, and it was
  verified on a real device); the VAD segmentation model is a **shared resource** and stays.
- **Recognition never goes online**: audio does not leave the device.
- **Japanese output carries no spaces between kana**: ReazonSpeech emits **one token at a time**, so writing
  the raw output to disk gives you `こ ん に ち は`. The post-processing collapses only the space between
  **kana and kana**; the space between kana and English (`こんにちは world`) is kept as it is, otherwise it
  would eat the word separators too.
- **Four different failures, four different messages**: "this file has no usable audio track" /
  "no speech detected" / "subtitles were recognized but could not be saved" / "decoding failed". Their
  next steps are completely different (pick another file / pick audio with someone talking / free up
  space / use another file), so collapsing them into one "recognition failed" would explain nothing.
- **Decode and recognize in one pass, no intermediate file**: an intermediate wav for a 33-second clip is
  already a few MB, and for a full-length video it reaches GB territory. The whole run shares **one**
  engine instance (building one takes seconds, so rebuilding it per segment costs more than the
  recognition itself). Progress is reported as **samples processed**, so the bar shows "processed / total"
  instead of spinning forever.
- **Where the output lives, and what it is called**: the file lands in the app-private directory
  `files/generated_subtitles/<hash of the media uri>.srt`, and shows up in the sheet as `<title>.asr.srt`,
  tagged "On-device · SubRip" and **auto-selected** — making the user hunt for it in a list afterwards
  would be as good as never saying "it is done". The trade-offs behind a private directory (writing next
  to the video is more portable, but library entries are read-only) and behind hashing instead of using
  the title (titles contain `/`, `:`, spaces, and can collide) are documented in the KDoc of
  `GeneratedSubtitleStore`.
- **Nothing is overwritten**: the result is a new file; existing sidecar subtitles and embedded tracks are
  left exactly as they were.

### 1.9 On-device offline translation

Settings → Subtitles and translation → the provider list has an extra entry, **"Local (runs on this
device)"**. It sits next to the cloud providers, but it **never goes online**: no base URL, no API key,
and the subtitle text never leaves the phone.

- **The model is downloaded on demand**: the default tier is **Qwen3-0.6B (int4 quantized, about 345 MB)**, defaulting to the
  `hf-mirror.com` mirror. After the download the file is **verified against sha256**, and a mismatch
  deletes the partial file — there is no "looks downloaded but will not install" model left behind.
  The download source is **a separate key** from the speech recognition one (pointing one source somewhere
  is no reason to silently change the other); leaving it blank restores the default. The download can be
  stopped mid-way, and resuming is not supported, so a half-finished download **says how much was
  received** and states that it will start over rather than pretending to resume.
- **Two tiers to choose from**: the light one is Qwen3-0.6B (about 345 MB); the other is **Tencent
  Hunyuan HY-MT2-1.8B (int8, about 1.7 GB)**, a translation-*specialised* model (33 languages) that is
  visibly better. 0.6B stays the default because **a default is for everyone**, and the 1.8B one will
  likely not run on anything below 8 GB of RAM. Those who pick it do pay 5x the size — and get back a
  difference they can see.
- **The memory requirement is stated before the download**: the model card says "needs about 2.7 GB of
  memory". Without that, a user only finds out their device cannot cope after spending 1.7 GB of
  traffic, and by then they have no way back. If the measured requirement exceeds 40% of the device's
  total memory, one more line appears in red: "this device has about 3.8 GB of memory in total, so this
  model may fail to run" — a **hint, never a block**: the threshold is only an estimate (missing a
  warning costs 1.7 GB of wasted traffic, a false warning costs one grey line — the two mistakes are
  nowhere near equal), so the decision stays with the user.
- **Why it is not bundled into the APK**: the models start at 345 MB while the whole installer is about 135 MiB.
  On-device translation is a feature you need *when you use it*, so making every user pay nearly three
  times the size for it is a bad trade.
- **The installer did grow anyway**: about 88.6 MiB in v0.6.3 → about 134.7 MiB now. What grew is the
  **inference runtime** `liblitertlm_jni.so` (arm64-v8a 21,802,960 B + x86_64 25,968,008 B, stored
  uncompressed), and that part cannot be dropped — without it the on-device model cannot run at all.
  The model itself is still downloaded on demand.
- **Why 0.6B**: it is the size that actually runs on a phone. Subtitle translation needs no world
  knowledge, and larger models (1.7B / 4B) take several times as long on the same batch — better to ship
  one that works than an option that makes you wait ten minutes.
- **CPU only**: nothing to tune per device, reproducible output, at the cost of speed (a batch of 3–10
  lines measured at a few seconds to a few tens of seconds). The upside of batching is that the progress
  indicator keeps moving instead of waiting for one giant answer.
- **Constrained decoding pins the output shape down.** On this path the JSON Schema is not a suggestion:
  it is compiled into real **decoding constraints**, so the model never reaches a state where malformed
  JSON could be produced. That is the opposite of the cloud path, where `response_format` only guarantees
  "valid JSON" — the **field names are never sent to the model**, which in practice invents its own key
  names, so the cloud path relies on the format paragraph in the system prompt. In other words, it is the
  same statement said twice: once in the prompt, once by the local decoder. The two must agree (change
  one and you must change the other, and the prompt side also has `TRANSLATION_PROMPT_VERSION` to bump
  because the cache key follows it).
- **The item count goes into the schema, not the prompt.** The caller already knows how many lines it is
  sending, so it goes straight into `minItems` / `maxItems` instead of being echoed by the model. Both
  reasons come from real measurements: 0.6B **reliably** merges a whole batch into **one** string (an
  array of length 1, with `\n\n` between sentences) and retrying does not help; and asking a model to
  echo a number it cannot determine is a mistake in itself — every extra required field is one more thing
  that can be silently dropped. When a batch is split, the count follows that batch's own line count.
- **Failure reasons are split by "what do I do next"** — these three require completely different
  actions, so collapsing them into one "translation failed" would explain nothing.

  | What the UI says | What to do next |
  | --- | --- |
  | The on-device model "x" has not been downloaded | Download it under *Local model* |
  | The on-device inference engine could not start | **Downloading a model does not fix this one** — it is usually low memory or an unsupported device; restart the app, switch to a smaller model, or use a cloud provider |
  | The on-device model produced no result this time | Retrying usually works; if it keeps happening, use a smaller model or a cloud provider |

- **A model can be deleted on its own**: deleting clears only that model's files and **leaves every
  subtitle already translated untouched**; it can be downloaded again.
- **One known quirk of the model**: it **copies the "input / output" example** from the prompt — given a
  contentless short line (like `Line two.`) it emits the example's answer. Neighbouring lines are
  translated correctly, so this is not an alignment problem. Fixing it touches the whole prompt
  calibration, so it is left for a later release.

### 1.10 Cloud speech recognition subtitle generation

The mirror image of section 1.8: the audio **no longer stays on the phone**. It is cut into chunks and
uploaded to a provider, and the product is the same kind of thing — a `.srt` stored in the private
directory, attached and selected automatically. Settings → Speech recognition now starts with a
**Recognition method** section.

- **The default is still *On-device*.** That is not arbitrary: the cost of the on-device path (downloading
  a model, waiting) is visible on screen and can be abandoned at any moment, while the cost of the cloud
  path is that **the whole audio leaves this device, irreversibly**. Whether to pay that is a decision the
  user makes once.
- **Four provider presets plus *Custom***: OpenAI (`whisper-1`), Groq (`whisper-large-v3-turbo`),
  SiliconFlow (`FunAudioLLM/SenseVoiceSmall`, **no timestamps** — stated right on the option), and
  *Custom* (a relay service or a self-hosted compatibility layer). The default provider is SiliconFlow:
  reachable from mainland China, lowest key barrier.
- **The address field shows the final request URL live**: whatever *Base URL* holds, the line below reads
  `Will request: https://…/v1/audio/transcriptions`. That line is the most useful thing in this release —
  almost every cloud misconfiguration is **a missing path segment** (usually `/v1`), and this is the only
  place that shows it **before** the button is pressed. An address that is not an address is highlighted
  and explained, and it **also greys out the button on the player page**: the verdict here is the same one
  the client reaches just before opening a connection, so there is no state where "the button is live and
  pressing it must fail".
- **Switching providers clears the address and model fields** — otherwise the previous provider's address
  would be sent to the next one. Keys are different: **one key per provider**, stored and deleted
  separately, encrypted with the system keystore, never written to logs, never exported with settings.
- **Upload in fixed 5-minute chunks (about 9.6 MB each).** Why fixed length rather than silence:
  silence-based splitting (VAD) has to decode the whole track on the phone first, which is exactly what
  *On-device* already does — the cloud path would save no time at all. Why 5 minutes: 16000 Hz × 2 bytes ×
  300 s = 9.6 MB, which stays under the common 10 MB per-request limit; one oversized chunk fails the
  **whole** recognition, so the number is chosen so as never to touch the line. The chunks are WAV files
  assembled by hand (16 kHz mono, so no dependency on the device having an AAC encoder, one less
  device-specific failure mode), written to the cache directory and deleted when the run finishes.
- **Timestamps: per sentence when the provider offers them, one block otherwise.** The request asks for
  `verbose_json` when possible: with `segments` each sentence becomes its own cue, and with only `text`
  the whole chunk becomes **one** cue — **neither is a failure**, these providers simply differ. Two
  measured details: `start` / `end` are **floating-point seconds** (multiply by 1000, then add the chunk
  offset; treating them as milliseconds squeezes every cue to the start of the clip), and each `text`
  carries a **leading space** (cleaning goes through the same `AsrTextNormalizer` as the on-device path so
  both produce the same style).
- **Failure reasons are split by "what do I do next"** (10 of them):

  | What the UI says | What to do next |
  | --- | --- |
  | Cannot reach the recognition service | Try another network / retry later (this chunk cost nothing) |
  | "%s" rejected the request — API key missing or wrong | Fill it in or replace it on the settings page |
  | The provider rejected it by **account policy** (403) | Switch provider — **a new key will not help** |
  | There is **no** recognition endpoint at this address (404) | Fix the address — most compatible services need the path to include `/v1` |
  | The base URL is not filled in yet | Finish it, or switch back to on-device |
  | The provider does not accept this request (400) | Switch model or preset (a common cause: this model cannot return timestamps) |
  | This chunk exceeds the size limit (413) | Switch to on-device (no upload, no size limit) |
  | Quota exhausted or rate limited (429) | Top up / switch provider (`Retry-After` is surfaced) |
  | The provider itself is failing (5xx) | Retry later — nothing local can help |
  | The response could not be read (200 but no text) | Switch preset / file a bug (typically a relay that wrapped the body) |

  Collapsing these into one "cloud recognition failed" has a very concrete cost: **401 and 403 become
  the same sentence and the user keeps re-entering the key**, while a 403 is an account decision that no
  key will ever fix. When the provider gives no reason the message does **not** end in a dangling colon
  (it says "the provider did not explain why"), and the full body still goes to the log.
- **Two privacy notices, both before the button is pressed**: one where cloud is selected in settings, and
  **another right above the button on the player page** ("Cloud recognition: the whole audio will be
  uploaded to “provider”; recognition starts only after the upload finishes."). The button wording also
  changed from "Generate subtitles" to **"Upload and generate subtitles"** — that is the last place where
  the user can change their mind.
- **One generated subtitle per media file, re-running with another engine overwrites it.** The file name
  is a hash of the media address, independent of the path taken. Keeping two would put two identically
  named candidates in the panel (differing only in how the timeline was cut) with no way for the user to
  tell them apart, and a generated subtitle is by nature something that can be produced again —
  overwriting it destroys no user work.
- **A failure never leaves half a result behind**: if chunk 3 fails to upload, the first two chunks are
  **not** written out, so what remains on disk is the last complete subtitle rather than a partial one
  covering only the first 7 minutes.

### 1.11 App updates

Row 8 of the Settings hub is *Check for updates*, which opens a page with three sections:
**Current version** (a *Check* button on the right, and it also checks once when you enter the page),
**Update available** (only shown when one is really found: tag, publish time, the full release notes,
*Download and install* / *Ignore this version*), and **Update settings**
(update channel / check automatically / GitHub token).

- **Source:** GitHub Releases (`Aaze-wu/MultiSuperPlayer`) over the anonymous API.
- **The token is optional.** It works without one; supplying one only raises the limit from
  60 to 5000 requests per hour. It is stored on this device only, encrypted with the system
  keystore, and never uploaded — the page says so right next to the field.

#### The check layer does not know about GitHub

Other channels are planned, so there is **no GitHub anywhere** in the check logic:

```
UpdateSource (interface)   <- only listReleases(): List<UpdateRelease>
    ^
GitHubReleasesSource       <- the only place that knows GitHub's JSON shape
    ^
UpdateManager              <- comparison, throttling, ignore, settings, all live here
```

`UpdateRelease` is channel-neutral: tag, version, whether it is a pre-release, publish time,
notes, and **one (or zero) APK asset** (url / size / sha256). A new channel means a new
`UpdateSource` implementation and nothing above it changes.

#### Four states, four different sentences

`UpdateRules.decide(current, releases, channel, ignoredTag)` is a **pure function with zero
Android dependencies**:

| State | What the page says |
| --- | --- |
| `NotChecked` | Not checked yet · Current version x.y.z |
| `UpToDate` | Up to date · Current version x.y.z |
| `Available(release)` | vX is available · Current version x.y.z |
| `Ignored(release)` | This version is ignored · Current version x.y.z |

Two of these are not arbitrary:

- **"Not checked yet" and "up to date" are not the same thing.** The automatic check is
  throttled to **12 hours**; when it is skipped it returns `null` and the page **changes nothing**.
  Treating a skip as "up to date" would wipe out the "an update is available" you found last
  time every time you open this page — and that is the single most important line on it.
- **When the current version will not parse, do not guess.** `UpdateVersion.parse` failing means
  `NotChecked` outright, rather than comparing a made-up `0.0.0` (which would make every single
  release look like an update).

#### Download and install: four layers

An upgrade package is something you are about to **hand to the system installer**, so the risk
profile is not the same as downloading a model:

1. **Write `<name>.part` first and only rename once verification passes.** Writing straight to the
   final name means one interruption leaves a "right size, wrong content" APK that the next visit
   mistakes for a completed download — and the failure only surfaces later as "there was a problem
   parsing the package" in the system installer, by which point nobody suspects the download.
   A rename within the same directory is atomic, so **the final name exists iff the content is whole**.
2. **A failure or a cancel must delete the `.part`**, otherwise the user sees "tens of MB occupied
   by nothing", taps download again, and occupies tens of MB more. The cleanup lives in one
   `catch (Throwable)` so **any failure path added later inherits the rule automatically**.
3. **Verification is sha256**, not "does it open" and not "is the size right". If the release
   publishes a sha256 we compare it; if it does not, the log says the check was skipped —
   layer 4 still backstops it.
4. **Package name and signature are checked before the installer is ever started**: only an APK
   whose package name *and* signing certificate match what is already installed is allowed through.
   A different package name installs a different app; a different signature would be rejected by
   the system anyway, but only after the user has tapped *Install* and got
   `INSTALL_FAILED_UPDATE_INCOMPATIBLE` — better to stop it ourselves and say why.

Also: when the server reports a length, the release reports a length, and **they disagree**, it
fails immediately instead of reading 140 MB and then failing the digest — and the message becomes
something that points at the real cause instead of "checksum mismatch".

#### Two system gates

- **The "install unknown apps" grant.** Since Android 8 an app must first flip that switch in the
  system settings before it can launch the installer. When it is not granted the page shows a
  notice plus *Allow*, which jumps straight to this app's grant screen. The state is **re-read from
  the system every time the page resumes** instead of being cached locally.
- **`FileProvider`.** The installer cannot read our private directory, so the APK has to be handed
  over as a `content://` URI. `update_apk_paths.xml` exposes **only the `cache/updates/` directory** —
  exposing another directory, or all of `cache/`, makes the installer call throw
  `Failed to find configured root`. It lives under `cacheDir` rather than `filesDir` because an
  upgrade package is useless once installed, so the system may reclaim it when space runs low.

#### Failures are reported separately

`UpdateFailureText` has eight values: network / rate limited / not found / server error /
no asset / download corrupted / signature mismatch / no installer. **Collapsing them into one
"update failed" has a very concrete cost**: "GitHub rate limited" and "no network" become the same
sentence, while the first wants "wait a bit, or add a token" and the second wants "check your
connection" — send the user the wrong way and it never gets fixed.

### 1.12 Roadmap

| Version | Content | Status |
| --- | --- | --- |
| v0.1 | Module skeleton, media library, subtitle parsers, playback engine, theming | Done |
| v0.2 | Subtitle discovery, matching, parsing, rendering | Done |
| v0.3 | Subtitle translation (local + cloud APIs) | Done |
| v0.4 | FFmpeg software decoding, automatic fallback, force-software switch | Done |
| v0.5 | Playback: fullscreen/landscape, aspect modes, gestures, speed, A-B repeat, resume | Done |
| v0.5.5 | Localization + documentation | Done |
| v0.5.6 | Media library rework: five-tab navigation, mini player, SAF folder browsing, recent, playlists | Done |
| **v0.5.7** | **Built-in file browser: own directory listing, optional all-files access, attach a tapped subtitle file** | Done |
| **v0.5.8** | **Media opened from the browser discovers sibling subtitles (by listing the directory, not the media library)** | Done |
| v0.5.9 | Multi-select in the built-in file browser (select all / add to playlist / play) | Done |
| **v0.5.10** | **Recent page keeps short clips, new "Record recent plays" switch, auto-refresh on return** | Done |
| **v0.5.11** | **Recent page: delete one entry (undoable) and clear all (confirmed)** | Done |
| **v0.5.12** | **Portrait player gets a *Collapse* button top-left (a visible exit that keeps playing)** | Done |
| **v0.5.13** | **Landscape layout dedicated to audio: cover left, lyrics right, permanent control strip (also fixes controls that could not be brought back)** | Done |
| **v0.5.14** | **"Choose subtitle file…" in the subtitle panel; previous / next buttons on the bottom bar; fixes playback controls never appearing in the notification (the Media3 session was never registered)** | Done |
| **v0.5.15** | **Subtitle tracks muxed into the container are fed into our own subtitle layer (including the fix that reads the format from `codecs`); audio / embedded-subtitle track pickers; subtitle timeline offset ±** | Done |
| **v0.5.16** | **Subtitle style: presets for text size / line spacing / outline / bottom margin, edited from either the player or the settings page (one shared setting, applied immediately), with a one-tap reset (the default presets match v0.5.15's rendering parameters exactly)** | Done |
| **v0.5.17** | **Embedded subtitles are no longer drawn twice (the `SubtitleView` inside `PlayerView` is hidden), which also makes display mode *Hidden* actually hide; the subtitle panel now tells "no external subtitle attached" apart from "embedded tracks exist but no line has been read yet"; the automatic embedded-track pick now logs why it did or did not act** | Done |
| **v0.5.18** | **Sleep timer (5 min – 1 h 30 m / *until the end of this item*, with a live countdown and a way to cancel it); picture-in-picture (auto-enters on HOME, or manually from the player page, with a play/pause action in the small window); playback queue panel (tap a row to jump, remove one item, drag the handle to reorder, clear the whole queue), with the queue order kept strictly in sync with the underlying playlist** | Done |
| **v0.5.19** | **Sleep timer gains a "Custom…" duration (hours / minutes fields, 1 minute to 24 hours; *Set* stays disabled for invalid or out-of-range input, and an empty form is not treated as an error; a custom duration shows up on the player chip just like a preset, e.g. "3 h 20 m")** | Done |
| **v0.6** | **On-device offline ASR subtitle generation: incremental download with per-file sha256 verification, deleting a model leaves generated subtitles alone, editable download source (empty = mirror), generated subtitles stored in a private directory and attached and selected automatically** | Done |
| **v0.6.1** | **"About" page rework, help question marks on settings entries, more professional wording; release signing wired up with a build-time signature self-check** | Done |
| **v0.6.2** | **Multi-language support (Japanese first): a dedicated Japanese offline model (ReazonSpeech) to download; automatic Shift-JIS (CP932) detection for subtitle files; prompt examples generated per target language; automatic collapsing of spaces between Japanese kana** | Done |
| **v0.6.3** | **Reordering and transport fixes: playlists and the items inside them can be reordered by long-pressing a whole row (the order is saved, a new playlist goes last); multi-select in the library and file browser follows the order you tapped the items in; fixed the transport row overflowing on 360dp-wide screens (the last button was squeezed into a sliver); cleartext `http://` is allowed (a NAS on your LAN, a local LLM server)** | Done |
| **v0.6.4** | **On-device offline translation: the provider list gains *Local (runs on this device)* (Qwen3-0.6B, about 345 MB, downloaded on demand with sha256 verification and a delete-that-model-only action); constrained decoding pins the output shape down (including the item count), fixing two "it can never work on a real device" bugs (a benchmark query that always throws was treated as a failed generation, and 0.6B merging a whole batch into one array element)** | Done |
| **v0.6.5** | **Cloud speech recognition subtitle generation: *Recognition method* gains *Cloud* (OpenAI-compatible `/audio/transcriptions`, four presets plus custom); upload in fixed 5-minute chunks (about 9.6 MB each) with per-sentence timestamps when `segments` are returned and one cue per chunk otherwise; 20 failure classes split by "what to do next"; the address field shows the final request URL live and an unfinished address greys out the player button; two privacy notices** | Done |
| **v0.6.6** | **More translation models: on-device gains *Tencent Hunyuan HY-MT2-1.8B* (int8, about 1.7 GB, translation-specialised, offered as an optional high-quality tier while 0.6B stays the default); the memory requirement is stated before the download and one extra hint appears when it exceeds 40% of the device's total memory (a hint, never a block); the Ollama preset now defaults to Hunyuan HY-MT1.5-1.8B; target languages go 5 → 15 (Russian / Spanish / French / German / Portuguese / Italian / Arabic / Thai / Vietnamese / Indonesian added)** | Done |
| **v0.6.7** | **Permissions: a new *Permissions* page in Settings lists the four things the app can ask for (media read / all files access / notifications / Bluetooth) with their state and action, plus a collapsible note for the six permissions granted at install time; the first launch after install asks once for notifications and media read (not for all files access or Bluetooth); the library re-scans itself when a permission was granted elsewhere and the app comes back to the foreground** | Done |
| **v0.6.8** | **Background keep-alive: a new *Background keep-alive* page in Settings requests the battery-optimisation exemption with one tap (the switch re-reads the system state every time the page is resumed instead of keeping a local copy) and opens the vendor's own background-management page (Xiaomi / Huawei / Honor / OPPO / vivo / Meizu / Samsung / OnePlus, falling back to the app info page for unknown vendors); the permissions page's collapsible section gains *ignore battery optimizations*** | Done |
| **v0.7.0-alpha.1** | **App updates: a new *Check for updates* page in Settings checks, downloads and installs new versions from GitHub Releases (the source sits behind an interface so more channels can be added); sha256 plus package-name and signature verification; the APK is handed to the installer through a `FileProvider`; an optional GitHub token stored on-device and encrypted; eight failure classes reported separately** | Done |
| **v0.7.0-alpha.2** | **Embedded-subtitle wording fixes: the title slot now reads *Embedded subtitle 1* instead of a bare language tag, a *No line read yet* hint covers the window before the first cue arrives, and *This file has subtitle tracks; pick one above.* replaces the blanket *nothing is attached* claim; a selected embedded text track is now claimed as soon as the track list arrives instead of waiting for its first cue** | Done |
| **v0.8.0-alpha.1** | **Subtitle rate (proportional nudging: five presets plus ±0.01 / ±0.10, the counterpart to the timeline offset's *shift*) + a full read-ahead of the embedded subtitle track (the prerequisite for the rate to work in both directions, and for subtitles to be there the moment you open a file) + a rework of the lyrics space on the audio page (the portrait artwork gives way to the lyrics, and the chips and transport controls move into the left column in landscape)** | **Current** |
| Later | drag-to-reorder auto-scroll / equalizer (**not scheduled yet**) -> **0.9.0** bitmap subtitle formats (PGS / VobSub / DVB) -> **1.0** stable; audio translation (dubbing) lands after stable | Planned |

---

## 2. Building and running

### 2.1 Requirements

| Item | Version | Notes |
| --- | --- | --- |
| JDK | **21** | See the JDK pitfall below. Source `jvmTarget` is 17. |
| Android SDK | **compileSdk 36** (`android-36` installed) | `minSdk 26`, `targetSdk 36` |
| Gradle | 8.14.3 (via wrapper) | Distribution served from a Tencent Cloud mirror |
| AGP / Kotlin | 8.13.2 / 2.4.20 | See `gradle/libs.versions.toml` |

#### JDK pitfall (read this first)

**The Gradle daemon must run on JDK 21.** On JDK 25 the build fails, and all it prints is a bare version number:

```text
FAILURE: Build failed with an exception.
* What went wrong:
25.0.1
```

Fix it by adding the following to your **user-level** `~/.gradle/gradle.properties`
(on Windows: `%USERPROFILE%\.gradle\gradle.properties`):

```properties
# Forward slashes are required: backslash is an escape character in .properties,
# so C:\Program Files is silently eaten into C:Program Files
org.gradle.java.home=C:/Program Files/Microsoft/jdk-21.0.8.9-hotspot
# Also register 21 as a known toolchain, so Android Studio's "Gradle JVM criteria"
# (Version: 21) doesn't depend on JAVA_HOME happening to point at 21
org.gradle.java.installations.paths=C:/Program Files/Microsoft/jdk-21.0.8.9-hotspot
```

This goes in the user-level file rather than the project one so that no absolute local path ends up in
version control (`gradle.properties` has a comment saying exactly that). On the Android Studio side:
Settings → Build, Execution, Deployment → Build Tools → Gradle → set "Default Gradle JVM criteria" to
**21**; the auto-detected 25 makes sync fail outright.

> Note the distinction: `JAVA_HOME` only chooses the **launcher** JVM, while `org.gradle.java.home`
> chooses the **daemon** JVM. Having `JAVA_HOME` point at 25 builds fine here because the daemon is pinned to 21.

### 2.2 Mirrors

Everything downloads through mirrors, so no extra configuration is needed where they matter most:

- Gradle distribution: `mirrors.cloud.tencent.com` (`gradle/wrapper/gradle-wrapper.properties`)
- Maven repositories: Aliyun's `gradle-plugin` / `public` / `google`, with the official repositories as
  fallback (`settings.gradle.kts`). Gradle tries them in order, so "the mirror doesn't have this new
  version yet" just falls through instead of failing.
- The only dependency that needs a non-standard repository is `io.github.anilbeesetti:nextlib-media3ext`,
  and it is published on **Maven Central** — so jitpack.io is **not** needed.

### 2.3 Common commands

```bash
# Compile every module
./gradlew assembleDebug

# Compile only (much faster; use this while editing)
./gradlew compileDebugKotlin

# All unit tests
./gradlew test

# A single module's unit tests
./gradlew :core:translate:testDebugUnitTest

# Install to a connected device or emulator
./gradlew installDebug
```

On Windows PowerShell:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-25'
.\gradlew :app:assembleDebug
```

### 2.4 Release signing

Copy `keystore.properties.example` to `keystore.properties`, fill in the real values, and put it in the
repository root (it is already in `.gitignore`). **When the file is missing, release builds silently fall
back to debug signing** so that a fresh clone or a CI run still produces an installable package instead of
failing outright.

### 2.5 One source of truth for the version

The version lives in `appVersionName` at the top of `app/build.gradle.kts`; `versionCode` and BuildConfig
are both derived from it:

```text
versionCode = major * 10000 + minor * 100 + patch      // 0.6.0 -> 600
```

Do not write a second copy of the number in `defaultConfig`. That was how it used to work, and the result
was that git had already tagged v0.5.3 while the package still declared 0.4.0 — **and nothing reported it**.
The build also injects the commit, tag and build time into BuildConfig using `git describe` /
`git rev-parse`, for the About screen. If `git` is not on `PATH`, everything degrades to "unknown" rather
than failing the build.

### 2.6 Test media

`tools/make-test-media.ps1` generates a fixed set of verification assets with ffmpeg (including silent
gaps, bilingual lines, word-by-word LRC, GBK-encoded files and CJK filenames). Typing the ffmpeg commands
by hand always drops one or two of them — and the dropped one is precisely what you were about to test.

```powershell
pwsh -File tools\make-test-media.ps1
pwsh -File tools\make-test-media.ps1 -OutputDir D:\tmp\media -FfmpegPath D:\ffmpeg-build\bin\ffmpeg.exe
```

Output goes to `%TEMP%` by default and is not committed.

---

## 3. Project structure

```text
app/                     App shell: theme wiring, bottom navigation, nav graph, Application/Activity
core/
  common/                Utilities, logging, Result, dispatchers, formatting, MspText
  model/                 Domain models (media entries, subtitles, tracks, playback state) - no module deps
  data/                  Data sources (MediaStore scanning, SAF, settings persistence)
  subtitle/              Subtitle/lyrics parsing engine (pure logic, unit-testable)
  translate/             Subtitle translation (provider presets, batching/cache/glossary, export)
  asr/                   On-device speech recognition (sherpa-onnx: VAD segmentation + offline Paraformer / Zipformer, including a Japanese-only model)
  ui/                    Design system and theming (dynamic color, presets, custom accents)
  player/                Media3 playback wrapper and playback service
feature/
  library/               Media library browsing
  player/                Player screen (audio/video + lyrics/subtitles + translation panel)
  settings/              Settings (theme, language, playback, translation, about)
tools/                   Development helper scripts
```

### Dependency direction

```mermaid
graph TD
    app[":app"]
    fl[":feature:library"]
    fp[":feature:player"]
    fs[":feature:settings"]

    cc[":core:common"]
    cm[":core:model"]
    cu[":core:ui"]
    cs[":core:subtitle"]
    ct[":core:translate"]
    cp[":core:player"]
    cd[":core:data"]
    ca[:":core:asr"]

    app --> fl
    app --> fp
    app --> fs
    app --> cc
    app --> cm
    app --> cu
    app --> cd
    app --> cp
    app --> cs
    app --> ca

    fl --> cc
    fl --> cm
    fl --> cu
    fl --> cd

    fp --> cc
    fp --> cm
    fp --> cu
    fp --> cd
    fp --> cs
    fp --> cp

    fs --> cc
    fs --> cm
    fs --> cu
    fs --> cd
    fs --> ca

    cu --> cc
    cu --> cm

    cs --> cm
    cs --> cc

    ct --> cc
    ct --> cm

    cp --> cc
    cp --> cm

    cd --> cm
    cd --> cc
    cd --> cs
    cd --> ct
    cd --> cp
    cd --> ca
```

Rules that must not be broken:

1. **`core:model` stays free of module dependencies.** It is the shared vocabulary; the moment it depends
   on something else, the graph gains a cycle. The practical cost of this rule is listed under
   "Known limitations" below.
2. **`feature:*` modules never depend on each other**, and never on `:app` (`:app` depends on them).
   Anything shared across feature domains belongs in `core:*` or travels by navigation.
3. **Only `:app` is a `com.android.application`**; the other eleven modules are `com.android.library`.
4. **`android.nonTransitiveRClass=true`**: modules **cannot reference each other's `R` classes.**
   If two modules need the same sentence, each keeps its own copy (for example `core:common`'s
   `msp_wrapped_in_parens` and `feature:player`'s `msp_player_wrapped_in_parens` are the same sentence).
   It looks like duplication, but under this compile-time constraint it is the only approach that cannot break.

### Why user-facing text is `MspText`, not `String`

`Resources` is unusable in JVM unit tests — `testOptions { unitTests.isReturnDefaultValues = true }` makes
every `android.*` call return 0/null. If the pure-logic layers returned `String`, then "what does this
sentence look like in another language" could only be inspected on a device, and not a single assertion
about it could be written.

So the pure-logic layers (`core:common` / `core:player` / `core:translate` / `core:data` / the ViewModels)
return `MspText`: either `Plain` (language-independent by nature, e.g. the format name `SubRip`), or
`Res(id, args)`, which is resolved **at the UI boundary only**. Compose code always goes through

```kotlin
Text(row.label.string())          // import com.multisuperplayer.core.ui.text.string
```

When a sentence is assembled from several parts, do **not** use `buildString` or `"$a · $b"` — the
separator itself is part of the language (Chinese uses `·`, English uses `,`), and the order of the parts
may need to change in another language. Use:

```kotlin
MspText.join(SEPARATOR, listOf(partA, partB, partC))
```

---

## 4. Tests

```bash
./gradlew test
```

- Everything is a **JVM unit test** (JUnit 4); no device or emulator is needed.
- What is covered: subtitle parsers, translation failure classification, batching and caching, export,
  theme enums, settings summary text, and a few **repository-level guards** — for instance "every module
  with a `values/strings.xml` must also have `values-en/` and `values-b+zh+Hant/`". That guard exists
  because the symptom of a missing directory is that switching the app language simply **does nothing**,
  with no error anywhere, and the compiler cannot see it.
- Anything that needs a device (real decoding, real gestures, real SAF) is verified by hand. The
  procedures are recorded in `tools/` and in the KDoc of the relevant modules.

---

## 5. Known limitations

These are deliberate for this release, not oversights:

1. **Some legacy formats still do not play.** NextLib provides **decoders** (codecs), not **demuxers**
   (containers). So "this machine has no decoder for it" can be rescued by it, while "the container format
   is not recognized" cannot: WMV/ASF and RealMedia (RM/RMVB) remain unsupported, and upgrading the library
   will not change that.
2. **DTS / AC-3 inside Matroska is dropped by Media3's extractor**: you get a progress bar, no audio,
   and **no error**. This is upstream behaviour and the project's error mapping cannot see it.
3. **Only `arm64-v8a` and `x86_64` ABIs are kept.** Bundling all four roughly doubles the APK
   (measured uncompressed: 7.5 MB for arm64-v8a, 11.1 MB for x86_64). The cost is that 32-bit devices
   **cannot install it** — the installer refuses outright, rather than installing something that doesn't
   work. Supporting them properly means ABI splits or an AAB.
   The speech recognition engine's native libraries (`core/asr/src/main/jniLibs/`) **keep only these two
   ABIs as well**: the other two (about 57 MB) can never reach the APK, so there is no reason to carry
   them in the repository. To support 32-bit devices, extract them from `sherpa-onnx-1.13.8.aar`
   (`jni/<abi>/`) and put them back — directory and file names must match upstream, JNI loads by name.
4. **`core:subtitle` parse warnings are still Chinese.** The warning type is `List<String>`, tied to the
   **dependency-free `core:model`**. The right fix is to move `MspText` into `core:model` (which is itself
   dependency-free, so it fits that module's role) and make the warnings `List<MspText>`. Deferred.
5. **Machine-readable skeletons in the log report and in exported subtitles are deliberately not
   translated.** The log report's header lines (produced by the UI) follow the UI language, but the
   `===== ... =====` and `导出时间:` skeleton stays fixed: it exists to be searched by keyword while
   debugging, and mixing translations in would defeat that. For the same reason the `; 由
   MultiSuperPlayer 导出` provenance line in an exported ASS file is not UI text.
6. **The translation target's "name shown to the model" is fixed to Chinese**
   (`TranslationTarget.promptName`). It describes *what to translate into*, is independent of the UI
   language, and pinning it is what keeps the cache key stable.
7. **Sorting the library by title uses Unicode code points for Chinese, not pinyin order.** Pinyin
   ordering needs an extra mapping table (otherwise 张 sorts after 王), and the cost is out of proportion
   to the benefit. Equal titles fall back to sorting by id, which at least keeps the order **stable** —
   it does not change on every refresh.
8. **No artwork in lists, only type icons (audio / video).** Doing it properly means adding an image
   loading library and owning decode, caching and OOM; this release chooses not to, rather than shipping
   a version that OOMs.
9. **Library entries on Android 9 and below do not auto-discover sibling subtitles.** `RELATIVE_PATH`
   only exists from Android 10, and without it auto-discovery has no starting point for those entries.
   The UI **states the reason** ("cannot tell which folder this file is in, so same-name
   subtitles cannot be found automatically") instead of pretending "this folder has no subtitles"; you
   can still pick a sibling `.srt` / `.ass` by hand in the sheet, and that path works.
   (Media opened through the built-in browser is **not affected**: it takes the "list the directory"
   route, see section 1.7.)
10. **The built-in browser writes nothing to the media library.** Browsing is browsing: files opened
    this way do not enter the library or the Recent list. Since v0.5.9 it **does take part in
    multi-select** (it can "add to playlist" exactly like the library), but the selected entries still
    are not written back to the library — "add to playlist" writes to the playlist table, a different
    table.
11. **Playlist entries do not keep a "display name".** Entries are stored by id key, so restoring one
    only brings back the title / artist / duration snapshot fields. What this costs is the **subtitle
    discovery log line**: it writes "looking for subtitles for xxx", and for an entry restored from a
    playlist that one field is missing (everything else is there). Fixing it means changing the storage
    format and writing a migration, which is disproportionate for one name in one log line.
12. **Bitmap subtitles inside the file (PGS / VobSub / DVB) never show up in the panel.** Our own subtitle
    layer draws text, and a bitmap `Cue` carries an empty `text`, so there is nothing to lay out at all.
    The only renderer that can draw bitmaps is Media3's own `SubtitleView` (the one inside `PlayerView`) —
    and the player screen hides exactly that one, so that embedded **text** subtitles are not drawn twice
    (see "Subtitles that ship inside the file" above). The panel therefore lists **text** embedded tracks
    only: a bitmap track neither appears nor can be selected, and the panel does not explain why (it simply
    looks like "this file has no embedded subtitles"). Supporting it for real means giving bitmap tracks a
    rendering path of their own, at the cost of two subtitle layers having to agree on "who drew text at
    which instant". Left for a later version.
13. **The on-device speech recognition models are not bundled into the APK; the user downloads them.**
    The two models are about 78.1 MB and 189.8 MB, and putting 78 MB into the installer would make
    everyone who does not want this feature wait through an extra download, so the flow is "pick a model,
    then download it". The cost is that the first run needs a download first (measured: 78.1 MB from the
    mirror in about a minute), and **without a network the model cannot be installed** (recognition
    itself needs no network). Recognition runs on the CPU, so speed depends on the phone (about 8 seconds
    for a 33-second clip on an x86_64 emulator) and scales with length; the result is computed **in one
    go** — there is no "stream the first half while playback continues" mode.
14. **Recognition quality is entirely a property of the source audio.** Pure music, heavy background
    noise, several people talking over each other and strong dialects can come out empty or misspelled,
    and **none of that is corrected automatically** — the sheet cannot even show "this line had low
    confidence", because the result is just an ordinary SRT. Improving it means running recognition
    again, switching models, or overriding it with an external subtitle.
15. **Dragging does not auto-scroll.** Dragging a row to the very edge of the screen does not scroll
    the list, so a target off screen means scrolling near it first. A "keep scrolling while near the
    edge" loop is the obvious fix, but it feeds back into `LazyListState`'s visible-items callback
    (scroll a bit, row height changes, the drop target changes) and that cycle has to be broken
    cleanly first. Left for a later release.
16. **The on-device translation model is not bundled either, and it runs on the CPU only.** It is about
    345 MB, the same trade as the speech recognition models: bundling it would make everyone who does not
    want the feature wait through an extra download. The cost is that the first run needs the download
    to finish first (measured: about 83 seconds from the mirror), and **without a network the model
    cannot be installed** (the translation itself needs no network). Inference is fixed to the CPU
    backend, which means no per-device tuning and reproducible output at the cost of speed. There is also
    currently **only one model to choose from**, and the translation cache has **no "clear" action** —
    forcing a re-translation means editing the subtitle content, or switching target language / model.
    Note that **"the model is not bundled" does not mean "the installer did not grow"**: to make the
    on-device inference run at all, the installer went from about 88.6 MiB in v0.6.3 to about 134.7 MiB
    (that is `liblitertlm_jni.so`, about 45.6 MiB across both ABIs, stored uncompressed).
17. **Cloud recognition supports OpenAI-compatible `/audio/transcriptions` only.** Services such as
    Alibaba Cloud Qwen-ASR, which push the audio through `…/compatible-mode/v1/chat/completions`, are
    **not supported in this release**: the request shape is a different one and would need its own
    request-building path. Any other service with the same interface shape can be hooked up through
    *Custom*.
18. **Chunking is by fixed length, so a chunk boundary can cut a sentence in half.** In practice that
    means one truncated cue every five minutes. Overlapping chunks would fix it, but the overlap then has
    to be de-duplicated (the same sentence recognised twice with timestamps half a character apart), which
    this release does not do. There is also **no resumable upload** — cancelling mid-run loses the chunks
    already sent (and the quota is spent), and there is no "stream the first half" partial output; the
    result is assembled in one piece at the end.
19. **A container that does not declare its duration cannot use the cloud path.** Chunking needs the
    total length up front, and bare ADTS AAC and some streaming containers do not write one. In that case
    the app **does not** degrade into "send the whole thing as one chunk" (a two-hour video would first
    produce a 230 MB temporary WAV and most likely fill the phone's storage) — it reports the problem and
    suggests switching to on-device recognition instead.
20. **Pre-reading an embedded subtitle track walks the whole file in order, and only works for local
    files.** Subtitle samples are interleaved with the audio and video in the container, so "read the
    whole text track" means reading the file from end to end. A network stream cannot be downloaded for
    the sake of its subtitles, so it is reported as not applicable, and a very large file takes a while.
    Playback **never waits for it** (the streaming table is used until the read finishes), but the panel
    says `Pre-reading subtitles…` while it runs; on failure there is no error, only
    `Could not pre-read subtitles, rate adjustment may be off`, and the **specific reason (unrecognised
    container / track not found / no cues / read error) only ever reaches the log**. The pre-read table
    also **replaces** the streaming one wholesale rather than being published progressively (swapping the
    table mid-playback would shift cue indices, and hand-edited translations are keyed on *index + source
    text*).

---

## 6. License

This project is distributed under the **GNU General Public License v3.0**; see [LICENSE](LICENSE).

That is not an arbitrary choice — it is forced by a dependency: the FFmpeg build that ships with
`io.github.anilbeesetti:nextlib-media3ext` **enables GPL components**. Linking it in means the whole
application must be distributed under GPL-3.0: full source must accompany it, and derivative works must
also be GPL-3.0.

Main third-party dependencies and their licenses:

| Dependency | License |
| --- | --- |
| AndroidX / Jetpack Compose / Media3 | Apache-2.0 |
| Kotlin / kotlinx.coroutines / kotlinx.serialization | Apache-2.0 |
| Koin | Apache-2.0 |
| NextLib (FFmpeg software decoding extension) | **GPL-3.0** |

The "Licenses" section of the About screen lists the components the app actually uses.

---

## 7. Contributing

Issues and pull requests are welcome. Before you start:

- Commit messages and code comments are written in Chinese; identifiers, log keys and resource keys are
  in English.
- **Every new string must also be added to `values-en/` and `values-b+zh+Hant/`**, or the guard test fails.
- Pure-logic layers must not return user-facing text as `String` (see the `MspText` section above).
- Before changing an AndroidX/Compose version in `gradle/libs.versions.toml`, confirm that the target
  version's AAR metadata has `minCompileSdk <= 36` and `minAndroidGradlePluginVersion <= 8.13.2`. Those
  constraints live in `META-INF/com/android/build/gradle/aar-metadata.properties` inside the AAR and are
  **invisible from both the version catalog and the Maven page**; they only blow up during the
  `CheckAarMetadata` task. Likewise, changing `media3` requires changing `nextlib` in lockstep — its
  version scheme is `<the media3 version it supports>-<its own version>`, and a mismatched pair surfaces
  only at **runtime** as `NoSuchMethodError` / `AbstractMethodError`.
