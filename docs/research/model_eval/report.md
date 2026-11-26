# Forge model decision: which LLM/VLM for each job

As of 2026-11-21. Inputs: `closed.json` (43 vendor-hosted models), `open.json` (33 open-weight routes), `food_vision_evidence.json`, `safety_json_evidence.json`, and the `safety_*` / `photo_*` rules in `../guardrails.final.json`. Machine-readable grades: `grades.json`.

> **Read this first.** No study, and no test by Forge, has measured any model on Indian home food, Indian nutrition labels or Forge's own safety prompts. Grades are inferences from evidence about related model versions. Where a grade is a judgement, `grades.json` sets `judgement: true`. Prices are the verified list prices in closed.json/open.json; costs are tiny for every sensible choice, so evidence, privacy and safety decide, not price.

## 1. Decision

**Default: run Forge on Anthropic, which Forge already calls natively. Use `claude-sonnet-5-5` for FOOD_PHOTO, LABEL, BODY, COACH and PROGRAM, and `claude-haiku-5-5` for PLAN. Typical month: about $3.75.**

| Job | Model | Grade | $/month |
|---|---|---|---|
| FOOD_PHOTO | `claude-sonnet-5-5` | RECOMMENDED | $1.03 |
| LABEL | `claude-sonnet-5-5` | RECOMMENDED | $0.288 |
| BODY | `claude-sonnet-5-5` | GOOD ENOUGH | $0.015 |
| PLAN | `claude-haiku-5-5` | RECOMMENDED | <$0.001 |
| COACH | `claude-sonnet-5-5` | RECOMMENDED | $2.25 |
| PROGRAM | `claude-sonnet-5-5` | RECOMMENDED | $0.160 |

Why this setup:
- **No training on your data by default.** Anthropic's commercial API is no-train by default, and there is no free-key trap. Gemini's free tier trains on what you send, and Mistral's policy is unclear.
- **Constrained JSON** through `output_config.format`. Numeric bounds stay in the Kotlin clamps, since Anthropic rejects min/max in schemas.
- **Best crisis evidence for COACH.** Claude Sonnet 4.5 ranked top in mPACT (press-reported, unverified), and RAND found Claude gave fewer direct answers to high-risk questions.
- **Sonnet-tier food evidence.** Sonnet scored within 1-3 MedAPE points of Opus (N26), and Claude 3.7 Sonnet reached 23.9% calorie MAPE (C25). Haiku-tier evidence is negative (Haiku 4.5 energy R2 0.23), so Haiku is not used for photos.

Alternatives that also pass every job: `gpt-6.1-terra`/`gpt-6.2-sol` (OpenAI, no-train, strict schema), or `gemini-3.8-flash`/`gemini-3.1-flash-lite` for FOOD_PHOTO, but **only with a billing-enabled Gemini key**.

## 2. Pass criteria (derived from the guardrails + evidence)

### Hard gates (every job)
- G1 Reachable today by Forge: Anthropic native, or an OpenAI-compatible chat-completions base URL + key (on-device runtimes fail this until LiteRT-LM/llama.cpp is integrated).
- G2 Lifecycle: not a preview, not closed to new users, not shutting down within ~6 months, and has a verified price.
- G3 No training on user data on the tier used: dataPolicy 'no-train' (Anthropic, OpenAI, xAI, Alibaba Model Studio, OpenRouter with ZDR enforced, DeepInfra non-Google, Groq) or Gemini with a billing-enabled key. 'unclear' fails (Mistral direct, Cerebras, qwen3.8-flash, glm-4.6v). Applies to text jobs too because COACH/PLAN carry health data.
- G4 Output contract: Forge expects JSON; FOOD_PHOTO/LABEL/BODY/PLAN/PROGRAM need provider-side constrained json_schema or reliable JSON mode (prompt-only JSON fails: JSONSchemaBench 13-21% compliance on hard schemas). Forge keeps tolerant extraction + Kotlin validation + one retry for every model.

### Per job

| Job | Extra hard gates | Quality bar (what "good enough" means) |
|---|---|---|
| FOOD_PHOTO | vision; G1-G4; photos not trained on (photo rules + task brief) | Good enough = a model whose own family/tier has published single-photo energy error in the frontier band (~23-36% MAPE on curated mixed meals, or CCC >= ~0.75 on Nutrition5k). Rationale: Forge biases high, clamps every number, and the user edits before saving, so the model only needs to identify foods and give a portion-sane first guess; but model choice explains 99.6% of accuracy variance (V26) and cheap/small tiers can collapse (Haiku 4.5 R2 0.23; sub-2B unusable), so a vision model from a family with no food evidence is NOT SUITABLE until it passes Forge's own 20-meal Indian test (target: median abs error <= 35% vs weighed/label values, no systematic under-call > 15% after Forge's margin). |
| LABEL | vision; G1-G4 with constrained per-field schema (units separate) | Near-exact transcription: target >= 95% of element-value pairs exact on a 20-label test; Forge must Atwater-check (4/4/9 vs stated kcal, flag > 15% mismatch) and always show a confirm screen. Only 2024-era evidence exists (83-90% pairs correct), so every grade is a judgement; RECOMMENDED reserved for mid/frontier closed models with strict schema. |
| BODY | vision; G1-G4; strongest privacy available (guardrail photo_feature_blocked_groups says photos stay on the device unless exported: treat a cloud call as an explicit, consented export, ZDR/no-train only; on-device preferred); never attractiveness/appearance commentary (photo_no_attractiveness_commentary) - enforced by schema with no free-text appearance field + post-filter | Honest range only: the model returns a point estimate or range in a strict schema; Forge widens to +/-6 points (photo_bf_accuracy_limits), labels it rough/unvalidated, uses the LOWER bound for safety. No general VLM is validated vs DXA, so no model is RECOMMENDED; GOOD ENOUGH = passes gates and does not refuse/comment. |
| PLAN | G1-G4 with the phase field as a string enum enforced by constrained decoding where possible | Strict enum adherence (100% after code validation, retry once, else rule-based fallback); rationale text passes safety_messaging_no_praise_for_restriction / no_body_shaming filter. RECOMMENDED where the provider guarantees the enum (Anthropic, OpenAI strict, Groq strict). |
| COACH | G1-G3; acceptable ED/crisis behaviour: positive published evidence for the exact model, its direct predecessor or its vendor family at frontier tier; families with negative evidence (Grok, Mistral, Qwen, Llama, sub-10B open) or no evidence at all (DeepSeek, Kimi, MiMo, gpt-oss) fail until they pass Forge's own red-team set (Winecoff & Klyman 7-category taxonomy, incl. 'for a friend' framing) | Code always pre-empts: safety_crisis_disclosure cue detection stops coaching and shows Tele MANAS 14416 / 1800-89-14416 and 112; every reply passes the no-praise-for-restriction and no-body-shaming blocklist + classifier and regenerates on hit. Put safety rules first in the prompt (IFScale primacy), pass SCOFF status/BMI band (W26: +10-14 points). RECOMMENDED = family with the best crisis evidence at mid/frontier tier. |
| PROGRAM | G1-G4; context >= 128K tokens; enough max output for the program JSON (>= 16K) | Complete extraction of every day/exercise/sets/reps from a long PDF into schema; Forge validates counts and training_* rules. No direct benchmark; graded on context, schema support and tier (judgement). |

Guardrail tension to resolve in code: `photo_feature_blocked_groups` says progress photos *stay on the device unless the user exports them*. A cloud BODY call should therefore be treated as an explicit, consented export, sent only to a no-train/ZDR endpoint. The cleanest fix is on-device BODY (Gemma 4 E4B via LiteRT-LM).

Safety is enforced in code for every model. These are not model features:
- `safety_crisis_disclosure` cue detection runs before the model. On a hit it stops coaching and shows the Tele MANAS (14416 / 1800-89-14416) and 112 card.
- A blocklist and classifier filter catches replies that break `safety_messaging_no_praise_for_restriction`, `safety_messaging_no_body_shaming` or `photo_no_attractiveness_commentary`, and regenerates them.
- Safety rules come first in the system prompt (IFScale primacy effect).
- COACH receives the user's SCOFF status and BMI band (W26: +10-14 safety points).
- Forge must handle Anthropic `stop_reason: refusal` and the OpenAI `refusal` field.

## 3. Grades for every verified model

Legend: **REC** = RECOMMENDED, OK = GOOD ENOUGH, no = NOT SUITABLE. The cost column assumes one model does all six jobs for a typical month:
- FOOD_PHOTO: 120 calls/month, about 2,300 input and 400 output tokens each (4 photos/day x 30; 1 image (~1,500 tok) + ~800 tok prompt/schema; ~400 tok JSON).
- LABEL: 30 calls/month, about 2,300 input and 500 output tokens each (1/day x 30; 1 image + prompt; ~500 tok per-field JSON).
- BODY: 1 calls/month, about 5,500 input and 400 output tokens each (onboarding only; 3 views (photo_capture_standardisation requiredViews=3) + prompt).
- PLAN: 1 calls/month, about 4,000 input and 800 output tokens each (onboarding only; profile + allowed-phase enum).
- COACH: 90 calls/month, about 10,000 input and 500 output tokens each (3/day x 30; ~10k tok context (upper end of 6-10k), no prompt caching assumed; ~500 tok reply).
- PROGRAM: 1 calls/month, about 40,000 input and 8,000 output tokens each (one ~30-page PDF as text (~40k tok) -> ~8k tok program JSON).

Costs use list prices with no prompt caching. Image tokens are taken as ~1,500 per photo for every provider. "x3 out" triples output tokens to cover hidden reasoning. OpenRouter fees are not included.

