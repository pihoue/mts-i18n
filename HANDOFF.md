# MTS/IV 中文翻译模组 — 交接文档

## 项目概述

Minecraft 沉浸车辆 (Immersive Vehicles / MTS) 的运行时国际化 (i18n) NeoForge 模组。通过反射注入 `LanguageSystem.packLanguageEntries`，在运行时为每个 LanguageEntry 添加对应游戏语言的翻译值。支持多语言翻译包，自动根据玩家游戏语言选择加载。

## 代码架构

```
src/main/java/com/mts/chinese/
├── MTSI18nMod.java          # @Mod 入口 + applyTranslations() + injectItemDescriptions()
├── TranslationDict.java        # 词典引擎：exactMap + wordMap + simpleMap + normalize()
├── TranslationExtractor.java   # 扫描JAR + 提取 + 合并 + 加载用户翻译

src/main/resources/assets/mts_i18n/lang/
└── (translations.json 已删除，翻译全部从文件加载)

run/mts_i18n/translations/   # 25 个文件，~12400 条目（含新增约2675条，约78%已翻译）
run/mts_i18n/                # 用户放置 .zip/.jar 翻译包（不限文件名）
```

## 核心流程

```
启动 → loadZipPack() → run() [扫描JAR生成翻译文件]
→ extractFromLanguageSystem() [反射补全全部条目]
→ prefillFiles() [词替换预填充名称]
→ loadUserTranslations() → addExactTranslations()
→ applyTranslations() [注入 MTS LanguageSystem]
→ 世界加载 → injectItemDescriptions() [延迟注入 AItemPack]
```

## 关键方法说明

### applyTranslations()
- 反射 `LanguageSystem.packLanguageEntries`，遍历所有 LanguageEntry
- 取 `en_us` 值
- `.description`/`.desc`/`.info`/.tooltip 结尾的 key → `translateExact()` → 写入 `values["zh_cn"]`
- 其他 key → `translate()` → 写入 `values["zh_cn"]`
- 输出 Names/Descriptions/Exact matches/Word replaced/Not translated 统计

### injectItemDescriptions()
- 注册在 `NeoForge.EVENT_BUS`，`ClientPlayerNetworkEvent.LoggingIn` 时触发
- 反射 `PackParser.packItemMap`，遍历所有 `AItemPack` 实例
- 从 `definition.general.description` 取原始文本
- `translateExact()` 查询字典后写入 `languageDescription.values["zh_cn"]`
- 若 `languageDescription` 为 null，反射构造新 LanguageEntry 并设置

### TranslationDict.translate(name)
1. `normalize()` → 去 `§` 颜色码 + NFKC 标准化 + 去空格 + trim
2. `exactMap.get(clean)` → 命中返回
3. `wordPattern` 替换 → `simplePattern` 替换
4. 去中文字间空格 → 返回
5. 都未命中 → `noMatch++`，记入 untranslated，返回原文

### TranslationExtractor 三阶段
1. `processJarJsondefs()` — 扫描 IV 附属包 JAR 的 `jsondefs/`，提取 `general.name` + `general.description`
2. `processJarLanguageFiles()` — 扫描所有 JAR 的 `language/en_us.json`
3. `extractFromLanguageSystem()` — 运行时反射读取 MTS 完整 `packLanguageEntries`

## 当前翻译进度

| 指标 | 数值 |
|---|---|
| 总数文件 | 25 个 JSON 文件 |
| 总条目 | ~16200 |
| 已翻译 | **100%**（所有文件零未译） |
| 新增条目 | ~2675 条（2025-05-31 修复提取后新增并翻译） |
| 新增条目来源 | Fix 1: 递归扫描子目录 / Fix 2: 根级提取 / Fix 3-4: 放宽过滤器 |
| 清理垃圾条目 | gvp.json 中 ~1357 条 OBJ 3D 模型节点名被移除（Fix 2 副作用） |

## 已修复问题

### ~~问题1: 物品名称翻译未生效~~ → ✅ **不存在**

原交接文档误判为"名称翻译逻辑有 bug"。经验证，名称翻译（词替换 + exactMap）工作正常。
根因是 **提取阶段未获取完全**，导致翻译文件中缺少条目，注入时自然找不到。

### 修复: 提取不完整（2025-05-31）

**4 项修复：**

