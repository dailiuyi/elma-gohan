package com.elma.gohan.provider.evidence;

import com.elma.gohan.config.BaiduProperties;
import com.elma.gohan.domain.restaurant.Location;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.stereotype.Component;

/** Database-wide single outbound lane; cache lookup and cooldown work across JVMs. */
@Component
@Primary
public class CoordinatedBaiduProvider implements PlatformEvidenceProvider {
    private static final Logger log = LoggerFactory.getLogger(CoordinatedBaiduProvider.class);
    private static final long LOCK = 741930101L;
    private final DataSource dataSource;
    private final BaiduPlaceApiProvider delegate;
    private final ObjectMapper mapper;
    private final BaiduProperties properties;

    public CoordinatedBaiduProvider(DataSource dataSource, BaiduPlaceApiProvider delegate,
                                    ObjectMapper mapper, BaiduProperties properties) {
        this.dataSource = dataSource;
        this.delegate = delegate;
        this.mapper = mapper;
        this.properties = properties;
    }

    public static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private PlatformSearchResult call(List<Object> query, Supplier<PlatformSearchResult> action) {
        try (var ignored = new BaiduCallContext(8000)) { return callWithinDeadline(query, action); }
    }

    private PlatformSearchResult callWithinDeadline(List<Object> query, Supplier<PlatformSearchResult> action) {
        if (!properties.isEnabled() || properties.getAk().isBlank())
            return PlatformSearchResult.unavailable("DISABLED");
        String key;
        try { key = hash(mapper.writeValueAsString(query) + properties.getBaseUrl()
                + properties.getPageSize() + hash(properties.getAk())); }
        catch (Exception e) { return PlatformSearchResult.unavailable("SERIALIZATION"); }
        try (Connection connection = dataSource.getConnection()) {
            // Session lock deliberately spans committed throttle state and the HTTP request.
            JdbcTemplate db = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            boolean locked = false;
            try {
                do {
                    var cached = db.queryForList("SELECT result_json::text FROM baidu_query_cache "
                            + "WHERE query_hash=? AND expires_at>now()", String.class, key);
                    if (!cached.isEmpty()) return mapper.readValue(cached.get(0), PlatformSearchResult.class);
                    locked = Boolean.TRUE.equals(db.queryForObject("SELECT pg_try_advisory_lock(?)",
                            Boolean.class, LOCK));
                    if (locked) break;
                    if (!pause()) return PlatformSearchResult.unavailable("LOCAL_QUEUE_TIMEOUT");
                } while (BaiduCallContext.remainingMillis() > 0);
                if (!locked) return PlatformSearchResult.unavailable("LOCAL_QUEUE_TIMEOUT");
                // A prior caller may have completed while this connection was waiting.
                var cached = db.queryForList("SELECT result_json::text FROM baidu_query_cache "
                        + "WHERE query_hash=? AND expires_at>now()", String.class, key);
                if (!cached.isEmpty()) return mapper.readValue(cached.get(0), PlatformSearchResult.class);
                var state = db.queryForMap("SELECT blocked_until>now() AS blocked, failure_reason "
                        + "FROM baidu_call_state WHERE id=1");
                if (Boolean.TRUE.equals(state.get("blocked")))
                    return PlatformSearchResult.unavailable(String.valueOf(state.get("failure_reason")));
                while (Boolean.TRUE.equals(db.queryForObject("SELECT next_allowed_at>now() "
                        + "FROM baidu_call_state WHERE id=1", Boolean.class))) {
                    if (!pause()) return PlatformSearchResult.unavailable("LOCAL_QUEUE_TIMEOUT");
                }
                if (BaiduCallContext.remainingMillis() <= 0)
                    return PlatformSearchResult.unavailable("LOCAL_QUEUE_TIMEOUT");
                long interval = Math.max(400, (1000L + Math.max(1, properties.getRateLimitPerSecond()) - 1)
                        / Math.max(1, properties.getRateLimitPerSecond()));
                db.update("UPDATE baidu_call_state SET next_allowed_at=clock_timestamp() "
                        + "+ (? * interval '1 millisecond') WHERE id=1", interval);
                PlatformSearchResult result = action.get();
                if ("DAILY_QUOTA".equals(result.failureReason())) {
                    db.update("UPDATE baidu_call_state SET blocked_until="
                            + "(date_trunc('day', now() AT TIME ZONE 'Asia/Shanghai') + interval '1 day') "
                            + "AT TIME ZONE 'Asia/Shanghai', failure_reason='DAILY_QUOTA' WHERE id=1");
                } else if ("UPSTREAM_RATE_LIMIT".equals(result.failureReason())) {
                    db.update("UPDATE baidu_call_state SET blocked_until=now()+interval '30 seconds', "
                            + "failure_reason='UPSTREAM_RATE_LIMIT' WHERE id=1");
                }
                if (result.status() != EvidenceStatus.UNAVAILABLE) {
                    db.update("INSERT INTO baidu_query_cache(query_hash,result_json,expires_at) "
                            + "VALUES (?,?::jsonb,now()+interval '5 minutes') ON CONFLICT(query_hash) "
                            + "DO UPDATE SET result_json=excluded.result_json,expires_at=excluded.expires_at",
                            key, mapper.writeValueAsString(result));
                } else log.info("Baidu outbound unavailable reason={}", result.failureReason());
                return result;
            } finally {
                if (locked) db.queryForObject("SELECT pg_advisory_unlock(?)", Boolean.class, LOCK);
            }
        } catch (Exception e) {
            log.warn("Baidu coordination unavailable errorType={}", e.getClass().getSimpleName());
            return PlatformSearchResult.unavailable("COORDINATION_UNAVAILABLE");
        }
    }

    private boolean pause() {
        long remaining = BaiduCallContext.remainingMillis();
        if (remaining <= 0 || Thread.currentThread().isInterrupted()) return false;
        try { Thread.sleep(Math.min(40, remaining)); return true; }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
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
