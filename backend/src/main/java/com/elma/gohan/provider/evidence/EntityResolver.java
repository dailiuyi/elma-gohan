package com.elma.gohan.provider.evidence;

import com.elma.gohan.config.EntityResolutionProperties;
import com.elma.gohan.domain.restaurant.Restaurant;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** 透明、确定性的高德餐厅与百度 POI 一对一匹配。 */
@Component
public class EntityResolver {

    private static final Logger log = LoggerFactory.getLogger(EntityResolver.class);
    private static final Pattern NON_TEXT = Pattern.compile("[^\\p{IsHan}a-z0-9]");
    private static final Pattern STORE_SUFFIX = Pattern.compile("(旗舰店|总店|分店|门店|店)$");
    private static final Pattern PARENTHETICAL = Pattern.compile("[（(][^（）()]{0,30}[）)]");
    private static final Pattern CLOSED_MARK = Pattern.compile("暂停营业|已关闭|已歇业|已停业");
    private final EntityResolutionProperties properties;

    public EntityResolver(EntityResolutionProperties properties) {
        this.properties = properties;
    }

    public Map<String, EntityMatchResult> resolve(List<Restaurant> restaurants,
                                                   List<PlatformEvidence> evidence,
                                                   Set<String> reservedProviderIds) {
        Map<String, List<ScoredCandidate>> candidates = new HashMap<>();
        for (Restaurant restaurant : restaurants) {
            List<ScoredCandidate> scores = new ArrayList<>();
            for (PlatformEvidence item : evidence) {
                if (reservedProviderIds.contains(item.providerPoiId())) continue;
                ScoredCandidate candidate = score(restaurant, item);
                if (candidate != null) scores.add(candidate);
            }
            scores.sort(Comparator.comparingDouble(ScoredCandidate::score).reversed()
                    .thenComparing(c -> c.evidence().providerPoiId()));
            candidates.put(restaurant.sourcePoiId(), scores);
        }

        List<PrimaryCandidate> accepted = new ArrayList<>();
        Map<String, EntityMatchResult> result = new LinkedHashMap<>();
        for (Restaurant restaurant : restaurants) {
            List<ScoredCandidate> scores = candidates.get(restaurant.sourcePoiId());
            if (scores == null || scores.isEmpty()) {
                result.put(restaurant.sourcePoiId(), EntityMatchResult.noMatch());
                continue;
            }
            if (scores.get(0).score() < properties.getAcceptThreshold()) {
                result.put(restaurant.sourcePoiId(),
                        EntityMatchResult.noMatch(scores.get(0).features()));
                continue;
            }
            ScoredCandidate best = scores.get(0);
            accepted.add(new PrimaryCandidate(restaurant.sourcePoiId(), best));
        }

        accepted.sort(Comparator.comparingDouble(
                (PrimaryCandidate value) -> value.candidate().score()).reversed()
                .thenComparing(PrimaryCandidate::primaryPoiId));
        Set<String> used = new HashSet<>(reservedProviderIds);
        int conflictCount = 0;
        int conflictNoMatchCount = 0;
        int acceptableSecondCandidateCount = 0;
        for (PrimaryCandidate primary : accepted) {
            ScoredCandidate candidate = primary.candidate();
            if (!used.add(candidate.evidence().providerPoiId())) {
                conflictCount++;
                conflictNoMatchCount++;
                List<ScoredCandidate> scores = candidates.getOrDefault(primary.primaryPoiId(), List.of());
                boolean hasAcceptableSecondCandidate = scores.stream().skip(1)
                        .anyMatch(alternative -> alternative.score() >= properties.getAcceptThreshold());
                if (hasAcceptableSecondCandidate) acceptableSecondCandidateCount++;
                result.put(primary.primaryPoiId(),
                        EntityMatchResult.noMatch(candidate.features()));
                continue;
            }
            result.put(primary.primaryPoiId(), new EntityMatchResult(EntityMatchStatus.MATCHED,
                    round(candidate.score()), candidate.evidence(), candidate.features()));
        }
        if (conflictCount > 0) {
            log.info("百度 UID 冲突统计 conflicts={} conflictNoMatch={} acceptableSecondCandidate={} acceptableSecondCandidateCount={}",
                    conflictCount, conflictNoMatchCount, acceptableSecondCandidateCount > 0,
                    acceptableSecondCandidateCount);
        }
        return result;
    }

