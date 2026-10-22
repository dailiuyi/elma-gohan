package com.elma.gohan.provider.evidence;

import com.elma.gohan.config.BaiduProperties;
import com.elma.gohan.domain.restaurant.Location;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Supplier;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.stereotype.Component;

/** Database-wide single outbound lane; cache lookup and cooldown work across JVMs. */
@Component
@Primary
public class CoordinatedBaiduProvider implements PlatformEvidenceProvider, org.springframework.boot.actuate.health.HealthIndicator {
    private static final Logger log = LoggerFactory.getLogger(CoordinatedBaiduProvider.class);
    private static final long LOCK = 741930101L;
    private final BaiduDatabase database;
    private final BaiduPlaceApiProvider delegate;
    private final ObjectMapper mapper;
    private final BaiduProperties properties;
    private final BaiduRequestDispatcher dispatcher;
    private final MeterRegistry meters;
    private record Block(String reason, long untilMillis) { }
    private volatile Block observedBlock = new Block("NONE", 0);

    public CoordinatedBaiduProvider(BaiduDatabase database, BaiduPlaceApiProvider delegate,
                                    ObjectMapper mapper, BaiduProperties properties, MeterRegistry meters) {
        this.database = database;
        this.delegate = delegate;
        this.mapper = mapper;
        this.properties = properties;
        this.meters = meters;
        this.dispatcher = new BaiduRequestDispatcher(properties.getDispatchQueueCapacity(), meters);
        for (String reason : List.of("CONFIGURATION_ERROR", "PERMANENT_QUOTA", "DAILY_QUOTA", "UPSTREAM_RATE_LIMIT",
                "NETWORK_ERROR", "UPSTREAM_UNAVAILABLE")) {
            io.micrometer.core.instrument.Gauge.builder("elma.baidu.blocked", this,
                    provider -> provider.blockedFor(reason) ? 1 : 0).tag("reason", reason).register(meters);
        }
    }

