package com.elma.gohan.provider.evidence;

import com.elma.gohan.TestRestaurants;
import com.elma.gohan.config.EntityResolutionProperties;
import com.elma.gohan.domain.restaurant.Restaurant;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class StrictEntityResolverTest {
    private final EntityResolver resolver = new EntityResolver(new EntityResolutionProperties());
    private Restaurant restaurant(String name, String address) {
        Restaurant r = TestRestaurants.full("a", name, 4.5, 50, 40);
        return new Restaurant(r.id(), r.source(), r.sourcePoiId(), name, r.latitude(), r.longitude(),
                r.distanceMeters(), r.categoryCode(), r.categoryLabel(), r.rating(), r.reviewCount(),
                r.averagePrice(), r.businessStatus(), r.openingHours(), address, "400-123-4567", r.dataCompleteness());
    }
    private PlatformEvidence poi(String id, String name, String address) {
        return new PlatformEvidence("BAIDU", id, EvidenceStatus.AVAILABLE, Instant.now(), name, address,
                28.2291, 112.9412, 4.5, null, null, null, 30, 40, null, null, "400-123-4567");
    }
    @Test void rejectsDifferentBranchesDespiteSharedPhoneAndIdenticalCoordinates() {
        var r = restaurant("湘味小馆(国金中心店)", "解放西路188号2楼");
        assertThat(resolver.resolve(List.of(r), List.of(poi("b", "湘味小馆(五一广场店)",
                "解放西路188号2楼")), Set.of()).get("a").status()).isEqualTo(EntityMatchStatus.NO_MATCH);
    }
    @Test void rejectsFloorAndDoorNumberConflicts() {
        var r = restaurant("湘味小馆(国金中心店)", "解放西路188号2楼201室");
        for (String address : List.of("解放西路188号3楼201室", "解放西路188号2楼202室"))
            assertThat(resolver.resolve(List.of(r), List.of(poi("b", r.name(), address)), Set.of())
                    .get("a").status()).isEqualTo(EntityMatchStatus.NO_MATCH);
    }
    @Test void normalizesFloorSuffixAndDoesNotConfuseFloorOneWithEleven() {
        assertThat(StoreIdentity.of("店名", "2楼201室").conflicts(StoreIdentity.of("店名", "2层201铺"))).isFalse();
        assertThat(StoreIdentity.of("店名", "1楼").conflicts(StoreIdentity.of("店名", "11层"))).isTrue();
        assertThat(StoreIdentity.of("店名", "B1层").conflicts(StoreIdentity.of("店名", "1层"))).isTrue();
    }

    @Test void matchesVerifiedSameBranchAndKeepsDiagnostics() {
        var r = restaurant("湘味小馆(国金中心店)", "解放西路188号2楼201室");
        var match = resolver.resolve(List.of(r), List.of(poi("b", r.name(), r.address())), Set.of()).get("a");
        assertThat(match.status()).isEqualTo(EntityMatchStatus.MATCHED);
        assertThat(match.features()).containsKeys("name", "address", "distanceMeters");
    }
    @Test void identicalCandidatesRemainAmbiguousAndReservedIdsAreExcluded() {
        var r = restaurant("湘味小馆", "麓山南路1号");
        var evidence = List.of(poi("b1", r.name(), r.address()), poi("b2", r.name(), r.address()));
        assertThat(resolver.resolve(List.of(r), evidence, Set.of()).get("a").status()).isEqualTo(EntityMatchStatus.AMBIGUOUS);
        assertThat(resolver.resolve(List.of(r), evidence, Set.of("b1")).get("a").evidence().providerPoiId()).isEqualTo("b2");
    }
    @Test void proximityAndSharedPhoneDoNotRescueWeakNames() {
        var r = restaurant("湘味小馆", "麓山南路1号");
        assertThat(resolver.resolve(List.of(r), List.of(poi("b", "湘遇小馆", r.address())), Set.of())
                .get("a").status()).isEqualTo(EntityMatchStatus.NO_MATCH);
    }
    @Test void assignmentReallocatesSecondChoiceForBetterGlobalResult() {
        double[][] weights = {{0.9, 0.8, 0, 0}, {0.89, -1000, 0, 0}};
        assertThat(MaximumWeightAssignment.solve(weights)).containsExactly(1, 0);
        assertThat(MaximumWeightAssignment.total(weights, MaximumWeightAssignment.solve(weights))).isEqualTo(1.69);
    }
    @Test void assignmentCanLeaveRowsUnmatched() {
        assertThat(MaximumWeightAssignment.solve(new double[][]{{-1000, 0}})).containsExactly(1);
    }
}
