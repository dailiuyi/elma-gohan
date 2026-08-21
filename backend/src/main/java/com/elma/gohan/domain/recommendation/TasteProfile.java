package com.elma.gohan.domain.recommendation;

import com.elma.gohan.config.TasteProperties;
import com.elma.gohan.domain.restaurant.Restaurant;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 可解释的长期画像。风险分不读取此对象。 */
public record TasteProfile(
        int schemaVersion,
        Map<String, Double> categoryWeights,
        Map<String, Double> flavorWeights,
        Map<String, Double> priceBandWeights,
        Map<String, Double> distanceBandWeights,
        Map<String, ImplicitAccumulator> implicitAccumulators,
        int explicitFeedbackCount,
        int implicitBehaviorCount,
        LocalDateTime lastDecayedAt,
        LocalDateTime lastFeedbackAt,
        LocalDateTime updatedAt
) {
    public static final int SCHEMA_VERSION = 3;

    public TasteProfile {
        categoryWeights = immutable(categoryWeights);
        flavorWeights = immutable(flavorWeights);
        priceBandWeights = immutable(priceBandWeights);
        distanceBandWeights = immutable(distanceBandWeights);
        implicitAccumulators = implicitAccumulators == null ? Map.of() : Map.copyOf(implicitAccumulators);
    }

    public TasteProfile(int ignoredSchemaVersion, Map<String, Double> categoryWeights,
            Map<String, Double> priceBandWeights, Map<String, Double> distanceBandWeights,
            int feedbackCount, LocalDateTime updatedAt) {
        this(SCHEMA_VERSION, categoryWeights, Map.of(), priceBandWeights, distanceBandWeights,
                Map.of(), feedbackCount, 0, updatedAt, updatedAt, updatedAt);
    }

    public static TasteProfile empty() { return empty(LocalDateTime.now()); }
    public static TasteProfile empty(LocalDateTime now) {
        return new TasteProfile(SCHEMA_VERSION, Map.of(), Map.of(), Map.of(), Map.of(),
                Map.of(), 0, 0, now, null, now);
    }
    public int feedbackCount() { return explicitFeedbackCount; }
    public double confidence(TasteProperties properties) {
        int effective = explicitFeedbackCount + implicitBehaviorCount / 3;
        return Math.max(0.0, Math.min(1.0,
                effective / (double) Math.max(1, properties.getConfidenceTargetSamples())));
    }

    public TasteProfile apply(Restaurant restaurant, int distanceMeters, String result,
                              LocalDateTime occurredAt, TasteProperties properties) {
        return applyFeedback(restaurant, distanceMeters, result, List.of(), occurredAt, properties);
    }

    public TasteProfile applyFeedback(Restaurant restaurant, int distanceMeters, String result,
            Collection<FlavorTag> flavorTags, LocalDateTime occurredAt, TasteProperties properties) {
        TasteProfile base = decayedTo(occurredAt, properties);
        Map<String, Double> categories = mutable(base.categoryWeights);
        Map<String, Double> flavors = mutable(base.flavorWeights);
        Map<String, Double> prices = mutable(base.priceBandWeights);
        Map<String, Double> distances = mutable(base.distanceBandWeights);
        double delta = feedbackDelta(result, properties);
        add(categories, categoryKey(restaurant), delta, properties.getMaxAbsoluteWeight());
        if (restaurant.averagePrice() != null) {
            add(prices, priceBandKey(restaurant.averagePrice(), properties), delta,
                    properties.getMaxAbsoluteWeight());
        }
        add(distances, distanceBandKey(distanceMeters, properties), delta,
                properties.getMaxAbsoluteWeight());
        if (flavorTags != null) flavorTags.forEach(tag -> add(flavors, tag.name(), delta,
                properties.getMaxAbsoluteWeight()));
        return new TasteProfile(SCHEMA_VERSION, categories, flavors, prices, distances,
                base.implicitAccumulators, base.explicitFeedbackCount + 1,
                base.implicitBehaviorCount, occurredAt, occurredAt, occurredAt);
    }

    public TasteProfile applyImplicit(Restaurant restaurant, int distanceMeters,
            Collection<FlavorTag> flavorTags, BehaviorType type, LocalDateTime occurredAt,
            TasteProperties properties) {
        double eventDelta = behaviorDelta(type, properties);
        if (eventDelta == 0.0) return this;
        TasteProfile base = decayedTo(occurredAt, properties);
        Map<String, Double> categories = mutable(base.categoryWeights);
        Map<String, Double> flavors = mutable(base.flavorWeights);
        Map<String, Double> prices = mutable(base.priceBandWeights);
        Map<String, Double> distances = mutable(base.distanceBandWeights);
        Map<String, ImplicitAccumulator> accumulators = new LinkedHashMap<>(base.implicitAccumulators);
        java.util.ArrayList<String> keys = new java.util.ArrayList<>();
        keys.add("C:" + categoryKey(restaurant));
        if (restaurant.averagePrice() != null) keys.add("P:" + priceBandKey(restaurant.averagePrice(), properties));
        keys.add("D:" + distanceBandKey(distanceMeters, properties));
        if (flavorTags != null) flavorTags.forEach(tag -> keys.add("F:" + tag.name()));
        for (String key : keys) {
            ImplicitAccumulator old = accumulators.get(key);
            if (old != null && old.updatedAt().isBefore(occurredAt.minusDays(properties.getImplicitWindowDays()))) old = null;
            int count = old == null ? 1 : old.count() + 1;
            double delta = (old == null ? 0.0 : old.delta()) + eventDelta;
            if (count >= properties.getImplicitThreshold()) {
                double learned = clamp(delta, -properties.getMaxImplicitBatchAdjustment(),
                        properties.getMaxImplicitBatchAdjustment());
                applyFeature(key, learned, categories, flavors, prices, distances,
                        properties.getMaxAbsoluteWeight());
                accumulators.remove(key);
            } else accumulators.put(key, new ImplicitAccumulator(count, delta, occurredAt));
        }
        return new TasteProfile(SCHEMA_VERSION, categories, flavors, prices, distances,
                accumulators, base.explicitFeedbackCount, base.implicitBehaviorCount + 1,
                occurredAt, base.lastFeedbackAt, occurredAt);
    }

    public TasteProfile decayedTo(LocalDateTime now, TasteProperties properties) {
        if (lastDecayedAt == null || !now.isAfter(lastDecayedAt)) return this;
        double days = Duration.between(lastDecayedAt, now).toSeconds() / 86400.0;
        double factor = Math.pow(0.5, days / Math.max(1, properties.getHalfLifeDays()));
        return new TasteProfile(SCHEMA_VERSION, scaled(categoryWeights, factor),
                scaled(flavorWeights, factor), scaled(priceBandWeights, factor),
                scaled(distanceBandWeights, factor), implicitAccumulators,
                explicitFeedbackCount, implicitBehaviorCount, now, lastFeedbackAt, updatedAt);
    }

    public double categoryWeight(Restaurant restaurant) { return categoryWeights.getOrDefault(categoryKey(restaurant), 0.0); }
    public double flavorWeight(FlavorTag tag) { return flavorWeights.getOrDefault(tag.name(), 0.0); }
    public double priceWeight(Restaurant restaurant, TasteProperties p) {
        return restaurant.averagePrice() == null ? 0.0 : priceBandWeights.getOrDefault(priceBandKey(restaurant.averagePrice(), p), 0.0);
    }
    public double distanceWeight(Restaurant restaurant, TasteProperties p) {
        return distanceBandWeights.getOrDefault(distanceBandKey(restaurant.distanceMeters(), p), 0.0);
    }

    private static String categoryKey(Restaurant r) { return r.categoryCode() == null ? "UNKNOWN" : r.categoryCode().toUpperCase(Locale.ROOT); }
    private static String priceBandKey(int value, TasteProperties p) { return bandKey("P", value, p.getPriceBandUpperBounds()); }
    private static String distanceBandKey(int value, TasteProperties p) { return bandKey("D", Math.max(0, value), p.getDistanceBandUpperBounds()); }
    private static String bandKey(String prefix, int value, List<Integer> bounds) {
        int index = 0; while (index < bounds.size() && value > bounds.get(index)) index++; return prefix + index;
    }
    private static double feedbackDelta(String result, TasteProperties p) {
        return switch (result) { case "LIKE" -> p.getFeedback().getLike(); case "NORMAL" -> p.getFeedback().getNormal(); case "DISLIKE" -> p.getFeedback().getDislike(); default -> throw new IllegalArgumentException("未知反馈值: " + result); };
    }
    private static double behaviorDelta(BehaviorType type, TasteProperties p) {
        return switch (type) { case ACCEPT -> p.getBehavior().getAccept(); case NAVIGATE -> p.getBehavior().getNavigate(); case REROLL -> p.getBehavior().getReroll(); case SKIP -> p.getBehavior().getSkip(); default -> 0.0; };
    }
    private static void applyFeature(String key, double delta, Map<String, Double> categories,
            Map<String, Double> flavors, Map<String, Double> prices, Map<String, Double> distances, double max) {
        String name = key.substring(2);
        switch (key.charAt(0)) { case 'C' -> add(categories, name, delta, max); case 'F' -> add(flavors, name, delta, max); case 'P' -> add(prices, name, delta, max); case 'D' -> add(distances, name, delta, max); default -> { } }
    }
    private static void add(Map<String, Double> values, String key, double delta, double max) { values.put(key, clamp(values.getOrDefault(key, 0.0) + delta, -max, max)); }
    private static Map<String, Double> scaled(Map<String, Double> source, double factor) {
        Map<String, Double> result = new LinkedHashMap<>(); source.forEach((key, value) -> { double scaled = value * factor; if (Math.abs(scaled) >= 0.0001) result.put(key, scaled); }); return result;
    }
    private static Map<String, Double> mutable(Map<String, Double> source) { return new LinkedHashMap<>(source == null ? Map.of() : source); }
    private static Map<String, Double> immutable(Map<String, Double> source) { return source == null ? Map.of() : Map.copyOf(source); }
    private static double clamp(double value, double min, double max) { return Math.max(min, Math.min(max, value)); }
}
