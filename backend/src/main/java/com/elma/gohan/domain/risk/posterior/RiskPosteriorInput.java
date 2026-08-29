package com.elma.gohan.domain.risk.posterior;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Stable input snapshot for RiskPosterior v0.5.
 *
 * <p>The current six {@code RiskFactors} values can map to h after division by
 * 100. Evidence availability, match confidence, sample coverage and freshness
 * can be combined into q by an adapter. The adapter is deliberately not part of
 * this change, so the existing recommendation response remains untouched.</p>
 */
public record RiskPosteriorInput(List<RiskPosteriorFactor> factors) {

    public RiskPosteriorInput {
        Objects.requireNonNull(factors, "factors");
        List<RiskPosteriorFactor> stable = new ArrayList<>(factors.size());
        for (RiskPosteriorFactor factor : factors) {
            stable.add(Objects.requireNonNull(factor, "factor"));
        }
        stable.sort(Comparator.comparing(RiskPosteriorFactor::key));
        Set<String> keys = new HashSet<>();
        for (RiskPosteriorFactor factor : stable) {
            if (!keys.add(factor.key())) {
                throw new IllegalArgumentException("duplicate factor key: " + factor.key());
            }
        }
        factors = List.copyOf(stable);
    }

    public static RiskPosteriorInput of(RiskPosteriorFactor... factors) {
        return new RiskPosteriorInput(List.of(factors));
    }
}
