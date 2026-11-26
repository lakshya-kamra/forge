# Food-vision evidence: VLM accuracy for meals, labels and body fat

Compiled 2026-11-21. Machine-readable version: `food_vision_evidence.json` (22 studies).
Sources were checked against PubMed abstracts, PMC, arXiv or nature.com full text where available. Items marked **UNVERIFIED** were seen only through search summaries.

## Bottom line for Forge

| Job | What the evidence says | Implication |
|---|---|---|
| FOOD_PHOTO | Frontier VLMs reach about 24-36% energy MAPE on curated mixed meals and 52-90%+ on real free-living photos. Protein and fat are worse than energy or carbohydrate. Total energy is usually **underestimated**, more so as portions grow. Hidden oil and fat is the biggest single error. | Keep the deliberate high bias and code clamps. Ask about portion and cooking fat (ghee/oil). Feed any user text to the model, since context roughly halves error. Use temperature 0. |
| LABEL | 2024-era models (GPT-4V, GPT-4o, gemini-pro-vision) got about 83-90% of English element-value pairs right after post-processing. Errors: units, synonyms, decimals, column alignment. No current-model benchmark found. | Strict per-field schema with separate units, an Atwater 4/4/9 check against stated kcal, and always a user confirm screen. |
| BODY | Purpose-built CNNs reach MAE about 2.2 %BF vs DXA (LoA -5.5 to +4.7) with standardized capture. **No peer-reviewed validation of any general LLM/VLM** exists. | Keep the +/-6-point range, the "rough, unvalidated" label and lower-bound safety logic, as in guardrails `photo_bf_accuracy_limits`. |
| Model size | Haiku 4.5 performed far worse than Sonnet 4.6 (energy R2 0.23 vs 0.60), while Sonnet was within 1-3 points of Opus. Old or small open models (Janus-Pro 7B, LLaMA-3.2-Vision, LLaVA-era, models under 2B) collapse. Cheap Gemini Flash/Flash-Lite and GPT-4o-mini held up. Recent 4-9B Qwen3.5 and MiniCPM-V 4.5 come within about 20-35% of frontier portion error. | Minimum for FOOD_PHOTO: a mid-tier frontier model or a strong Flash-class model. Avoid the cheapest Claude tier and old open VLMs. A 4-9B local model is only acceptable as an offline fallback with wider ranges. |
| Indian food | **No study found.** The nearest are Singapore, Taiwan and Japan studies, which show composite dishes are worse and cuisine-specific tuning helps. | Treat as an evidence gap. Widen ranges for curries and thalis, and prompt for ghee/oil and the number of roti or katori. |

## Meal-photo calorie and macro studies

