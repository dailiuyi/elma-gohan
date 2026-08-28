package com.elma.gohan.provider.deep;

import com.elma.gohan.config.DeepEvidenceProperties;
import com.elma.gohan.domain.restaurant.Restaurant;
import java.text.Normalizer;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** 按店名和地址关键词过滤同店公开搜索结果。 */
@Component
public class WebEvidenceMatcher {

    private static final Pattern STORE_QUALIFIER =
            Pattern.compile("[（(]([^（）()]{2,20})[）)]");

    private final DeepEvidenceProperties properties;

    public WebEvidenceMatcher(DeepEvidenceProperties properties) {
        this.properties = properties;
    }

    public double match(Restaurant restaurant, String title, String snippet) {
        String name = normalizeName(restaurant.name());
        String content = normalize((title == null ? "" : title) + (snippet == null ? "" : snippet));
        if (name.isBlank() || content.isBlank()) return 0.0;

        boolean addressHit = addressKeywords(restaurant.address()).stream()
                .map(this::normalize)
                .filter(token -> token.length() >= 2)
                .anyMatch(content::contains);
        if (content.contains(name)) {
            return addressHit ? 1.0 : 0.95;
        }

        double similarity = jaccard(bigrams(name), bigrams(normalize(title)));
        if (similarity >= properties.getEntityMatchThreshold() && addressHit) {
            return Math.min(0.94, similarity);
        }
        return 0.0;
    }

    public List<String> addressKeywords(String address) {
        if (address == null || address.isBlank()) return List.of();
        return List.of(Normalizer.normalize(address, Normalizer.Form.NFKC)
                        .split("[^\\p{IsHan}A-Za-z0-9]+"))
                .stream()
                .map(this::compactLocation)
                .filter(token -> token.length() >= 2)
                .filter(token -> !token.matches("\\d+号?"))
                .distinct()
                .limit(3)
                .toList();
    }

    /** 搜索只携带一个短位置词，优先使用高德门店名中的分店/商圈限定。 */
    public String searchLocationKeyword(String restaurantName, String address) {
        if (restaurantName != null) {
            Matcher qualifier = STORE_QUALIFIER.matcher(
                    Normalizer.normalize(restaurantName, Normalizer.Form.NFKC));
            if (qualifier.find()) {
                String keyword = qualifier.group(1)
                        .replaceAll("(?:旗舰店|总店|分店|门店|店)$", "")
                        .replaceAll("[^\\p{IsHan}A-Za-z0-9]", "")
                        .trim();
                if (keyword.length() >= 2 && keyword.length() <= 16) return keyword;
            }
        }
        return addressKeywords(address).stream()
                .filter(keyword -> keyword.length() <= 16)
                .filter(keyword -> keyword.matches(".*(?:中心|广场|商场|商城|大厦|街区|城|路|街|大道).*") )
                .findFirst()
                .orElseGet(() -> addressKeywords(address).stream().findFirst().orElse(""));
    }

    private String compactLocation(String value) {
        if (value == null) return "";
        String candidate = value.trim()
                .replaceFirst("(?i)[A-Z]?\\d+(?:栋|楼|层|室).*", "");
        int houseNumber = candidate.lastIndexOf('号');
        if (houseNumber >= 0 && houseNumber < candidate.length() - 1) {
            candidate = candidate.substring(houseNumber + 1);
        }
        candidate = candidate.replaceAll("\\d+号?", "").trim();
        return candidate.length() <= 16 ? candidate : candidate.substring(candidate.length() - 16);
    }

    public String normalizeName(String value) {
        String normalized = normalize(value);
        List<String> suffixes = properties.getStoreSuffixes().stream()
                .map(this::normalize)
                .filter(suffix -> !suffix.isBlank())
                .sorted((left, right) -> Integer.compare(right.length(), left.length()))
                .toList();
        boolean changed;
        do {
            changed = false;
            for (String suffix : suffixes) {
                if (normalized.length() > suffix.length() + 1 && normalized.endsWith(suffix)) {
                    normalized = normalized.substring(0, normalized.length() - suffix.length());
                    changed = true;
                    break;
                }
            }
        } while (changed);
        return normalized;
    }

    public String normalize(String value) {
        if (value == null) return "";
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{IsHan}a-z0-9]", "");
    }

    private Set<String> bigrams(String value) {
        Set<String> grams = new HashSet<>();
        if (value == null || value.isBlank()) return grams;
        if (value.length() == 1) {
            grams.add(value);
            return grams;
        }
        for (int i = 0; i < value.length() - 1; i++) {
            grams.add(value.substring(i, i + 2));
        }
        return grams;
    }

    private double jaccard(Set<String> left, Set<String> right) {
        if (left.isEmpty() || right.isEmpty()) return 0.0;
        Set<String> intersection = new HashSet<>(left);
        intersection.retainAll(right);
        Set<String> union = new HashSet<>(left);
        union.addAll(right);
        return (double) intersection.size() / union.size();
    }
}
