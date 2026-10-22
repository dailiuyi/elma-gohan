package com.elma.gohan.application.shadow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.elma.gohan.config.SafeRegretProperties;
import com.elma.gohan.config.TasteProperties;
import com.elma.gohan.infrastructure.persistence.RecommendationDecisionSnapshotRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/** 固定于 ead3da1 的 v0.4 结果与 V9 完整 payload，禁止整理代码时重写预期。 */
class RecommendationRegressionTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    @Test
    void frozenInputsKeepScoresReasonsPoolsAndShadowSnapshotsUnchanged() throws Exception {
        var repository = mock(RecommendationDecisionSnapshotRepository.class);
        when(repository.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        var shadow = new SafeRegretShadowService(new SafeRegretProperties(), new SafeRegretShadowSnapshotWriter(repository),
                new SafeRegretShadowCalculator(new SafeRegretProperties(), new TasteProperties(), mapper));
        Map<String, Object> actual = new TreeMap<>();
        for (var scenario : RecommendationReplayFixtures.scenarios()) {
            for (long seed : RecommendationReplayFixtures.SEEDS) {
                var input = scenario.input(seed);
                var snapshot = shadow.capture(input).orElseThrow();
                actual.put(scenario.name() + "/" + seed, Map.of(
                        "served", hash(mapper.writeValueAsBytes(input.servedResult())),
                        "shadow", hash(mapper.writeValueAsBytes(mapper.readTree(snapshot.getAllCandidatesJson()))),
                        "config", snapshot.getConfigHash()));
            }
        }
        Path generated = Path.of("target/recommendation-regression-actual.json");
        Files.createDirectories(generated.getParent());
        mapper.writerWithDefaultPrettyPrinter().writeValue(generated.toFile(), actual);
        try (var expected = getClass().getResourceAsStream("/recommendation/regression-ead3da1.json")) {
            assertThat(expected).as("Frozen baseline must be created before refactoring").isNotNull();
            assertThat(mapper.<com.fasterxml.jackson.databind.JsonNode>valueToTree(actual)).isEqualTo(mapper.readTree(expected));
        }
    }

    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