1. **递归扫描子目录** (`run()` → `findJarsRecursively()`)
   - 之前只扫描 `mods/` 的直接子 JAR，子目录中的包被遗漏
   - 新增收获：gvp、craftspeedwheels 等子目录 JAR 中的大量条目

2. **根级对象提取** (`extractAllJson` → `extractText=true`)
   - 之前只在 `general` 对象内提取 `name`/`description`/`desc`
   - 现在根级对象也能提取
   - ⚠️ 副作用：gvp 包中 ~1194 条 3D 模型 OBJ 节点名（`obj4.005` 等）被误抓
   - 这些 `[name]` 值不是实际物品名，需要过滤或标记

3. **移除短文过滤器**（`isDesc && val.length() < 10`）
   - 之前短于 10 字符的描述被跳过

4. **放宽 systemName 过滤**（移除 `/` 和空格检查）

**新增条目统计：**

| 包名 | 新增 | 其中可翻译 |
|---|---|---|
| gvp | 1360 | ~166（其余是 OBJ 节点名，需过滤）|
| craftspeedwheels | 713 | 713 |
| craftspeed | 146 | 146 |
| gtcraft_rims | 137 | 137 |
| gtcraft | 91 | 91 |
| craftspeedparts | 90 | 90 |
| gtcraft_interior | 84 | 84 |
| gtcraft_parts | 28 | 28 |
| pgth | 14 | 14 |
| gtcraft_exterior | 12 | 12 |
| mtsofficialpack | 7 | 7 |
| ifs | 2 | 2 |
| **合计** | **~2684** | **~1490 条真实待译** |

## 待解决问题

### ~~1. OBJ 节点名污染~~ → ✅ **已修复**

已添加 `isModelInternalName()` 过滤方法，在三个提取位置生效：
- `processJarJsondefs()` — JSON 提取时跳过
- `processJarLanguageFiles()` — 语言文件提取时跳过
- `extractFromLanguageSystem()` — 运行时反射提取时跳过

过滤规则：
- `obj`/`nohit`/`mesh`/`cube`/`hit` + 可选数字 + 可选 `.` + 3位数字
- 任何以 `.001` `.002` 等 Blender 去重后缀结尾的名称

### 2. §7 颜色码变体
完整版描述（带 `§7` 前缀）比普通版多若干句。`normalize()` 去 `§7` 后完整版文本与普通版不同，无法匹配已有精确翻译。

### 3. MTS 手册翻译
`mts.json` 的 `[mts.handbook_*]` 条目是手册正文（驾驶指南、燃料、控制等），词替换不适用，当前基本未译。

## 多语言翻译包支持（2025-05-31）

mts_i18n/ 目录现在支持放置多个语言翻译 zip 包，模组自动根据玩家游戏语言选择对应包。

### 命名约定

```
mts_i18n/
├── zh_cn.zip          → 简体中文（自动匹配游戏语言=zh_cn）
├── de_de.zip          → 德语
├── ja_jp.zip          → 日语
├── translations/      → JSON 文件（不受影响，始终加载）
```

格式：`语言代码.zip`。不匹配 `xx_xx.zip` 模式的文件/`.jar` 按原逻辑处理（向后兼容）。

### 加载逻辑

- 文件名匹配当前游戏语言 → 加载到翻译词典 + 标记覆盖（压制生成）
- 文件名匹配其他语言 → 只标记覆盖，不加载到词典
- 无语言标签的文件 → 按原逻辑加载

### 动态注入

- 游戏语言 = `zh_cn` → `values["zh_cn"]` 写入中文
- 游戏语言 = `de_de` → `values["de_de"]` 写入德语
- 以此类推

### 代码位置

- `TranslationExtractor.loadZipPack()` — 语言感知的 zip 过滤
- `MTSI18nMod.LANG_CODE` — 运行时检测的游戏语言码
- `applyTranslations()` / `injectItemDescriptions()` — 使用 `LANG_CODE` 而非硬编码 `"zh_cn"`

## 后续建议

1. **补完 §7 条目** — 将完整版描述翻译补入 exactMap
2. **打包分发** — `translations/` 打包为 `translations.zip` 随模组发布
3. **缓存提取结果** — 避免每次启动都重新扫描所有 JAR
4. **手册翻译** — 需要完整句子翻译，不适用词替换
