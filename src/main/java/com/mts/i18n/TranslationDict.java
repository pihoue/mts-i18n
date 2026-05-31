package com.mts.i18n;

import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TranslationDict {

    private static final Logger LOGGER = LoggerFactory.getLogger("MTSI18n");

    private final Map<String, String> exactMap = new LinkedHashMap<>();

    private Map<String, String> untranslated = new LinkedHashMap<>();
    private int exactHits;
    private int noMatch;

    public TranslationDict() {
    }

    static String normalize(String s) {
        if (s == null) return "";
        return Normalizer.normalize(
            s.replaceAll("\u00a7[0-9a-fk-or]", "")
                .replaceAll("[\\u00A0\\u00AE\\u2122\\u1680\\u2000-\\u200A\\u202F\\u205F\\u3000]", " ")
                .replaceAll("\\s+", " "),
            Normalizer.Form.NFKC
        ).trim();
    }

    public String translate(String name) {
        if (name == null || name.isEmpty()) return name;

        String clean = normalize(name);
        if (clean.isEmpty()) return name;

        String result = exactMap.get(clean);
        if (result != null) {
            exactHits++;
            return result;
        }

        noMatch++;
        untranslated.put(clean.intern(), clean);
        return name;
    }

    public String translateExact(String name) {
        if (name == null || name.isEmpty()) return name;
        String clean = normalize(name);
        String result = exactMap.get(clean);
        return result != null ? result : name;
    }

    public boolean hasExact(String name) {
        if (name == null || name.isEmpty()) return false;
        String clean = normalize(name);
        return exactMap.containsKey(clean);
    }

    public void resetStats() {
        exactHits = 0;
        noMatch = 0;
        untranslated.clear();
    }

    public int getExactHits() { return exactHits; }
    public int getNoMatch() { return noMatch; }
    public Set<String> getUntranslated() { return untranslated.keySet(); }

    public void addExactTranslations(Map<String, String> translations) {
        for (Map.Entry<String, String> e : translations.entrySet()) {
            String key = normalize(e.getKey());
            String val = e.getValue();
            if (key != null && !key.isEmpty() && val != null && !val.isEmpty()) {
                exactMap.put(key, val);
            }
        }
    }
}
