package com.elma.gohan.provider.evidence;

import com.elma.gohan.config.EntityResolutionProperties;
import com.elma.gohan.domain.restaurant.Restaurant;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.assertj.core.api.Assertions.assertThat;

/** Explicit acceptance run only. Never substitute synthetic fixtures for independently labelled stores. */
class PoiMatchingBenchmarkTest {
    public record Sample(String district, String split, String scenario, String batchId,
                         boolean humanVerified, List<String> verificationSources,
                         Restaurant restaurant, String expectedBaiduId,
                         List<PlatformEvidence> realtimeCandidates, List<PlatformEvidence> backgroundCandidates) { }

    @Test
    @EnabledIfSystemProperty(named = "poi.benchmark", matches = ".+")
    void evaluateIndependentHoldout() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        List<Sample> samples = Arrays.asList(mapper.readValue(Path.of(System.getProperty("poi.benchmark")).toFile(), Sample[].class));
        assertThat(samples).hasSizeGreaterThanOrEqualTo(300);
        assertThat(samples).allSatisfy(s -> {
            assertThat(s.humanVerified()).isTrue();
            assertThat(s.verificationSources()).hasSizeGreaterThanOrEqualTo(2);
            assertThat(s.district()).isNotBlank(); assertThat(s.batchId()).isNotBlank();
            assertThat(s.split()).isIn("TUNE", "HOLDOUT");
            assertThat(s.scenario()).isIn("MALL_FLOORS", "NEARBY_CHAINS", "ALIASES", "STREET_STORES");
        });
        assertThat(samples.stream().map(s -> s.restaurant().sourcePoiId()).distinct().count()).isEqualTo(samples.size());
        for (var district : samples.stream().collect(Collectors.groupingBy(Sample::district)).values())
            assertThat(district.stream().map(Sample::split).distinct().count()).isEqualTo(1);
        var holdout = samples.stream().filter(s -> "HOLDOUT".equals(s.split())).toList();
        assertThat(holdout).hasSizeGreaterThanOrEqualTo(200);
        for (String scenario : List.of("MALL_FLOORS", "NEARBY_CHAINS", "ALIASES", "STREET_STORES"))
            assertThat(holdout.stream().filter(s -> scenario.equals(s.scenario())).count()).isGreaterThanOrEqualTo(20);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("totalLabelledStores", samples.size()); report.put("holdoutStores", holdout.size());
        report.put("legacyRealtime", evaluate(holdout, false, false));
        report.put("legacyBackground", evaluate(holdout, false, true));
        report.put("strictRealtime", evaluate(holdout, true, false));
        var background = evaluate(holdout, true, true); report.put("strictBackground", background);
        Files.createDirectories(Path.of("target"));
        mapper.writerWithDefaultPrettyPrinter().writeValue(Path.of("target/poi-matching-report.json").toFile(), report);
        assertThat(background.coverage()).isGreaterThanOrEqualTo(.80);
        assertThat(background.precision()).isGreaterThanOrEqualTo(.98);
    }
    private Metrics evaluate(List<Sample> samples, boolean strict, boolean background) {
        var config = new EntityResolutionProperties(); config.setStrictMatchingEnabled(strict);
        var resolver = new EntityResolver(config);
        long eligible = 0, correct = 0, accepted = 0;
        for (var batch : samples.stream().collect(Collectors.groupingBy(s -> s.district() + ":" + s.batchId())).values()) {
            var evidence = batch.stream().flatMap(s -> (background ? s.backgroundCandidates() : s.realtimeCandidates()).stream()).toList();
            var results = resolver.resolve(batch.stream().map(Sample::restaurant).toList(), evidence, Set.of());
            for (Sample sample : batch) {
                if (sample.expectedBaiduId() != null) eligible++;
                var result = results.get(sample.restaurant().sourcePoiId());
                if (result.status() == EntityMatchStatus.MATCHED) {
                    accepted++;
                    if (Objects.equals(result.evidence().providerPoiId(), sample.expectedBaiduId())) correct++;
                }
            }
        }
        return new Metrics(eligible, accepted, correct, eligible == 0 ? 0 : (double) correct / eligible,
                accepted == 0 ? 0 : (double) correct / accepted);
    }
    public record Metrics(long eligible, long accepted, long correct, double coverage, double precision) { }
}
