package com.mts.chinese;

import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TranslationDict {

    private static final Logger LOGGER = LoggerFactory.getLogger("MTSChinese");

    private final Map<String, String> exactMap = new LinkedHashMap<>();
    private final Map<String, String> wordMap;
    private final Map<String, String> simpleMap;
    private final Pattern wordPattern;
    private final Pattern simplePattern;
    private final Pattern chineseSpacePattern = Pattern.compile("([\\u4e00-\\u9fff])\\s+([\\u4e00-\\u9fff])");

    private Map<String, String> untranslated = new LinkedHashMap<>();
    private int exactHits;
    private int wordHits;
    private int noMatch;

    public TranslationDict() {
        this.wordMap = new LinkedHashMap<>();
        this.simpleMap = new LinkedHashMap<>();
        loadBuiltinWords();
        this.wordPattern = buildPattern(wordMap);
        this.simplePattern = buildPattern(simpleMap);
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

        result = replaceByPattern(clean, wordPattern, wordMap);
        result = replaceByPattern(result, simplePattern, simpleMap);

        if (!result.equals(clean)) {
            wordHits++;
            result = chineseSpacePattern.matcher(result).replaceAll("$1$2");
            return result.trim();
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
        wordHits = 0;
        noMatch = 0;
        untranslated.clear();
    }

    public int getExactHits() { return exactHits; }
    public int getWordHits() { return wordHits; }
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

    private void loadBuiltinWords() {
        // Compound word replacements
        wordMap.put("Heavy Machine Gun", "重机枪");
        wordMap.put("Machine Gun", "机枪");
        wordMap.put("Machine gun", "机枪");
        wordMap.put("Anti Air", "防空");
        wordMap.put("Anti-Air", "防空");
        wordMap.put("Anti-air", "防空");
        wordMap.put("Rocket Pod", "火箭发射巢");
        wordMap.put("rocket pod", "火箭发射巢");
        wordMap.put("Bomb Rack", "炸弹挂架");
        wordMap.put("bomb rack", "炸弹挂架");
        wordMap.put("bombrack", "炸弹挂架");
        wordMap.put("Bombrack", "炸弹挂架");
        wordMap.put("High Explosive", "高爆");
        wordMap.put("High explosive", "高爆");
        wordMap.put("high explosive", "高爆");
        wordMap.put("Armor Piercing", "穿甲");
        wordMap.put("armor piercing", "穿甲");
        wordMap.put("Incendiary", "燃烧");
        wordMap.put("incendiary", "燃烧");
        wordMap.put("long range", "远程");
        wordMap.put("Long range", "远程");
        wordMap.put("Long Range", "远程");
        wordMap.put("short range", "短程");
        wordMap.put("medium range", "中程");
        wordMap.put("Fuel Injection", "燃油喷射");
        wordMap.put("fuel injection", "燃油喷射");
        wordMap.put("Fuel injected", "燃油喷射");
        wordMap.put("fuel injected", "燃油喷射");
        wordMap.put("Turbo charged", "涡轮增压");
        wordMap.put("Turbo Charged", "涡轮增压");
        wordMap.put("turbo charged", "涡轮增压");
        wordMap.put("turbocharged", "涡轮增压");
        wordMap.put("Turbocharged", "涡轮增压");
        wordMap.put("Super charged", "机械增压");
        wordMap.put("Super Charged", "机械增压");
        wordMap.put("super charged", "机械增压");
        wordMap.put("supercharger", "机械增压器");
        wordMap.put("Supercharger", "机械增压器");
        wordMap.put("Rear Wheel Drive", "后轮驱动");
        wordMap.put("rear wheel drive", "后轮驱动");
        wordMap.put("Rear wheel drive", "后轮驱动");
        wordMap.put("Front Wheel Drive", "前轮驱动");
        wordMap.put("front wheel drive", "前轮驱动");
        wordMap.put("All Wheel Drive", "全轮驱动");
        wordMap.put("all wheel drive", "全轮驱动");
        wordMap.put("Four Wheel Drive", "四轮驱动");
        wordMap.put("4 Wheel Drive", "四轮驱动");
        wordMap.put("4 wheel drive", "四轮驱动");
        wordMap.put("4 Wheels Drive", "四轮驱动");
        wordMap.put("4 wheels drive", "四轮驱动");
        wordMap.put("Inline 6", "直列6缸");
        wordMap.put("Inline 4", "直列4缸");
        wordMap.put("Inline 5", "直列5缸");
        wordMap.put("V6 engine", "V6发动机");
        wordMap.put("V8 engine", "V8发动机");
        wordMap.put("V12 engine", "V12发动机");
        wordMap.put("Inline 6 engine", "直列6缸发动机");
        wordMap.put("Inline 4 engine", "直列4缸发动机");
        wordMap.put("straight-six", "直列6缸");
        wordMap.put("straight 6", "直列6缸");
        wordMap.put("Straight 6", "直列6缸");
        wordMap.put("four-cylinder", "四缸");
        wordMap.put("manual transmission", "手动变速箱");
        wordMap.put("automatic transmission", "自动变速箱");
        wordMap.put("Stock engine", "原厂发动机");
        wordMap.put("stock engine", "原厂发动机");
        wordMap.put("front bumper", "前保险杠");
        wordMap.put("Front Bumper", "前保险杠");
        wordMap.put("rear bumper", "后保险杠");
        wordMap.put("Rear Bumper", "后保险杠");
        wordMap.put("side skirts", "侧裙");
        wordMap.put("Sideskirts", "侧裙");
        wordMap.put("spoiler", "扰流板");
        wordMap.put("Spoiler", "扰流板");
        wordMap.put("hood", "发动机盖");
        wordMap.put("Hood", "发动机盖");
        wordMap.put("grille", "进气格栅");
        wordMap.put("Grille", "进气格栅");
        wordMap.put("headlight", "头灯");
        wordMap.put("Headlight", "头灯");
        wordMap.put("taillight", "尾灯");
        wordMap.put("Taillight", "尾灯");
        wordMap.put("steering wheel", "方向盘");
        wordMap.put("Steering wheel", "方向盘");
        wordMap.put("Steering Wheel", "方向盘");
        wordMap.put("rollcage", "防滚架");
        wordMap.put("Rollcage", "防滚架");
        wordMap.put("exhaust", "排气");
        wordMap.put("Exhaust", "排气");
        wordMap.put("wheel", "轮毂");
        wordMap.put("Wheel", "轮毂");
        wordMap.put("seat", "座椅");
        wordMap.put("Seat", "座椅");
        wordMap.put("backseat", "后座");
        wordMap.put("Backseat", "后座");
        wordMap.put("Stock", "原厂");
        wordMap.put("stock", "原厂");
        wordMap.put("Custom", "自定义");
        wordMap.put("custom", "自定义");
        wordMap.put("Carbon Fibre", "碳纤维");
        wordMap.put("Carbon fiber", "碳纤维");
        wordMap.put("carbon fibre", "碳纤维");
        wordMap.put("carbon fiber", "碳纤维");
        wordMap.put("turbo", "涡轮");
        wordMap.put("Turbo", "涡轮");

        // Simple word replacements
        simpleMap.put("engine", "发动机");
        simpleMap.put("Engine", "发动机");
        simpleMap.put("turboprop", "涡轮螺旋桨");
        simpleMap.put("turbofan", "涡轮风扇");
        simpleMap.put("turbojet", "涡轮喷气");
        simpleMap.put("airliner", "客机");
        simpleMap.put("fuselage", "机身");
        simpleMap.put("cockpit", "驾驶舱");
        simpleMap.put("radial", "星型");
        simpleMap.put("piston", "活塞");
        simpleMap.put("regional", "支线");
        simpleMap.put("utility", "通用");
        simpleMap.put("observation", "侦察");
        simpleMap.put("reconnaissance", "侦察");
        simpleMap.put("surveillance", "监视");
        simpleMap.put("trainer", "教练机");
        simpleMap.put("freighter", "货机");
        simpleMap.put("cargo", "货运");
        simpleMap.put("supersonic", "超音速");
        simpleMap.put("widebody", "宽体");
        simpleMap.put("narrowbody", "窄体");
        simpleMap.put("twinjet", "双发喷气");
        simpleMap.put("twinjet", "双发喷气");
        simpleMap.put("monoplane", "单翼机");
        simpleMap.put("prototype", "原型机");
        simpleMap.put("variant", "变体");
        simpleMap.put("production", "量产型");
        simpleMap.put("airframe", "机体");
        simpleMap.put("wingspan", "翼展");
        simpleMap.put("payload", "有效载荷");
        simpleMap.put("cruise", "巡航");
        simpleMap.put("climb", "爬升");
        simpleMap.put("autopilot", "自动驾驶");
        simpleMap.put("airspeed", "空速");
        simpleMap.put("altitude", "高度");
        simpleMap.put("propeller", "螺旋桨");
        simpleMap.put("helicopter", "直升机");
        simpleMap.put("rotorcraft", "旋翼机");
        simpleMap.put("compound", "复合");
        simpleMap.put("fenestron", "涵道尾桨");
        simpleMap.put("swashplate", "倾斜盘");
        simpleMap.put("throttle", "油门");
        simpleMap.put("nacelle", "短舱");
        simpleMap.put("cowling", "整流罩");
        simpleMap.put("empennage", "尾翼");
        simpleMap.put("fin", "垂直尾翼");
        simpleMap.put("rudder", "方向舵");
        simpleMap.put("aileron", "副翼");
        simpleMap.put("flap", "襟翼");
        simpleMap.put("spoiler", "扰流板");
        simpleMap.put("undercarriage", "起落架");
        simpleMap.put("retractable", "可收放");
        simpleMap.put("supercharged", "机械增压");
        simpleMap.put("turbocharged", "涡轮增压");
        simpleMap.put("gearbox", "变速箱");
        simpleMap.put("ignition", "点火");
        simpleMap.put("exhaust", "排气");
        simpleMap.put("intake", "进气");
        simpleMap.put("carburetor", "化油器");
        simpleMap.put("injector", "喷油器");
        simpleMap.put("nozzle", "喷管");
        simpleMap.put("sedan", "轿车");
        simpleMap.put("coupe", "轿跑");
        simpleMap.put("convertible", "敞篷");
        simpleMap.put("hatchback", "掀背车");
        simpleMap.put("wagon", "旅行车");
        simpleMap.put("pickup", "皮卡");
        simpleMap.put("SUV", "SUV");
        simpleMap.put("suv", "SUV");
        simpleMap.put("minivan", "MPV");
        simpleMap.put("minibus", "小巴");
        simpleMap.put("van", "厢式车");
        simpleMap.put("truck", "卡车");
        simpleMap.put("trailer", "拖车");
        simpleMap.put("bus", "巴士");
        simpleMap.put("chassis", "底盘");
        simpleMap.put("suspension", "悬挂");
        simpleMap.put("brake", "刹车");
        simpleMap.put("transmission", "变速箱");
        simpleMap.put("radiator", "散热器");
        simpleMap.put("cylinder", "缸");
        simpleMap.put("piston", "活塞");
        simpleMap.put("valve", "气门");
        simpleMap.put("camshaft", "凸轮轴");
        simpleMap.put("crankshaft", "曲轴");
        simpleMap.put("flywheel", "飞轮");
        simpleMap.put("clutch", "离合器");
        simpleMap.put("differential", "差速器");
        simpleMap.put("driveshaft", "传动轴");
        simpleMap.put("axle", "车轴");
        simpleMap.put("rim", "轮辋");
        simpleMap.put("tire", "轮胎");
        simpleMap.put("hubcap", "轮毂盖");
        simpleMap.put("dashboard", "仪表台");
        simpleMap.put("speedometer", "速度表");
        simpleMap.put("tachometer", "转速表");
        simpleMap.put("odometer", "里程表");
        simpleMap.put("fuel", "燃油");
        simpleMap.put("coolant", "冷却液");
        simpleMap.put("oil", "机油");
        simpleMap.put("battery", "电池");
        simpleMap.put("alternator", "发电机");
        simpleMap.put("starter", "起动机");
        simpleMap.put("turbocharger", "涡轮增压器");
        simpleMap.put("intercooler", "中冷器");
        simpleMap.put("supercharger", "机械增压器");
        simpleMap.put("muffler", "消音器");
        simpleMap.put("catalyst", "催化器");
        simpleMap.put("manifold", "歧管");
        simpleMap.put("downpipe", "头段");
    }

    private Pattern buildPattern(Map<String, String> map) {
        if (map.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (String key : map.keySet()) {
            if (sb.length() > 0) sb.append("|");
            sb.append("\\b").append(Pattern.quote(key)).append("\\b");
        }
        return Pattern.compile(sb.toString(), Pattern.CASE_INSENSITIVE);
    }

    private String replaceByPattern(String input, Pattern pattern, Map<String, String> map) {
        if (pattern == null || input == null) return input;
        Matcher m = pattern.matcher(input);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String key = m.group();
            String replacement = map.get(key);
            if (replacement == null) {
                for (Map.Entry<String, String> e : map.entrySet()) {
                    if (key.equalsIgnoreCase(e.getKey())) {
                        replacement = e.getValue();
                        break;
                    }
                }
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement != null ? replacement : key));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
