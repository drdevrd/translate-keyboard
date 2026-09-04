# Translate Keyboard (com.drdevrd.translatekeyboard)

Custom Android keyboard (IME) that:
- Autocorrects English as you type (bundled ~40k word dictionary, edit-distance suggestions + silent auto-fix for confident 1-letter typos).
- Translates what you're typing into **Hindi or Tamil** (switchable) via the OpenAI API — both live (auto, ~0.8s after you pause, or at `. ? !`) and on demand ("Translate now" button).
- Shows the translation, an English-letter transliteration of it, and a one-line explanation of the meaning/grammar.
- "Insert" button replaces your typed sentence with the translated text.

## Build

Standard Gradle Android project.

```
./gradlew assembleDebug      # unsigned debug APK
```

Or push to `main` — GitHub Actions (`.github/workflows/build.yml`) builds it and uploads the APK as a workflow artifact. Signed release build requires the same repo secrets your other apps use:
`KEYSTORE_BASE64`, `KEY_ALIAS`, `KEY_PASSWORD`, `STORE_PASSWORD`. Without those secrets set, the workflow falls back to an unsigned debug build.

## Setup on phone

1. Install the APK.
2. Open the app icon ("Translate Keyboard") — paste your **OpenAI API key** and tap Save. Key is stored locally in SharedPreferences, never leaves the device except in direct calls to `api.openai.com`.
3. Settings → System → Languages & input → On-screen keyboard → Manage keyboards → enable **Translate Keyboard**.
4. In any text field, switch to it via the keyboard-switch icon, or tap the 🌐 key on the keyboard itself once it's active.

## How it works

- `TranslateKeyboardService.kt` — the IME itself: key handling, autocorrect wiring, translate triggers (debounce + punctuation + manual button + Insert/Dismiss).
- `AutocorrectEngine.kt` — loads `assets/words_en.txt`, groups by first letter, scores candidates by Levenshtein distance.
- `OpenAiTranslator.kt` — calls `POST https://api.openai.com/v1/chat/completions` with `gpt-4o-mini`, `response_format: json_object`, asking for `{translation, transliteration, explanation}`.
- `SettingsActivity.kt` — the launcher screen where you paste the API key.

## Known limitations / next steps

- Keyboard layout is a basic QWERTY built with the old `android.inputmethodservice.Keyboard`/`KeyboardView` classes (deprecated but functional) — no swipe-typing, no key popups, no multi-language native script keys (Hindi/Tamil typing itself is not implemented — only autocorrected **English input** with a translation panel).
- Translation cost: every debounce/punctuation trigger is a live OpenAI call — fine for personal use, but for heavy typists you may want to raise `TRANSLATE_DEBOUNCE_MS` in `TranslateKeyboardService.kt` or make live-translate opt-in only.
- No usage of `com.drdevrd`'s existing Claude API key ("SK") — this build uses OpenAI per your instruction; swapping `OpenAiTranslator` for a Claude Messages API call is a small, isolated change if you'd rather standardize on one provider later.
- App icon is a placeholder vector — swap `ic_launcher_foreground.xml` for real branding whenever convenient.
