# MTS/IV I18N

[![NeoForge](https://img.shields.io/badge/NeoForge-21.1.232-blue)](https://neoforged.net/)
[![Minecraft](https://img.shields.io/badge/Minecraft-1.21.1-green)](https://minecraft.net/)
[![License](https://img.shields.io/badge/License-MIT-yellow)](LICENSE)

Runtime internationalization (i18n) injector for **Immersive Vehicles (MTS/IV)** and its addon content packs.  
Supports **multi-language packs** — translations are automatically selected based on the player's game language.

## Features

- **Full Chinese translation** built-in for 25+ MTS content packs (~16200 entries, 100% complete)
- **Multi-language support** — place `zh_cn.zip`, `de_de.zip`, etc. in `mts_i18n/` and the mod auto-selects by game locale
- **Runtime injection** — no resource pack needed, works via reflection into MTS `LanguageSystem`
- **Auto-extraction** — scans content pack JARs on first run to generate translation template files
- **Item name word replacement** — built-in dictionary (e.g. `Heavy Machine Gun` → `重机枪`)
- **Exact description matching** — full sentence translations for item tooltips

## How It Works

1. At client startup, the mod scans `/mods` for MTS content pack JARs
2. Extracts English item names and descriptions from `jsondefs/` and `language/en_us.json`
3. Generates translation JSON files in `mts_i18n/translations/`
4. Loads translations from those files and any matching language pack
5. At runtime, injects translations into MTS's `LanguageSystem` via reflection
6. Also injects item descriptions when joining a world (late pass)

## Installation

1. Install [NeoForge](https://neoforged.net/) 21.1.232 for Minecraft 1.21.1
2. Install [Immersive Vehicles (MTS)](https://www.curseforge.com/minecraft/mc-mods/mts-immersive-vehicles) V24+
3. Place `mts_i18n-1.0.0.jar` in your `mods/` folder
4. Launch the game — translations are applied automatically based on your game language

## Translation Files

Generated automatically at: `[game_dir]/mts_i18n/translations/`

```
mts_i18n/
├── zh_cn.zip              → Language pack for Chinese (auto-detected)
├── de_de.zip              → Language pack for German
├── translations/
│   ├── craftspeed.json
│   ├── gvp.json
│   ├── mtsofficialpack.json
│   └── ... (25 files total)
```

### Adding your own translations

1. Launch the game once to generate the JSON template files
2. Open a `.json` file and fill in the empty values:
```json
{
  "Original English text": "",
  "Another description": ""
}
```
3. Change to:
```json
{
  "Original English text": "Your translation",
  "Another description": "Another translation"
}
```
4. Save and restart the game

### Multi-language packs

Place language-specific zip files in the `mts_i18n/` directory:
- `zh_cn.zip` — loaded when game language is 简体中文
- `de_de.zip` — loaded when game language is Deutsch
- `ja_jp.zip` — loaded when game language is 日本語

The mod automatically selects the matching pack at startup.

## Supported Content Packs

| Pack | File | Entries |
|------|------|---------|
| MTS Official Pack | mtsofficialpack.json | 882 |
| Craftspeed (Racing) | craftspeed.json | 7625 |
| Craftspeed Wheels | craftspeedwheels.json | 1844 |
| Craftspeed Parts | craftspeedparts.json | 335 |
| GT CRAFT | gtcraft.json | 397 |
| GT Craft Rims | gtcraft_rims.json | 372 |
| GT Craft Interior | gtcraft_interior.json | 375 |
| GT Craft Exterior | gtcraft_exterior.json | 33 |
| GT Craft Bodykit | gtcraft_bodykit.json | 328 |
| GT Craft Parts | gtcraft_parts.json | 164 |
| Kaminari Motor Work (KMW) | gvp.json | 1944 |
| Immersive Flight Simulator | ifs.json | 308 |
| IV Airliner Pack | ivairlinerpack.json | 332 |
| S.L.O.P. Vehicle Pack | ah1g / amx10p / amx13 / ... | 252 |
| pgth | pgth.json | 15 |
| MTS Core Handbook | mts.json | 131 |
| **Total** | **25 files** | **~16200** |

## Building from Source

```bash
# Clone the repo
git clone https://github.com/pihoue/mts-i18n.git
cd mts-i18n

# Build
./gradlew build

# Output will be in build/libs/
```

## Technical Details

### Architecture

```
src/main/java/com/mts/i18n/
├── MTSI18nMod.java          # @Mod entry point, translation injection
├── TranslationDict.java     # Dictionary engine (exact + word replacement)
├── TranslationExtractor.java # JAR scanning, file generation, zip loading
```

### Injection Flow

```
Start → loadZipPack() → run() [scan JARs → generate files]
→ extractFromLanguageSystem() [runtime MTS reflection]
→ loadUserTranslations() → addExactTranslations()
→ applyTranslations() [inject into LanguageSystem]
→ World Load → injectItemDescriptions() [late pass for AItemPack]
```

### Language Detection

Language is auto-detected from `Minecraft.options.languageCode` at startup.  
Injection target (`values[lang_code]`) is dynamic based on the detected language.

## License

MIT License — feel free to use, modify, and distribute.

## Credits

- [Don_bruce](https://www.curseforge.com/minecraft/mc-mods/mts-immersive-vehicles) — Immersive Vehicles (MTS)
- MTS I18N Team
- All contributors