    private ScoredCandidate score(Restaurant restaurant, PlatformEvidence evidence) {
        String restaurantAddress = normalizeText(restaurant.address());
        String evidenceAddress = normalizeText(evidence.address());
        double name = nameSimilarity(restaurant.name(), evidence.name());
        double address = jaccardBigrams(restaurantAddress, evidenceAddress);
        double distance = haversineMeters(restaurant.latitude(), restaurant.longitude(),
                evidence.latitude(), evidence.longitude());
        Set<String> restaurantTelephones = telephones(restaurant.telephone());
        Set<String> evidenceTelephones = telephones(evidence.telephone());
        boolean telephoneComparable = !restaurantTelephones.isEmpty()
                && !evidenceTelephones.isEmpty();
        boolean telephoneExact = telephoneComparable
                && restaurantTelephones.stream().anyMatch(evidenceTelephones::contains);
        if (distance > properties.getMaximumDistanceMeters() && !telephoneExact) return null;
        if (name < properties.getMinimumNameSimilarity() && !telephoneExact && distance > 40) {
            return null;
        }

        boolean addressComparable = !restaurantAddress.isBlank() && !evidenceAddress.isBlank();
        boolean coordinateComparable = evidence.latitude() != null && evidence.longitude() != null;
        boolean sparseEvidence = !addressComparable || !telephoneComparable;
        boolean closeEnoughToSkipSparse = distance <= 200 && name >= 0.20;
        if (sparseEvidence && !telephoneExact && !closeEnoughToSkipSparse
                && (name < properties.getSparseMatchMinimumNameSimilarity()
                || distance > properties.getSparseMatchMaximumDistanceMeters())) {
            return null;
        }

        double coordinate = coordinateSimilarity(distance);
        double telephone = telephoneExact ? 1.0 : 0.0;
        double weightedScore = name * properties.getNameWeight();
        double availableWeight = properties.getNameWeight();
        if (coordinateComparable) {
            weightedScore += coordinate * properties.getCoordinateWeight();
            availableWeight += properties.getCoordinateWeight();
        }
        if (addressComparable) {
            weightedScore += address * properties.getAddressWeight();
            availableWeight += properties.getAddressWeight();
        }
        if (telephoneComparable) {
            weightedScore += telephone * properties.getTelephoneWeight();
            availableWeight += properties.getTelephoneWeight();
        }
        double score = availableWeight == 0.0 ? 0.0 : weightedScore / availableWeight;
        boolean coreEqual = coreNamesEqual(restaurant.name(), evidence.name());
        int prefix = longestCommonPrefix(brandName(restaurant.name()), brandName(evidence.name()));
        if (telephoneExact && name >= 0.7) score = Math.max(score, 0.85);
        if (telephoneExact && distance <= 80 && name >= 0.35) score = Math.max(score, 0.82);
        if (distance <= 40 && name >= 0.12) score = Math.max(score, 0.70);
        if (distance <= 80 && name >= 0.30) score = Math.max(score, 0.70);
        if (distance <= 200 && name >= 0.75) score = Math.max(score, 0.70);
        if (distance <= 80 && name >= 0.85) score = Math.max(score, 0.84);
        if (distance <= 80 && name >= 0.60) score = Math.max(score, 0.72);
        if (distance <= 100 && coreEqual) score = Math.max(score, 0.86);
        if (prefix >= 3 && distance <= 250) score = Math.max(score, 0.78);
        if (prefix >= 2 && distance <= 50) score = Math.max(score, 0.72);
        if (distance <= 80 && (brandContained(brandName(restaurant.name()),
                brandName(evidence.name())) || coreEqual)) {
            score = Math.max(score, 0.80);
        }
        Map<String, Double> features = new LinkedHashMap<>();
        features.put("name", round(name));
        features.put("coordinate", round(coordinate));
        features.put("address", round(address));
        features.put("telephone", telephone);
        features.put("distanceMeters", round(distance));
        features.put("availableWeight", round(availableWeight));
        features.put("weightedScore", round(weightedScore));
        return new ScoredCandidate(evidence, Math.min(1.0, score), features);
    }

    private double coordinateSimilarity(double meters) {
        if (meters <= 30) return 1.0;
        if (meters <= 100) return 1.0 - (meters - 30) * 0.4 / 70.0;
        if (meters <= 300) return 0.6 - (meters - 100) * 0.6 / 200.0;
        return 0.0;
    }

    static String normalizeName(String value) {
        String text = CLOSED_MARK.matcher(normalizeText(value)).replaceAll("");
        boolean changed;
        do {
            String stripped = STORE_SUFFIX.matcher(text).replaceFirst("");
            changed = !stripped.equals(text);
            text = stripped;
        } while (changed && !text.isBlank());
        return text;
    }

    public static String coreName(String value) {
        if (value == null || value.isBlank()) return "";
        String withoutParen = PARENTHETICAL.matcher(value).replaceAll("");
        return normalizeName(withoutParen);
    }

    private static final Pattern VENUE_SUFFIX = Pattern.compile(
            "(餐饮管理有限公司|管理有限公司|有限公司|餐饮公司|"
                    + "原味家菜馆|家菜馆|湘菜馆|湘潭菜|家常菜|菜馆|跳跳蛙活鱼馆|活鱼馆|"
                    + "围炉麻辣烫|麻辣烫|披萨速递|速递|坛子菜钵子饭|钵子饭|坛子菜|"
                    + "必吃餐厅|必吃|餐厅|餐馆|饭店|食堂|酒楼|美食店|粉面馆|面馆|火锅)$");
    public static String brandName(String value) {
        String text = coreName(value);
        boolean changed;
        do {
            String stripped = VENUE_SUFFIX.matcher(text).replaceFirst("");
            changed = !stripped.equals(text) && stripped.length() >= 2;
            if (changed) text = stripped;
        } while (changed);
        return text.length() >= 2 ? text : coreName(value);
    }