| Study | Data | Models | Key numbers |
|---|---|---|---|
| Vedovelli 2026, *Sci Rep* | Nutrition5k plus smartphone dishes | 40 VLMs via OpenRouter (GPT-5/4.1/4o families incl. mini/nano, Claude Opus 4/4.1, Sonnet 4/4.5, 3.7, 3 Haiku, Gemini 2.0/2.5, Gemma 3 12B/27B, Pixtral, Qwen3-VL-235B, Qwen2.5-VL-32B, Llama 3.2-90B/4 Scout/Maverick, Grok 4) | Top: GPT-5-chat RMSLE 0.545 (MAE 37.1 kcal), GPT-4o 0.614, GPT-4.1 0.621, Gemini 2.5 Flash 0.647, Gemini 2.0 Flash 0.661. Model choice explains 99.6% of variance. Multi-angle and prompt tricks gave no gain. Nutritionists 0.176 vs best AI 0.443. Fat is the main error. |
| Coburn 2025, arXiv 2507.07048 | ACETADA, 806 Australian meals | GPT-4o, GPT-4.1, Claude 3.7 Sonnet, Gemini 2.5 Pro, Janus-Pro 7B, Gemma-3 27B, LLaMA-3.2-Vision, Qwen2.5-VL 72B | Calorie MAPE: GPT-4.1 23.3%, Claude 3.7 23.9%, GPT-4o 24.5%, Gemma-3 27.7%, Qwen2.5-VL 33.9%, Gemini 2.5 Pro 34.1%, LLaMA-3.2-V 66.2%, Janus-Pro 86.3%. Metadata reduced error. |
| Rodriguez-Jimenez 2025, *Nutrients* | 195 dishes | ChatGPT-5 | Image only: 123 kcal MAE / 30.5% MAPE. With ingredient list: 53 kcal / 13.9%. Macro MAPE 42-53% from the image alone. |
| Fridolfsson 2025, *Curr Dev Nutr* | 52 standardized photos | GPT-4o, Claude 3.5 Sonnet, Gemini 1.5 Pro | Energy MAPE 35.8% (GPT-4o and Claude). Gemini 64-110%. **All underestimated, worse for larger portions** (slope -0.23 to -0.50). |
| O'Hara 2025, *Nutrients* | 114 Irish meals | ChatGPT-4 | 93% food ID precision. Poor weight agreement for medium and large meals. Underestimated 11 of 16 nutrients. |
| Isobe 2026, *Nutrients* | 15 Japanese hospital meals | 10 models incl. GPT-4o, Gemini 1.5 Pro | Energy and carbohydrate r > 0.8. Protein and fat were poor. **Lipids overestimated by more than 20%** in all models. |
| Gravellier 2026, medRxiv (SeeFood) | SNAPMe free-living, 1,680 photos | GPT-4o mini, GPT-5.2, Gemini 3 Pro Preview, Gemini 2.5 Flash-Lite, Qwen3-VL 8B/32B, Llama 4 Maverick, Gemma-3n-E4B | GPT-4o mini 86.8% MAPE (bias -61 kcal), Flash-Lite 132% (+30 kcal), Maverick 98.8% (-46 kcal). Newer or bigger was not better. Drinks 312% MAPE. |
| Sterling 2026, bioRxiv | Nutrition5k, 3,229 images | Gemini 2.0/2.5/3.0 Flash, 3.1 Flash Lite, GPT-4o, GPT-4o-mini, GPT-5 Mini, Claude Haiku 4.5, Qwen2-VL-7B, FatSecret | Gemini 3.0 Flash best: CCC 0.767, MAE 80.7 kcal. 3.1 Flash Lite CCC 0.754 at $0.59/1K images (authors' cost figure). |
| Nakagawa 2026, *Nutrients* | Japanese meals, SNAPMe, packaged food | Claude Haiku 4.5, Sonnet 4.6, Opus 4.6 | **Haiku much worse** (energy R2 0.23 vs 0.60). Sonnet close to Opus (1-3 points MedAPE). Salt unreliable. |
| Marchetti 2026, *Nutrients* | Portion grams, recognition | 26 local open configurations (Qwen, MiniCPM, Gemma, Mistral, LLaVA, Moondream, Granite) plus GPT-4o/4.1/5.2/5.5 and Claude Sonnet 5 as reported | GPT-4o NMAE 58 g. Best open: qwen3.5:9b 70 g, minicpm-v4.5 73 g, qwen3.5:4b 78 g. Moondream, bakllava, granite and llava-llama3 unusable. Every model under-predicts portions above 187 g. |
| Nakagawa, JMIR preprint (**UNVERIFIED**) | 15 open 2-32B models, on-device | not retrieved | 4-bit 4B model: about 36-44% energy MedAPE. 8B: 23.9% on ACETADA. |
| Chen 2025, *AJCN* | 714 Taiwanese free-living images | GPT-4o and a customized GPT-4o bot | Group-level energy within +/-10-20%. Fat and protein less consistent. Giving the food name helped. |
| Lian 2026, *Front Nutr* | 150 Singapore items | ChatGPT-5 vs NutriDish | ChatGPT-5 carbohydrate MAE 8.7 g, MAPE 32.9%. Purpose-built app was better. A visible label as reference gave MAE 2.7 g. |
| Lane 2026, *J Diabetes Sci Technol* | Japanese meals | Fine-tuned GPT-4o vs 27 models | Fine-tuning beat the dietitian. Generic GPT-5.1 and GPT-4o were strong but did not beat the dietitian on every nutrient. |
| Romero-Tapiador 2025, CVPRW | FoodNExTDB recognition | ChatGPT, Gemini, Claude, Moondream, DeepSeek, LLaVA | Closed models above 90% recall on single items. Cooking style is a weak spot. |

## Nutrition-label reading
- **Assiri 2025, *J Imaging*** (294 bilingual labels; GPT-4o, GPT-4V, gemini-pro-vision). English element names reached Jaccard about 0.96-0.99 after post-processing. Element-value pairs were 83-90% correct (GPT-4V 0.836, GPT-4o 0.795, Gemini 0.745 Jaccard). Arabic was worse.
- **Hsuan 2025, *Nutrients*** (8 meals vs labels; GPT-4o, Claude 3.7, Grok 3, Gemini, Copilot). Macros were 70-90% accurate. Sodium and saturated fat were severely underestimated. Answers varied between repeated runs.
- Gap: no current benchmark of GPT-5-class, Claude 4.x or Gemini 3 models on nutrition-panel transcription.

## Body fat vs DXA
- **Majmudar 2022** (purpose-built CNN, n=134): MAE 2.16 +/- 1.54 %BF, CCC 0.96, LoA -5.5 to +4.7.
- **Ferreira 2025** (AI-2D photos): CCC >= 0.96, which beat BIA.
- **Reynolds 2026** (medRxiv, vendor preprint, n=195): CCC 0.930.
- **General LLMs:** only an anecdotal X/Twitter test of GPT-4o (**UNVERIFIED**). It reported median error of 2.4 points for men and 5.7 for women, against a visual guide rather than DXA. No peer-reviewed validation exists.

## Sources
- https://www.nature.com/articles/s41598-026-58755-w
- https://arxiv.org/abs/2507.07048
- https://pmc.ncbi.nlm.nih.gov/articles/PMC12655113/
- https://pubmed.ncbi.nlm.nih.gov/41081011/
- https://pubmed.ncbi.nlm.nih.gov/40004936/
- https://pubmed.ncbi.nlm.nih.gov/41901141/
- https://pmc.ncbi.nlm.nih.gov/articles/PMC13738896/
- https://doi.org/10.64898/2026.07.26.740845
- https://pubmed.ncbi.nlm.nih.gov/42356404/
- https://pmc.ncbi.nlm.nih.gov/articles/PMC13764829/
- https://preprints.jmir.org/preprint/116178
- https://pubmed.ncbi.nlm.nih.gov/41138916/
- https://pubmed.ncbi.nlm.nih.gov/42871627/
- https://pubmed.ncbi.nlm.nih.gov/42410932/
- https://arxiv.org/abs/2504.06925
- https://pmc.ncbi.nlm.nih.gov/articles/PMC12387780/
- https://pubmed.ncbi.nlm.nih.gov/41097121/
- https://doi.org/10.1038/s41746-022-00628-3
- https://doi.org/10.1038/s41746-024-01380-6
- https://www.medrxiv.org/content/10.64898/2026.06.15.26355736v1
- https://x.com/AnnaLeptikon/status/1923114810025332811
- https://arxiv.org/abs/2604.10425
