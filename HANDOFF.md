# MTS/IV I18N — 交接文档

## 项目概述

Minecraft 沉浸车辆 (Immersive Vehicles / MTS) 的运行时国际化 (i18n) NeoForge 模组。通过反射注入 `LanguageSystem.packLanguageEntries`，在运行时为每个 LanguageEntry 添加对应游戏语言的翻译值。支持多语言翻译包，自动根据玩家游戏语言选择加载。

## 代码架构

```
src/main/java/com/mts/i18n/
├── MTSI18nMod.java              # @Mod 入口 + applyTranslations() + injectItemDescriptions()
├── TranslationDict.java         # 词典引擎：exactMap + normalize()（已移除词替换）
├── TranslationExtractor.java    # 扫描JAR + 提取 + 合并 + 加载用户翻译

run/mts_i18n/translations/   # 25 个文件，~16200 条目（100% 已翻译）
run/mts_i18n/                # 用户放置 .zip/.jar 翻译包
```

## 核心流程

```
启动 → loadZipPack() [加载匹配语言包 + 标记其他语言包覆盖]
     → run() [扫描JAR → 生成翻译模板文件]
     → extractFromLanguageSystem() [运行时反射补全]
     → loadUserTranslations() → addExactTranslations()
     → applyTranslations() [注入 MTS LanguageSystem]
     → 世界加载 → injectItemDescriptions() [延迟注入 AItemPack]
```

## 关键方法说明

### applyTranslations()
- 反射 `LanguageSystem.packLanguageEntries`，遍历所有 LanguageEntry
- 取 `en_us` 值
- `.description`/`.desc`/`.info`/`.tooltip` 结尾的 key → `translateExact()` → 写入 `values[LANG_CODE]`
- 其他 key → `translate()` → 写入 `values[LANG_CODE]`
- 注入语言代码 `LANG_CODE` 动态检测自 `Minecraft.options.languageCode`

### injectItemDescriptions()
- 注册在 `NeoForge.EVENT_BUS`，`ClientPlayerNetworkEvent.LoggingIn` 时触发
- 反射 `PackParser.packItemMap`，遍历所有 `AItemPack` 实例
- 从 `definition.general.description` 取原始文本
- `translateExact()` 查询字典后写入 `languageDescription.values[LANG_CODE]`

### TranslationDict.translate(name)
1. `normalize()` → 去 `§` 颜色码 + NFKC 标准化 + 去空格 + trim
2. `exactMap.get(clean)` → 命中返回
3. 未命中 → 返回原文

### TranslationExtractor 三阶段
1. `processJarJsondefs()` — 扫描 IV 附属包 JAR 的 `jsondefs/`，提取 `general.name` + `general.description`
2. `processJarLanguageFiles()` — 扫描所有 JAR 的 `language/en_us.json`
3. `extractFromLanguageSystem()` — 运行时反射读取 MTS 完整 `packLanguageEntries`

### 提取过滤
| 过滤器 | 方式 | 范围 |
|--------|------|------|
| `isModelInternalName()` | 正则匹配 `obj/nohit/mesh/cube/hit` + 数字 / `.001` 后缀 | 3 个提取阶段 |
| `isEnglishText()` | 检测非拉丁字符（CJK/西里尔/阿拉伯/希伯来/泰文/希腊/亚美尼亚） | 3 个提取阶段 |

所有 3 个提取阶段都会调用两种过滤器，确保只提取纯英文文本，跳过模型节点名。

## 翻译进度

| 指标 | 数值 |
|---|---|
| 总数文件 | 25 个 JSON |
| 总条目 | ~16200 |
| 已翻译 | **100%** |

## 已移除功能

| 功能 | 原因 |
|------|------|
| `wordMap` + `simpleMap` 内置词典 | 词替换效果有限，翻译应完全基于精确匹配 |
| `hasNonEnglishChars()` 语言包感知过滤 | 改为统一的 `isEnglishText()`，只提取英文 |
| `hasChinese` 硬编码过滤 | 同上 |

## 多语言翻译包支持

`mts_i18n/` 目录支持放置多个语言翻译 zip 包，模组自动根据玩家游戏语言选择对应包。

### 命名约定

```
mts_i18n/
├── zh_cn.zip          → 简体中文（自动匹配游戏语言=zh_cn）
├── de_de.zip          → 德语
├── ja_jp.zip          → 日语
├── translations/      → JSON 模板文件（自动生成，不受影响）
```

格式：`语言代码.zip`。不匹配 `xx_xx.zip` 模式的文件/`.jar` 按原逻辑处理（向后兼容）。

### 加载逻辑

- 文件名匹配当前游戏语言 → 加载到翻译词典 + 标记覆盖（压制生成）
- 文件名匹配其他语言 → 只标记覆盖，不加载到词典
- 无语言标签的文件 → 按原逻辑加载

### 动态注入

- `values[LANG_CODE]` 中的 `LANG_CODE` 由 `Minecraft.options.languageCode` 动态决定
- 示例：游戏 zh_cn → 注入 zh_cn / 游戏 de_de → 注入 de_de

## 待解决问题

1. **§7 颜色码变体** — 完整版描述（带 `§7`）与普通版文本不同，exactMap 无法匹配
2. **MTS 手册翻译** — `mts.json` 的 `[mts.handbook_*]` 条目需要完整句子翻译
3. **提取缓存** — 避免每次启动都重新扫描所有 JAR
4. **打包分发** — `translations/` 打包为 `translations.zip` 随模组发布