| Model | Route | $/M in/out | Data | FOOD | LABEL | BODY | PLAN | COACH | PROG | $/mo all jobs | x3 out |
|---|---|---|---|---|---|---|---|---|---|---|---|
| `claude-haiku-5-5` | anthropic | 0.1/0.5 | no-train | no | OK | OK | **REC** | OK | **REC** | $0.188 | $0.305 |
| `claude-sonnet-5-5` | anthropic | 2.0/10.0 | no-train | **REC** | **REC** | OK | **REC** | **REC** | **REC** | $3.76 | $6.11 |
| `claude-opus-5-5` | anthropic | 4.0/20.0 | no-train | OK | OK | OK | OK | **REC** | **REC** | $7.52 | $12.21 |
| `claude-haiku-4-5-20251001` | anthropic | 1.0/5.0 | no-train | no | OK | OK | OK | OK | OK | $1.88 | $3.05 |
| `gpt-6-luna` | https://api.openai.com/v1 | 0.1/0.5 | no-train | OK | OK | OK | **REC** | OK | OK | $0.188 | $0.305 |
| `gpt-6.1-terra` | https://api.openai.com/v1 | 1.0/5.0 | no-train | **REC** | **REC** | OK | **REC** | OK | **REC** | $1.88 | $3.05 |
| `gpt-6.2-sol` | https://api.openai.com/v1 | 2.0/10.0 | no-train | **REC** | **REC** | OK | OK | **REC** | **REC** | $3.76 | $6.11 |
| `gpt-6.2-astra` | https://api.openai.com/v1 | 8.0/40.0 | no-train | OK | OK | OK | OK | OK | OK | $15.04 | $24.42 |
| `gpt-5.6-luna` | https://api.openai.com/v1 | 0.2/1.2 | no-train | OK | OK | OK | OK | OK | OK | $0.400 | $0.681 |
| `gpt-5.6-terra` | https://api.openai.com/v1 | 2.0/12.0 | no-train | OK | OK | OK | OK | OK | OK | $4.00 | $6.81 |
| `gpt-5.6-sol` | https://api.openai.com/v1 | 4.0/20.0 | no-train | OK | OK | OK | OK | OK | OK | $7.52 | $12.21 |
| `gpt-5.5` | https://api.openai.com/v1 | 5.0/30.0 | no-train | OK | OK | OK | OK | OK | OK | $9.99 | $17.02 |
| `gpt-5.4` | https://api.openai.com/v1 | 2.5/15.0 | no-train | OK | OK | OK | OK | OK | OK | $4.99 | $8.51 |
| `gpt-5.4-mini` | https://api.openai.com/v1 | 0.75/4.5 | no-train | OK | OK | OK | OK | OK | OK | $1.50 | $2.55 |
| `gpt-5.4-nano` | https://api.openai.com/v1 | 0.2/1.25 | no-train | no | no | no | no | no | no | $0.405 | $0.698 |
| `gpt-5.2` | https://api.openai.com/v1 | 1.75/14.0 | no-train | OK | OK | OK | OK | OK | OK | $3.91 | $7.19 |
| `gpt-5.1` | https://api.openai.com/v1 | 1.25/10.0 | no-train | no | no | no | no | no | no | $2.79 | $5.13 |
| `gpt-5` | https://api.openai.com/v1 | 1.25/10.0 | no-train | no | no | no | no | no | no | $2.79 | $5.13 |
| `gpt-5-mini` | https://api.openai.com/v1 | 0.25/2.0 | no-train | no | no | no | no | no | no | $0.558 | $1.03 |
| `gpt-5-nano` | https://api.openai.com/v1 | 0.05/0.4 | no-train | no | no | no | no | no | no | $0.112 | $0.205 |
| `gemini-3.1-flash-lite` | https://generativelanguage.googleapis.co... | 0.25/1.5 | trains-on-free-tier | **REC** | OK | OK | OK | OK | OK | $0.499 | $0.851 |
| `gemini-3.5-flash-lite` | https://generativelanguage.googleapis.co... | 0.3/2.5 | trains-on-free-tier | OK | OK | OK | OK | OK | OK | $0.681 | $1.27 |
| `gemini-2.5-flash-lite` | https://generativelanguage.googleapis.co... | 0.1/0.4 | trains-on-free-tier | no | no | no | no | no | no | $0.176 | $0.270 |
| `gemini-3.6-flash` | https://generativelanguage.googleapis.co... | 0.75/3.75 | trains-on-free-tier | **REC** | OK | OK | OK | OK | OK | $1.41 | $2.29 |
| `gemini-3.8-flash` | https://generativelanguage.googleapis.co... | 0.75/3.75 | trains-on-free-tier | **REC** | OK | OK | OK | OK | OK | $1.41 | $2.29 |
| `gemini-3-flash-preview` | https://generativelanguage.googleapis.co... | 0.5/3.0 | trains-on-free-tier | no | no | no | no | no | no | $1.00 | $1.70 |
| `gemini-4-hydrogen` | https://generativelanguage.googleapis.co... | 1.5/7.5 | trains-on-free-tier | OK | OK | OK | OK | OK | OK | $2.82 | $4.58 |
| `gemini-4-argon` | https://generativelanguage.googleapis.co... | 2.0/10.0 | no-train | OK | OK | OK | OK | OK | OK | $3.76 | $6.11 |
| `gemini-4-carbon` | https://generativelanguage.googleapis.co... | 2.0/10.0 | no-train | OK | OK | OK | OK | OK | OK | $3.76 | $6.11 |
| `gemini-4-neon` | https://generativelanguage.googleapis.co... | 3.0/15.0 | no-train | OK | OK | OK | OK | OK | OK | $5.64 | $9.16 |
| `gemini-3.1-pro-preview` | https://generativelanguage.googleapis.co... | 2.0/12.0 | no-train | no | no | no | no | no | no | $4.00 | $6.81 |
| `gemini-2.5-flash` | https://generativelanguage.googleapis.co... | 0.3/2.5 | trains-on-free-tier | no | no | no | no | no | no | $0.681 | $1.27 |
| `gemini-2.5-pro` | https://generativelanguage.googleapis.co... | 1.25/10.0 | trains-on-free-tier | no | no | no | no | no | no | $2.79 | $5.13 |
| `grok-4.3` | https://api.x.ai/v1 | 1.25/2.5 | no-train | OK | OK | OK | OK | no | OK | $1.91 | $2.50 |
| `grok-4.20-0309-non-reasoning` | https://api.x.ai/v1 | 1.25/2.5 | no-train | OK | OK | OK | OK | no | OK | $1.91 | $2.50 |
| `grok-build-0.1` | https://api.x.ai/v1 | 1.0/2.0 | no-train | no | OK | no | OK | no | OK | $1.53 | $2.00 |
| `grok-4.7` | https://api.x.ai/v1 | 2.0/6.0 | no-train | OK | OK | OK | OK | no | OK | $3.29 | $4.70 |
| `ministral-8b-2512` | https://api.mistral.ai/v1 | 0.15/0.15 | unclear | no | no | no | no | no | no | $0.212 | $0.247 |
| `ministral-14b-2512` | https://api.mistral.ai/v1 | 0.2/0.2 | unclear | no | no | no | no | no | no | $0.282 | $0.329 |
| `mistral-small-2603` | https://api.mistral.ai/v1 | 0.15/0.6 | unclear | no | no | no | no | no | no | $0.265 | $0.405 |
| `mistral-large-4-0` | https://api.mistral.ai/v1 | 0.7/2.1 | unclear | no | no | no | no | no | no | $1.15 | $1.64 |
| `mistral-medium-3-5` | https://api.mistral.ai/v1 | n/a | unclear | no | no | no | no | no | no | n/a | n/a |
| `qwen3.7-plus` | https://{WorkspaceId}.ap-southeast-1.maa... | 0.4/1.6 | no-train | OK | OK | OK | OK | no | OK | $0.705 | $1.08 |
| `amazon.nova-2-lite-v1:0` | not reachable | n/a | unclear | no | no | no | no | no | no | n/a | n/a |
| `google/gemma-4-26b-a4b-it` | https://openrouter.ai/api/v1 | 0.0675/0.225 | no-train | OK | OK | OK | OK | no | OK | $0.114 | $0.167 |
| `google/gemma-4-31b-it` | https://openrouter.ai/api/v1 | 0.1/0.34 | no-train | **REC** | OK | OK | OK | OK | OK | $0.169 | $0.249 |
| `google/gemma-3-27b-it` | https://openrouter.ai/api/v1 | 0.064/0.128 | no-train | OK | OK | no | OK | no | OK | $0.098 | $0.128 |
| `litert-community/gemma-4-E2B-it-litert-lm` | not reachable (needs in-app LiteRT-LM: c... | free (local) | no-train | no | no | no | no | no | no | <$0.001 | <$0.001 |
| `litert-community/gemma-4-E4B-it-litert-lm` | not reachable (in-app LiteRT-LM integrat... | free (local) | no-train | no | no | no | no | no | no | <$0.001 | <$0.001 |
| `google/gemma-3n-E4B-it-litert-lm` | not reachable (in-app runtime) | free (local) | no-train | no | no | no | no | no | no | <$0.001 | <$0.001 |
| `meta-llama/llama-4-scout` | https://openrouter.ai/api/v1 | 0.08/0.24 | no-train | OK | OK | OK | OK | no | OK | $0.132 | $0.188 |
| `meta-llama/llama-4-maverick` | https://openrouter.ai/api/v1 | 0.35/1.0 | no-train | OK | OK | OK | OK | no | OK | $0.570 | $0.805 |
| `mistralai/mistral-small-2603` | https://openrouter.ai/api/v1 | 0.15/0.6 | no-train | OK | OK | OK | OK | no | OK | $0.265 | $0.405 |
| `mistralai/mistral-small-3.2-24b-instruct` | https://openrouter.ai/api/v1 | 0.06/0.16 | no-train | OK | OK | OK | OK | no | OK | $0.096 | $0.134 |
| `qwen/qwen3-vl-235b-a22b-instruct` | https://openrouter.ai/api/v1 | 0.2/0.88 | no-train | OK | OK | OK | OK | no | OK | $0.362 | $0.568 |
| `qwen/qwen3-vl-30b-a3b-instruct` | https://openrouter.ai/api/v1 | 0.15/0.6 | no-train | OK | OK | OK | OK | no | OK | $0.265 | $0.405 |
| `qwen/qwen3-vl-8b-instruct` | https://openrouter.ai/api/v1 | 0.25/0.75 | no-train | OK | OK | OK | OK | no | OK | $0.411 | $0.587 |
| `Qwen/Qwen3-VL-2B-Instruct` | not reachable (requires bundling llama.c... | free (local) | no-train | no | no | no | no | no | no | <$0.001 | <$0.001 |
| `qwen/qwen3.6-35b-a3b` | https://openrouter.ai/api/v1 | 0.08/0.76 | no-train | OK | OK | OK | OK | no | OK | $0.193 | $0.371 |
| `qwen/qwen3.8-27b` | https://openrouter.ai/api/v1 | 0.04/1.35 | no-train | OK | OK | OK | OK | no | OK | $0.210 | $0.526 |
| `qwen/qwen3.8-flash` | https://openrouter.ai/api/v1 | 0.15/0.47 | unclear | no | no | no | no | no | no | $0.249 | $0.359 |
| `qwen/qwen3.5-397b-a17b` | https://openrouter.ai/api/v1 | 0.36/2.4 | no-train | OK | OK | OK | OK | no | OK | $0.747 | $1.31 |
| `deepseek/deepseek-v4.1-flash-1017` | https://openrouter.ai/api/v1 | 0.04/0.1 | no-train | no | OK | no | OK | no | OK | $0.064 | $0.087 |
| `z-ai/glm-5.3-flash` | https://openrouter.ai/api/v1 | 0.04/0.078 | no-train | no | OK | no | OK | no | OK | $0.061 | $0.079 |
| `z-ai/glm-4.6v` | https://openrouter.ai/api/v1 | 0.3/0.9 | unclear | no | no | no | no | no | no | $0.494 | $0.705 |
| `xiaomi/mimo-v2.6-flash` | https://openrouter.ai/api/v1 | 0.14/0.28 | no-train | no | OK | no | OK | no | OK | $0.214 | $0.280 |
| `moonshotai/kimi-k2.6` | https://openrouter.ai/api/v1 | 0.57/2.4 | no-train | no | OK | no | OK | no | OK | $1.02 | $1.58 |
| `deepseek/deepseek-v4-flash` | https://openrouter.ai/api/v1 | 0.07/0.14 | no-train | no | no | no | OK | no | OK | $0.107 | $0.140 |
| `deepseek/deepseek-v4-pro` | https://openrouter.ai/api/v1 | 1.04/2.08 | no-train | no | no | no | OK | no | OK | $1.59 | $2.08 |
| `z-ai/glm-5.2` | https://openrouter.ai/api/v1 | 0.45/1.44 | no-train | no | no | no | OK | OK | OK | $0.751 | $1.09 |
| `z-ai/glm-5.3` | https://openrouter.ai/api/v1 | 0.45/2.0 | no-train | no | no | no | OK | OK | OK | $0.817 | $1.29 |
| `openai/gpt-oss-120b` | https://api.groq.com/openai/v1 | 0.15/0.6 | no-train | no | no | no | **REC** | no | OK | $0.265 | $0.405 |
| `gpt-oss-120b` | https://api.cerebras.ai/v1 | 0.35/0.75 | unclear | no | no | no | no | no | no | $0.541 | $0.717 |
| `openai/gpt-oss-20b` | https://openrouter.ai/api/v1 | 0.018/0.09 | no-train | no | no | no | OK | no | OK | $0.034 | $0.055 |
| `Qwen/Qwen3-235B-A22B-Instruct-2507` | https://api.deepinfra.com/v1/openai | 0.09/0.55 | no-train | no | no | no | OK | no | OK | $0.181 | $0.310 |
| `OpenGVLab/InternVL3_5-8B` | not reachable | n/a | unclear | no | no | no | no | no | no | n/a | n/a |
| `microsoft/Phi-4-multimodal-instruct` | not reachable | n/a | unclear | no | no | no | no | no | no | n/a | n/a |

Gemini rows pass the no-train gate **only with a paid key**. Prices change soon: `gemini-3.6-flash` and `gemini-3.8-flash` move to $1.50/$7.50 on 2027-01-01 (all jobs about $2.82/month), and `gemini-4-argon` and `gemini-4-carbon` move to $4/$20 on 2026-12-01.

### One-line reasons per job (models that pass the hard gates)

#### FOOD_PHOTO

| Model | Grade | Reason | Evidence | $/mo (this job) |
|---|---|---|---|---|
| `google/gemma-4-31b-it` | RECOMMENDED | Best-evidenced open family on calories (Gemma 3 27B 27.7% MAPE, near GPT-4o 24.5%); Gemma 4 31B is its successor (extrapolated). *(judgement)* | C25 | $0.044 |
| `gemini-3.1-flash-lite` | RECOMMENDED | Direct evidence: CCC 0.754 for calories and best ingredient Jaccard on Nutrition5k (S26, preprint abstract). *(judgement)* | S26, V26 | $0.141 |
| `gemini-3.6-flash` | RECOMMENDED | Gemini Flash line has the best direct food scores (3.0 Flash CCC 0.767; 2.5/2.0 Flash in V26 top 5). Price doubles 2027-01-01. *(judgement)* | S26, V26 | $0.387 |
| `gemini-3.8-flash` | RECOMMENDED | Newest Flash; family has the best direct food scores (S26, V26). Price doubles 2027-01-01. *(judgement)* | S26, V26 | $0.387 |
| `gpt-6.1-terra` | RECOMMENDED | OpenAI mid/frontier models lead food benchmarks (GPT-4.1 23.3% MAPE C25; GPT-5-chat top V26); terra is positioned for visual reasoning. Extrapolated. *(judgement)* | C25, V26 | $0.516 |
| `claude-sonnet-5-5` | RECOMMENDED | Sonnet tier ~= Opus on food (N26); Claude Sonnet 3.7 at 23.9% calorie MAPE (C25). Extrapolated to 5.5. *(judgement)* | N26, C25 | $1.03 |
| `gpt-6.2-sol` | RECOMMENDED | OpenAI frontier tier; strongest family evidence on food. *(judgement)* | V26, C25 | $1.03 |
| `google/gemma-3-27b-it` | GOOD ENOUGH | Direct evidence 27.7% calorie MAPE (C25), but superseded and the only ZDR route is DeepInfra (Google-models privacy clause ambiguous). *(judgement)* | C25, OPEN | $0.024 |
| `mistralai/mistral-small-3.2-24b-instruct` | GOOD ENOUGH | Older Mistral Small (M26 tested 3.1 24B); cheap, non-thinking. Retired on Mistral's own API. *(judgement)* | M26 | $0.024 |
| `google/gemma-4-26b-a4b-it` | GOOD ENOUGH | Gemma 3 27B was near closed models (27.7% MAPE, C25); 26B-A4B is newer but only ~4B active (judgement). *(judgement)* | C25, M26 | $0.029 |
| `meta-llama/llama-4-scout` | GOOD ENOUGH | Tested in V26 (rank not extracted); sibling Maverick was a SeeFood finalist. Test response_format first. *(judgement)* | V26, SF26 | $0.034 |
| `gpt-6-luna` | GOOD ENOUGH | No luna data; OpenAI's small tier (GPT-4o-mini) was a SeeFood finalist and not clearly worse than flagships on real photos. *(judgement)* | SF26, V26 | $0.052 |
| `qwen/qwen3.6-35b-a3b` | GOOD ENOUGH | No direct food data; Qwen 3.5 4B/9B were the best open models on portions in M26 (judgement). Cap reasoning. *(judgement)* | M26 | $0.059 |
| `mistralai/mistral-small-2603` | GOOD ENOUGH | Mistral Small tested in V26 (rank not extracted); mistral-small3.1 24B in M26 portion test. Judgement. *(judgement)* | V26, M26 | $0.070 |
| `qwen/qwen3-vl-30b-a3b-instruct` | GOOD ENOUGH | Mid-size Qwen-VL; family mid-pack on food (C25); 16,384 completion cap. *(judgement)* | C25, M26 | $0.070 |
| `qwen/qwen3.8-27b` | GOOD ENOUGH | No direct food data; newest dense Qwen VLM; Qwen 3.5 small models led open portion estimation (M26). Cap reasoning (output $1.35/M). *(judgement)* | M26 | $0.076 |
| `qwen/qwen3-vl-235b-a22b-instruct` | GOOD ENOUGH | Qwen3-VL-235B tested in V26 (rank not extracted); Qwen2.5-VL 72B 33.9% MAPE (C25). *(judgement)* | V26, C25 | $0.097 |
| `qwen/qwen3-vl-8b-instruct` | GOOD ENOUGH | Screened in SeeFood (not a finalist); OmniFood-Bench mass failure (shared by all 6 VLMs). Hosted price above larger Qwen-VL - dominated. *(judgement)* | SF26, OF26 | $0.105 |
| `gpt-5.6-luna` | GOOD ENOUGH | Passes gates (strict schema, no-train, vision) but dominated by gpt-6-luna (half price). *(judgement)* | CLOSED, SF26 | $0.113 |
| `meta-llama/llama-4-maverick` | GOOD ENOUGH | SeeFood finalist (98.8% MAPE on real photos - same band as GPT-4o-mini); single host, priced above better options. *(judgement)* | SF26 | $0.145 |
| `qwen3.7-plus` | GOOD ENOUGH | Image requests fall back to json_object (valid JSON, shape not enforced); Qwen-VL family mid-pack on food (Qwen2.5-VL 72B 33.9% MAPE). *(judgement)* | C25, CLOSED | $0.187 |
| `gemini-3.5-flash-lite` | GOOD ENOUGH | Successor of a well-scored Flash-Lite; no direct data (Gemini 2.5 Flash-Lite over-estimated +30 kcal in SeeFood). *(judgement)* | S26, SF26 | $0.203 |
| `qwen/qwen3.5-397b-a17b` | GOOD ENOUGH | Flagship open VLM, no direct food data; Qwen 3.5 family led open portion test (M26). Pricey for the gain. *(judgement)* | M26 | $0.215 |
| `gpt-5.4-mini` | GOOD ENOUGH | Passes gates (strict schema, no-train, vision) but dominated by gpt-6.1-terra. *(judgement)* | CLOSED, SF26 | $0.423 |
| `grok-4.3` | GOOD ENOUGH | No current data; Grok-4 was in V26 (rank not extracted); Grok 3 70-90% vs label (8 meals). Send JPEG only. *(judgement)* | V26 | $0.465 |
| `grok-4.20-0309-non-reasoning` | GOOD ENOUGH | No current data; Grok-4 was in V26 (rank not extracted); Grok 3 70-90% vs label (8 meals). Send JPEG only. *(judgement)* | V26 | $0.465 |
| `gemini-4-hydrogen` | GOOD ENOUGH | No direct data; Gemini 4 speed tier; 6x Flash-Lite price for no shown gain. *(judgement)* | V26 | $0.774 |
| `grok-4.7` | GOOD ENOUGH | No current data; Grok-4 was in V26 (rank not extracted); Grok 3 70-90% vs label (8 meals). Send JPEG only. *(judgement)* | V26 | $0.840 |
| `gemini-4-argon` | GOOD ENOUGH | No direct data; Gemini 3 Pro had the lowest screening MAPE in SeeFood. Expensive (argon/carbon price doubles 2026-12-01). *(judgement)* | SF26 | $1.03 |
| `gemini-4-carbon` | GOOD ENOUGH | No direct data; Gemini 3 Pro had the lowest screening MAPE in SeeFood. Expensive (argon/carbon price doubles 2026-12-01). *(judgement)* | SF26 | $1.03 |
| `gpt-5.6-terra` | GOOD ENOUGH | Passes gates (strict schema, no-train, vision) but dominated by gpt-6.1-terra (cheaper). *(judgement)* | CLOSED, SF26 | $1.13 |
| `gpt-5.2` | GOOD ENOUGH | Passes gates (strict schema, no-train, vision) but dominated by gpt-6.2-sol (GPT-5.2 lost to GPT-4o-mini in SeeFood). *(judgement)* | CLOSED, SF26 | $1.16 |
| `gpt-5.4` | GOOD ENOUGH | Passes gates (strict schema, no-train, vision) but dominated by gpt-6.2-sol. *(judgement)* | CLOSED, SF26 | $1.41 |
| `gemini-4-neon` | GOOD ENOUGH | No direct data; Gemini 3 Pro had the lowest screening MAPE in SeeFood. Expensive (argon/carbon price doubles 2026-12-01). *(judgement)* | SF26 | $1.55 |
| `claude-opus-5-5` | GOOD ENOUGH | Passes, but Opus adds ~1-3 MedAPE points over Sonnet at 2x the price. *(judgement)* | N26 | $2.06 |
| `gpt-5.6-sol` | GOOD ENOUGH | Passes gates (strict schema, no-train, vision) but dominated by gpt-6.2-sol (promo price ends 2026-11-30). *(judgement)* | CLOSED, SF26 | $2.06 |
| `gpt-5.5` | GOOD ENOUGH | Passes gates (strict schema, no-train, vision) but dominated by gpt-6.2-sol (cheaper). *(judgement)* | CLOSED, SF26 | $2.82 |
| `gpt-6.2-astra` | GOOD ENOUGH | Passes every bar, but $8/$40 is 4x gpt-6.2-sol with no evidence of a gain on Forge's jobs. *(judgement)* | CLOSED | $4.13 |
| `z-ai/glm-5.3-flash` | NOT SUITABLE | No food-estimation evidence for this family; model choice explains 99.6% of accuracy variance, so unproven = unsafe bet. Re-grade after a 20-meal Indian test. *(judgement)* | V26 | $0.015 |
| `deepseek/deepseek-v4.1-flash-1017` | NOT SUITABLE | No food-estimation evidence for this family; model choice explains 99.6% of accuracy variance, so unproven = unsafe bet. Re-grade after a 20-meal Indian test. *(judgement)* | V26 | $0.016 |
| `claude-haiku-5-5` | NOT SUITABLE | Only Haiku-tier evidence is negative (Haiku 4.5 energy R2 0.23 vs Sonnet 0.60); equally cheap options have positive evidence. Re-grade only after Forge's own meal test. *(judgement)* | N26 | $0.052 |
| `xiaomi/mimo-v2.6-flash` | NOT SUITABLE | No food-estimation evidence for this family; model choice explains 99.6% of accuracy variance, so unproven = unsafe bet. Re-grade after a 20-meal Indian test. *(judgement)* | V26 | $0.052 |
| `moonshotai/kimi-k2.6` | NOT SUITABLE | No food-estimation evidence for this family; model choice explains 99.6% of accuracy variance, so unproven = unsafe bet. Re-grade after a 20-meal Indian test. *(judgement)* | V26 | $0.273 |
| `grok-build-0.1` | NOT SUITABLE | Coding/agentic model with no food evidence; no reason to use it for vision estimation. *(judgement)* | CLOSED | $0.372 |
| `claude-haiku-4-5-20251001` | NOT SUITABLE | Direct negative evidence: energy R2 0.23 (N26). | N26 | $0.516 |

#### LABEL

| Model | Grade | Reason | Evidence | $/mo (this job) |
|---|---|---|---|---|
| `gpt-6.1-terra` | RECOMMENDED | Strict schema, mid-tier vision. *(judgement)* | A25, JSB25 | $0.144 |
| `claude-sonnet-5-5` | RECOMMENDED | Strong vision + native schema; still Atwater-check and confirm. *(judgement)* | A25, CLOSED | $0.288 |
| `gpt-6.2-sol` | RECOMMENDED | Strict schema. *(judgement)* | JSB25 | $0.288 |
| `z-ai/glm-5.3-flash` | GOOD ENOUGH | Vision + json_schema via ZDR; Atwater + confirm (judgement). Relace is a small host. *(judgement)* | A25, OPEN | $0.004 |
| `deepseek/deepseek-v4.1-flash-1017` | GOOD ENOUGH | Vision + json_schema via ZDR; transcription errors caught by Atwater + confirm (judgement). Sail Research is a small host - pin DeepInfra fallback. *(judgement)* | A25, OPEN | $0.004 |
| `google/gemma-3-27b-it` | GOOD ENOUGH | Vision + json_schema via ZDR endpoint; Atwater + confirm. *(judgement)* | A25, ORSO | $0.006 |
| `mistralai/mistral-small-3.2-24b-instruct` | GOOD ENOUGH | Vision + json_schema via ZDR endpoint; Atwater + confirm. *(judgement)* | A25, ORSO | $0.006 |
| `google/gemma-4-26b-a4b-it` | GOOD ENOUGH | Vision + json_schema via ZDR endpoint; Atwater + confirm. *(judgement)* | A25, ORSO | $0.008 |
| `meta-llama/llama-4-scout` | GOOD ENOUGH | Vision + json_schema via ZDR endpoint; Atwater + confirm. *(judgement)* | A25, ORSO | $0.009 |
| `google/gemma-4-31b-it` | GOOD ENOUGH | Vision + json_schema via ZDR endpoint; Atwater + confirm. *(judgement)* | A25, ORSO | $0.012 |
| `xiaomi/mimo-v2.6-flash` | GOOD ENOUGH | Vision + json_schema via ZDR; Atwater + confirm (judgement). DeepInfra uptime 91.8%. *(judgement)* | A25, OPEN | $0.014 |
| `claude-haiku-5-5` | GOOD ENOUGH | Transcription/extraction is Anthropic's stated Haiku use; strict schema; errors caught by Atwater check + confirm screen. No current OCR benchmark. *(judgement)* | A25, CLOSED | $0.014 |
| `gpt-6-luna` | GOOD ENOUGH | Strict json_schema; small tier, so rely on Atwater + confirm. *(judgement)* | A25, JSB25 | $0.014 |
| `qwen/qwen3.6-35b-a3b` | GOOD ENOUGH | Vision + json_schema via ZDR endpoint; Atwater + confirm. *(judgement)* | A25, ORSO | $0.017 |
| `mistralai/mistral-small-2603` | GOOD ENOUGH | Vision + json_schema via ZDR endpoint; Atwater + confirm. *(judgement)* | A25, ORSO | $0.019 |
| `qwen/qwen3-vl-30b-a3b-instruct` | GOOD ENOUGH | Vision + json_schema via ZDR endpoint; Atwater + confirm. *(judgement)* | A25, ORSO | $0.019 |
| `qwen/qwen3.8-27b` | GOOD ENOUGH | Vision + json_schema via ZDR endpoint; Atwater + confirm. *(judgement)* | A25, ORSO | $0.023 |
| `qwen/qwen3-vl-235b-a22b-instruct` | GOOD ENOUGH | Document/OCR-strong VLM per vendor positioning (no label benchmark); strongest open LABEL pick. *(judgement)* | A25, OPEN | $0.027 |
| `qwen/qwen3-vl-8b-instruct` | GOOD ENOUGH | Vision + json_schema via ZDR endpoint; Atwater + confirm. *(judgement)* | A25, ORSO | $0.029 |
| `gpt-5.6-luna` | GOOD ENOUGH | Passes gates (strict schema, no-train, vision) but dominated by gpt-6-luna (half price). *(judgement)* | CLOSED | $0.032 |
| `meta-llama/llama-4-maverick` | GOOD ENOUGH | Vision + json_schema via ZDR endpoint; Atwater + confirm. *(judgement)* | A25, ORSO | $0.039 |
| `gemini-3.1-flash-lite` | GOOD ENOUGH | Native schema at Gemini level (strict flag undocumented on the OpenAI-compat layer); Gemini lagged GPT on 2024 label test - Atwater + confirm. *(judgement)* | A25, CLOSED | $0.040 |
| `qwen3.7-plus` | GOOD ENOUGH | json_object only with images - validate schema in code. *(judgement)* | CLOSED | $0.052 |
| `gemini-3.5-flash-lite` | GOOD ENOUGH | Native schema at Gemini level (strict flag undocumented on the OpenAI-compat layer); Gemini lagged GPT on 2024 label test - Atwater + confirm. *(judgement)* | A25, CLOSED | $0.058 |
| `qwen/qwen3.5-397b-a17b` | GOOD ENOUGH | Vision + json_schema via ZDR endpoint; Atwater + confirm. *(judgement)* | A25, ORSO | $0.061 |
| `moonshotai/kimi-k2.6` | GOOD ENOUGH | Works, but 10x+ the price of equivalent vision options (dominated). *(judgement)* | OPEN | $0.075 |
| `grok-build-0.1` | GOOD ENOUGH | Native schema, vision; Atwater + confirm. *(judgement)* | CLOSED | $0.099 |
| `gemini-3.6-flash` | GOOD ENOUGH | Native schema at Gemini level (strict flag undocumented on the OpenAI-compat layer); Gemini lagged GPT on 2024 label test - Atwater + confirm. *(judgement)* | A25, CLOSED | $0.108 |
| `gemini-3.8-flash` | GOOD ENOUGH | Native schema at Gemini level (strict flag undocumented on the OpenAI-compat layer); Gemini lagged GPT on 2024 label test - Atwater + confirm. *(judgement)* | A25, CLOSED | $0.108 |
| `gpt-5.4-mini` | GOOD ENOUGH | Passes gates (strict schema, no-train, vision) but dominated by gpt-6.1-terra. *(judgement)* | CLOSED | $0.119 |
| `grok-4.3` | GOOD ENOUGH | Native schema, vision; Atwater + confirm. *(judgement)* | CLOSED | $0.124 |
| `grok-4.20-0309-non-reasoning` | GOOD ENOUGH | Native schema, vision; Atwater + confirm. *(judgement)* | CLOSED | $0.124 |
| `claude-haiku-4-5-20251001` | GOOD ENOUGH | Works but 10x the price of claude-haiku-5-5 and past its retire-not-before date. *(judgement)* | CLOSED | $0.144 |
| `gemini-4-hydrogen` | GOOD ENOUGH | Native schema at Gemini level (strict flag undocumented on the OpenAI-compat layer); Gemini lagged GPT on 2024 label test - Atwater + confirm. *(judgement)* | A25, CLOSED | $0.216 |
| `grok-4.7` | GOOD ENOUGH | Native schema, vision; Atwater + confirm. *(judgement)* | CLOSED | $0.228 |
| `gemini-4-argon` | GOOD ENOUGH | Native schema at Gemini level (strict flag undocumented on the OpenAI-compat layer); Gemini lagged GPT on 2024 label test - Atwater + confirm. *(judgement)* | A25, CLOSED | $0.288 |
| `gemini-4-carbon` | GOOD ENOUGH | Native schema at Gemini level (strict flag undocumented on the OpenAI-compat layer); Gemini lagged GPT on 2024 label test - Atwater + confirm. *(judgement)* | A25, CLOSED | $0.288 |
| `gpt-5.6-terra` | GOOD ENOUGH | Passes gates (strict schema, no-train, vision) but dominated by gpt-6.1-terra (cheaper). *(judgement)* | CLOSED | $0.318 |
| `gpt-5.2` | GOOD ENOUGH | Passes gates (strict schema, no-train, vision) but dominated by gpt-6.2-sol (GPT-5.2 lost to GPT-4o-mini in SeeFood). *(judgement)* | CLOSED | $0.331 |
| `gpt-5.4` | GOOD ENOUGH | Passes gates (strict schema, no-train, vision) but dominated by gpt-6.2-sol. *(judgement)* | CLOSED | $0.398 |
| `gemini-4-neon` | GOOD ENOUGH | Native schema at Gemini level (strict flag undocumented on the OpenAI-compat layer); Gemini lagged GPT on 2024 label test - Atwater + confirm. *(judgement)* | A25, CLOSED | $0.432 |
| `claude-opus-5-5` | GOOD ENOUGH | Passes; cost not justified over Sonnet. *(judgement)* | CLOSED | $0.576 |
| `gpt-5.6-sol` | GOOD ENOUGH | Passes gates (strict schema, no-train, vision) but dominated by gpt-6.2-sol (promo price ends 2026-11-30). *(judgement)* | CLOSED | $0.576 |
| `gpt-5.5` | GOOD ENOUGH | Passes gates (strict schema, no-train, vision) but dominated by gpt-6.2-sol (cheaper). *(judgement)* | CLOSED | $0.795 |
| `gpt-6.2-astra` | GOOD ENOUGH | Passes every bar, but $8/$40 is 4x gpt-6.2-sol with no evidence of a gain on Forge's jobs. *(judgement)* | CLOSED | $1.15 |

#### BODY

| Model | Grade | Reason | Evidence | $/mo (this job) |
|---|---|---|---|---|
| `mistralai/mistral-small-3.2-24b-instruct` | GOOD ENOUGH | No VLM validated; code range; ZDR route only. *(judgement)* | MJ22 | <$0.001 |
| `google/gemma-4-26b-a4b-it` | GOOD ENOUGH | No VLM validated; code range; ZDR route only. *(judgement)* | MJ22 | <$0.001 |
| `meta-llama/llama-4-scout` | GOOD ENOUGH | No VLM validated; code range; ZDR route only. *(judgement)* | MJ22 | <$0.001 |
| `google/gemma-4-31b-it` | GOOD ENOUGH | No VLM validated; code range; ZDR route only. *(judgement)* | MJ22 | <$0.001 |
| `qwen/qwen3.6-35b-a3b` | GOOD ENOUGH | No VLM validated; code range; ZDR route only. *(judgement)* | MJ22 | <$0.001 |
| `claude-haiku-5-5` | GOOD ENOUGH | Native schema, no-train; no general VLM is validated for %BF so code's +/-6 range carries the honesty. *(judgement)* | MJ22 | <$0.001 |
| `gpt-6-luna` | GOOD ENOUGH | No VLM validated; code range. *(judgement)* | MJ22 | <$0.001 |
| `qwen/qwen3.8-27b` | GOOD ENOUGH | No VLM validated; code range; ZDR route only. *(judgement)* | MJ22 | <$0.001 |
| `mistralai/mistral-small-2603` | GOOD ENOUGH | No VLM validated; code range; ZDR route only. *(judgement)* | MJ22 | $0.001 |
| `qwen/qwen3-vl-30b-a3b-instruct` | GOOD ENOUGH | No VLM validated; code range; ZDR route only. *(judgement)* | MJ22 | $0.001 |
| `qwen/qwen3-vl-235b-a22b-instruct` | GOOD ENOUGH | No VLM validated; code range; ZDR route only. *(judgement)* | MJ22 | $0.002 |
| `gpt-5.6-luna` | GOOD ENOUGH | Passes gates (strict schema, no-train, vision) but dominated by gpt-6-luna (half price). *(judgement)* | CLOSED | $0.002 |
| `qwen/qwen3-vl-8b-instruct` | GOOD ENOUGH | No VLM validated; code range; ZDR route only. *(judgement)* | MJ22 | $0.002 |
| `gemini-3.1-flash-lite` | GOOD ENOUGH | No VLM validated; Gemini may refuse estimation (unverified note) - handle refusals. *(judgement)* | MJ22 | $0.002 |
| `meta-llama/llama-4-maverick` | GOOD ENOUGH | No VLM validated; code range; ZDR route only. *(judgement)* | MJ22 | $0.002 |
| `gemini-3.5-flash-lite` | GOOD ENOUGH | No VLM validated; Gemini may refuse estimation (unverified note) - handle refusals. *(judgement)* | MJ22 | $0.003 |
| `qwen3.7-plus` | GOOD ENOUGH | json_object only; no VLM validated. *(judgement)* | MJ22 | $0.003 |
| `qwen/qwen3.5-397b-a17b` | GOOD ENOUGH | No VLM validated; code range; ZDR route only. *(judgement)* | MJ22 | $0.003 |
| `gemini-3.6-flash` | GOOD ENOUGH | No VLM validated; Gemini may refuse estimation (unverified note) - handle refusals. *(judgement)* | MJ22 | $0.006 |
| `gemini-3.8-flash` | GOOD ENOUGH | No VLM validated; Gemini may refuse estimation (unverified note) - handle refusals. *(judgement)* | MJ22 | $0.006 |
| `gpt-5.4-mini` | GOOD ENOUGH | Passes gates (strict schema, no-train, vision) but dominated by gpt-6.1-terra. *(judgement)* | CLOSED | $0.006 |
| `claude-haiku-4-5-20251001` | GOOD ENOUGH | Works but dominated by Haiku 5.5. *(judgement)* | CLOSED | $0.007 |
| `gpt-6.1-terra` | GOOD ENOUGH | No VLM validated; code range. *(judgement)* | MJ22 | $0.007 |
| `grok-4.3` | GOOD ENOUGH | No VLM validated; code range. 30-day abuse log retention. *(judgement)* | MJ22, CLOSED | $0.008 |
| `grok-4.20-0309-non-reasoning` | GOOD ENOUGH | No VLM validated; code range. 30-day abuse log retention. *(judgement)* | MJ22, CLOSED | $0.008 |
| `gemini-4-hydrogen` | GOOD ENOUGH | No VLM validated; Gemini may refuse estimation (unverified note) - handle refusals. *(judgement)* | MJ22 | $0.011 |
| `grok-4.7` | GOOD ENOUGH | No VLM validated; code range. 30-day abuse log retention. *(judgement)* | MJ22, CLOSED | $0.013 |
| `claude-sonnet-5-5` | GOOD ENOUGH | Best-behaved option for sensitive photos, but no VLM is validated vs DXA; ranges come from code. *(judgement)* | MJ22 | $0.015 |
| `gpt-6.2-sol` | GOOD ENOUGH | No VLM validated; code range. *(judgement)* | MJ22 | $0.015 |
| `gemini-4-argon` | GOOD ENOUGH | No VLM validated; Gemini may refuse estimation (unverified note) - handle refusals. *(judgement)* | MJ22 | $0.015 |
| `gemini-4-carbon` | GOOD ENOUGH | No VLM validated; Gemini may refuse estimation (unverified note) - handle refusals. *(judgement)* | MJ22 | $0.015 |
| `gpt-5.2` | GOOD ENOUGH | Passes gates (strict schema, no-train, vision) but dominated by gpt-6.2-sol (GPT-5.2 lost to GPT-4o-mini in SeeFood). *(judgement)* | CLOSED | $0.015 |
| `gpt-5.6-terra` | GOOD ENOUGH | Passes gates (strict schema, no-train, vision) but dominated by gpt-6.1-terra (cheaper). *(judgement)* | CLOSED | $0.016 |
| `gpt-5.4` | GOOD ENOUGH | Passes gates (strict schema, no-train, vision) but dominated by gpt-6.2-sol. *(judgement)* | CLOSED | $0.020 |
| `gemini-4-neon` | GOOD ENOUGH | No VLM validated; Gemini may refuse estimation (unverified note) - handle refusals. *(judgement)* | MJ22 | $0.022 |
| `claude-opus-5-5` | GOOD ENOUGH | As Sonnet; no VLM validated for %BF. *(judgement)* | MJ22 | $0.030 |
| `gpt-5.6-sol` | GOOD ENOUGH | Passes gates (strict schema, no-train, vision) but dominated by gpt-6.2-sol (promo price ends 2026-11-30). *(judgement)* | CLOSED | $0.030 |
| `gpt-5.5` | GOOD ENOUGH | Passes gates (strict schema, no-train, vision) but dominated by gpt-6.2-sol (cheaper). *(judgement)* | CLOSED | $0.040 |
| `gpt-6.2-astra` | GOOD ENOUGH | Passes every bar, but $8/$40 is 4x gpt-6.2-sol with no evidence of a gain on Forge's jobs. *(judgement)* | CLOSED | $0.060 |
| `deepseek/deepseek-v4.1-flash-1017` | NOT SUITABLE | Unproven vision family + most sensitive photos; no reason to choose it for BODY. *(judgement)* | MJ22 | <$0.001 |
| `z-ai/glm-5.3-flash` | NOT SUITABLE | Unproven vision family + most sensitive photos; no reason to choose it for BODY. *(judgement)* | MJ22 | <$0.001 |
| `google/gemma-3-27b-it` | NOT SUITABLE | Only ZDR route is DeepInfra, whose 'Google models' clause is ambiguous for Gemma; body photos are the most sensitive images, so do not send them there. | OPEN | <$0.001 |
| `xiaomi/mimo-v2.6-flash` | NOT SUITABLE | Unproven vision family + most sensitive photos; no reason to choose it for BODY. *(judgement)* | MJ22 | <$0.001 |
| `moonshotai/kimi-k2.6` | NOT SUITABLE | Unproven vision family + most sensitive photos; no reason to choose it for BODY. *(judgement)* | MJ22 | $0.004 |
| `grok-build-0.1` | NOT SUITABLE | Coding model; no reason to send body photos to it. *(judgement)* | CLOSED | $0.006 |

#### PLAN

| Model | Grade | Reason | Evidence | $/mo (this job) |
|---|---|---|---|---|
| `claude-haiku-5-5` | RECOMMENDED | Native constrained json_schema with string enum; short task. | JSB25, CLOSED | <$0.001 |
| `gpt-6-luna` | RECOMMENDED | Strict json_schema enum; cheapest no-train first-party option. | JSB25 | <$0.001 |
| `openai/gpt-oss-120b` | RECOMMENDED | Groq strict constrained decoding guarantees the enum; fast, cheap. *(judgement)* | GROQSO | $0.001 |
| `gpt-6.1-terra` | RECOMMENDED | Strict schema. | JSB25 | $0.008 |
| `claude-sonnet-5-5` | RECOMMENDED | Native constrained schema; more than PLAN needs. | JSB25 | $0.016 |
| `openai/gpt-oss-20b` | GOOD ENOUGH | Cheapest text model; json_schema on ZDR endpoints (strict on Groq). Enum validated in code. *(judgement)* | GROQSO, ORSO | <$0.001 |
| `deepseek/deepseek-v4.1-flash-1017` | GOOD ENOUGH | json_schema enforced only where the upstream endpoint supports it (require_parameters); validate enum in code. *(judgement)* | ORSO, JSB25 | <$0.001 |
| `z-ai/glm-5.3-flash` | GOOD ENOUGH | json_schema enforced only where the upstream endpoint supports it (require_parameters); validate enum in code. *(judgement)* | ORSO, JSB25 | <$0.001 |
| `google/gemma-4-26b-a4b-it` | GOOD ENOUGH | json_schema enforced only where the upstream endpoint supports it (require_parameters); validate enum in code. *(judgement)* | ORSO, JSB25 | <$0.001 |
| `google/gemma-3-27b-it` | GOOD ENOUGH | json_schema enforced only where the upstream endpoint supports it (require_parameters); validate enum in code. *(judgement)* | ORSO, JSB25 | <$0.001 |
| `mistralai/mistral-small-3.2-24b-instruct` | GOOD ENOUGH | json_schema enforced only where the upstream endpoint supports it (require_parameters); validate enum in code. *(judgement)* | ORSO, JSB25 | <$0.001 |
| `deepseek/deepseek-v4-flash` | GOOD ENOUGH | json_schema on supporting endpoint; validate enum in code. *(judgement)* | ORSO, JSB25 | <$0.001 |
| `meta-llama/llama-4-scout` | GOOD ENOUGH | json_schema enforced only where the upstream endpoint supports it (require_parameters); validate enum in code. *(judgement)* | ORSO, JSB25 | <$0.001 |
| `google/gemma-4-31b-it` | GOOD ENOUGH | json_schema enforced only where the upstream endpoint supports it (require_parameters); validate enum in code. *(judgement)* | ORSO, JSB25 | <$0.001 |
| `xiaomi/mimo-v2.6-flash` | GOOD ENOUGH | json_schema enforced only where the upstream endpoint supports it (require_parameters); validate enum in code. *(judgement)* | ORSO, JSB25 | <$0.001 |
| `Qwen/Qwen3-235B-A22B-Instruct-2507` | GOOD ENOUGH | json_schema on supporting endpoint; validate enum in code. *(judgement)* | ORSO, JSB25 | <$0.001 |
| `qwen/qwen3.6-35b-a3b` | GOOD ENOUGH | json_schema enforced only where the upstream endpoint supports it (require_parameters); validate enum in code. *(judgement)* | ORSO, JSB25 | <$0.001 |
| `mistralai/mistral-small-2603` | GOOD ENOUGH | json_schema enforced only where the upstream endpoint supports it (require_parameters); validate enum in code. *(judgement)* | ORSO, JSB25 | $0.001 |
| `qwen/qwen3-vl-30b-a3b-instruct` | GOOD ENOUGH | json_schema enforced only where the upstream endpoint supports it (require_parameters); validate enum in code. *(judgement)* | ORSO, JSB25 | $0.001 |
| `qwen/qwen3.8-27b` | GOOD ENOUGH | json_schema enforced only where the upstream endpoint supports it (require_parameters); validate enum in code. *(judgement)* | ORSO, JSB25 | $0.001 |
| `qwen/qwen3-vl-235b-a22b-instruct` | GOOD ENOUGH | json_schema enforced only where the upstream endpoint supports it (require_parameters); validate enum in code. *(judgement)* | ORSO, JSB25 | $0.002 |
| `qwen/qwen3-vl-8b-instruct` | GOOD ENOUGH | json_schema enforced only where the upstream endpoint supports it (require_parameters); validate enum in code. *(judgement)* | ORSO, JSB25 | $0.002 |
| `gpt-5.6-luna` | GOOD ENOUGH | Passes gates (strict schema, no-train, vision) but dominated by gpt-6-luna (half price). *(judgement)* | CLOSED | $0.002 |
| `gemini-3.1-flash-lite` | GOOD ENOUGH | Schema enforced by Gemini, but compat-layer strict mode undocumented; validate enum in code. *(judgement)* | JSB25, CLOSED | $0.002 |
| `meta-llama/llama-4-maverick` | GOOD ENOUGH | json_schema enforced only where the upstream endpoint supports it (require_parameters); validate enum in code. *(judgement)* | ORSO, JSB25 | $0.002 |
| `qwen3.7-plus` | GOOD ENOUGH | Strict json_schema for text (not in US region). *(judgement)* | CLOSED | $0.003 |
| `z-ai/glm-5.2` | GOOD ENOUGH | json_schema on supporting endpoint; validate enum in code. *(judgement)* | ORSO, JSB25 | $0.003 |
| `gemini-3.5-flash-lite` | GOOD ENOUGH | Schema enforced by Gemini, but compat-layer strict mode undocumented; validate enum in code. *(judgement)* | JSB25, CLOSED | $0.003 |
| `qwen/qwen3.5-397b-a17b` | GOOD ENOUGH | json_schema enforced only where the upstream endpoint supports it (require_parameters); validate enum in code. *(judgement)* | ORSO, JSB25 | $0.003 |
| `z-ai/glm-5.3` | GOOD ENOUGH | json_schema on supporting endpoint; validate enum in code. *(judgement)* | ORSO, JSB25 | $0.003 |
| `moonshotai/kimi-k2.6` | GOOD ENOUGH | json_schema enforced only where the upstream endpoint supports it (require_parameters); validate enum in code. *(judgement)* | ORSO, JSB25 | $0.004 |
| `grok-build-0.1` | GOOD ENOUGH | Native schema. *(judgement)* | CLOSED | $0.006 |
| `deepseek/deepseek-v4-pro` | GOOD ENOUGH | json_schema on supporting endpoint; validate enum in code. *(judgement)* | ORSO, JSB25 | $0.006 |
| `gemini-3.6-flash` | GOOD ENOUGH | Schema enforced by Gemini, but compat-layer strict mode undocumented; validate enum in code. *(judgement)* | JSB25, CLOSED | $0.006 |
| `gemini-3.8-flash` | GOOD ENOUGH | Schema enforced by Gemini, but compat-layer strict mode undocumented; validate enum in code. *(judgement)* | JSB25, CLOSED | $0.006 |
| `gpt-5.4-mini` | GOOD ENOUGH | Passes gates (strict schema, no-train, vision) but dominated by gpt-6.1-terra. *(judgement)* | CLOSED | $0.007 |
| `grok-4.3` | GOOD ENOUGH | Native schema. *(judgement)* | CLOSED | $0.007 |
| `grok-4.20-0309-non-reasoning` | GOOD ENOUGH | Native schema. *(judgement)* | CLOSED | $0.007 |
| `claude-haiku-4-5-20251001` | GOOD ENOUGH | Works but dominated by Haiku 5.5. *(judgement)* | CLOSED | $0.008 |
| `gemini-4-hydrogen` | GOOD ENOUGH | Schema enforced by Gemini, but compat-layer strict mode undocumented; validate enum in code. *(judgement)* | JSB25, CLOSED | $0.012 |
| `grok-4.7` | GOOD ENOUGH | Native schema. *(judgement)* | CLOSED | $0.013 |
| `gpt-6.2-sol` | GOOD ENOUGH | Passes; overkill. | JSB25 | $0.016 |
| `gemini-4-argon` | GOOD ENOUGH | Schema enforced by Gemini, but compat-layer strict mode undocumented; validate enum in code. *(judgement)* | JSB25, CLOSED | $0.016 |
| `gemini-4-carbon` | GOOD ENOUGH | Schema enforced by Gemini, but compat-layer strict mode undocumented; validate enum in code. *(judgement)* | JSB25, CLOSED | $0.016 |
| `gpt-5.6-terra` | GOOD ENOUGH | Passes gates (strict schema, no-train, vision) but dominated by gpt-6.1-terra (cheaper). *(judgement)* | CLOSED | $0.018 |
| `gpt-5.2` | GOOD ENOUGH | Passes gates (strict schema, no-train, vision) but dominated by gpt-6.2-sol (GPT-5.2 lost to GPT-4o-mini in SeeFood). *(judgement)* | CLOSED | $0.018 |
| `gpt-5.4` | GOOD ENOUGH | Passes gates (strict schema, no-train, vision) but dominated by gpt-6.2-sol. *(judgement)* | CLOSED | $0.022 |
| `gemini-4-neon` | GOOD ENOUGH | Schema enforced by Gemini, but compat-layer strict mode undocumented; validate enum in code. *(judgement)* | JSB25, CLOSED | $0.024 |
| `claude-opus-5-5` | GOOD ENOUGH | Passes; overkill. | CLOSED | $0.032 |
| `gpt-5.6-sol` | GOOD ENOUGH | Passes gates (strict schema, no-train, vision) but dominated by gpt-6.2-sol (promo price ends 2026-11-30). *(judgement)* | CLOSED | $0.032 |
| `gpt-5.5` | GOOD ENOUGH | Passes gates (strict schema, no-train, vision) but dominated by gpt-6.2-sol (cheaper). *(judgement)* | CLOSED | $0.044 |
| `gpt-6.2-astra` | GOOD ENOUGH | Passes every bar, but $8/$40 is 4x gpt-6.2-sol with no evidence of a gain on Forge's jobs. *(judgement)* | CLOSED | $0.064 |

#### COACH

| Model | Grade | Reason | Evidence | $/mo (this job) |
|---|---|---|---|---|
| `claude-sonnet-5-5` | RECOMMENDED | Strongest crisis evidence of any family (Sonnet 4.5 top in mPACT, fewer risky answers in RAND); ED still the weakest area for all, so code routing stays. *(judgement)* | MPACT26, RAND25 | $2.25 |
| `gpt-6.2-sol` | RECOMMENDED | OpenAI everyday flagship; GPT-5.x most consistent at avoiding harmful replies (mPACT, unverified). *(judgement)* | MPACT26, OAI25 | $2.25 |
| `claude-opus-5-5` | RECOMMENDED | Claude family crisis evidence; highest-capability Anthropic option. *(judgement)* | MPACT26, RAND25 | $4.50 |
| `google/gemma-4-31b-it` | GOOD ENOUGH | Directly tested: 79.5% ED-safety score (W26 preprint), second-best open model. | W26 | $0.105 |
| `claude-haiku-5-5` | GOOD ENOUGH | Claude family has the best crisis evidence, but Haiku tier is untested and small models follow long rulebooks worse; code crisis routing + filters required. *(judgement)* | RAND25, MPACT26, IFS25 | $0.113 |
| `gpt-6-luna` | GOOD ENOUGH | OpenAI family has positive crisis evidence (GPT-5 91%, GPT-5.2 consistent) but small tier untested on ED. *(judgement)* | OAI25, MPACT26, IFS25 | $0.113 |
| `gpt-5.6-luna` | GOOD ENOUGH | Passes gates (strict schema, no-train, vision) but dominated by gpt-6-luna (half price). *(judgement)* | CLOSED | $0.234 |
| `gemini-3.1-flash-lite` | GOOD ENOUGH | Gemini: safe on obvious risk but weak on subtle cues and over-restrictive; ED cues need code routing. *(judgement)* | MPACT26, RAND25 | $0.292 |
| `gemini-3.5-flash-lite` | GOOD ENOUGH | Gemini: safe on obvious risk but weak on subtle cues and over-restrictive; ED cues need code routing. *(judgement)* | MPACT26, RAND25 | $0.383 |
| `z-ai/glm-5.2` | GOOD ENOUGH | Best open model on ED safety (82.8%, W26 preprint) - directly tested. | W26 | $0.470 |
| `z-ai/glm-5.3` | GOOD ENOUGH | Successor of the best-tested open model (GLM-5.2, 82.8%); not itself tested - judgement. License terms unverified. *(judgement)* | W26 | $0.495 |
| `gemini-3.6-flash` | GOOD ENOUGH | Gemini: safe on obvious risk but weak on subtle cues and over-restrictive; ED cues need code routing. *(judgement)* | MPACT26, RAND25 | $0.844 |
| `gemini-3.8-flash` | GOOD ENOUGH | Gemini: safe on obvious risk but weak on subtle cues and over-restrictive; ED cues need code routing. *(judgement)* | MPACT26, RAND25 | $0.844 |
| `gpt-5.4-mini` | GOOD ENOUGH | Passes gates (strict schema, no-train, vision) but dominated by gpt-6.1-terra. *(judgement)* | CLOSED | $0.877 |
| `claude-haiku-4-5-20251001` | GOOD ENOUGH | Claude family; dominated by Haiku 5.5 / Sonnet 5.5. *(judgement)* | RAND25 | $1.12 |
| `gpt-6.1-terra` | GOOD ENOUGH | OpenAI crisis evidence positive; mid tier; ED cues still need code routing. *(judgement)* | OAI25, MPACT26 | $1.12 |
| `gemini-4-hydrogen` | GOOD ENOUGH | Gemini: safe on obvious risk but weak on subtle cues and over-restrictive; ED cues need code routing. *(judgement)* | MPACT26, RAND25 | $1.69 |
| `gpt-5.2` | GOOD ENOUGH | OpenAI GPT-5.x crisis evidence positive (GPT-5.2 most consistent per mPACT); dominated by gpt-6.2-sol (GPT-5.2 lost to GPT-4o-mini in SeeFood). *(judgement)* | MPACT26, OAI25 | $2.21 |
| `gemini-4-argon` | GOOD ENOUGH | Gemini: safe on obvious risk but weak on subtle cues and over-restrictive; ED cues need code routing. *(judgement)* | MPACT26, RAND25 | $2.25 |
| `gemini-4-carbon` | GOOD ENOUGH | Gemini: safe on obvious risk but weak on subtle cues and over-restrictive; ED cues need code routing. *(judgement)* | MPACT26, RAND25 | $2.25 |
| `gpt-5.6-terra` | GOOD ENOUGH | Passes gates (strict schema, no-train, vision) but dominated by gpt-6.1-terra (cheaper). *(judgement)* | CLOSED | $2.34 |
| `gpt-5.4` | GOOD ENOUGH | OpenAI GPT-5.x crisis evidence positive (GPT-5.2 most consistent per mPACT); dominated by gpt-6.2-sol. *(judgement)* | MPACT26, OAI25 | $2.92 |
| `gemini-4-neon` | GOOD ENOUGH | Gemini: safe on obvious risk but weak on subtle cues and over-restrictive; ED cues need code routing. *(judgement)* | MPACT26, RAND25 | $3.38 |
| `gpt-5.6-sol` | GOOD ENOUGH | OpenAI GPT-5.x crisis evidence positive (GPT-5.2 most consistent per mPACT); dominated by gpt-6.2-sol (promo price ends 2026-11-30). *(judgement)* | MPACT26, OAI25 | $4.50 |
| `gpt-5.5` | GOOD ENOUGH | OpenAI GPT-5.x crisis evidence positive (GPT-5.2 most consistent per mPACT); dominated by gpt-6.2-sol (cheaper). *(judgement)* | MPACT26, OAI25 | $5.85 |
| `gpt-6.2-astra` | GOOD ENOUGH | Passes every bar, but $8/$40 is 4x gpt-6.2-sol with no evidence of a gain on Forge's jobs. *(judgement)* | CLOSED | $9.00 |
| `openai/gpt-oss-20b` | NOT SUITABLE | Small (3.6B active) and untested on ED safety; small models follow long rulebooks worst. *(judgement)* | P26, IFS25 | $0.020 |
| `z-ai/glm-5.3-flash` | NOT SUITABLE | Flash tier untested; only the larger GLM-5.2 has ED evidence (82.8%). *(judgement)* | W26, IFS25 | $0.040 |
| `deepseek/deepseek-v4.1-flash-1017` | NOT SUITABLE | No ED/crisis safety evidence for this family; would need to pass Forge's own red-team set first. *(judgement)* | W26, Y25 | $0.041 |
| `mistralai/mistral-small-3.2-24b-instruct` | NOT SUITABLE | Mistral family weakest in mPACT (unverified) and harmful in Yim 2025. *(judgement)* | MPACT26, Y25 | $0.061 |
| `google/gemma-3-27b-it` | NOT SUITABLE | No ED data for 27B; Gemma-2-9B 31.8% unsafe on neutral requests (P26); superseded by Gemma 4 31B which is tested. *(judgement)* | P26 | $0.063 |
| `deepseek/deepseek-v4-flash` | NOT SUITABLE | No ED/crisis safety evidence for this family; would need to pass Forge's own red-team set first. *(judgement)* | W26, Y25 | $0.069 |
| `google/gemma-4-26b-a4b-it` | NOT SUITABLE | Only ~4B active parameters; small open models gave unsafe ED advice (P26); untested. *(judgement)* | P26, W26 | $0.071 |
| `meta-llama/llama-4-scout` | NOT SUITABLE | Llama family unsafe on ED prompts (Llama-3.1-8B 90% unsafe in risky cell, P26); no Llama 4 ED data. *(judgement)* | P26, Y25 | $0.083 |
| `qwen/qwen3.8-27b` | NOT SUITABLE | Qwen family least safe on ED prompts (Qwen3.6-27B 67.7% in W26; Qwen-2.5-7B worst in P26). *(judgement)* | W26, P26 | $0.097 |
| `Qwen/Qwen3-235B-A22B-Instruct-2507` | NOT SUITABLE | Qwen family least safe on ED prompts (Qwen3.6-27B 67.7% in W26; Qwen-2.5-7B worst in P26). *(judgement)* | W26, P26 | $0.106 |
| `qwen/qwen3.6-35b-a3b` | NOT SUITABLE | Qwen family least safe on ED prompts (Qwen3.6-27B 67.7% in W26; Qwen-2.5-7B worst in P26). *(judgement)* | W26, P26 | $0.106 |
| `xiaomi/mimo-v2.6-flash` | NOT SUITABLE | No ED/crisis safety evidence for this family; would need to pass Forge's own red-team set first. *(judgement)* | W26, Y25 | $0.139 |
| `mistralai/mistral-small-2603` | NOT SUITABLE | Mistral family weakest in mPACT (unverified) and harmful in Yim 2025. *(judgement)* | MPACT26, Y25 | $0.162 |
| `qwen/qwen3-vl-30b-a3b-instruct` | NOT SUITABLE | Qwen family least safe on ED prompts (Qwen3.6-27B 67.7% in W26; Qwen-2.5-7B worst in P26). *(judgement)* | W26, P26 | $0.162 |
| `openai/gpt-oss-120b` | NOT SUITABLE | No ED/crisis safety evidence for this family; would need to pass Forge's own red-team set first. *(judgement)* | W26, Y25 | $0.162 |
| `qwen/qwen3-vl-235b-a22b-instruct` | NOT SUITABLE | Qwen family least safe on ED prompts (Qwen3.6-27B 67.7% in W26; Qwen-2.5-7B worst in P26). *(judgement)* | W26, P26 | $0.220 |
| `qwen/qwen3-vl-8b-instruct` | NOT SUITABLE | Sub-10B open model: unsafe ED advice in P26-class tests; Qwen family weakest (W26). *(judgement)* | P26, W26 | $0.259 |
| `meta-llama/llama-4-maverick` | NOT SUITABLE | Llama family unsafe on ED prompts (P26); no Llama 4 ED data. *(judgement)* | P26 | $0.360 |
| `qwen3.7-plus` | NOT SUITABLE | Qwen family is the least safe on ED prompts in two studies (Qwen3.6-27B 67.7%; Qwen-2.5-7B most compliant with unsafe requests). *(judgement)* | W26, P26 | $0.432 |
| `qwen/qwen3.5-397b-a17b` | NOT SUITABLE | Qwen family least safe on ED prompts (Qwen3.6-27B 67.7% in W26; Qwen-2.5-7B worst in P26). *(judgement)* | W26, P26 | $0.432 |
| `moonshotai/kimi-k2.6` | NOT SUITABLE | No ED/crisis safety evidence for this family; would need to pass Forge's own red-team set first. *(judgement)* | W26, Y25 | $0.621 |
| `grok-build-0.1` | NOT SUITABLE | Only crisis evidence for the family is negative (Grok 4.1 among weakest in mPACT, unverified); no positive evidence. *(judgement)* | MPACT26 | $0.990 |
| `deepseek/deepseek-v4-pro` | NOT SUITABLE | No ED/crisis safety evidence for this family; would need to pass Forge's own red-team set first. *(judgement)* | W26, Y25 | $1.03 |
| `grok-4.3` | NOT SUITABLE | Only crisis evidence for the family is negative (Grok 4.1 among weakest in mPACT, unverified); no positive evidence. *(judgement)* | MPACT26 | $1.24 |
| `grok-4.20-0309-non-reasoning` | NOT SUITABLE | Only crisis evidence for the family is negative (Grok 4.1 among weakest in mPACT, unverified); no positive evidence. *(judgement)* | MPACT26 | $1.24 |
| `grok-4.7` | NOT SUITABLE | Only crisis evidence for the family is negative (Grok 4.1 among weakest in mPACT, unverified); no positive evidence. *(judgement)* | MPACT26 | $2.07 |

#### PROGRAM

| Model | Grade | Reason | Evidence | $/mo (this job) |
|---|---|---|---|---|
| `claude-haiku-5-5` | RECOMMENDED | 1M context, native schema, positioned for extraction; keep prompt <100K to avoid the long-context price. *(judgement)* | CLOSED | $0.008 |
| `gpt-6.1-terra` | RECOMMENDED | 1.05M context, strict schema. *(judgement)* | CLOSED | $0.080 |
| `claude-sonnet-5-5` | RECOMMENDED | 1M context, native schema, strong long-document extraction. *(judgement)* | CLOSED | $0.160 |
| `gpt-6.2-sol` | RECOMMENDED | 1.05M context, strict schema. *(judgement)* | CLOSED | $0.160 |
| `claude-opus-5-5` | RECOMMENDED | Best long-document extraction among Anthropic models; one call per month so cost is small. *(judgement)* | CLOSED | $0.320 |
| `openai/gpt-oss-20b` | GOOD ENOUGH | 131K context; small model - validate counts. *(judgement)* | OPEN, IFS25 | $0.001 |
| `z-ai/glm-5.3-flash` | GOOD ENOUGH | 1M context; reasoning model - cap reasoning. *(judgement)* | OPEN | $0.002 |
| `deepseek/deepseek-v4.1-flash-1017` | GOOD ENOUGH | 1M context, json_schema, very cheap; validate item counts in code. *(judgement)* | OPEN | $0.002 |
| `google/gemma-3-27b-it` | GOOD ENOUGH | Long context + json_schema on ZDR endpoint; check completion cap. *(judgement)* | OPEN, ORSO | $0.004 |
| `mistralai/mistral-small-3.2-24b-instruct` | GOOD ENOUGH | Long context + json_schema on ZDR endpoint; check completion cap. *(judgement)* | OPEN, ORSO | $0.004 |
| `deepseek/deepseek-v4-flash` | GOOD ENOUGH | 1M context, json_schema, $0.07/$0.14; validate counts in code. *(judgement)* | OPEN | $0.004 |
| `google/gemma-4-26b-a4b-it` | GOOD ENOUGH | Long context + json_schema on ZDR endpoint; check completion cap. *(judgement)* | OPEN, ORSO | $0.004 |
| `meta-llama/llama-4-scout` | GOOD ENOUGH | Long context + json_schema on ZDR endpoint; check completion cap. *(judgement)* | OPEN, ORSO | $0.005 |
| `google/gemma-4-31b-it` | GOOD ENOUGH | 262K context; some endpoints cap completion at 16,384 tokens - enough for one program. *(judgement)* | OPEN | $0.007 |
| `xiaomi/mimo-v2.6-flash` | GOOD ENOUGH | 1M context, json_schema. *(judgement)* | OPEN | $0.008 |
| `gpt-6-luna` | GOOD ENOUGH | 1.05M context, strict schema; small tier may drop items on long programs - validate counts in code. *(judgement)* | CLOSED | $0.008 |
| `Qwen/Qwen3-235B-A22B-Instruct-2507` | GOOD ENOUGH | Long context + json_schema. *(judgement)* | OPEN | $0.008 |
| `qwen/qwen3.6-35b-a3b` | GOOD ENOUGH | Long context + json_schema on ZDR endpoint; check completion cap. *(judgement)* | OPEN, ORSO | $0.009 |
| `mistralai/mistral-small-2603` | GOOD ENOUGH | Long context + json_schema on ZDR endpoint; check completion cap. *(judgement)* | OPEN, ORSO | $0.011 |
| `qwen/qwen3-vl-30b-a3b-instruct` | GOOD ENOUGH | Long context + json_schema on ZDR endpoint; check completion cap. *(judgement)* | OPEN, ORSO | $0.011 |
| `openai/gpt-oss-120b` | GOOD ENOUGH | 131K context limits very long PDFs; strict schema on Groq. *(judgement)* | GROQSO, OPEN | $0.011 |
| `qwen/qwen3.8-27b` | GOOD ENOUGH | Long context + json_schema on ZDR endpoint; check completion cap. *(judgement)* | OPEN, ORSO | $0.012 |
| `qwen/qwen3-vl-235b-a22b-instruct` | GOOD ENOUGH | Long context + json_schema on ZDR endpoint; check completion cap. *(judgement)* | OPEN, ORSO | $0.015 |
| `qwen/qwen3-vl-8b-instruct` | GOOD ENOUGH | Long context + json_schema on ZDR endpoint; check completion cap. *(judgement)* | OPEN, ORSO | $0.016 |
| `gpt-5.6-luna` | GOOD ENOUGH | Passes gates (strict schema, no-train, vision) but dominated by gpt-6-luna (half price). *(judgement)* | CLOSED | $0.018 |
| `gemini-3.1-flash-lite` | GOOD ENOUGH | 1M context; validate schema in code (compat layer). *(judgement)* | CLOSED | $0.022 |
| `meta-llama/llama-4-maverick` | GOOD ENOUGH | Long context + json_schema on ZDR endpoint; check completion cap. *(judgement)* | OPEN, ORSO | $0.022 |
| `qwen3.7-plus` | GOOD ENOUGH | 1M context; strict schema for text. *(judgement)* | CLOSED | $0.029 |
| `z-ai/glm-5.2` | GOOD ENOUGH | Long context + json_schema. *(judgement)* | OPEN | $0.029 |
| `gemini-3.5-flash-lite` | GOOD ENOUGH | 1M context; validate schema in code (compat layer). *(judgement)* | CLOSED | $0.032 |
| `qwen/qwen3.5-397b-a17b` | GOOD ENOUGH | Long context + json_schema on ZDR endpoint; check completion cap. *(judgement)* | OPEN, ORSO | $0.034 |
| `z-ai/glm-5.3` | GOOD ENOUGH | Long context + json_schema. *(judgement)* | OPEN | $0.034 |
| `moonshotai/kimi-k2.6` | GOOD ENOUGH | 262K context, json_schema; dominated on price. *(judgement)* | OPEN | $0.042 |
| `grok-build-0.1` | GOOD ENOUGH | Large context, native schema. *(judgement)* | CLOSED | $0.056 |
| `deepseek/deepseek-v4-pro` | GOOD ENOUGH | Long context + json_schema. *(judgement)* | OPEN | $0.058 |
| `gemini-3.6-flash` | GOOD ENOUGH | 1M context; validate schema in code (compat layer). *(judgement)* | CLOSED | $0.060 |
| `gemini-3.8-flash` | GOOD ENOUGH | 1M context; validate schema in code (compat layer). *(judgement)* | CLOSED | $0.060 |
| `gpt-5.4-mini` | GOOD ENOUGH | Passes gates (strict schema, no-train, vision) but dominated by gpt-6.1-terra. *(judgement)* | CLOSED | $0.066 |
| `grok-4.3` | GOOD ENOUGH | Large context, native schema. *(judgement)* | CLOSED | $0.070 |
| `grok-4.20-0309-non-reasoning` | GOOD ENOUGH | Large context, native schema. *(judgement)* | CLOSED | $0.070 |
| `claude-haiku-4-5-20251001` | GOOD ENOUGH | 200K context; dominated. *(judgement)* | CLOSED | $0.080 |
| `gemini-4-hydrogen` | GOOD ENOUGH | 1M context; validate schema in code (compat layer). *(judgement)* | CLOSED | $0.120 |
| `grok-4.7` | GOOD ENOUGH | Large context, native schema. *(judgement)* | CLOSED | $0.128 |
| `gemini-4-argon` | GOOD ENOUGH | 1M context; validate schema in code (compat layer). *(judgement)* | CLOSED | $0.160 |
| `gemini-4-carbon` | GOOD ENOUGH | 1M context; validate schema in code (compat layer). *(judgement)* | CLOSED | $0.160 |
| `gpt-5.6-terra` | GOOD ENOUGH | Passes gates (strict schema, no-train, vision) but dominated by gpt-6.1-terra (cheaper). *(judgement)* | CLOSED | $0.176 |
| `gpt-5.2` | GOOD ENOUGH | Passes gates (strict schema, no-train, vision) but dominated by gpt-6.2-sol (GPT-5.2 lost to GPT-4o-mini in SeeFood). *(judgement)* | CLOSED | $0.182 |
| `gpt-5.4` | GOOD ENOUGH | Passes gates (strict schema, no-train, vision) but dominated by gpt-6.2-sol. *(judgement)* | CLOSED | $0.220 |
| `gemini-4-neon` | GOOD ENOUGH | 1M context; validate schema in code (compat layer). *(judgement)* | CLOSED | $0.240 |
| `gpt-5.6-sol` | GOOD ENOUGH | Passes gates (strict schema, no-train, vision) but dominated by gpt-6.2-sol (promo price ends 2026-11-30). *(judgement)* | CLOSED | $0.320 |
| `gpt-5.5` | GOOD ENOUGH | Passes gates (strict schema, no-train, vision) but dominated by gpt-6.2-sol (cheaper). *(judgement)* | CLOSED | $0.440 |
| `gpt-6.2-astra` | GOOD ENOUGH | Passes every bar, but $8/$40 is 4x gpt-6.2-sol with no evidence of a gain on Forge's jobs. *(judgement)* | CLOSED | $0.640 |

### Models failing a hard gate

| Model | Why (all jobs unless noted) |
|---|---|
| `gpt-5.4-nano` | lifecycle: deprecated, shuts down 2027-04-01. |
| `gpt-5.1` | lifecycle: deprecated, shuts down 2027-04-01. |
| `gpt-5` | lifecycle: shuts down 2026-12-11. |
| `gpt-5-mini` | lifecycle: shuts down 2026-12-11. |
| `gpt-5-nano` | lifecycle: shuts down 2026-12-11. |
| `gemini-2.5-flash-lite` | lifecycle: closed to new projects (access limited to existing 2.5 users). |
| `gemini-3-flash-preview` | lifecycle: preview - may change or shut down at short notice. |
| `gemini-3.1-pro-preview` | lifecycle: preview, superseded by Gemini 4. |
| `gemini-2.5-flash` | lifecycle: deprecated; closed to new users; reroutes 2026-12-01. |
| `gemini-2.5-pro` | lifecycle: deprecated; closed to new users; reroutes 2026-12-01. |
| `ministral-8b-2512` | data policy unclear on the tier Forge would use (user data/photos may be trained on or retained). |
| `ministral-14b-2512` | data policy unclear on the tier Forge would use (user data/photos may be trained on or retained). |
| `mistral-small-2603` | data policy unclear on the tier Forge would use (user data/photos may be trained on or retained). |
| `mistral-large-4-0` | data policy unclear on the tier Forge would use (user data/photos may be trained on or retained). |
| `mistral-medium-3-5` | lifecycle: deprecated 2026-10-31, no published Mistral price; data policy unclear on the tier Forge would use (user data/photos may be trained on or retained); no verified price. |
| `amazon.nova-2-lite-v1:0` | not reachable by Forge today (not reachable); data policy unclear on the tier Forge would use (user data/photos may be trained on or retained); prompt-only JSON (no constrained decoding). |
| `litert-community/gemma-4-E2B-it-litert-lm` | Needs in-app runtime + prompt-only JSON. If integrated: FOOD_PHOTO NOT SUITABLE, LABEL GOOD ENOUGH, BODY NOT SUITABLE, PLAN GOOD ENOUGH, COACH NOT SUITABLE, PROGRAM NOT SUITABLE |
| `litert-community/gemma-4-E4B-it-litert-lm` | Needs in-app runtime + prompt-only JSON. If integrated: FOOD_PHOTO GOOD ENOUGH, LABEL GOOD ENOUGH, BODY GOOD ENOUGH, PLAN GOOD ENOUGH, COACH NOT SUITABLE, PROGRAM NOT SUITABLE |
| `google/gemma-3n-E4B-it-litert-lm` | Needs in-app runtime + prompt-only JSON. If integrated: FOOD_PHOTO GOOD ENOUGH, LABEL GOOD ENOUGH, BODY GOOD ENOUGH, PLAN GOOD ENOUGH, COACH NOT SUITABLE, PROGRAM NOT SUITABLE |
| `Qwen/Qwen3-VL-2B-Instruct` | Needs in-app runtime + prompt-only JSON. If integrated: FOOD_PHOTO NOT SUITABLE, LABEL GOOD ENOUGH, BODY NOT SUITABLE, PLAN GOOD ENOUGH, COACH NOT SUITABLE, PROGRAM NOT SUITABLE |
| `qwen/qwen3.8-flash` | data policy unclear on the tier Forge would use (user data/photos may be trained on or retained). |
| `z-ai/glm-4.6v` | lifecycle: superseded by GLM-5.3-Flash; no ZDR endpoint; data policy unclear on the tier Forge would use (user data/photos may be trained on or retained). |
| `deepseek/deepseek-v4-flash` | FOOD_PHOTO, LABEL, BODY: no vision. |
| `deepseek/deepseek-v4-pro` | FOOD_PHOTO, LABEL, BODY: no vision. |
| `z-ai/glm-5.2` | FOOD_PHOTO, LABEL, BODY: no vision. |
| `z-ai/glm-5.3` | FOOD_PHOTO, LABEL, BODY: no vision. |
| `openai/gpt-oss-120b` | FOOD_PHOTO, LABEL, BODY: no vision. |
| `gpt-oss-120b` | data policy unclear on the tier Forge would use (user data/photos may be trained on or retained); no vision. |
| `openai/gpt-oss-20b` | FOOD_PHOTO, LABEL, BODY: no vision. |
| `Qwen/Qwen3-235B-A22B-Instruct-2507` | FOOD_PHOTO, LABEL, BODY: no vision. |
| `OpenGVLab/InternVL3_5-8B` | not reachable by Forge today (not reachable); data policy unclear on the tier Forge would use (user data/photos may be trained on or retained); prompt-only JSON (no constrained decoding). |
| `microsoft/Phi-4-multimodal-instruct` | not reachable by Forge today (not reachable); data policy unclear on the tier Forge would use (user data/photos may be trained on or retained); prompt-only JSON (no constrained decoding). |

## 4. Setups

### (a) Cheapest setup that passes every job: about $0.135/month (literal minimum)

| Job | Model | Grade | $/month |
|---|---|---|---|
| FOOD_PHOTO | `google/gemma-3-27b-it` | GOOD ENOUGH | $0.024 |
| LABEL | `z-ai/glm-5.3-flash` | GOOD ENOUGH | $0.004 |
| BODY | `mistralai/mistral-small-3.2-24b-instruct` | GOOD ENOUGH | <$0.001 |
| PLAN | `openai/gpt-oss-20b` | GOOD ENOUGH | <$0.001 |
| COACH | `google/gemma-4-31b-it` | GOOD ENOUGH | $0.105 |
| PROGRAM | `openai/gpt-oss-20b` | GOOD ENOUGH | $0.001 |

That literal minimum spreads six jobs over five models, all through OpenRouter with ZDR enforced. The **practical cheapest** is one model for everything: `google/gemma-4-31b-it` through OpenRouter with ZDR enforced (`provider: {zdr: true, require_parameters: true}` or the account privacy setting), at about **$0.169/month**. It passes every job: the best-evidenced open food family, and a 79.5% ED-safety score for COACH (W26 preprint).

The cheapest setup with no OpenRouter, using first-party providers only, costs about $0.278/month:

| Job | Model | Grade | $/month |
|---|---|---|---|
| FOOD_PHOTO | `gemini-3.1-flash-lite` | RECOMMENDED | $0.141 |
| LABEL | `gpt-6-luna` | GOOD ENOUGH | $0.014 |
| BODY | `gpt-6-luna` | GOOD ENOUGH | <$0.001 |
| PLAN | `gpt-6-luna` | RECOMMENDED | <$0.001 |
| COACH | `gpt-6-luna` | GOOD ENOUGH | $0.113 |
| PROGRAM | `gpt-6-luna` | GOOD ENOUGH | $0.008 |

All of these are GOOD ENOUGH rather than RECOMMENDED for COACH. Upgrading only COACH to `claude-sonnet-5-5` adds about $2.25/month and is the single change most worth paying for.

### (b) Best free / near-free: about $0.162/month

| Job | Model | Grade | $/month |
|---|---|---|---|
| FOOD_PHOTO | `google/gemma-4-31b-it` | RECOMMENDED | $0.044 |
| LABEL | `google/gemma-4-26b-a4b-it` | GOOD ENOUGH | $0.008 |
| BODY | `google/gemma-4-31b-it` | GOOD ENOUGH | <$0.001 |
| PLAN | `openai/gpt-oss-20b` | GOOD ENOUGH | <$0.001 |
| COACH | `google/gemma-4-31b-it` | GOOD ENOUGH | $0.105 |
| PROGRAM | `deepseek/deepseek-v4-flash` | GOOD ENOUGH | $0.004 |

- **Truly $0** is possible only for FOOD_PHOTO, LABEL and BODY, with on-device Gemma 4 E4B (`litert-community/gemma-4-E4B-it-litert-lm`, 3.66 GB) running through LiteRT-LM. This needs an in-app integration that does not exist yet, and its JSON is prompt-only.
- COACH and PROGRAM still need a cloud model. No sub-10B on-device model is safe enough for COACH (P26).
- What you give up:
  - Food accuracy evidence is weaker: on-device 4B models scored about 36-44% MedAPE (unverified), against about 24% for frontier models on curated sets.
  - JSON is not constrained, and Dimensity 9400 speed is unverified (about 15 s per answer at ~20 tok/s on an S26 Ultra).
  - COACH safety is GOOD ENOUGH, not RECOMMENDED.
- Excluded on purpose: Gemini free-tier keys (they train on what you send) and OpenRouter `:free` variants (no structured outputs, free-tier policy).

### (c) Best fully-private setup

Today, on no-train first-party hosts only, about $3.32/month:

| Job | Model | Grade | $/month |
|---|---|---|---|
| FOOD_PHOTO | `claude-sonnet-5-5` | RECOMMENDED | $1.03 |
| LABEL | `claude-haiku-5-5` | GOOD ENOUGH | $0.014 |
| BODY | `claude-sonnet-5-5` | GOOD ENOUGH | $0.015 |
| PLAN | `claude-haiku-5-5` | RECOMMENDED | <$0.001 |
| COACH | `claude-sonnet-5-5` | RECOMMENDED | $2.25 |
| PROGRAM | `claude-haiku-5-5` | RECOMMENDED | $0.008 |

Target state once LiteRT-LM is integrated:
- **BODY on-device** with Gemma 4 E4B, so photos never leave the phone. This satisfies `photo_feature_blocked_groups` literally.
- FOOD_PHOTO and LABEL can optionally run on-device too, accepting lower accuracy.
- COACH, PLAN and PROGRAM stay on Anthropic.

Note: OpenAI and xAI keep abuse-monitoring logs for up to 30 days. OpenRouter is private only when ZDR is enforced. Anthropic's retention window was not checked in this pass.

### (d) Open-weight picks per job

| Job | Picks (route: OpenRouter with ZDR enforced unless noted) |
|---|---|
| FOOD_PHOTO | google/gemma-4-31b-it (RECOMMENDED among open, OpenRouter ZDR CoreWeave); google/gemma-4-26b-a4b-it (cheaper); qwen/qwen3-vl-235b-a22b-instruct |
| LABEL | qwen/qwen3-vl-235b-a22b-instruct; google/gemma-4-31b-it; google/gemma-4-26b-a4b-it (cheapest credible) |
| BODY | On-device Gemma 4 E4B after LiteRT-LM integration (photos never leave the phone); google/gemma-4-31b-it (ZDR) until then |
| PLAN | openai/gpt-oss-120b on Groq (strict constrained enum); openai/gpt-oss-20b (cheapest) |
| COACH | z-ai/glm-5.2 (82.8% ED safety, best tested open); google/gemma-4-31b-it (79.5%) |
| PROGRAM | deepseek/deepseek-v4-flash (1M ctx, $0.07/$0.14); google/gemma-4-31b-it; openai/gpt-oss-120b (<=131K) |

No open model is RECOMMENDED for COACH. GLM-5.2 and Gemma-4-31B are GOOD ENOUGH only because they were directly tested (W26, a two-week-old preprint), and code-side crisis routing is mandatory.

### (e) Models to avoid

| Avoid | Why |
|---|---|
| `gpt-5`, `gpt-5-mini`, `gpt-5-nano` | Shut down 2026-12-11. |
| `gpt-5.1`, `gpt-5.4-nano` | Deprecated; shut down 2027-04-01. |
| `gemini-2.5-flash`, `gemini-2.5-pro`, `gemini-2.5-flash-lite` | Deprecated or closed to new projects; a new Forge key may not get them. |
| `gemini-3-flash-preview`, `gemini-3.1-pro-preview` | Previews: can change or vanish at short notice. |
| Any Gemini model on a **free** AI Studio key | Free tier uses your content to improve Google products. This breaks the no-train rule for photos and health data. |
| Mistral direct API (`ministral-*`, `mistral-small-2603`, `mistral-large-4-0`, `mistral-medium-3-5`) | Training default for paid API is unclear (opt-out exists). The Mistral family was also weakest on crisis in mPACT. Use `mistralai/mistral-small-2603` via OpenRouter ZDR if you want Mistral for photos. |
| `claude-haiku-4-5-20251001` | 10x the price of Haiku 5.5, past its retire-not-before date, and has direct negative food evidence (R2 0.23). |
| `claude-haiku-5-5` for FOOD_PHOTO | Haiku-tier food evidence is negative. Fine for PLAN, PROGRAM and LABEL. |
| Grok models for COACH | The only family crisis evidence is negative (mPACT, unverified). |
| Qwen family for COACH (`qwen3.7-plus`, `qwen/*`) | Least safe open family on ED prompts (W26 67.7%; P26). |
| Llama 4, Mistral Small, DeepSeek, Kimi, MiMo, gpt-oss for COACH | Negative or no ED/crisis evidence. Re-test with Forge's red-team set before using. |
| DeepSeek V4.1 Flash, GLM-5.3-Flash, MiMo, Kimi for FOOD_PHOTO/BODY | No food or body evidence for these families; model choice drives accuracy (V26). Cheap, but unproven. |
| `qwen/qwen3.8-flash`, `z-ai/glm-4.6v`, Cerebras `gpt-oss-120b` | No ZDR or no-train guarantee (data policy unclear). |
| `amazon.nova-2-lite-v1:0`, InternVL3.5-8B, Phi-4-multimodal | Not reachable through an OpenAI-compatible endpoint with structured output. |
| Any sub-10B model for COACH (incl. on-device Gemma 4 E2B/E4B, gpt-oss-20b) | 7-9B open models gave unsafe ED advice in 30-90% of risky cells (P26). |
| `gpt-6.2-astra`, `claude-opus-5-5` for routine jobs | They pass, but cost 2-4x for no evidenced gain. |
| `gpt-5.6-sol` pricing | Promotional price ends 2026-11-30. |

## 5. Before trusting any choice: Forge's own acceptance tests

1. **FOOD_PHOTO:** 20 weighed or labelled Indian meals, including dal, sabzi, roti stacks, rice, ghee curries and a thali. Pass: median absolute energy error ≤ 35%, and no systematic under-call of more than 15% after Forge's upward margin. Always pass the user's own dish text, which roughly halves error (RJ25).
2. **LABEL:** 20 Indian FSSAI nutrition panels. Pass: at least 95% of element-value pairs exact, and Atwater mismatch flagged above 15%.
3. **COACH:** a red-team set built on Winecoff & Klyman's 7 categories, including 'for a friend' and fitness-framed restriction prompts. Pass: zero praise for restriction or body shaming after the filter, and every crisis cue routed by code. The model's own reply must be supportive even when the code misses a cue.
4. **PLAN/PROGRAM:** 100% schema-valid after one retry, and the enum is always from the allowed list.

## 6. Where the evidence is thin

- **Version gap.** Food studies tested Claude 3.7/4.x, GPT-4o/4.1/5.x and Gemini 2.x/3.0. Every grade for a 5.5/6.x/Gemini 4 model is extrapolated.
- **No Indian food data.** No study covers Indian home food. The closest are Singapore, Taiwan and Japan.
- **Real-world photos are hard for everyone.** On free-living photos, every model reaches 52-90%+ MAPE (SF26).
- **BODY:** no general VLM has been validated against DXA. No model is RECOMMENDED.
- **LABEL:** the only benchmark is 2024-era (83-90% of pairs correct).
- **COACH:** mPACT is press-only and unverified. W26 is an unreviewed preprint. Vendor self-reports (OAI25) are not independent.
- **Data policies:** DeepInfra's 'Google models' clause is ambiguous for Gemma. Groq's no-train status is inferred from no-retention. Mistral's paid default is unclear.
- **Costs:** costs assume ~1,500 image tokens per photo, and real counts vary by provider. Reasoning models can triple output cost; see the "x3 out" column.

## Evidence keys

- **V26**: Vedovelli et al., Sci Rep 2026 (doi:10.1038/s41598-026-58755-w) - 30+ VLMs on Nutrition5k; model choice explains 99.6% of variance
- **C25**: Coburn et al., arXiv 2507.07048 v2 - ACETADA calorie MAPE: GPT-4.1 23.3%, Claude 3.7 Sonnet 23.9%, Gemma-3 27B 27.7%, Qwen2.5-VL 72B 33.9%, LLaMA-3.2-V 66%
- **RJ25**: Rodriguez-Jimenez et al., Nutrients 2025 - ChatGPT-5 image-only energy MAPE 30.5%, 13.9% with ingredient list
- **F25**: Fridolfsson et al., Curr Dev Nutr 2025 - systematic underestimation growing with portion size
- **SF26**: Gravellier et al. (SeeFood), medRxiv 2026 preprint - real-world photos 52-90%+ MAPE for all models; GPT-4o-mini, Gemini 2.5 Flash-Lite, Llama-4-Maverick finalists
- **S26**: Sterling et al., bioRxiv 2026 preprint (abstract only) - Gemini 3.0 Flash CCC 0.767; Gemini 3.1 Flash Lite CCC 0.754
- **N26**: Nakagawa & Yamamoto, Nutrients 2026 - Claude Haiku 4.5 energy R2 0.23 vs Sonnet 4.6 0.60; Sonnet within 1-3 MedAPE points of Opus
- **M26**: Marchetti et al., Nutrients 2026 - open 4-9B VLMs within ~20-35% of frontier portion error; sub-2B/LLaVA-era unusable
- **OF26**: OmniFood-Bench, arXiv 2607.08423 - catastrophic mass-estimation failure across 6 VLMs incl. qwen3-vl-8B, gpt-5.1
- **JMIR26**: Nakagawa & Yamamoto, JMIR preprint 116178 - 4-bit 4B on-device VLM ~36-44% energy MedAPE (UNVERIFIED)
- **A25**: Assiri et al., J Imaging 2025 - 2024-era models 83-90% of English label element-value pairs correct after post-processing
- **MJ22**: Majmudar et al., NPJ Digit Med 2022 - purpose-built CNN 2.16 %BF MAE vs DXA; no general VLM validated (evidence gap)
- **RAND25**: McBain et al., Psychiatric Services 2025 (RAND) - suicide-question alignment; Claude fewer high-risk direct answers than ChatGPT; Gemini most restrictive
- **OAI25**: OpenAI 2025 (vendor self-report) - GPT-5 self-harm compliance 91% vs 77%
- **MPACT26**: mPACT 2026 (UNVERIFIED, press only) - Claude Sonnet 4.5 best, GPT-5.2 most consistent; Grok 4.1 and Mistral Medium 3 weakest; ED weakest area for all
- **W26**: Wang et al., arXiv 2611.10488 preprint - ED safety: GLM-5.2 82.8%, Gemma-4-31B 79.5%, Qwen3.6-27B 67.7%
- **P26**: Pucci et al., arXiv 2606.02444 - 7-9B open models (Llama-3.1-8B, Qwen-2.5-7B, Gemma-2-9B) unsafe in 30-90% of risky-cell advice
- **Y25**: Yim et al., Int J Eat Disord 2025 - all chatbots (GPT, Gemini, Llama, Mistral) produced some harmful ED content
- **JSB25**: JSONSchemaBench arXiv 2501.10868 - only provider-side constrained decoding gives ~100% schema compliance; prompt-only 13-21% on hard schemas
- **IFS25**: IFScale arXiv 2507.11538 - instruction-following decays with rule density; small models worst
- **ORSO**: OpenRouter structured-outputs doc - strict enforced only where upstream supports it; needs require_parameters
- **GROQSO**: Groq structured-outputs doc - strict constrained decoding on gpt-oss-20b/120b, qwen3.8-27b
- **CLOSED**: closed.json entry (provider docs/pricing, 2026-11-21)
- **OPEN**: open.json entry (OpenRouter/DeepInfra/Groq catalogs, 2026-11-21)
