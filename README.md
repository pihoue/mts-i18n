# MTS/IV I18N

[![NeoForge](https://img.shields.io/badge/NeoForge-21.1.232-blue)](https://neoforged.net/)
[![Minecraft](https://img.shields.io/badge/Minecraft-1.21.1-green)](https://minecraft.net/)
[![IV](https://img.shields.io/badge/IV-24.0.0–26.1.2+-orange)](https://www.curseforge.com/minecraft/mc-mods/mts-immersive-vehicles)
[![License](https://img.shields.io/badge/License-MIT-yellow)](LICENSE)

Runtime internationalization (i18n) injector for **Immersive Vehicles (MTS/IV)** and its addon content packs.  
Automatically translates item names, descriptions, and GUI text into the player's selected game language.

**No resource packs needed.** Translation is injected directly into MTS's `LanguageSystem` via reflection at runtime.

## Why Not a Resource Pack?

MTS (Immersive Vehicles) does **not** load item names and descriptions from standard Minecraft language files (`assets/*/lang/*.json`). Instead, it uses its own `LanguageSystem` which stores text in a custom in-memory data structure (`packLanguageEntries`). This means:

- **Resource packs cannot override MTS text** — MTS ignores `en_us.json` from resource packs for its item definitions
- **MTS reads text from `jsondefs/` inside JARs** — item names and descriptions are embedded in each content pack's JSON definition files
- **The only way to localize MTS is at the Java level** — by accessing the `LanguageSystem` via reflection and injecting translated values at runtime

This mod does exactly that: it reads MTS's internal language entries, looks up translations from its dictionary, and writes the localized text directly into the `values` map of each `LanguageEntry` — the same mechanism MTS itself uses internally.

## Features

- **Full Chinese translation built-in** — 25+ content packs, ~16200 entries, 100% complete
- **Multi-language pack system** — place `zh_cn.zip`, `de_de.zip`, `ja_jp.zip`, etc. in `mts_i18n/` — the mod auto-selects by game locale
- **Runtime injection** — modifies MTS `LanguageSystem` entries in memory, no pack reloading
- **Auto-extraction** — scans content pack JARs on first launch, generates translation template files with all English source text
- **Exact description matching** — full sentence-level translations for item tooltips
- **Late-join injection** — translates item descriptions even when joining a world mid-session

## How It Works

1. At client startup, the mod scans `/mods` for MTS content pack JARs
2. Extracts English item names and descriptions from `jsondefs/` and `language/en_us.json`
3. Generates translation template files in `mts_i18n/translations/`
4. Loads translations from those files and any matching language pack (`.zip`)
5. Detects the game's current language from `Minecraft.options.languageCode`
6. Injects translations into MTS's `LanguageSystem` — each entry gets the localized text as `values[lang_code]`
7. Also injects item descriptions when joining a world (late pass for `AItemPack`)

## Installation

1. Install [NeoForge](https://neoforged.net/) 21.1.232 for Minecraft 1.21.1
2. Install [Immersive Vehicles (MTS)](https://www.curseforge.com/minecraft/mc-mods/mts-immersive-vehicles) V24.0.0 ~ V26.1.x+
3. Place `mts_i18n-1.0.0.jar` in your `mods/` folder
4. Launch the game — translations are applied automatically based on your game language

## Translation Files

Generated and loaded from: `[game_dir]/mts_i18n/`

```
mts_i18n/
├── zh_cn.zip              → Chinese pack (auto-loaded when game is 简体中文)
├── de_de.zip              → German pack (auto-loaded when game is Deutsch)
├── ja_jp.zip              → Japanese pack (auto-loaded when game is 日本語)
├── translations/          → JSON template files (auto-generated, one per pack)
│   ├── craftspeed.json
│   ├── gvp.json
│   ├── mtsofficialpack.json
│   └── ... (25 files total)
```

### Creating or editing translations

1. Launch the game once to generate JSON template files with all English source text
2. Fill in the empty values:
```json
{
  "Original English text": "Your translation",
  "Another description": "Another translation"
}
```
3. Save and restart the game

### Language pack format (zip)

Zip files contain `.json` files with the same key-value format as the template files.  
Pack name follows the pattern `language_code.zip` (e.g., `de_de.zip`, `ja_jp.zip`).  
Only the pack matching the player's game language is loaded into the active dictionary; other packs are still tracked to prevent duplicate generation.

### Adding a new language

1. Copy the generated `.json` files into a zip
2. Rename it to your language code (e.g., `de_de.zip` for German)
3. Place it in `mts_i18n/`
4. Translate all empty values in the zip
5. Players using that language will automatically get your translations

## Built-in Translations

The mod ships with complete Chinese translations for the following content packs:

| Pack | Entries |
|------|---------|
| MTS Official Pack | 882 |
| Craftspeed (Racing) | 7625 |
| Craftspeed Wheels | 1844 |
| Craftspeed Parts | 335 |
| GT CRAFT | 397 |
| GT Craft Rims | 372 |
| GT Craft Interior | 375 |
| GT Craft Exterior / Bodykit / Parts | 525 |
| Kaminari Motor Work (KMW) | 1944 |
| Immersive Flight Simulator | 308 |
| IV Airliner Pack | 332 |
| S.L.O.P. Vehicle Pack (8 sub-packs) | 252 |
| MTS Core Handbook | 131 |
| PGTH | 15 |
| **Total (25 files)** | **~16200** |

## Building from Source

```bash
git clone https://github.com/pihoue/mts-i18n.git
cd mts-i18n
./gradlew build
# Output: build/libs/mts_i18n-1.0.0.jar
```

## Technical Details

### Architecture

```
src/main/java/com/mts/i18n/
├── MTSI18nMod.java           # @Mod entry, injection orchestration
├── TranslationDict.java      # Translation dictionary (exact string matching)
├── TranslationExtractor.java # JAR scanning, file generation, multi-zip loading
```

### Injection Pipeline

```
Start → loadZipPack()     [load matching language pack, mark coverage for others]
     → run()              [scan JARs → generate translation template JSON files]
     → extractFromLanguageSystem() [runtime MTS reflection to catch any missed items]
     → loadUserTranslations()      [load all JSON files + active zip into dict]
     → addExactTranslations()      [feed into TranslationDict]
     → applyTranslations()         [inject translations into MTS LanguageSystem]
     → World Join → injectItemDescriptions() [late pass for AItemPack fields]
```

### Language Detection

Language is auto-detected from `Minecraft.options.languageCode` at startup.  
Injection target (`values[lang_code]`) is fully dynamic — the same mod binary supports any language.

### Zip Coverage System

When multiple language packs are present:
- Only the pack matching the current game language is loaded into the active dictionary
- All other packs are still scanned and their keys recorded as "covered" — preventing the auto-extractor from re-generating template entries for already-translated text

## License

MIT License — feel free to use, modify, and distribute.

## Credits

- [Don_bruce](https://www.curseforge.com/minecraft/mc-mods/mts-immersive-vehicles) — Immersive Vehicles (MTS)
- MTS I18N Team
- All contributors
