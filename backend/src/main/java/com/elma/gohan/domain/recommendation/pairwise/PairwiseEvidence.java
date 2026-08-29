package com.elma.gohan.domain.recommendation.pairwise;

import java.util.Objects;

/**
 * 一条可序列化的差分证据。
 *
 * <p>结构化特征同时携带胜者 B 的值和败者 A 的值，消费方可分别做正、负更新。
 * FLAVOR 只允许用户显式选择的 B 标签，因此该维度是 winner-only，loser 固定为 {@code null}。
 */
public record PairwiseEvidence(
        PairwiseFeatureKey featureKey,
        String winner,
        String loser,
        double strength,
        int support) {

    public PairwiseEvidence {
        featureKey = Objects.requireNonNull(featureKey, "featureKey must not be null");
        winner = requireValue(winner, "winner");
        loser = normalize(loser);
        if (!Double.isFinite(strength) || strength <= 0.0) {
            throw new IllegalArgumentException("strength must be finite and positive");
        }
        if (support <= 0) {
            throw new IllegalArgumentException("support must be positive");
        }
        if (featureKey == PairwiseFeatureKey.FLAVOR) {
            if (loser != null) {
                throw new IllegalArgumentException("flavor evidence must be winner-only");
            }
        } else {
            if (loser == null) {
                throw new IllegalArgumentException("structural evidence requires a loser");
            }
            if (winner.equals(loser)) {
                throw new IllegalArgumentException("winner and loser must differ");
            }
        }
    }

    private static String requireValue(String value, String field) {
        String normalized = normalize(value);
        if (normalized == null) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.strip();
        return normalized.isEmpty() ? null : normalized;
    }
}