    public static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private PlatformSearchResult call(List<Object> query, Supplier<PlatformSearchResult> action) {
        if (!properties.isEnabled() || properties.getAk().isBlank())
            return PlatformSearchResult.unavailable("DISABLED");
        String key;
        try { key = hash(mapper.writeValueAsString(query) + properties.getBaseUrl()
                + properties.getPageSize() + hash(properties.getAk())); }
        catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize Baidu query", e);
        }
        PlatformSearchResult result = dispatcher.call(key, () -> callWithinDeadline(key, action));
        meters.counter("elma.baidu.result", "outcome",
                result.failureReason() == null ? result.status().name() : result.failureReason()).increment();
        return result;
    }

    private PlatformSearchResult callWithinDeadline(String key, Supplier<PlatformSearchResult> action) {
        long started = System.nanoTime();
        PlatformSearchResult completedResult = null;
        long lockDeadline = started + TimeUnit.MILLISECONDS.toNanos(BaiduCallContext.remainingMillis());
        try (Connection connection = database.open()) {
            meters.timer("elma.baidu.connection.wait").record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
            if (BaiduCallContext.remainingMillis() <= 0) return PlatformSearchResult.unavailable("LOCAL_QUEUE_TIMEOUT");
            connection.setNetworkTimeout(Runnable::run, (int) Math.min(Integer.MAX_VALUE,
                    BaiduCallContext.remainingMillis() + 250));
            // Session lock deliberately spans committed throttle state and the HTTP request.
            JdbcTemplate db = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            db.setQueryTimeout(Math.max(1, (int) ((BaiduCallContext.remainingMillis() + 999) / 1000)));
            boolean locked = false;
            try {
                do {
                    var cached = db.queryForList("SELECT result_json::text FROM baidu_query_cache "
                            + "WHERE query_hash=? AND expires_at>now()", String.class, key);
                    if (!cached.isEmpty()) return cached(cached.get(0));
                    locked = Boolean.TRUE.equals(db.queryForObject("SELECT pg_try_advisory_lock(?)",
                            Boolean.class, LOCK));
                    if (locked) break;
                    if (!pause(lockDeadline)) return PlatformSearchResult.unavailable("LOCAL_QUEUE_TIMEOUT");
                } while (BaiduCallContext.remainingMillis() > 0);
                if (!locked) return PlatformSearchResult.unavailable("LOCAL_QUEUE_TIMEOUT");
                // A prior caller may have completed while this connection was waiting.
                var cached = db.queryForList("SELECT result_json::text FROM baidu_query_cache "
                        + "WHERE query_hash=? AND expires_at>now()", String.class, key);
                if (!cached.isEmpty()) return cached(cached.get(0));
                var state = db.queryForMap("SELECT blocked_until>now() AS blocked, failure_reason, "
                        + "CASE WHEN blocked_until='infinity' THEN 9223372036854775807 "
                        + "ELSE (extract(epoch FROM blocked_until)*1000)::bigint END AS until_millis "
                        + "FROM baidu_call_state WHERE id=1");
                observedBlock = new Block(String.valueOf(state.get("failure_reason")), ((Number) state.get("until_millis")).longValue());
                if (Boolean.TRUE.equals(state.get("blocked")))
                    return PlatformSearchResult.unavailable(observedBlock.reason());
                long waitDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(properties.getRateLimitMaxWaitMs());
                while (Boolean.TRUE.equals(db.queryForObject("SELECT next_allowed_at>now() "
                        + "FROM baidu_call_state WHERE id=1", Boolean.class))) {
                    if (!pause(waitDeadline)) return PlatformSearchResult.unavailable("LOCAL_QUEUE_TIMEOUT");
                }
                if (BaiduCallContext.remainingMillis() <= 0)
                    return PlatformSearchResult.unavailable("LOCAL_QUEUE_TIMEOUT");
                long interval = Math.max(400, (1000L + Math.max(1, properties.getRateLimitPerSecond()) - 1)
                        / Math.max(1, properties.getRateLimitPerSecond()));
                db.update("UPDATE baidu_call_state SET next_allowed_at=clock_timestamp() "
                        + "+ (? * interval '1 millisecond') WHERE id=1", interval);
                long outboundStarted = System.nanoTime();
                PlatformSearchResult result = action.get();
                completedResult = result;
                if (result.status() == EvidenceStatus.UNAVAILABLE
                        && !"LOCAL_QUEUE_TIMEOUT".equals(result.failureReason())
                        && !"DISABLED".equals(result.failureReason())) BaiduCallContext.recordOutboundFailure();
                // 间隔从调用结束计算，冷连接/网络抖动不会压缩下一次实际出站间隔。
                db.update("UPDATE baidu_call_state SET next_allowed_at=clock_timestamp() "
                        + "+ (? * interval '1 millisecond') WHERE id=1", interval);
                meters.timer("elma.baidu.outbound.duration").record(System.nanoTime() - outboundStarted, TimeUnit.NANOSECONDS);
                meters.counter("elma.baidu.outbound", "outcome",
                        result.failureReason() == null ? result.status().name() : result.failureReason()).increment();
                if ("DAILY_QUOTA".equals(result.failureReason())) {
                    db.update("UPDATE baidu_call_state SET blocked_until="
                            + "(date_trunc('day', now() AT TIME ZONE 'Asia/Shanghai') + interval '1 day') "
                            + "AT TIME ZONE 'Asia/Shanghai', failure_reason='DAILY_QUOTA' WHERE id=1");
                } else if ("UPSTREAM_RATE_LIMIT".equals(result.failureReason())) {
                    db.update("UPDATE baidu_call_state SET blocked_until=clock_timestamp()+(? * interval '1 millisecond'), "
                            + "failure_reason='UPSTREAM_RATE_LIMIT' WHERE id=1",
                            30000 + ThreadLocalRandom.current().nextInt(1000));
                } else if ("CONFIGURATION_ERROR".equals(result.failureReason())) {
                    log.error("Baidu outbound blocked reason=CONFIGURATION_ERROR cooldownSeconds=900");
                    observedBlock = new Block("CONFIGURATION_ERROR", System.currentTimeMillis() + 900000);
                    db.update("UPDATE baidu_call_state SET blocked_until=clock_timestamp()+interval '15 minutes', "
                            + "failure_reason='CONFIGURATION_ERROR' WHERE id=1");
                } else if ("PERMANENT_QUOTA".equals(result.failureReason())) {
                    log.error("Baidu outbound blocked reason=PERMANENT_QUOTA requiresManualReset=true");
                    observedBlock = new Block("PERMANENT_QUOTA", Long.MAX_VALUE);
                    db.update("UPDATE baidu_call_state SET blocked_until='infinity', failure_reason=? WHERE id=1",
                            result.failureReason());
                } else if ("NETWORK_ERROR".equals(result.failureReason())
                        || "UPSTREAM_UNAVAILABLE".equals(result.failureReason())) {
                    db.update("UPDATE baidu_call_state SET blocked_until=clock_timestamp()+(? * interval '1 millisecond'), "
                            + "failure_reason=? WHERE id=1", 500 + ThreadLocalRandom.current().nextInt(500), result.failureReason());
                }
                if (result.status() != EvidenceStatus.UNAVAILABLE) {
                    db.update("INSERT INTO baidu_query_cache(query_hash,result_json,expires_at) "
                            + "VALUES (?,?::jsonb,now()+interval '5 minutes') ON CONFLICT(query_hash) "
                            + "DO UPDATE SET result_json=excluded.result_json,expires_at=excluded.expires_at",
                            key, mapper.writeValueAsString(result));
                } else log.info("Baidu outbound unavailable reason={}", result.failureReason());
                return result;
            } finally {
                if (locked) {
                    try { db.queryForObject("SELECT pg_advisory_unlock(?)", Boolean.class, LOCK); }
                    catch (RuntimeException e) {
                        connection.abort(Runnable::run);
                        throw e;
                    }
                }
            }
        } catch (java.sql.SQLException | org.springframework.dao.DataAccessException
                | com.fasterxml.jackson.core.JsonProcessingException e) {
            log.warn("Baidu coordination unavailable errorType={}", e.getClass().getSimpleName());
            meters.counter("elma.baidu.coordination.write.failure", "outboundCompleted",
                    Boolean.toString(completedResult != null)).increment();
            return completedResult != null ? completedResult : PlatformSearchResult.unavailable("COORDINATION_UNAVAILABLE");
        }
    }

    private PlatformSearchResult cached(String json) throws com.fasterxml.jackson.core.JsonProcessingException {
        meters.counter("elma.baidu.cache", "outcome", "hit").increment();
        return mapper.readValue(json, PlatformSearchResult.class);
    }

    private boolean blockedFor(String reason) {
        Block block = observedBlock;
        return block.reason().equals(reason) && block.untilMillis() > System.currentTimeMillis();
    }

    @Override
    public org.springframework.boot.actuate.health.Health health() {
        return dispatcher.healthy() ? org.springframework.boot.actuate.health.Health.up().build()
                : org.springframework.boot.actuate.health.Health.down().withDetail("reason", "DISPATCHER_FAILED").build();
    }

    @PreDestroy
    public void close() {
        dispatcher.close();
    }

    private boolean pause(long waitDeadline) {
        long remaining = Math.min(BaiduCallContext.remainingMillis(),
                TimeUnit.NANOSECONDS.toMillis(waitDeadline - System.nanoTime()));
        if (remaining <= 0 || Thread.currentThread().isInterrupted()) return false;
        long started = System.nanoTime();
        try { Thread.sleep(Math.min(40, remaining)); return true; }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
        finally { meters.timer("elma.baidu.permit.wait").record(System.nanoTime() - started, TimeUnit.NANOSECONDS); }
    }
    @Override public PlatformSearchResult searchV3(Location c, int r, int p) {
        return searchV3(c, r, p, properties.getQuery());
    }
    @Override public PlatformSearchResult searchV3(Location c, int r, int p, String q) {
        return call(List.of("v3", c, r, p, q), () -> delegate.searchV3(c, r, p, q));
    }
    @Override public PlatformSearchResult searchNearby(Location c, int r, String q) {
        return call(List.of("nearby", c, r, q), () -> delegate.searchNearby(c, r, q));
    }
    @Override public PlatformSearchResult searchRegion(String q, String r) {
        return call(List.of("region", q, r), () -> delegate.searchRegion(q, r));
    }
    @Override public PlatformSearchResult searchSuggestion(Location c, String q, String r) {
        return call(java.util.Arrays.asList("suggest", c, q, r), () -> delegate.searchSuggestion(c, q, r));
    }
    @Override public PlatformSearchResult searchV2(Location c, int r) {
        return call(List.of("v2", c, r), () -> delegate.searchV2(c, r));
    }
}
