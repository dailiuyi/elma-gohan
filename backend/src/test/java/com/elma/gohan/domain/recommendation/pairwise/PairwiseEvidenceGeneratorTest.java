package com.elma.gohan.domain.recommendation.pairwise;

import static org.assertj.core.api.Assertions.assertThat;

import com.elma.gohan.domain.recommendation.FlavorTag;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PairwiseEvidenceGeneratorTest {

    private final PairwiseEvidenceGenerator generator = new PairwiseEvidenceGenerator();

    @Test
    void sameCategoryDoesNotProduceCategoryEvidence() {
        PairwiseCandidateFeatures rejected = features("CHINESE", "P1", "D1");
        PairwiseCandidateFeatures chosen = features("CHINESE", "P2", "D2");

        List<PairwiseEvidence> evidence = generator.generate(rejected, chosen, Set.of());

        assertThat(evidence)
                .extracting(PairwiseEvidence::featureKey)
                .containsExactly(PairwiseFeatureKey.PRICE_BAND, PairwiseFeatureKey.DISTANCE_BAND);
    }

    @Test
    void onlyDifferentStructuralFeaturesProduceWinnerAndLoserEvidence() {
        PairwiseCandidateFeatures rejected = features("CHINESE", "P1", "D1");
        PairwiseCandidateFeatures chosen = features("WESTERN", "P1", "D2");

        List<PairwiseEvidence> evidence = generator.generate(rejected, chosen, Set.of(), 0.75, 3);

        assertThat(evidence).containsExactly(
                new PairwiseEvidence(PairwiseFeatureKey.CATEGORY, "WESTERN", "CHINESE", 0.75, 3),
                new PairwiseEvidence(PairwiseFeatureKey.DISTANCE_BAND, "D2", "D1", 0.75, 3));
    }

    @Test
    void flavorEvidenceComesOnlyFromExplicitChosenTagsAndHasStableOrder() {
        PairwiseCandidateFeatures rejected = features("CHINESE", "P1", "D1");
        PairwiseCandidateFeatures chosen = features("CHINESE", "P1", "D1");
        Set<FlavorTag> explicitTags = new HashSet<>(List.of(FlavorTag.SPICY, FlavorTag.LIGHT));

        assertThat(generator.generate(rejected, chosen, Set.of())).isEmpty();
        assertThat(generator.generate(rejected, chosen, explicitTags)).containsExactly(
                new PairwiseEvidence(PairwiseFeatureKey.FLAVOR, "LIGHT", null, 1.0, 1),
                new PairwiseEvidence(PairwiseFeatureKey.FLAVOR, "SPICY", null, 1.0, 1));
    }

    @Test
    void isolatedRerollProducesNoLongTermEvidence() {
        assertThat(generator.isolatedReroll(features("CHINESE", "P1", "D1"))).isEmpty();
    }

    @Test
    void evidenceIsDeterministicAndJacksonRoundTripSerializable() throws Exception {
        PairwiseCandidateFeatures rejected = features("CHINESE", "P1", "D1");
        PairwiseCandidateFeatures chosen = features("WESTERN", "P2", "D2");
        Set<FlavorTag> explicitTags = new HashSet<>(List.of(FlavorTag.SPICY, FlavorTag.LIGHT));

        List<PairwiseEvidence> first = generator.generate(rejected, chosen, explicitTags);
        List<PairwiseEvidence> second = generator.generate(rejected, chosen, explicitTags);
        ObjectMapper objectMapper = new ObjectMapper();
        String json = objectMapper.writeValueAsString(first);
        List<PairwiseEvidence> restored = objectMapper.readValue(json, new TypeReference<>() { });

        assertThat(second).isEqualTo(first);
        assertThat(restored).isEqualTo(first);
        assertThat(json).contains("\"featureKey\":\"CATEGORY\"", "\"winner\":\"WESTERN\"",
                "\"loser\":\"CHINESE\"", "\"strength\":1.0", "\"support\":1");
    }

    private static PairwiseCandidateFeatures features(String category, String priceBand, String distanceBand) {
        return new PairwiseCandidateFeatures(category, priceBand, distanceBand);
    }
}
