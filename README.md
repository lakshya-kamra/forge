# Forge

A personal fitness app for Android: AI calorie tracking from photos, barcodes and nutrition
labels; an onboarding that measures you and recommends a phase (cut, lean gain, maingain,
maintain, recomp…) with a plan; 24 free, source-verified training programs with automatic
progression; 3D skeletal exercise demos with tutorials from evidence-based coaches; wearables
from almost every brand; and an AI coach — all inside evidence-based safety guardrails.

**Install:** download `Forge-2.0.apk` from the repo's
[Releases](https://github.com/lakshya-kamra/forge/releases) page.

---

## Installing on the vivo X200 Pro

1. Copy the APK to the phone and open it with the Files app; allow installs from that source.
2. **Updating from 1.x:** install straight over the old app. It is signed with the same key, and
   the database upgrades in place — your weigh-ins, food logs and workouts are kept (verified on
   an emulator with real v1 data).
3. vivo is aggressive with background limits. For reminders, the rest timer and background
   wearable sync: Settings → Battery → Background power consumption management → Forge →
   Allow high background power consumption.

The APK is signed with a private key (`forge-release.jks`, not in this repo). Keep it — Android
only accepts updates signed with the same key.

---

## Any AI key

Settings → AI provider. Paste a key from **Anthropic, OpenAI, Google Gemini, OpenRouter, Groq,
xAI, Mistral, DeepSeek, Together**, or any OpenAI-compatible endpoint. Forge detects the provider
from the key (or you pick it), and routes each job to a sensible model for your cost tier:

| Tier | What it does | Rough cost (Anthropic) |
|---|---|---|
| Economy | Cheapest capable model for everything | well under $1 / month |
| Balanced (default) | Stronger model for photos, coach and plans; cheap one for labels | a few $ / month |
| Maximum | Top models for judgement-heavy jobs | ~$15–20 / month |

The screen shows exactly which model each job uses and a live monthly estimate from current
prices. Advanced: override the model per job, or set a custom base URL.

**Cheapest option now is Claude Haiku 5.5** ($0.10 / $0.50 per million tokens) — cheaper than
Gemini Flash-Lite or GPT-5-mini for this app's workload.

Keys are encrypted with an Android Keystore key and excluded from backups. Without a key, logging,
barcodes, the program library, demos, wearables and the rules-based plan all still work.

**Data use:** paid API traffic from these providers is not used for training. Google's *free*
Gemini tier may train on prompts — the AI screen warns you when that applies, since it would
include your meal and body photos.

---

## Onboarding and your plan

Ten steps: basics, training background, health & safety (incl. the 5-question SCOFF eating
screen), tape measurements, optional photos, then:

- **Body fat as a range, never a fake exact number.** US Navy tape formula, plus an AI visual
  estimate if you add photos and an AI key. Ranges are honest about error, and the app notes that
  no method has been validated specifically for South Asian adults.
- **Phase recommendation.** Built-in evidence rules produce the safe options and a default; with
  an AI key the model chooses among *only those options* and explains why. Guardrails clamp the
  numbers either way.
- **Plan:** calories, macros, weekly rate, steps, review date, check-in cadence, and recommended
  programs. Weekly check-ins adjust calories from your real trend and adherence.

Progress (Settings → Progress): measurements over time, Navy body-fat trend, and progress photos
with a pose guide and a side-by-side / slider compare.

---

## Training: no PDF needed

Train → **Browse free programs**: 24 programs, each checked field-by-field against its author's
own free publication, including Starting Strength, StrongLifts 5×5, GZCLP, 5/3/1 for Beginners,
nSuns, Texas Method, Madcow, Candito, Layne Norton's PHAT, Reddit PPL, PHUL, Lyle McDonald's GBR,
Greg Nuckols' beginner programs, Eric Helms' bodybuilding programs, Jeff Nippard's free YouTube
routines (minimalist full body, Ultimate PPL), GVS's Rampage/Ravage and Jeremy Ethier's plan.
Paid programs (PH3, Nippard's Fundamentals etc.) are deliberately excluded.

Adopt one, enter a few starting numbers, and every session prescribes weight × reps per set using
the program's real progression rules (linear, double progression, training-max waves, AMRAP-driven
increases, RIR). PR detection, smart swaps by muscle, and PDF import still work.

---

## 3D exercise demos and tutorials

62 lifts. Each has a rotatable 3D skeleton (drag to orbit, pinch to zoom, front/side/45° views)
animated from verified biomechanics keyframes with inverse kinematics, so feet stay planted and
hands stay on the bar; the working muscles glow on the lifting phase. Below it: cues, common
mistakes, your history and e1RM, and tutorial videos from evidence-based creators (Jeff Nippard,
Squat University, Alan Thrall, Calgary Barbell, Stronger By Science…). All 172 videos were checked
to exist; creators carry their **GVS Science Based Fitness Tier List** rank.

---

## Wearables

| Brand | How it reaches Forge |
|---|---|
| Amazfit/Zepp, Samsung, Pixel Watch/Fitbit, Garmin, Oura, Polar, Xiaomi, Withings, COROS | Health Connect (enable sharing in the brand's app) |
| WHOOP | Health Connect **plus** WHOOP's own API for recovery, HRV, strain (connect with your free personal WHOOP developer app — the screen walks you through it) |
| Apple Watch | **Not possible live on Android** — Apple Watch only pairs with an iPhone. You can import an Apple Health `export.zip`. |
| Huawei | Doesn't write to Health Connect; needs the third-party Health Sync app |

Readiness compares HRV, resting heart rate and sleep only with your own baseline, and says what's
missing rather than inventing a score.

---

## Safety guardrails

Built from a research spec that went through three adversarial reviews (numbers, citations,
safety), grounded in Helms, Schoenfeld, Pelland 2024, Morton 2018, ISSN and others, with source
trust following the GVS tier list. Highlights: calorie floors; deficit and surplus caps by body
fat and training age; 16-week cut limit then maintenance; no deficit for under-18s, pregnancy,
BMI < 18.5 or a positive eating screen; kidney-disease protein cap; crisis detection in the coach
(Tele MANAS 14416); training volume bands and load-jump limits. Not medical advice.

---

## Building from source

Toolchain: JDK 21, Android SDK with platform 37.2, AGP 9.4, Gradle 9.7.1 (wrapper included).

```bash
./gradlew :app:testDebugUnitTest
```

```bash
./gradlew :app:assembleRelease
```

Release signing reads `keystore.properties` (not committed); without it the release build is
debug-signed. Research and sources behind every number: `docs/research/`. Architecture and
ownership: `docs/CONTRACTS.md`.
