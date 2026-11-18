# Forge

A personal fitness tracker for Android: calorie logging by photo/barcode/description, bodyweight
trends with learned maintenance, workout logging from an imported program PDF, wearable data via
Health Connect, and a coach that reasons over your own logs.

**`Forge-1.0.apk`** in this folder is a signed release build — install it directly.

---

## Installing on the vivo X200 Pro

1. Copy `Forge-1.0.apk` to the phone (USB, Google Drive, whatever).
2. Open it with the Files app. OriginOS will ask to allow installs from that source — allow it.
3. If "Install blocked" appears, go to Settings → Security → Install unknown apps and enable it
   for the app you're installing from.
4. vivo phones are aggressive about background restrictions. For the rest timer to keep running
   with the screen off: Settings → Battery → Background power consumption management → Forge →
   Allow high background power consumption.

The APK is signed with a self-signed key (`forge-release.jks`). Keep that file — you need the same
key to install an update over the top, otherwise Android rejects it as a different app.

---

## First run

The app needs **your own Anthropic API key** for anything AI-driven. Get one at
[console.anthropic.com](https://console.anthropic.com) → API keys.

Paste it during onboarding, or later in Settings → Claude connection → Save → Test.

Without a key: manual logging, barcode scanning, weight tracking, workout logging, and all charts
still work. Photo estimates, label reading, PDF import, and the coach do not.

### Cost tiers

Each job runs on the smallest model that does it well, rather than one model for everything —
reading a printed nutrition panel is transcription that Haiku does as well as anything, while
judging portion size from a photo genuinely benefits from a stronger model.

| Job | Economy | Balanced (default) | Maximum |
|---|---|---|---|
| Meal photos | Haiku 4.5 | Sonnet 5 | Opus 5 |
| Nutrition labels | Haiku 4.5 | Haiku 4.5 | Haiku 4.5 |
| Program PDFs | Sonnet 5 | Sonnet 5 | Opus 5 |
| Coach | Haiku 4.5 | Sonnet 5 | Opus 5 |
| **Rough monthly** | **$2-3** | **$4-6** | **$15-25** |

Estimates assume ~4 photo logs and ~3 coach questions a day. Switch tiers in
Settings → Claude connection.

Program PDFs stay on Sonnet even in Economy: a program is imported once, a misread rep scheme
is something you live with for a whole block, and the one-off cost is a few cents.

Labels are always Haiku — it's transcription, and a bigger model buys nothing.

**Balanced is the recommended default.** The estimator's accuracy comes mostly from the
calibration instructions in the prompt and the safety margin on top, not from raw model power,
so the drop from Opus to Sonnet on food photos is small. If you want to check that for yourself,
photograph the same meal twice on different tiers and compare — nothing here is guesswork you
have to take on faith.

### Other things that keep the bill down

- **Meal photos are downscaled to 1000px** on the long edge before sending. Image tokens scale
  with area (roughly width x height / 750), so this is a direct saving with little accuracy cost.
  Nutrition labels go at 1400px because printed small text needs the detail.
- **The coach caches its context.** The evidence base and the conversation history are sent with
  cache breakpoints, so follow-up questions within a few minutes re-read them at a tenth of the
  input price. Asking three questions in one sitting costs far less than three cold ones.
- **Thinking effort is tuned per job** — low for photo estimates, medium for the coach and PDF
  parsing. Thinking tokens bill at the output rate, so this matters more than it looks.
- **Barcodes cost nothing.** Scanning is on-device and Open Food Facts is free; a scanned product
  is also cached locally, so rescanning the same item never hits the network again.

The cheapest habit of all: use the barcode scanner for packaged food and quick-add for repeat
meals, and save photo estimation for food that genuinely needs it.

---

## Connecting the Amazfit Helio strap

The Helio has no public API. The supported path is **Zepp → Health Connect → Forge**:

1. In the **Zepp** app: Profile → Settings → Health Connect → turn on sharing for steps, sleep,
   heart rate, and activity.
2. In **Forge**: Settings → Amazfit / Zepp → Connect, and grant the permissions.
3. Tap **Sync now**. Steps, active calories, sleep, resting HR and workout minutes appear on Today
   and Trends, and the coach can see them.

If sync returns nothing, it is almost always Zepp's Health Connect toggle being off rather than a
problem on the Forge side.

---

## How calories are estimated

Two deliberate layers of upward bias, because under-counting is what quietly ruins a cut:

1. **Prompt-level.** The estimator is instructed to resolve every judgement call upward — assume
   the larger portion, assume more cooking oil, assume the higher-calorie variant when it can't
   tell. It aims at roughly the 80th percentile of what the meal could plausibly be, not the median.
2. **Numeric margin.** A further fixed percentage on top, 12% by default, adjustable 0–40% in
   Settings → Photo estimates.

Protein is never padded — overstating it isn't useful. The entry detail shows the pre-margin figure
so you can see how much padding was applied, and every number is editable before saving.

Scanned nutrition labels are **transcribed, not estimated** — no margin is applied to them.

---

## Photos and privacy — the honest version

You asked whether photos can be analysed without ever being saved or used. The accurate answer:

- **Never trained on.** Anthropic does not train models on API inputs or outputs. This is a
  contractual property of the API, not a setting.
- **Not stored long-term.** API traffic may be retained briefly for abuse monitoring. So the honest
  claim is "not retained long-term and never trained on" — not "never leaves your phone".
- **Your control.** Settings → Photos and privacy → **Keep meal photos** off means Forge analyses
  the image in memory and discards it instead of writing it to disk. Photos it does keep live in
  the app's private storage, which no other app can read, and are deleted with the entry.
- **Barcode scanning is fully on-device.** ML Kit runs locally; only the digits are sent to the
  food database (Open Food Facts), never an image.

There is no way to get a genuinely accurate calorie estimate from a photo without the image leaving
the device — no on-device model is close. If that tradeoff isn't acceptable for a given meal, the
Describe tab estimates from text instead, which sends only your words.

---

## Importing a training program

Train → **Import PDF**. Text-layer PDFs are parsed directly; scanned ones are rendered and read
visually. The program is reconstructed as training days with sets, rep ranges, RIR targets, rest
times, exercise notes and listed substitutions — as prescribed, without "improving" it.

Start a day, and each exercise pre-fills with what you lifted last time so a repeat set is one tap.
The rest timer starts automatically off the prescribed rest for that exercise.

---

## Learned maintenance (TDEE)

The Mifflin–St Jeor estimate is only used until there's enough real data. After roughly two weeks
of consistent logging plus weigh-ins, Forge solves energy balance backwards — what you actually ate
versus what the scale actually did — and uses that instead. Trends → Intake shows which one is
active. This is far more accurate than any predictive equation, and it's what the daily target is
built from.

Weigh in daily if you can. Day-to-day scale noise is water and gut contents; the smoothed trend
line is the thing to react to, and the chart shows both.

---

## Rebuilding from source

Toolchain (installed under `C:\Users\laksh\Android`):

- JDK 21 — `C:\Users\laksh\Android\jdk\jdk-21.0.12.1+1`
- Gradle 8.14.5 — `C:\Users\laksh\Android\gradle\gradle-8.14.5`
- Android SDK — `C:\Users\laksh\Android\Sdk` (compileSdk 36, build-tools 36.1.0)

```bash
JAVA_HOME="C:/Users/laksh/Android/jdk/jdk-21.0.12.1+1" "C:/Users/laksh/Android/gradle/gradle-8.14.5/bin/gradle.bat" :app:assembleRelease
```

Output lands in `app/build/outputs/apk/release/app-release.apk`.

Stack: Kotlin 2.2.10, AGP 8.13.2, Jetpack Compose (BOM 2025.09.01), Room 2.7.2 + KSP, CameraX 1.5.3,
ML Kit barcode scanning, Health Connect 1.1.0, PDFBox-Android, OkHttp. The Anthropic Messages API is
called over raw HTTP rather than via the Java SDK — that SDK targets server-side JVM and is a poor
fit for an APK.

`local.properties`, `keystore.properties` and `*.jks` are gitignored; the keystore password is in
`keystore.properties`.

---

## Not medical advice

The coach gives general training and nutrition guidance drawn from current literature. It is not a
doctor or a dietitian, and it's instructed to say so and defer for anything medical or for
disordered-eating patterns.
