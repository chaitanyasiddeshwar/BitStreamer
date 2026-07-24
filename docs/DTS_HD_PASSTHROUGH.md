# DTS-HD MA Lossless Passthrough — Implementation Plan

**Status:** experimental · branch `dts-hd-experiment`
**Author's source:** cross-player decompile research (`emby/hdr_handling.md` §12–§14)
**Prereq reading:** [AUDIO_PASSTHROUGH.md](AUDIO_PASSTHROUGH.md) (esp. §7 core extraction), and this repo's one rule (`CLAUDE.md`).

---

## 1. Goal & scope

Today BitStreamer plays **DTS-HD Master Audio as a lossy DTS-core downmix** (5.1 / 48 kHz)
via `DtsCoreAudioSink` — always produces sound, but discards the lossless MA extension and
the 7.1/96 kHz content. ViMu and Kodi instead ship the **full lossless bitstream** by packing
**IEC 61937** frames themselves and writing them to an `AudioTrack` opened as
`ENCODING_IEC61937` — the one path that actually works for apps on Fire TV.

**This plan adds that path for DTS-HD MA**, gated behind a capability probe, with a guaranteed
fallback to today's `DtsCoreAudioSink`. It is an *additive, opt-in-when-supported* enhancement,
not a replacement of the safe default.

### In scope
- A new `Iec61937AudioSink` that packs DTS-HD MA (`audio/vnd.dts.hd`) into IEC 61937 "high
  bit-rate" (HBR / DTS Type IV) bursts and streams them via `ENCODING_IEC61937`.
- Runtime capability probing + automatic selection between the new sink and `DtsCoreAudioSink`.
- Verification on real hardware (AFTKM + an AVR that displays the incoming format).

### Explicitly out of scope (for this branch)
- **TrueHD / Atmos (MAT) packing.** MAT framing for TrueHD is materially harder than DTS-HD
  bursts (§13 of the research). Deferred to a follow-up (see §9, Phase 5). Note: TrueHD is not
  currently carried losslessly at all — that's a separate gap, not a regression.
- **Any server change.** No transcoding, no remux, no sample rewriting server-side. `dvProfile`
  and audio metadata already come from `ffprobe`; nothing new is needed on the server.
- **DTS:X.** DTS:X rides on a DTS-HD MA substream; if the HBR burst carries it transparently we
  get it for free, but we do **not** special-case it in this plan.

## 2. Does this violate "never transcode / never decode audio in software"? No.

IEC 61937 packing **re-frames an already-compressed bitstream** — it wraps the DTS-HD frames in
a preamble + padding so an AVR can recover them over HDMI. No decode-to-PCM, no re-encode, no
sample math on audio content. This is exactly what Kodi's `CAEBitstreamPacker` and ViMu's
native engine do (research §12). It stays inside the one rule. The honest change it *does*
introduce: we move from "ask Android to passthrough" to "we own the AudioTrack and the burst
framing" — a real increase in audio-path ownership. Budget hardware test time accordingly.

## 3. Why the direct/simple paths don't work (recap of the research)

