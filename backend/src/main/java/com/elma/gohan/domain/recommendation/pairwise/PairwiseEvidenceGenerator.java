package com.elma.gohan.domain.recommendation.pairwise;

import com.elma.gohan.domain.recommendation.FlavorTag;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** 从一次“拒绝 A，随后选择 B”生成最小差分偏好证据。 */
public final class PairwiseEvidenceGenerator {

    public static final double DEFAULT_STRENGTH = 1.0;
    public static final int DEFAULT_SUPPORT = 1;

    public List<PairwiseEvidence> generate(
            PairwiseCandidateFeatures rejected,
            PairwiseCandidateFeatures chosen,
            Collection<FlavorTag> explicitChosenFlavorTags) {
        return generate(rejected, chosen, explicitChosenFlavorTags, DEFAULT_STRENGTH, DEFAULT_SUPPORT);
    }

    public List<PairwiseEvidence> generate(
            PairwiseCandidateFeatures rejected,
            PairwiseCandidateFeatures chosen,
            Collection<FlavorTag> explicitChosenFlavorTags,
            double strength,
            int support) {
        Objects.requireNonNull(rejected, "rejected must not be null");
        Objects.requireNonNull(chosen, "chosen must not be null");
        Objects.requireNonNull(explicitChosenFlavorTags, "explicitChosenFlavorTags must not be null");
        validateWeight(strength, support);

        List<PairwiseEvidence> evidence = new ArrayList<>();
        addDifference(evidence, PairwiseFeatureKey.CATEGORY,
                rejected.category(), chosen.category(), strength, support);
        addDifference(evidence, PairwiseFeatureKey.PRICE_BAND,
                rejected.priceBand(), chosen.priceBand(), strength, support);
        addDifference(evidence, PairwiseFeatureKey.DISTANCE_BAND,
                rejected.distanceBand(), chosen.distanceBand(), strength, support);

        explicitChosenFlavorTags.stream()
                .map(tag -> Objects.requireNonNull(tag, "explicit flavor tag must not be null"))
                .distinct()
                .sorted(Comparator.comparing(FlavorTag::name))
                .map(tag -> new PairwiseEvidence(
                        PairwiseFeatureKey.FLAVOR, tag.name(), null, strength, support))
                .forEach(evidence::add);

        return List.copyOf(evidence);
    }

    /** 孤立 REROLL 只用于会话内降权，不产生任何长期偏好证据。 */
    public List<PairwiseEvidence> isolatedReroll(PairwiseCandidateFeatures rejected) {
        Objects.requireNonNull(rejected, "rejected must not be null");
        return List.of();
    }

    private static void addDifference(
            List<PairwiseEvidence> evidence,
            PairwiseFeatureKey featureKey,
            String rejectedValue,
            String chosenValue,
            double strength,
            int support) {
        if (rejectedValue == null || chosenValue == null || rejectedValue.equals(chosenValue)) {
            return;
        }
        evidence.add(new PairwiseEvidence(featureKey, chosenValue, rejectedValue, strength, support));
    }

    private static void validateWeight(double strength, int support) {
        if (!Double.isFinite(strength) || strength <= 0.0) {
            throw new IllegalArgumentException("strength must be finite and positive");
        }
        if (support <= 0) {
            throw new IllegalArgumentException("support must be positive");
        }
    }
}
