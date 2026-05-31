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

    public MTSI18nMod() {
        LOGGER.info("[MTSI18n] Constructor called");
        NeoForge.EVENT_BUS.addListener(LateApplicator::onJoinWorld);
    }

    @SuppressWarnings("removal")
    @EventBusSubscriber(modid = "mts_i18n", bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static class ModBus {
        @SubscribeEvent
        public static void onClientSetup(FMLClientSetupEvent event) {
            event.enqueueWork(() -> {
                try {
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
                        LOGGER.warn("[MTSI18n] extract: could not access LanguageSystem: {}", ex.getMessage());
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

        Method getAllPackIDs = packParserClass.getMethod("getAllPackIDs");
        Set<String> packIDs = (Set<String>) getAllPackIDs.invoke(null);

        Field packLangField = langSysClass.getDeclaredField("packLanguageEntries");
        packLangField.setAccessible(true);
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

        for (String packID : packIDs) {
            Map<String, Object> packMap = packEntries.get(packID);
            if (packMap == null) continue;

            for (Map.Entry<String, Object> entry : packMap.entrySet()) {
                String entryKey = entry.getKey();
                Object langEntry = entry.getValue();
                if (langEntry == null) continue;

                try {
                    Field valuesField = langEntry.getClass().getDeclaredField("values");
                    valuesField.setAccessible(true);
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
        LOGGER.info("[MTSI18n] Exact matches: {} Word replaced: {} Not translated: {}",
            DICT.getExactHits(), DICT.getWordHits(), DICT.getNoMatch());

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

            Field packItemMapField = packParserClass.getDeclaredField("packItemMap");
            packItemMapField.setAccessible(true);
            Object packItemMap = packItemMapField.get(null);
            if (!(packItemMap instanceof Map)) return -1;

            Field definitionField = itemPackClass.getField("definition");
            Field descField = itemPackClass.getField("languageDescription");
            Field nameField = itemPackClass.getField("languageName");
            Field valuesField = langEntryClass.getField("values");
            Field generalField = ajsonItemClass.getField("general");
            Field generalDescField = generalClass.getField("description");
            Field generalNameField = generalClass.getField("name");

            Constructor<?> langEntryCtor = langEntryClass.getDeclaredConstructor(String.class, String.class);
            langEntryCtor.setAccessible(true);
            Field keyField = langEntryClass.getField("key");

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
                            nameEntry = langEntryCtor.newInstance(null, rawName);
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
                        descEntry = langEntryCtor.newInstance(entryKey != null ? entryKey + ".description" : null, rawDesc);
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
        } catch (Exception e) {
            LOGGER.warn("[MTSI18n] injectItemDescriptions error: {}", e.getMessage());
            return -1;
        }
    }
}