- **`ENCODING_DTS_HD` direct passthrough is a lie on Fire TV.** AFTKM advertises DTS-HD in its
  HDMI caps, the AudioTrack opens without error, and it **plays silence** (documented in
  `DtsCoreAudioSink.kt:225` and matched by Plex reports). That's why `PREFER_DIRECT_DTS_HD =
  false` and we force core extraction today.
- **Media3's `DefaultAudioSink` does not pack IEC 61937** for DTS-HD MA / TrueHD — it hands raw
  encoded frames to Android and relies on the per-codec passthrough encodings, i.e. the path
  that silently fails.
- **`ENCODING_IEC61937` is the generic "here are pre-packed IEC 61937 frames" channel that Fire
  TV honours for apps** where `ENCODING_DTS_HD` does not (research §12 "Why ViMu/Kodi succeed").
  So the fix is: pack the bursts ourselves and write them to an `ENCODING_IEC61937` AudioTrack.

## 4. Target hardware & verification reality

- **Primary target:** Fire TV Stick 4K / 4K Max (AFTKM), Fire OS 8, connected to an AVR (or TV)
  that **displays the incoming audio format** (e.g. shows "DTS-HD MA" / "DTS:X").
- **Emulators cannot verify passthrough** (per `CLAUDE.md`). Every claim of "works" in this plan
  means: verified on the AFTKM with the AVR reporting DTS-HD MA and audible, correct multichannel
  output — not an emulator run, not "compiles."
- All pipeline decisions are logged through `RemoteLog` (tees to `client-logs.txt`), because that
  is the only visibility into the Fire TV without adb.

---

## 5. Current state — what we build on

| Piece | Location | Role |
|---|---|---|
| DTS-core sink (lossy, safe default) | `client/.../playback/DtsCoreAudioSink.kt` | `ForwardingAudioSink` that truncates each DTS-HD sample to its DTS **core** and bitstreams `ENCODING_DTS`. Keep as fallback. |
| Sink wiring (one factory) | `PlayerFactory.kt` `buildAudioSink` (`:258`–`:265`) | Currently `return DtsCoreAudioSink(DefaultAudioSink.Builder(context).build())`. This is the single insertion point. |
| Capability helpers | `client/.../playback/AudioCaps.kt` | HDMI encodings / describe helpers used for logging. Extend with an `ENCODING_IEC61937` probe. |
| Frame parsing | `androidx.media3.extractor.DtsUtil` | `getFrameType`, `getDtsFrameSize` (already used by `DtsCoreAudioSink`); source of frame boundaries and sample counts for burst period. |

The convention (`CLAUDE.md`): **all playback config lives in `PlayerFactory`; never scatter sink
tweaks across activities. Log the negotiated audio pipeline on every start.** The new sink obeys
both.

---

## 6. Architecture

```
PlayerFactory.buildAudioSink(context)
        │
        ├── probe: does this device expose a usable ENCODING_IEC61937 AudioTrack?      (AudioCaps)
        │
        ├── YES → Iec61937AudioSink( fallback = DtsCoreAudioSink(DefaultAudioSink) )
        │            • for audio/vnd.dts.hd  → pack HBR (DTS Type IV) bursts, own AudioTrack @ ENCODING_IEC61937
        │            • for everything else   → delegate to the fallback chain (unchanged behavior)
        │            • on any open/write failure at runtime → permanently delegate to fallback, log it
        │
        └── NO  → DtsCoreAudioSink(DefaultAudioSink)                                    (today's behavior)
```

Key design decisions:

1. **`Iec61937AudioSink` wraps `DtsCoreAudioSink`, not the reverse.** DTS-HD MA is the only format
   it intercepts; every other format (AC3, E-AC3/DD+ Atmos, plain DTS, LPCM) passes straight
   through to the existing chain untouched. This guarantees we cannot regress the formats that
   already work.
2. **It almost certainly must own its own `AudioTrack`.** `ForwardingAudioSink` delegates
   AudioTrack creation to `DefaultAudioSink`, which will not open an `ENCODING_IEC61937` track for
   a DTS-HD format. So for the DTS-HD path the new sink manages its own `AudioTrack` (mirroring
   Kodi's `CAESinkAUDIOTRACK`), while delegating all non-DTS-HD formats to the wrapped sink.
   **Confirming this is Phase 0's spike** (see below) — if a forwarding trick can coerce
   `DefaultAudioSink` into an IEC61937 track, prefer it; otherwise own the track.
3. **Probe-gated, fail-safe.** If the probe says no, or if the IEC track fails to open / write at
   runtime, we fall back to `DtsCoreAudioSink` and log the reason. A device with a broken IEC path
   must degrade to today's working behavior, never to silence.

---

## 7. IEC 61937 framing for DTS-HD — the fiddly core

This is the part that must be gotten exactly right and can only be validated on hardware. Port
the mechanics from Kodi's `CAEBitstreamPacker` (research §12 appendix); the standard is **IEC
61937-5** (DTS) with the **DTS-HD substream / High Bit-Rate (HBR)** transmission mode.

Structure of one IEC 61937 data burst (16-bit words, then payload):

```
Pa = 0xF872   (sync word 1)
Pb = 0x4E1F   (sync word 2)
Pc = burst_info  → data-type field selects the codec/mode (DTS-HD uses the DTS Type IV / HBR code)
Pd = length_code → payload length (DTS-HD encodes length in BYTES with the HBR flag, unlike the
                   bit-length used by AC3/DTS Type I–III — verify against IEC 61937-5)
payload = the DTS-HD frame bytes (endianness/byte-swap per the output AudioTrack format)
padding = zero-stuff up to the burst repetition period
```

Points that must be pinned down during implementation (do **not** hard-code guesses — derive and
then hardware-verify):

- **`Pc` data-type for DTS-HD HBR.** DTS core Types I/II/III use data-type `0x0B` (11) at fixed
  frame sizes (512/1024/2048 samples). DTS-HD is transmitted as the **Type IV / HBR** burst;
  confirm the exact `Pc` value and any "substream present" bits from IEC 61937-5 and Kodi's
  packer, not from memory.
- **Repetition period & output rate.** HBR runs the AudioTrack at a **high IEC rate (typically
  192 kHz)** carrying one DTS-HD frame per burst period. The period is a function of the source
  DTS **core** frame's sample count (parse via `DtsUtil` → samples-per-frame) times the HD
  transmission multiplier. Getting the period wrong yields dropouts or a rejected track.
- **Byte order.** IEC 61937 payloads are commonly 16-bit-word byte-swapped relative to the raw
  stream; the correct swap depends on the AudioTrack PCM framing. Verify with the AVR display.
- **Whole-frame boundaries.** The sink receives whole encoded samples from Media3, but a "sample"
  may contain the core + extension substreams as one blob. For HBR we pass the **entire** DTS-HD
  frame (not just the core) as the burst payload — the opposite of what `DtsCoreAudioSink` does.

> Honesty note: the exact magic numbers above are deliberately left as "confirm from IEC 61937-5 /
> CAEBitstreamPacker." A plan that hard-codes unverified burst constants would be worse than one
> that names precisely which constants to verify and where. This is a hardware-in-the-loop task.

---

## 8. Selection logic (decision table)

Assumes AFTKM unless noted. "IEC ok" = probe found a usable `ENCODING_IEC61937` path.

| # | Source track | `sampleMimeType` | IEC ok? | Chosen sink & action | Result |
|---|---|---|---|---|---|
| 1 | DTS-HD MA | `audio/vnd.dts.hd` | yes | `Iec61937AudioSink` → HBR burst, own AudioTrack | **Lossless DTS-HD MA** |
| 2 | DTS-HD MA | `audio/vnd.dts.hd` | no  | fall back to `DtsCoreAudioSink` → DTS core | DTS 5.1/48k (today) |
| 3 | DTS:X (on MA substream) | `audio/vnd.dts.hd` | yes | same as #1 (transparent in HBR) | DTS:X if AVR decodes it |
| 4 | DTS Express | `audio/vnd.dts.hd`/`…dts.express` | either | delegate → core path (no core → skip, as today) | as today |
| 5 | Plain DTS | `audio/vnd.dts` | — | delegate (unchanged) | DTS passthrough |
| 6 | AC3 / E-AC3 / DD+ Atmos / LPCM | resp. | — | delegate (unchanged) | passthrough as today |
| 7 | TrueHD / Atmos (MAT) | `audio/true-hd` | — | **out of scope**; delegate (currently not carried losslessly) | unchanged |
| 8 | Runtime IEC open/write failure | any | — | permanently switch to fallback + `RemoteLog` | today's behavior, never silence |

Guardrail: the IEC path is entered **only** for DTS-HD MIME types **and** a positive probe.
Everything else is byte-for-byte the current pipeline.

---

## 9. Phased plan

Each phase is independently shippable/abandonable. Stop after any phase and the app still behaves
correctly (fallback intact).

### Phase 0 — Spike & scaffolding (no behavior change)
- Add `AudioCaps.supportsIec61937(context): Boolean` — probe `AudioManager`/`AudioTrack`
  for a usable `ENCODING_IEC61937` output; log the result.
- **Spike:** determine empirically whether `DefaultAudioSink` can be coerced to open an
  `ENCODING_IEC61937` AudioTrack (forwarding approach) or whether we must own the AudioTrack.
  Decide the architecture (§6.2) from the result. Timebox this.
- Create `Iec61937AudioSink` skeleton that, for now, **delegates everything** to a wrapped
  `DtsCoreAudioSink` (pure pass-through) and is **not** yet wired into `PlayerFactory`. Land it
  compiling with unit-test scaffolding.
- **DoD:** builds; probe result visible in `client-logs.txt`; architecture decision recorded here.

### Phase 1 — DTS-HD HBR burst packing (offline-testable core)
- Implement the IEC 61937 HBR packer as a **pure function** over a DTS-HD frame:
  `packDtsHdBurst(frame: ByteBuffer, samplesPerFrame: Int): ByteBuffer` (Pa/Pb/Pc/Pd + payload +
  zero-pad to period; byte-swap as required).
- Use `DtsUtil` to derive the core frame's samples-per-frame for the period.
- **JUnit tests** (no hardware): given known DTS-HD frames/fixtures, assert preamble words, data
  type, length field, total burst size, and padding. This is the one part that *can* be unit
  tested precisely, so cover it hard.
- **DoD:** packer produces bursts whose structure matches IEC 61937-5 fixtures in tests.

### Phase 2 — Own the AudioTrack, wire the sink
- In `Iec61937AudioSink`, for `audio/vnd.dts.hd` + IEC ok: open an `AudioTrack`
  (`ENCODING_IEC61937`, HBR rate, correct channel mask), write packed bursts in `handleBuffer`,
  and implement the required `AudioSink` surface (position, `playToEndOfStream`, `pause`, `flush`,
  `reset`, drain/underrun handling). Keep in-place/no-realloc discipline like `DtsCoreAudioSink`.
- Wire selection into `PlayerFactory.buildAudioSink` per §6.
- Runtime fail-safe: any open/write error → permanent delegate to fallback + `RemoteLog`.
- **DoD (hardware):** on AFTKM + AVR, a DTS-HD MA title shows **"DTS-HD MA"** on the AVR and plays
  correct multichannel audio; `client-logs.txt` shows the IEC path chosen and the negotiated
  encoding/rate.

### Phase 3 — Robustness & UX
- A/V sync check on real content (burst period correctness); seek/pause/resume; app-switch
  recovery (interplay with AUDIO_PASSTHROUGH.md §8); track-change between DTS-HD and other codecs.
- Optional manual override (remote menu / server flag) to force core extraction, mirroring ViMu's
  `prefere_dca_core` (research §12 appendix) — only if hardware testing shows a receiver that
  needs it.
- Update `docs/AUDIO_PASSTHROUGH.md` §7 (replace the "out of scope" note with the shipped design)
  and `CLAUDE.md`'s audio capability list.
- **DoD:** stable across the test matrix (§10); docs updated.

### Phase 4 — Merge decision
- If Phases 1–3 pass on hardware: merge to `main`, note in release notes as an opt-in-when-capable
  enhancement (default remains safe). If not: keep the branch, keep `DtsCoreAudioSink` as the
  shipped default, record findings here.

### Phase 5 — (Future, separate branch) TrueHD/Atmos MAT
- Only if demand justifies the MAT complexity. Same sink family, new burst packer for
  `audio/true-hd`. Explicitly deferred.

---

## 10. Test matrix (hardware)

Run each on **AFTKM + AVR that displays the format**; record the AVR readout and the
`client-logs.txt` pipeline line.

| Content | Expect (IEC ok) | Expect (IEC probe off / forced) |
|---|---|---|
| DTS-HD MA 5.1 | AVR: DTS-HD MA, audible 5.1 | DTS 5.1 core (fallback) |
| DTS-HD MA 7.1 | AVR: DTS-HD MA, 7.1 | DTS 5.1 core (channels folded) |
| DTS-HD MA 96 kHz | AVR: DTS-HD MA, correct | DTS 48k core |
| DTS:X | AVR: DTS:X (if AVR supports) | DTS core |
| Plain DTS | unchanged passthrough | unchanged |
| DD+/Atmos, AC3, LPCM | unchanged passthrough | unchanged |
| Seek / pause / app-switch on a DTS-HD title | no dropouts, recovers | n/a |
| Device with no IEC path | auto-fallback, sound present | n/a |

"Pass" = correct format on the AVR **and** audible, in-sync, correct-channel audio. Silence,
wrong channel count, or A/V drift = fail → fall back.

---

## 11. Risks & mitigations

| Risk | Mitigation |
|---|---|
| Wrong burst constants → silence / rejected track | Derive from IEC 61937-5 + Kodi `CAEBitstreamPacker`; unit-test the packer; **always** probe-gate and fall back. |
| `ENCODING_IEC61937` unsupported/unreliable on some Fire OS builds | Probe at init; runtime fail-safe to `DtsCoreAudioSink`; never regress working formats. |
| Owning the AudioTrack adds a lot of `AudioSink` surface to get right (position, drain, underrun) | Keep scope to DTS-HD only; delegate everything else; mirror Media3 `DefaultAudioSink` semantics; lean on hardware testing for A/V sync. |
| Can't verify without hardware | Explicit in this plan; no "works" claim from emulator; unit-test only the pure packer. |
| Burst period math wrong → A/V drift | Derive period from `DtsUtil` sample counts; verify sync on long content. |
| Scope creep into TrueHD/MAT | Hard out-of-scope; separate Phase 5/branch. |

## 12. Definition of done (branch)

- `Iec61937AudioSink` selected automatically for DTS-HD MA when the probe passes; otherwise the
  app behaves exactly as today.
- On AFTKM + AVR: DTS-HD MA reported as **DTS-HD MA** and audibly correct; fallback verified by
  disabling the probe.
- Pure packer covered by JUnit tests; every pipeline decision logged via `RemoteLog`.
- No server changes; no transcode/decode; other codecs unchanged.
- `docs/AUDIO_PASSTHROUGH.md` and this file reflect the shipped design.

## 13. Open questions (resolve during Phase 0/1)

1. Forwarding vs. self-owned AudioTrack for `ENCODING_IEC61937` (Phase 0 spike).
2. Exact `Pc` data-type and `Pd` length semantics for DTS-HD HBR (IEC 61937-5 / CAEBitstreamPacker).
3. HBR output sample rate and burst repetition period formula for our frame sizes.
4. Payload byte-swap requirement for the chosen AudioTrack PCM framing.
5. Whether DTS:X rides through the HBR burst transparently (nice-to-have, not required).

## 14. References

- **Cross-player research:** `emby/hdr_handling.md` §12 (four-way DTS comparison), §13 (what to
  change / the exact `Iec61937AudioSink` recipe), §14 (priorities). Appendix maps Kodi
  `CAEBitstreamPacker`/`CAESinkAUDIOTRACK`, ViMu `libmediaengine.so` (`spdif`,`bitstream`,
  `ExtractDCACore`), Emby `setTreatDtsHdLikeDts`.
- **This repo:** `docs/AUDIO_PASSTHROUGH.md` §7; `client/.../playback/DtsCoreAudioSink.kt`;
  `PlayerFactory.kt` `buildAudioSink`; `client/.../playback/AudioCaps.kt`.
- **Standards/tools:** IEC 61937-5 (DTS), IEC 60958 framing; `androidx.media3.extractor.DtsUtil`;
  `AudioFormat.ENCODING_IEC61937`.
