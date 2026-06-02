package com.mts.i18n;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TranslationExtractor {

    private static final Logger LOGGER = LoggerFactory.getLogger("MTSI18n");

    private final File translationsDir;
    private final File mtsI18nDir;
    private String langCode = "zh_cn";
    private Map<String, String> loadedFromZip = new LinkedHashMap<>();
    private Set<String> coveredKeys = new HashSet<>();

    public TranslationExtractor(File gameDir) {
        this.mtsI18nDir = new File(gameDir, "mts_i18n");
        this.translationsDir = new File(mtsI18nDir, "translations");
    }

    public void setLangCode(String code) {
        this.langCode = (code != null && !code.isEmpty()) ? code : "zh_cn";
    }

    /** Load translations from zip/jar files in mts_chinese/ dir */
    public void loadZipPack() {
        loadedFromZip.clear();
        coveredKeys.clear();
        if (!mtsI18nDir.isDirectory()) return;

        Gson gson = new Gson();
        Type mapType = new TypeToken<Map<String, String>>() {}.getType();
        Pattern langZipPattern = Pattern.compile("^([a-z]{2}_[a-z]{2})\\.zip$");

        for (File f : mtsI18nDir.listFiles()) {
            if (!f.isFile()) continue;
            String name = f.getName().toLowerCase();
            if (!name.endsWith(".zip") && !name.endsWith(".jar")) continue;
            if (name.equals(".jar") || name.startsWith(".")) continue;

            Matcher langMatcher = langZipPattern.matcher(name);
            String zipLang = langMatcher.matches() ? langMatcher.group(1) : null;
            boolean isMatchingLang = zipLang != null && zipLang.equals(langCode);
            boolean isForeignLang = zipLang != null && !zipLang.equals(langCode);
            boolean isGeneric = zipLang == null;

            try (ZipFile zf = new ZipFile(f)) {
                Enumeration<? extends ZipEntry> zEntries = zf.entries();
                while (zEntries.hasMoreElements()) {
                    ZipEntry ze = zEntries.nextElement();
                    if (ze.isDirectory() || !ze.getName().endsWith(".json")) continue;
                    String content = new String(zf.getInputStream(ze).readAllBytes(), "UTF-8");
                    Map<String, String> entries = gson.fromJson(content, mapType);
                    if (entries == null) continue;
                    for (Map.Entry<String, String> e : entries.entrySet()) {
                        if (e.getValue() != null && !e.getValue().isEmpty()) {
                            String cleanKey = stripPrefix(e.getKey());
                            coveredKeys.add(e.getKey());
                            if (isMatchingLang || isGeneric) {
                                loadedFromZip.putIfAbsent(cleanKey, e.getValue());
                            }
                        } else {
                            coveredKeys.add(e.getKey());
                        }
                    }
                }
                String label = isMatchingLang ? " (active lang)" : isForeignLang ? " (foreign lang)" : "";
                LOGGER.info("[MTSI18n] zip: loaded {} entries from {}{}", loadedFromZip.size(), f.getName(), label);
            } catch (Exception ex) {
                LOGGER.warn("[MTSI18n] zip: error reading {}: {}", f.getName(), ex.getMessage());
            }
        }
    }

    /** Check if a key is already covered by any zip pack */
    private boolean isCoveredByZip(String displayKey) {
        return coveredKeys.contains(displayKey);
    }

    public void run() {
        File modsDir = new File(translationsDir.getParentFile().getParentFile(), "mods");
        if (!modsDir.isDirectory()) {
            LOGGER.info("[MTSI18n] extract: mods folder not found at {}", modsDir);
            return;
        }

        translationsDir.mkdirs();
        Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

        List<File> jarFiles = new ArrayList<>();
        findJarsRecursively(modsDir, jarFiles);

        for (File f : jarFiles) {
            if (isIVContentPack(f)) {
                processJarJsondefs(f, gson);
            }
            processJarLanguageFiles(f, gson);
        }
    }

    private void findJarsRecursively(File dir, List<File> result) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                findJarsRecursively(f, result);
            } else if (f.isFile() && f.getName().endsWith(".jar")) {
                result.add(f);
            }
        }
    }

    private String systemNameFromPath(String entryName) {
        // "assets/packid/jsondefs/.../filename.json" → "filename"
        int dot = entryName.lastIndexOf('.');
        if (dot > 0) {
            int slash = entryName.lastIndexOf('/', dot - 1);
            return entryName.substring(slash + 1, dot);
        }
        return "";
    }

    private void processJarJsondefs(File jarFile, Gson gson) {
        Map<String, Map<String, String>> byPack = new LinkedHashMap<>();

        try (ZipFile zf = new ZipFile(jarFile)) {
            Enumeration<? extends ZipEntry> entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry ze = entries.nextElement();
                if (ze.isDirectory() || !ze.getName().endsWith(".json")) continue;
                if (!ze.getName().contains("/jsondefs/")) continue;

                try {
                    String json = new String(zf.getInputStream(ze).readAllBytes(), "UTF-8");
                    String packId = detectPackId(json);
                    if (packId == null) {
                        String[] parts = ze.getName().split("/");
                        if (parts.length >= 2) packId = parts[1];
                    }
                    if (packId == null) continue;

                    String sn = detectSystemName(json);
                    if (sn.isEmpty()) sn = systemNameFromPath(ze.getName());

                    Map<String, String> pd = byPack.computeIfAbsent(packId, k -> new LinkedHashMap<>());
                    extractAllJson(json, sn, pd);
                } catch (Exception e) {
                    // skip
                }
            }

        } catch (IOException e) {
            LOGGER.warn("[MTSI18n] extract: error reading {}: {}", jarFile.getName(), e.getMessage());
        }

        // Write/merge one file per pack
        Type mapType = new TypeToken<Map<String, String>>() {}.getType();

        for (Map.Entry<String, Map<String, String>> e : byPack.entrySet()) {
            String packId = e.getKey();
            Map<String, String> newDescs = e.getValue();
            if (newDescs.isEmpty()) continue;

            File outFile = new File(translationsDir, packId + ".json");

            // Read existing file to preserve user's translations
            Map<String, String> merged = new LinkedHashMap<>();
            if (outFile.isFile()) {
                try (InputStreamReader r = new InputStreamReader(new FileInputStream(outFile), StandardCharsets.UTF_8)) {
                    Map<String, String> existing = gson.fromJson(r, mapType);
                    if (existing != null) merged.putAll(existing);
                } catch (Exception ex) {
                    LOGGER.warn("[MTSI18n] extract: error reading existing {}: {}", outFile.getName(), ex.getMessage());
                }
            }

            // Add new entries — skip if already covered by zip pack
            int newAdded = 0;
            for (Map.Entry<String, String> nd : newDescs.entrySet()) {
                String displayKey = nd.getKey();
                if (!merged.containsKey(displayKey) && !isCoveredByZip(displayKey)) {
                    merged.put(displayKey, "");
                    newAdded++;
                }
            }

            try (OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(outFile), StandardCharsets.UTF_8)) {
                gson.toJson(merged, w);
                LOGGER.info("[MTSI18n] extract: {} - {} total ({} new)", packId, merged.size(), newAdded);
            } catch (IOException ex) {
                LOGGER.warn("[MTSI18n] extract: error writing {}: {}", outFile.getName(), ex.getMessage());
            }
        }
    }

    private void processJarLanguageFiles(File jarFile, Gson gson) {
        Map<String, Map<String, String>> byPack = new LinkedHashMap<>();

        try (ZipFile zf = new ZipFile(jarFile)) {
            Enumeration<? extends ZipEntry> entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry ze = entries.nextElement();
                if (ze.isDirectory() || !ze.getName().endsWith(".json")) continue;
                if (!ze.getName().contains("/language/")) continue;
                // Only process English language files
                String fname = ze.getName().substring(ze.getName().lastIndexOf('/') + 1);
                if (!fname.equals("en_us.json")) continue;

                try {
                    String content = new String(zf.getInputStream(ze).readAllBytes(), StandardCharsets.UTF_8);
                    Type t = new TypeToken<Map<String, String>>() {}.getType();
                    Map<String, String> langEntries = gson.fromJson(content, t);
                    if (langEntries == null) continue;

                    for (Map.Entry<String, String> le : langEntries.entrySet()) {
                        String key = le.getKey();
                        String val = le.getValue();
                        if (val == null || val.isEmpty()) continue;
                        if (!isEnglishText(val)) continue;

                        int colon = key.indexOf(':');
                        if (colon < 0) continue;
                        String packId = key.substring(0, colon);
                        String rest = key.substring(colon + 1);

                        boolean isDesc = rest.endsWith(".description") || rest.endsWith(".desc") || rest.endsWith(".info");
                        String systemName = rest;
                        if (isDesc) {
                            int dot = rest.lastIndexOf('.');
                            if (dot > 0) systemName = rest.substring(0, dot);
                        }
                        if (systemName.isEmpty()) continue;
                        if (!isDesc && isModelInternalName(val)) continue;

                        String type = isDesc ? "desc" : "name";
                        String displayKey = "[" + systemName + "] [" + type + "]" + val;
                        Map<String, String> pd = byPack.computeIfAbsent(packId, k -> new LinkedHashMap<>());
                        if (!pd.containsKey(displayKey)) {
                            pd.put(displayKey, "");
                        }
                    }
                } catch (Exception e) {
                    // skip
                }
            }
        } catch (IOException e) {
            LOGGER.warn("[MTSI18n] extract: error reading {}: {}", jarFile.getName(), e.getMessage());
        }

        // Write/merge per-pack
        Type mapType = new TypeToken<Map<String, String>>() {}.getType();
        for (Map.Entry<String, Map<String, String>> e : byPack.entrySet()) {
            String packId = e.getKey();
            Map<String, String> newEntries = e.getValue();
            if (newEntries.isEmpty()) continue;

            File outFile = new File(translationsDir, packId + ".json");
            Map<String, String> merged = new LinkedHashMap<>();
            if (outFile.isFile()) {
                try (InputStreamReader r = new InputStreamReader(new FileInputStream(outFile), StandardCharsets.UTF_8)) {
                    Map<String, String> existing = gson.fromJson(r, mapType);
                    if (existing != null) merged.putAll(existing);
                } catch (Exception ex) {
                    LOGGER.warn("[MTSI18n] extract: error reading {}: {}", outFile.getName(), ex.getMessage());
                }
            }

            int newAdded = 0;
            for (Map.Entry<String, String> nd : newEntries.entrySet()) {
                if (!merged.containsKey(nd.getKey()) && !isCoveredByZip(nd.getKey())) {
                    merged.put(nd.getKey(), "");
                    newAdded++;
                }
            }
            if (newAdded == 0) continue;

            try (OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(outFile), StandardCharsets.UTF_8)) {
                gson.toJson(merged, w);
                LOGGER.info("[MTSI18n] extract(lang): {} + {} entries", packId, newAdded);
            } catch (IOException ex) {
                LOGGER.warn("[MTSI18n] extract: error writing {}: {}", outFile.getName(), ex.getMessage());
            }
        }
    }

    public int extractFromLanguageSystem() throws Exception {
        Class<?> langSysClass = Class.forName("minecrafttransportsimulator.systems.LanguageSystem");
        Class<?> packParserClass = Class.forName("minecrafttransportsimulator.packloading.PackParser");

        java.lang.reflect.Method getAllPackIDs = packParserClass.getMethod("getAllPackIDs");
        java.util.Set<String> packIDs = (java.util.Set<String>) getAllPackIDs.invoke(null);

        java.lang.reflect.Field packLangField = null;
        try {
            packLangField = langSysClass.getDeclaredField("packLanguageEntries");
        } catch (NoSuchFieldException e) {
            for (java.lang.reflect.Field f : langSysClass.getDeclaredFields()) {
                if (Map.class.isAssignableFrom(f.getType())) {
                    packLangField = f;
                    LOGGER.info("[MTSI18n] extract: packLanguageEntries fallback -> {} (type=Map)", f.getName());
                    break;
                }
            }
        }
        if (packLangField == null) {
            LOGGER.warn("[MTSI18n] extract: packLanguageEntries field not found in LanguageSystem");
            return 0;
        }
        packLangField.setAccessible(true);
        Map<String, Map<String, Object>> packEntries =
            (Map<String, Map<String, Object>>) packLangField.get(null);

        if (packEntries == null) return 0;

        Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        Type mapType = new TypeToken<Map<String, String>>() {}.getType();
        int totalNew = 0;

        for (String packId : packIDs) {
            Map<String, Object> packMap = packEntries.get(packId);
            if (packMap == null) continue;

            Map<String, String> newEntries = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : packMap.entrySet()) {
                String entryKey = entry.getKey();
                Object langEntry = entry.getValue();
                if (langEntry == null) continue;

                try {
                    Field valuesField = resolveLangEntryValuesField(langEntry);
                    if (valuesField == null) continue;
                    Map<String, String> values = (Map<String, String>) valuesField.get(langEntry);
                    if (values == null) continue;

                    String enValue = values.get("en_us");
                    if (enValue == null || enValue.isEmpty()) continue;
                    if (!isEnglishText(enValue)) continue;

                    boolean isDesc = entryKey.contains(".description") || entryKey.contains(".desc")
                        || entryKey.contains(".info") || entryKey.contains(".tooltip");
                    String systemName = entryKey;
                    if (isDesc) {
                        int dot = entryKey.lastIndexOf('.');
                        if (dot > 0) systemName = entryKey.substring(0, dot);
                    }

                    if (!isDesc && isModelInternalName(enValue)) continue;

                    String type = isDesc ? "desc" : "name";
                    String displayKey = "[" + systemName + "] [" + type + "]" + enValue;
                    if (!newEntries.containsKey(displayKey)) {
                        newEntries.put(displayKey, "");
                    }
                } catch (Exception e) {
                    // skip individual entries
                }
            }

            if (newEntries.isEmpty()) continue;

            // Merge with existing file
            File outFile = new File(translationsDir, packId + ".json");
            Map<String, String> merged = new LinkedHashMap<>();
            if (outFile.isFile()) {
                try (InputStreamReader r = new InputStreamReader(new FileInputStream(outFile), StandardCharsets.UTF_8)) {
                    Map<String, String> existing = gson.fromJson(r, mapType);
                    if (existing != null) merged.putAll(existing);
                } catch (Exception ex) {
                    // ignore
                }
            }

            int newCount = 0;
            for (Map.Entry<String, String> nd : newEntries.entrySet()) {
                if (!merged.containsKey(nd.getKey()) && !isCoveredByZip(nd.getKey())) {
                    merged.put(nd.getKey(), "");
                    newCount++;
                }
            }
            if (newCount > 0) {
                try (OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(outFile), StandardCharsets.UTF_8)) {
                    gson.toJson(merged, w);
                    LOGGER.info("[MTSI18n] extract(LS): {} + {} entries", packId, newCount);
                } catch (IOException ex) {
                    LOGGER.warn("[MTSI18n] extract: error writing {}: {}", outFile.getName(), ex.getMessage());
                }
                totalNew += newCount;
            }
        }
        return totalNew;
    }

    public Map<String, String> loadUserTranslations() {
        Map<String, String> result = new LinkedHashMap<>();

        // First add zip-loaded translations so files can override them
        result.putAll(loadedFromZip);

        if (!translationsDir.isDirectory()) return result;

        Gson gson = new Gson();
        Type mapType = new TypeToken<Map<String, String>>() {}.getType();

        for (File f : translationsDir.listFiles()) {
            if (!f.isFile() || !f.getName().endsWith(".json")) continue;
            try (InputStreamReader r = new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8)) {
                Map<String, String> entries = gson.fromJson(r, mapType);
                if (entries != null) {
                    int loaded = 0;
                    for (Map.Entry<String, String> e : entries.entrySet()) {
                        if (e.getValue() != null && !e.getValue().isEmpty()) {
                            String plainText = stripPrefix(e.getKey());
                            result.put(plainText, e.getValue());
                            loaded++;
                        }
                    }
                    if (loaded > 0) {
                        LOGGER.info("[MTSI18n] extract: loaded {} translations from {}", loaded, f.getName());
                    }
                }
            } catch (Exception e) {
                LOGGER.warn("[MTSI18n] extract: error reading {}: {}", f.getName(), e.getMessage());
            }
        }
        return result;
    }

    private static String stripPrefix(String key) {
        // Format: "[systemName] [name]text" or "[systemName] [desc]text" -> "text"
        // First strip "[systemName] "
        if (key.startsWith("[") && key.contains("] ")) {
            key = key.substring(key.indexOf("] ") + 2);
        }
        // Then strip "[type]" where type is "name" or "desc"
        if (key.startsWith("[name]") || key.startsWith("[desc]")) {
            key = key.substring(6); // len of "[name]" or "[desc]"
        }
        return key;
    }

    private static boolean isModelInternalName(String val) {
        if (val == null || val.isEmpty()) return false;
        if (val.matches("^(?i)(obj|nohit|mesh|cube|hit)\\d*(\\.\\d+)?$")) return true;
        if (val.matches("^.+\\.\\d{3}$")) return true;
        return false;
    }

    /** Check if text is English (only ASCII / Latin characters, no CJK/Cyrillic/etc.). */
    private static boolean isEnglishText(String text) {
        if (text == null || text.isEmpty()) return true;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0x3400 && c <= 0x9FFF) return false;
            if (c >= 0x3040 && c <= 0x30FF) return false;
            if (c >= 0xAC00 && c <= 0xD7AF) return false;
            if (c >= 0x0400 && c <= 0x04FF) return false;
            if (c >= 0x0600 && c <= 0x06FF) return false;
            if (c >= 0x0590 && c <= 0x05FF) return false;
            if (c >= 0x0E00 && c <= 0x0E7F) return false;
            if (c >= 0x0370 && c <= 0x03FF) return false;
            if (c >= 0x0530 && c <= 0x058F) return false;
        }
        return true;
    }

    private boolean isIVContentPack(File jarFile) {
        try (ZipFile zf = new ZipFile(jarFile)) {
            boolean definesMtsItself = false;
            boolean hasDependencyOnMts = false;
            Enumeration<? extends ZipEntry> entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry ze = entries.nextElement();
                if (!ze.getName().contains("mods.toml")) continue;
                String content = new String(zf.getInputStream(ze).readAllBytes(), "UTF-8");
                String[] sections = content.split("\\[\\[");
                for (String section : sections) {
                    section = section.trim();
                    boolean isMods = section.startsWith("mods]");
                    boolean isDeps = section.startsWith("dependencies.");
                    if (!isMods && !isDeps) continue;
                    boolean refsMts = section.contains("\"mts\"");
                    if (isMods && refsMts) definesMtsItself = true;
                    if (isDeps && refsMts) hasDependencyOnMts = true;
                }
            }
            return hasDependencyOnMts && !definesMtsItself;
        } catch (IOException e) {
            return false;
        }
    }

    private String detectPackId(String json) {
        int idx = json.indexOf("\"packID\"");
        if (idx < 0) return null;
        idx = json.indexOf('"', idx + 8);
        if (idx < 0) return null;
        int end = json.indexOf('"', idx + 1);
        if (end < 0) return null;
        String id = json.substring(idx + 1, end);
        if (id.isEmpty() || id.contains("/") || id.contains("\\")) return null;
        return id;
    }

    private String detectSystemName(String json) {
        int idx = json.indexOf("\"systemName\"");
        if (idx < 0) return "";
        idx = json.indexOf('"', idx + 12);
        if (idx < 0) return "";
        int end = json.indexOf('"', idx + 1);
        if (end < 0) return "";
        return json.substring(idx + 1, end);
    }

    private void extractAllJson(String json, String sn, Map<String, String> out) {
        String prefix = sn.isEmpty() ? "" : "[" + sn + "] ";
        extractJsonValue(json, 0, sn, prefix, out, true);
    }

    private int extractJsonValue(String json, int pos, String sn, String prefix, Map<String, String> out, boolean extractText) {
        if (pos >= json.length()) return pos;
        char c = json.charAt(pos);
        if (c == '{') return extractJsonObject(json, pos, sn, prefix, out, extractText);
        if (c == '[') return extractJsonArray(json, pos, sn, prefix, out, extractText);
        if (c == '"') return extractJsonString(json, pos);
        int end = skipToNext(json, pos);
        return end > pos ? end : pos + 1;
    }

    private int extractJsonObject(String json, int start, String sn, String prefix, Map<String, String> out, boolean extractText) {
        int pos = start + 1;
        return extractJsonObjectContent(json, pos, sn, prefix, out, extractText);
    }

    private int extractJsonObjectContent(String json, int pos, String sn, String prefix, Map<String, String> out, boolean extractText) {
        while (pos < json.length()) {
            char c = json.charAt(pos);
            if (c == '}') return pos + 1;
            if (c == '"') {
                int[] keyResult = parseString(json, pos);
                String key = json.substring(keyResult[0], keyResult[1]);
                pos = keyResult[2];
                pos = skipWhitespace(json, pos);
                if (pos < json.length() && json.charAt(pos) == ':') {
                    pos = skipWhitespace(json, pos + 1);
                    if (pos < json.length() && json.charAt(pos) == '"') {
                        if (extractText && (key.equals("name") || key.equals("description") || key.equals("desc"))) {
                            int[] valResult = parseString(json, pos);
                            String val = json.substring(valResult[0], valResult[1]);
                            pos = valResult[2];
                            if (val.length() >= 2 && !isModelInternalName(val) && isEnglishText(val)) {
                                String type = key.equals("name") ? "name" : "desc";
                                String displayKey = prefix + "[" + type + "]" + val;
                                if (!out.containsKey(displayKey)) {
                                    out.put(displayKey, "");
                                }
                            }
                        } else {
                            pos = extractJsonString(json, pos);
                        }
                    } else if (pos < json.length() && json.charAt(pos) == '{') {
                        boolean isGeneral = key.equals("general");
                        pos = extractJsonObjectContent(json, pos + 1, sn, prefix, out, isGeneral);
                    } else {
                        pos = extractJsonValue(json, pos, sn, prefix, out, extractText);
                    }
                }
            } else {
                pos++;
            }
        }
        return pos;
    }

    private int extractJsonArray(String json, int start, String sn, String prefix, Map<String, String> out, boolean extractText) {
        int pos = start + 1;
        while (pos < json.length()) {
            char c = json.charAt(pos);
            if (c == ']') return pos + 1;
            pos = extractJsonValue(json, pos, sn, prefix, out, extractText);
            if (pos < json.length() && json.charAt(pos) == ',') pos++;
            pos = skipWhitespace(json, pos);
        }
        return pos;
    }

    private int extractJsonString(String json, int start) {
        int[] result = parseString(json, start);
        return result[2];
    }

    private int[] parseString(String json, int start) {
        int i = start + 1;
        StringBuilder sb = new StringBuilder();
        while (i < json.length()) {
            char c = json.charAt(i);
            if (c == '\\') {
                i++;
                if (i < json.length()) {
                    char next = json.charAt(i);
                    if (next == 'n') sb.append('\n');
                    else if (next == 't') sb.append('\t');
                    else if (next == 'r') sb.append('\r');
                    else if (next == '"') sb.append('"');
                    else if (next == '\\') sb.append('\\');
                    else if (next == 'u' && i + 4 < json.length()) {
                        try { sb.append((char) Integer.parseInt(json.substring(i + 1, i + 5), 16)); i += 4; }
                        catch (NumberFormatException e) { sb.append('u'); }
                    } else { sb.append(next); }
                    i++;
                }
            } else if (c == '"') {
                return new int[]{start + 1, start + 1 + sb.length(), i + 1};
            } else {
                sb.append(c);
                i++;
            }
        }
        return new int[]{start + 1, start + 1 + sb.length(), i};
    }

    private int skipWhitespace(String json, int pos) {
        while (pos < json.length()) {
            char c = json.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') pos++;
            else break;
        }
        return pos;
    }

    private int skipToNext(String json, int pos) {
        while (pos < json.length()) {
            char c = json.charAt(pos);
            if (c == ',' || c == '}' || c == ']' || c == ':') break;
            pos++;
        }
        return pos;
    }

    /** Resolve LanguageEntry.values field with cross-version fallback. */
    private static Field resolveLangEntryValuesField(Object langEntry) {
        Class<?> c = langEntry.getClass();
        try { Field f = c.getDeclaredField("values"); f.setAccessible(true); return f; } catch (NoSuchFieldException e1) {}
        try { Field f = c.getField("values"); return f; } catch (NoSuchFieldException e2) {}
        for (Field f : c.getFields()) {
            if (Map.class.isAssignableFrom(f.getType()) && f.getName().length() > 1) {
                f.setAccessible(true);
                return f;
            }
        }
        for (Field f : c.getDeclaredFields()) {
            if (Map.class.isAssignableFrom(f.getType()) && f.getName().length() > 1) {
                f.setAccessible(true);
                return f;
            }
        }
        return null;
    }
}
