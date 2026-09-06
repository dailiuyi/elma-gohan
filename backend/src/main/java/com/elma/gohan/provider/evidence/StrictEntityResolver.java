package com.elma.gohan.provider.evidence;

import com.elma.gohan.config.EntityResolutionProperties;
import com.elma.gohan.domain.restaurant.Restaurant;
import java.util.*;
import org.slf4j.LoggerFactory;

final class StrictEntityResolver {
    private record Candidate(double score, Map<String, Double> features) { }
    static Map<String, EntityMatchResult> resolve(List<Restaurant> restaurants,
            List<PlatformEvidence> evidence, Set<String> reserved, EntityResolutionProperties config) {
        var rows = restaurants.stream().sorted(Comparator.comparing(Restaurant::sourcePoiId)).toList();
        var byId = new TreeMap<String, PlatformEvidence>();
        evidence.stream().filter(e -> e.providerPoiId() != null && !reserved.contains(e.providerPoiId()))
                .forEach(e -> byId.putIfAbsent(e.providerPoiId(), e));
        var pois = List.copyOf(byId.values());
        int n = rows.size(), m = pois.size();
        double[][] weights = new double[n][m + n];
        Candidate[][] candidates = new Candidate[n][m];
        for (int i = 0; i < n; i++) for (int j = 0; j < m; j++) {
            candidates[i][j] = score(rows.get(i), pois.get(j), config);
            weights[i][j] = candidates[i][j] == null ? -1000 : candidates[i][j].score();
        }
        int[] assignment = MaximumWeightAssignment.solve(weights);
        double optimum = MaximumWeightAssignment.total(weights, assignment);
        Set<Integer> ambiguous = new HashSet<>();
        // Compare with the best global alternative, not just each row's second candidate.
        for (int i = 0; i < n; i++) if (assignment[i] < m) {
            int j = assignment[i]; double saved = weights[i][j]; weights[i][j] = -1000;
            double alternative = MaximumWeightAssignment.total(weights, MaximumWeightAssignment.solve(weights));
            weights[i][j] = saved;
            if (optimum - alternative < config.getAmbiguityMargin()) ambiguous.add(i);
        }
        Map<String, EntityMatchResult> result = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            int j = assignment[i];
            if (ambiguous.contains(i)) {
                result.put(rows.get(i).sourcePoiId(), new EntityMatchResult(EntityMatchStatus.AMBIGUOUS,
                        null, null, Map.of("reasonAmbiguous", 1.0)));
            } else if (j < m && candidates[i][j] != null) {
                var candidate = candidates[i][j];
                result.put(rows.get(i).sourcePoiId(), new EntityMatchResult(EntityMatchStatus.MATCHED,
                        candidate.score(), pois.get(j), candidate.features()));
            } else {
                boolean eligible = Arrays.stream(candidates[i]).anyMatch(Objects::nonNull);
                result.put(rows.get(i).sourcePoiId(), EntityMatchResult.noMatch(Map.of(
                        m == 0 ? "reasonNoRecall" : eligible ? "reasonUidConflict" : "reasonRuleRejected", 1.0)));
            }
        }
        var counts = new TreeMap<String, Long>();
        result.values().forEach(r -> counts.merge(r.status().name() + r.features().keySet().stream()
                .filter(k -> k.startsWith("reason")).findFirst().orElse(""), 1L, Long::sum));
        LoggerFactory.getLogger(StrictEntityResolver.class).info("Baidu identity diagnostics={}", counts);
        return result;
    }

    private static Candidate score(Restaurant r, PlatformEvidence e, EntityResolutionProperties p) {
        if (e.status() != EvidenceStatus.AVAILABLE || e.latitude() == null || e.longitude() == null) return null;
        var a = StoreIdentity.of(r.name(), r.address()); var b = StoreIdentity.of(e.name(), e.address());
        if (a.conflicts(b)) return null;
        double lat = Math.toRadians(e.latitude() - r.latitude()), lng = Math.toRadians(e.longitude() - r.longitude());
        double h = Math.pow(Math.sin(lat / 2), 2) + Math.cos(Math.toRadians(r.latitude()))
                * Math.cos(Math.toRadians(e.latitude())) * Math.pow(Math.sin(lng / 2), 2);
        double distance = 6371000 * 2 * Math.asin(Math.min(1, Math.sqrt(h)));
        if (distance > p.getMaximumDistanceMeters()) return null;
        double name = Math.max(StoreIdentity.similarity(a.brand(), b.brand()),
                StoreIdentity.similarity(EntityResolver.coreName(r.name()), EntityResolver.coreName(e.name())));
        if (name < 0.55) return null;
        boolean addressAvailable = r.address() != null && !r.address().isBlank() && e.address() != null && !e.address().isBlank();
        double address = StoreIdentity.similarity(r.address(), e.address());
        double coordinate = Math.max(0, 1 - distance / p.getMaximumDistanceMeters());
        boolean phoneAvailable = r.telephone() != null && e.telephone() != null;
        boolean phone = phoneAvailable && Arrays.stream(r.telephone().split("[;,/，；]"))
                .map(s -> s.replaceAll("\\D", "")).filter(s -> s.length() >= 7)
                .anyMatch(s -> Arrays.stream(e.telephone().split("[;,/，；]")).map(t -> t.replaceAll("\\D", "")).anyMatch(s::equals));
        double available = p.getNameWeight() + p.getCoordinateWeight()
                + (addressAvailable ? p.getAddressWeight() : 0) + (phoneAvailable ? p.getTelephoneWeight() : 0);
        double weighted = name * p.getNameWeight() + coordinate * p.getCoordinateWeight()
                + (addressAvailable ? address * p.getAddressWeight() : 0) + (phone ? p.getTelephoneWeight() : 0);
        double score = weighted / available;
        if (a.branchConfirmed(b)) score = Math.min(1, score + 0.05);
        if (score < p.getStrictAcceptThreshold()) return null;
        return new Candidate(score, Map.of("name", name, "coordinate", coordinate, "address", address,
                "telephone", phone ? 1.0 : 0.0, "distanceMeters", distance, "availableWeight", available));
    }
}
