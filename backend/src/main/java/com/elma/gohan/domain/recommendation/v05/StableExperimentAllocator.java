package com.elma.gohan.domain.recommendation.v05;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/** 使用匿名 UUID 和实验名稳定分桶，不依赖前端传递实验参数。 */
public final class StableExperimentAllocator {

    private static final int BUCKETS = 10_000;

    public Allocation allocate(UUID anonymousUserId, String experimentKey,
                               boolean servingEnabled, int rolloutPercentage) {
        if (anonymousUserId == null) throw new IllegalArgumentException("anonymousUserId 不能为空");
        if (experimentKey == null || experimentKey.isBlank()) {
            throw new IllegalArgumentException("experimentKey 不能为空");
        }
        if (rolloutPercentage < 0 || rolloutPercentage > 100) {
            throw new IllegalArgumentException("rolloutPercentage 必须在 0~100 之间");
        }
        int bucket = bucket(anonymousUserId, experimentKey);
        if (!servingEnabled) return new Allocation(Variant.SHADOW, bucket);
        int candidateBuckets = rolloutPercentage * 100;
        return new Allocation(bucket < candidateBuckets ? Variant.CANDIDATE : Variant.CONTROL,
                bucket);
    }

    private int bucket(UUID userId, String experimentKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(userId.toString().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            byte[] hash = digest.digest(experimentKey.getBytes(StandardCharsets.UTF_8));
            long unsigned = Integer.toUnsignedLong(ByteBuffer.wrap(hash).getInt());
            return (int) (unsigned % BUCKETS);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM 不支持 SHA-256", exception);
        }
    }

    public enum Variant { SHADOW, CONTROL, CANDIDATE }

    public record Allocation(Variant variant, int bucket) { }
}