    public static List<String> searchQueries(String name, String address) {
        LinkedHashSet<String> queries = new LinkedHashSet<>();
        String fullName = normalizeName(name);
        if (fullName.length() >= 2) queries.add(fullName);
        String brand = brandName(name);
        if (brand.length() >= 2) queries.add(brand);
        return List.copyOf(queries);
    }

    public static String searchQuery(String name, String address) {
        List<String> queries = searchQueries(name, address);
        return queries.isEmpty() ? "" : queries.get(0);
    }

    public static boolean coreNamesEqual(String left, String right) {
        String first = coreName(left);
        String second = coreName(right);
        return first.length() >= 2 && first.equals(second);
    }

    public static double nameSimilarity(String leftRaw, String rightRaw) {
        String left = normalizeName(leftRaw);
        String right = normalizeName(rightRaw);
        double jaccard = jaccardBigrams(left, right);
        String leftCore = coreName(leftRaw);
        String rightCore = coreName(rightRaw);
        if (leftCore.length() >= 2 && leftCore.equals(rightCore)) {
            return 1.0;
        }
        String leftBrand = brandName(leftRaw);
        String rightBrand = brandName(rightRaw);
        if (leftBrand.length() >= 2 && leftBrand.equals(rightBrand)) {
            return 1.0;
        }
        double coreJaccard = jaccardBigrams(leftCore, rightCore);
        double brandJaccard = jaccardBigrams(leftBrand, rightBrand);
        double contained = 0.0;
        if (brandContained(leftCore, rightCore) || brandContained(leftBrand, rightBrand)) {
            contained = 0.85;
        }
        int prefix = longestCommonPrefix(leftBrand, rightBrand);
        double prefixScore = prefix >= 3 ? 0.92 : (prefix >= 2 ? 0.72 : 0.0);
        return Math.max(jaccard, Math.max(coreJaccard,
                Math.max(brandJaccard, Math.max(contained, prefixScore))));
    }

    private static boolean brandContained(String left, String right) {
        if (left.length() < 2 || right.length() < 2) return false;
        return left.contains(right) || right.contains(left);
    }

    static int longestCommonPrefix(String left, String right) {
        if (left == null || right == null) return 0;
        int n = Math.min(left.length(), right.length());
        int i = 0;
        while (i < n && left.charAt(i) == right.charAt(i)) i++;
        return i;
    }

    static double charOverlap(String left, String right) {
        if (left == null || right == null || left.isBlank() || right.isBlank()) return 0.0;
        String shorter = left.length() <= right.length() ? left : right;
        String longer = left.length() <= right.length() ? right : left;
        int hit = 0;
        for (int i = 0; i < shorter.length(); i++) {
            if (longer.indexOf(shorter.charAt(i)) >= 0) hit++;
        }
        return (double) hit / shorter.length();
    }

    static String normalizeText(String value) {
        if (value == null) return "";
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        return NON_TEXT.matcher(normalized).replaceAll("");
    }

    private static Set<String> telephones(String value) {
        if (value == null || value.isBlank()) return Set.of();
        Set<String> result = new LinkedHashSet<>();
        // 空格和连字符常是同一个号码的排版字符；只用多号码分隔符拆分。
        for (String part : value.split("[,;/]+")) {
            String digits = part.replaceAll("[^0-9]", "");
            if (digits.length() >= 7) result.add(digits);
        }
        return result;
    }

    private static double jaccardBigrams(String first, String second) {
        if (first.isBlank() || second.isBlank()) return 0.0;
        if (first.equals(second)) return 1.0;
        Set<String> left = bigrams(first);
        Set<String> right = bigrams(second);
        Set<String> intersection = new HashSet<>(left);
        intersection.retainAll(right);
        Set<String> union = new HashSet<>(left);
        union.addAll(right);
        return union.isEmpty() ? 0.0 : (double) intersection.size() / union.size();
    }

    private static Set<String> bigrams(String value) {
        if (value.length() < 2) return Set.of(value);
        Set<String> result = new LinkedHashSet<>();
        for (int i = 0; i < value.length() - 1; i++) result.add(value.substring(i, i + 2));
        return result;
    }

    private static double haversineMeters(double lat1, double lng1, Double lat2, Double lng2) {
        if (lat2 == null || lng2 == null) return Double.MAX_VALUE;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 6_371_000 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private static double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    private record ScoredCandidate(PlatformEvidence evidence, double score,
                                   Map<String, Double> features) { }
    private record PrimaryCandidate(String primaryPoiId, ScoredCandidate candidate) { }
}
