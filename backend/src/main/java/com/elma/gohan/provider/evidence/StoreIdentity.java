package com.elma.gohan.provider.evidence;

import java.text.Normalizer;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/** Separate branch identity from brand similarity; missing fields are not conflicts. */
public record StoreIdentity(String brand, String branch, String floor, String unit, String streetNumber, String venue) {
    private static final Pattern BRANCH = Pattern.compile("[（(]([^（）()]+)[）)]");
    public static StoreIdentity of(String name, String address) {
        var branch = BRANCH.matcher(name == null ? "" : name);
        String qualifier = branch.find() ? clean(branch.group(1)).replaceFirst("(旗舰店|总店|分店|门店|店)$", "") : "";
        if (Set.of("旗舰", "总", "分", "门").contains(qualifier)) qualifier = "";
        String location = Normalizer.normalize(address == null ? "" : address, Normalizer.Form.NFKC).toLowerCase();
        return new StoreIdentity(EntityResolver.brandName(name), qualifier,
                floor(location),
                extractGroup(location, "([a-z]?\\d+(?:-\\d+)?)(?:室|房|铺)"),
                extract(location.replace(" ", ""), "([\\p{IsHan}]{2,}(?:路|街|道)\\d+号)"),
                extract(location.replaceFirst("^.*?\\d+号", ""), "[\\p{IsHan}a-z0-9·]{2,24}(?:中心|广场|商场|商城|大厦)"));
    }
    public boolean conflicts(StoreIdentity other) {
        return conflict(branch, other.branch) || exactConflict(floor, other.floor)
                || exactConflict(unit, other.unit) || conflict(streetNumber, other.streetNumber)
                || conflict(venue, other.venue);
    }
    public boolean branchConfirmed(StoreIdentity other) {
        return !branch.isBlank() && !other.branch.isBlank() && !conflict(branch, other.branch);
    }
    private static boolean conflict(String a, String b) {
        return !a.isBlank() && !b.isBlank() && !a.equals(b) && !a.contains(b) && !b.contains(a);
    }
    private static boolean exactConflict(String a, String b) {
        return !a.isBlank() && !b.isBlank() && !a.equals(b);
    }
    private static String floor(String location) {
        String value = extractGroup(location, "((?:地下|负|b)?[0-9]+)(?:楼|层|f)");
        return value.replace("地下", "b").replace("负", "b");
    }
    private static String extractGroup(String input, String regex) {
        var match = Pattern.compile(regex).matcher(input);
        return match.find() ? match.group(1) : "";
    }
    private static String extract(String input, String regex) {
        var match = Pattern.compile(regex).matcher(input);
        return match.find() ? match.group(0) : "";
    }
    public static String clean(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .toLowerCase(java.util.Locale.ROOT).replaceAll("[^\\p{IsHan}a-z0-9]", "");
    }
    public static double similarity(String a, String b) {
        a = clean(a); b = clean(b);
        if (a.isBlank() || b.isBlank()) return 0;
        if (a.equals(b)) return 1;
        Set<String> left = grams(a), right = grams(b), union = new HashSet<>(left);
        union.addAll(right); left.retainAll(right);
        return (double) left.size() / union.size();
    }
    private static Set<String> grams(String value) {
        Set<String> result = new HashSet<>();
        if (value.length() == 1) result.add(value);
        for (int i = 1; i < value.length(); i++) result.add(value.substring(i - 1, i + 1));
        return result;
    }
}
