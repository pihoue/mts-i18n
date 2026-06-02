package com.mts.i18n;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod("mts_i18n")
public class MTSI18nMod {

    private static final Logger LOGGER = LoggerFactory.getLogger("MTSI18n");
    private static final TranslationDict DICT = new TranslationDict();
    private static TranslationExtractor EXTRACTOR;
    private static String LANG_CODE = "zh_cn";
    private static boolean mtsAvailable = true;
    private static String ivVersion = "unknown";

    /** Resolve a field by name, falling back to type-based search for cross-version compat. */
    @SuppressWarnings("rawtypes")
    private static Field resolveField(Class<?> clazz, String name, Class<?> fallbackType) {
        try {
            Field f = clazz.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (NoSuchFieldException e) {
            if (fallbackType != null) {
                for (Field f : clazz.getDeclaredFields()) {
                    if (fallbackType.isAssignableFrom(f.getType())) {
                        f.setAccessible(true);
                        LOGGER.info("[MTSI18n] field fallback: {}->{} (type={})", name, f.getName(), fallbackType.getSimpleName());
                        return f;
                    }
                }
            }
            return null;
        }
    }

    private static Class<?> tryLoadClass(String... names) {
        for (String n : names) {
            try { return Class.forName(n); } catch (ClassNotFoundException ignored) {}
        }
        return null;
    }

    private static Field tryField(Class<?> clazz, String... names) {
        for (String n : names) {
            try {
                Field f = clazz.getDeclaredField(n);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {}
        }
        return null;
    }

    /** Detect IV version string from PackParser metadata at runtime. */
    private static void detectIVVersion() {
        try {
            Class<?> packParserClass = Class.forName("minecrafttransportsimulator.packloading.PackParser");
            Method getConfig = packParserClass.getMethod("getPackConfiguration", String.class);
            Method getAllIDs = packParserClass.getMethod("getAllPackIDs");
            Set<String> ids = (Set<String>) getAllIDs.invoke(null);
            if (ids != null && !ids.isEmpty()) {
                String firstId = ids.iterator().next();
                Object config = getConfig.invoke(null, firstId);
                if (config != null) {
                    for (java.lang.reflect.Field f : config.getClass().getFields()) {
                        if ("modVersion".equals(f.getName()) || "version".equals(f.getName())) {
                            Object v = f.get(config);
                            if (v != null) ivVersion = v.toString();
                            break;
                        }
                    }
                }
            }
        } catch (Exception e) {
            // cannot determine
        }
        LOGGER.info("[MTSI18n] detected IV version: {}", ivVersion);
    }

    public MTSI18nMod() {
        LOGGER.info("[MTSI18n] Constructor called");
        NeoForge.EVENT_BUS.addListener(LateApplicator::onJoinWorld);
    }

    @EventBusSubscriber(modid = "mts_i18n", value = Dist.CLIENT)
    public static class ModBus {
        @SubscribeEvent
        public static void onClientSetup(FMLClientSetupEvent event) {
            event.enqueueWork(() -> {
                try {
                    detectIVVersion();

                    // Detect current game language
                    try {
                        Minecraft mc = Minecraft.getInstance();
                        if (mc != null && mc.options != null && mc.options.languageCode != null
                            && !mc.options.languageCode.isEmpty()) {
                            LANG_CODE = mc.options.languageCode;
                        }
                    } catch (Exception e) {
                        // default to zh_cn
                    }
                    LOGGER.info("[MTSI18n] detected game language: {}", LANG_CODE);

                    // Determine game directory from Minecraft's working dir
                    java.io.File gameDir = new java.io.File(".").getAbsoluteFile();
                    EXTRACTOR = new TranslationExtractor(gameDir);
                    EXTRACTOR.setLangCode(LANG_CODE);

                    // Load zip-packaged translations first (so generated files skip covered entries)
                    EXTRACTOR.loadZipPack();

                    // Run extractor: scan JARs for jsondefs + language files
                    EXTRACTOR.run();

                    // Also extract from MTS LanguageSystem (injectables + full set)
                    try {
                        int langEntries = EXTRACTOR.extractFromLanguageSystem();
                        if (langEntries > 0) {
                            LOGGER.info("[MTSI18n] extract: added {} entries from MTS LanguageSystem", langEntries);
                        }
                    } catch (Exception ex) {
                        LOGGER.warn("[MTSI18n] extract: could not access LanguageSystem", ex);
                    }

                    // Load translations from generated files
                    Map<String, String> fileTrans = EXTRACTOR.loadUserTranslations();
                    if (!fileTrans.isEmpty()) {
                        DICT.addExactTranslations(fileTrans);
                        LOGGER.info("[MTSI18n] loaded {} translations from files", fileTrans.size());
                    }

                    applyTranslations();
                } catch (ClassNotFoundException e) {
                    LOGGER.warn("[MTSI18n] MTS not installed, translation injection skipped");
                } catch (Exception e) {
                    LOGGER.error("[MTSI18n] Translation injection failed", e);
                }
            });
        }
    }

    public static class LateApplicator {
        public static void onJoinWorld(ClientPlayerNetworkEvent.LoggingIn event) {
            if (!mtsAvailable) return;
            int n = injectItemDescriptions();
            if (n > 0) {
                LOGGER.info("[MTSI18n] Late pass: translated {} item fields", n);
            }
        }
    }

    @SuppressWarnings("unchecked")
    public static void applyTranslations() throws Exception {
        Class<?> langSysClass = Class.forName("minecrafttransportsimulator.systems.LanguageSystem");
        Class<?> packParserClass = Class.forName("minecrafttransportsimulator.packloading.PackParser");
        Class<?> langEntryClass = Class.forName("minecrafttransportsimulator.systems.LanguageSystem$LanguageEntry");

        Method getAllPackIDs = packParserClass.getMethod("getAllPackIDs");
        Set<String> packIDs = (Set<String>) getAllPackIDs.invoke(null);

        // Resolve packLanguageEntries with fallback (name stable across 24-26)
        Field packLangField = resolveField(langSysClass, "packLanguageEntries", Map.class);
        if (packLangField == null) {
            LOGGER.warn("[MTSI18n] packLanguageEntries field not found");
            return;
        }
        Map<String, Map<String, Object>> packEntries =
            (Map<String, Map<String, Object>>) packLangField.get(null);

        if (packEntries == null) {
            LOGGER.warn("[MTSI18n] packLanguageEntries is null, packs may not have loaded yet");
            return;
        }

        DICT.resetStats();
        int totalNames = 0;
        int totalDescs = 0;
        int translatedNames = 0;
        int translatedDescs = 0;
        Map<String, String> unmatchedDescs = new LinkedHashMap<>();
        boolean debugKeys = true;
        Set<String> allLangKeys = new LinkedHashSet<>();

        Field valuesField = tryField(langEntryClass, "values", "cachedValues", "languageValues");
        if (valuesField == null) {
            LOGGER.warn("[MTSI18n] LanguageEntry.values field not found");
            return;
        }
        valuesField.setAccessible(true);

        for (String packID : packIDs) {
            Map<String, Object> packMap = packEntries.get(packID);
            if (packMap == null) continue;

            for (Map.Entry<String, Object> entry : packMap.entrySet()) {
                String entryKey = entry.getKey();
                Object langEntry = entry.getValue();
                if (langEntry == null) continue;

                try {
                    Map<String, String> values = (Map<String, String>) valuesField.get(langEntry);
                    if (values == null) continue;

                    if (debugKeys) {
                        allLangKeys.addAll(values.keySet());
                    }

                    String enValue = values.get("en_us");
                    if (enValue != null && !enValue.isEmpty()) {
                        boolean isDescKey = entryKey.contains(".desc") || entryKey.contains(".info")
                            || entryKey.contains(".tooltip") || entryKey.contains("description");
                        if (isDescKey) {
                            totalDescs++;
                            String zh = DICT.translateExact(enValue);
                            if (!zh.equals(enValue)) {
                                values.put(LANG_CODE, zh);
                                translatedDescs++;
                            } else if (unmatchedDescs.size() < 5000) {
                                unmatchedDescs.put(enValue, packID + ":" + entryKey);
                            }
                        } else {
                            totalNames++;
                            String zh = DICT.translate(enValue);
                            if (!zh.equals(enValue)) {
                                values.put(LANG_CODE, zh);
                                translatedNames++;
                            }
                        }
                    }
                } catch (Exception e) {
                }
            }
            debugKeys = false;
        }

        LOGGER.info("[MTSI18n] LanguageEntry value keys found: {}", allLangKeys);
        LOGGER.info("[MTSI18n] Names: {}/{} translated", translatedNames, totalNames);
        LOGGER.info("[MTSI18n] Descriptions: {}/{} translated (exact only)", translatedDescs, totalDescs);
        LOGGER.info("[MTSI18n] Exact matches: {} Not translated: {}",
            DICT.getExactHits(), DICT.getNoMatch());

        if (!unmatchedDescs.isEmpty()) {
            LOGGER.info("[MTSI18n] --- Unmatched descriptions ({}) ---", unmatchedDescs.size());
            for (Map.Entry<String, String> ue : unmatchedDescs.entrySet()) {
                LOGGER.info("[MTSI18n]   [{}] {}", ue.getValue(), ue.getKey().substring(0, Math.min(120, ue.getKey().length())));
            }
        }

        Set<String> untranslated = DICT.getUntranslated();
        if (!untranslated.isEmpty()) {
            LOGGER.info("[MTSI18n] --- Untranslated entries (consider adding to dict) ---");
            int shown = 0;
            for (String s : untranslated) {
                if (shown >= 1000) {
                    LOGGER.info("[MTSI18n] ... and {} more", untranslated.size() - 1000);
                    break;
                }
                LOGGER.info("[MTSI18n]   UNTRANSLATED: {}", s);
                shown++;
            }
        }
    }

    private static int injectItemDescriptions() {
        try {
            Class<?> packParserClass = Class.forName("minecrafttransportsimulator.packloading.PackParser");
            Class<?> itemPackClass = Class.forName("minecrafttransportsimulator.items.components.AItemPack");
            Class<?> langEntryClass = Class.forName("minecrafttransportsimulator.systems.LanguageSystem$LanguageEntry");
            Class<?> ajsonItemClass = Class.forName("minecrafttransportsimulator.jsondefs.AJSONItem");
            Class<?> generalClass = Class.forName("minecrafttransportsimulator.jsondefs.AJSONItem$General");

            Field packItemMapField = resolveField(packParserClass, "packItemMap", Map.class);
            if (packItemMapField == null) return -1;
            Object packItemMap = packItemMapField.get(null);
            if (!(packItemMap instanceof Map)) return -1;

            Field definitionField = tryField(itemPackClass, "definition", "itemDefinition");
            Field descField = tryField(itemPackClass, "languageDescription", "descriptionEntry");
            Field nameField = tryField(itemPackClass, "languageName", "nameEntry");
            Field valuesField = tryField(langEntryClass, "values", "cachedValues", "languageValues");
            Field generalField = tryField(ajsonItemClass, "general", "itemGeneral");
            Field generalDescField = tryField(generalClass, "description", "itemDescription");
            Field generalNameField = tryField(generalClass, "name", "itemName");

            if (definitionField == null || descField == null || nameField == null ||
                valuesField == null || generalField == null || generalDescField == null ||
                generalNameField == null) {
                LOGGER.warn("[MTSI18n] injectItemDescriptions: required fields not found, skipping");
                return -1;
            }

            Constructor<?> langEntryCtor;
            try {
                langEntryCtor = langEntryClass.getDeclaredConstructor(String.class, String.class);
            } catch (NoSuchMethodException e) {
                langEntryCtor = langEntryClass.getDeclaredConstructor(String.class);
            }
            langEntryCtor.setAccessible(true);
            Field keyField = tryField(langEntryClass, "key", "entryKey");

            int translated = 0;
            int totalItems = 0;
            int totalWithDesc = 0;
            int totalWithZh = 0;
            for (Object packEntry : ((Map<String, ?>) packItemMap).values()) {
                if (!(packEntry instanceof Map)) continue;
                for (Object item : ((Map<String, ?>) packEntry).values()) {
                    if (item == null || !itemPackClass.isInstance(item)) continue;
                    totalItems++;

                    Object def = definitionField.get(item);
                    if (def == null) continue;
                    Object general = generalField.get(def);
                    if (general == null) continue;

                    // Translate name from general.name
                    String rawName = (String) generalNameField.get(general);
                    Object nameEntry = nameField.get(item);
                    if (rawName != null && !rawName.isEmpty()) {
                        if (nameEntry == null) {
                            nameEntry = newLangEntry(langEntryCtor, null, rawName);
                            nameField.set(item, nameEntry);
                        }
                        Map<String, String> nv = (Map<String, String>) valuesField.get(nameEntry);
                        if (nv.get(LANG_CODE) == null) {
                            String zh = DICT.translate(rawName);
                            if (!zh.equals(rawName)) {
                                nv.put(LANG_CODE, zh);
                                translated++;
                            }
                        }
                    }

                    // Translate description from general.description
                    String rawDesc = (String) generalDescField.get(general);
                    if (rawDesc == null || rawDesc.isEmpty()) continue;
                    totalWithDesc++;

                    Object descEntry = descField.get(item);
                    if (descEntry == null) {
                        String entryKey = nameEntry != null ? (String) keyField.get(nameEntry) : null;
                        descEntry = newLangEntry(langEntryCtor, entryKey != null ? entryKey + ".description" : null, rawDesc);
                        descField.set(item, descEntry);
                    }
                    Map<String, String> dv = (Map<String, String>) valuesField.get(descEntry);
                    if (dv.get(LANG_CODE) != null) {
                        totalWithZh++;
                        continue;
                    }
                    String zh = DICT.translateExact(rawDesc);
                    if (!zh.equals(rawDesc)) {
                        dv.put(LANG_CODE, zh);
                        translated++;
                    }
                }
            }
            if (translated > 0) {
                LOGGER.info("[MTSI18n]   injected {} new descriptions from General.description", translated);
            }
            return translated;
        } catch (ClassNotFoundException e) {
            mtsAvailable = false;
            LOGGER.warn("[MTSI18n] MTS classes not found, late-join injection disabled");
            return -1;
        } catch (Exception e) {
            LOGGER.warn("[MTSI18n] injectItemDescriptions error: {}", e.toString());
            return -1;
        }
    }

    /** Create a LanguageEntry, compatible with both (String,String) and (String) constructors across IV versions. */
    private static Object newLangEntry(Constructor<?> ctor, String key, String defaultValue) throws Exception {
        if (ctor.getParameterCount() == 2) {
            return ctor.newInstance(key, defaultValue);
        } else {
            Object entry = ctor.newInstance(defaultValue);
            if (key != null) {
                Field kf = tryField(entry.getClass(), "key", "entryKey");
                if (kf != null) {
                    kf.set(entry, key);
                }
            }
            Field vf = tryField(entry.getClass(), "values", "cachedValues", "languageValues");
            if (vf != null) {
                Map<String, String> vals = (Map<String, String>) vf.get(entry);
                if (vals != null && !vals.containsKey("en_us")) {
                    vals.put("en_us", defaultValue);
                }
            }
            return entry;
        }
    }
}
