package com.elma.gohan.application;

import com.elma.gohan.config.BaiduProperties;
import com.elma.gohan.domain.restaurant.Location;
import com.elma.gohan.domain.restaurant.Restaurant;
import com.elma.gohan.provider.evidence.CoordinatedBaiduProvider;
import com.elma.gohan.provider.evidence.BaiduDatabase;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.util.List;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import io.micrometer.core.instrument.MeterRegistry;

/** Durable, bounded-retry tasks; single consumer across application instances. */
@Component
@EnableScheduling
public class BaiduEnrichmentQueue {
    private static final Logger log = LoggerFactory.getLogger(BaiduEnrichmentQueue.class);
    private static final long LOCK = 741930102L;
    private static final long ENQUEUE_LOCK = 741930103L;
    public enum EnqueueResult { ENQUEUED, DUPLICATE, FULL, DISABLED }
    private final BaiduDatabase database;
    private final JdbcTemplate db;
    private final TransactionTemplate enqueueTransaction;
    private final MeterRegistry meters;
    private final ObjectMapper mapper;
    private final ObjectProvider<EvidenceAggregator> aggregator;
    private final BaiduProperties properties;
    private final com.elma.gohan.config.EntityResolutionProperties resolutionProperties;
    public BaiduEnrichmentQueue(DataSource ds, BaiduDatabase database, ObjectMapper mapper,
            ObjectProvider<EvidenceAggregator> aggregator, BaiduProperties properties,
            com.elma.gohan.config.EntityResolutionProperties resolutionProperties, MeterRegistry meters) {
        this.database = database; this.db = new JdbcTemplate(ds); this.mapper = mapper;
        this.aggregator = aggregator; this.properties = properties;
        this.resolutionProperties = resolutionProperties;
        this.meters = meters;
        this.enqueueTransaction = new TransactionTemplate(new DataSourceTransactionManager(ds));
        this.enqueueTransaction.setTimeout(1);
    }
    public EnqueueResult enqueue(List<Restaurant> restaurants, Location center, int radius) {
        if (!properties.isAsyncEnrichmentEnabled() || !properties.isEnabled() || properties.getAk().isBlank()) return EnqueueResult.DISABLED;
        try {
            var sorted = restaurants.stream().sorted(java.util.Comparator.comparing(Restaurant::sourcePoiId)).toList();
            String payload = mapper.writeValueAsString(new Task(sorted, center, radius));
            String key = CoordinatedBaiduProvider.hash(sorted.stream().map(Restaurant::sourcePoiId).toList()
                    + "|" + center + "|" + radius);
            EnqueueResult result = enqueueTransaction.execute(status -> {
                db.queryForObject("SELECT pg_advisory_xact_lock(?)", Object.class, ENQUEUE_LOCK);
                if (Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM baidu_enrichment_task WHERE task_key=?)",
                        Boolean.class, key))) return EnqueueResult.DUPLICATE;
                if (db.queryForObject("SELECT count(*) FROM baidu_enrichment_task", Integer.class) >= 1000)
                    return EnqueueResult.FULL;
                db.update("INSERT INTO baidu_enrichment_task(task_key,payload) VALUES (?,?::jsonb)", key, payload);
                return EnqueueResult.ENQUEUED;
            });
            meters.counter("elma.baidu.enrichment.enqueue", "outcome", result.name()).increment();
            return result;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize Baidu enrichment", e);
        }
    }

    @Scheduled(fixedDelayString = "${elma.baidu.worker-delay-ms:1000}", initialDelay = 10000)
    public void consume() {
        if (!properties.isAsyncEnrichmentEnabled() || !properties.isEnabled() || properties.getAk().isBlank()) return;
        try (Connection connection = database.open()) {
            connection.setNetworkTimeout(Runnable::run, 1250);
            JdbcTemplate lane = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            lane.setQueryTimeout(1);
            if (!Boolean.TRUE.equals(lane.queryForObject("SELECT pg_try_advisory_lock(?)", Boolean.class, LOCK))) return;
            try {
                lane.update("DELETE FROM baidu_query_cache WHERE expires_at<now()");
                int expired = lane.update("DELETE FROM baidu_enrichment_task WHERE expires_at<now()");
                int exhausted = lane.update("DELETE FROM baidu_enrichment_task WHERE attempts>=20");
                meters.counter("elma.baidu.enrichment.terminal", "outcome", "expired").increment(expired);
                meters.counter("elma.baidu.enrichment.terminal", "outcome", "exhausted").increment(exhausted);
                if (Boolean.TRUE.equals(lane.queryForObject("SELECT blocked_until>now() FROM baidu_call_state WHERE id=1", Boolean.class))) return;
                var tasks = lane.queryForList("SELECT task_key,payload::text AS payload, "
                        + "extract(epoch FROM clock_timestamp()-created_at) AS age_seconds FROM baidu_enrichment_task "
                        + "WHERE available_at<=now() ORDER BY available_at,created_at LIMIT 1");
                if (tasks.isEmpty()) return;
                meters.summary("elma.baidu.enrichment.age.seconds")
                        .record(((Number) tasks.get(0).get("age_seconds")).doubleValue());
                String key = (String) tasks.get(0).get("task_key");
                // Lease is committed before processing. A crash makes the task available again.
                lane.update("UPDATE baidu_enrichment_task SET available_at=now()+interval '2 minutes' WHERE task_key=?", key);
                try (var attempt = new com.elma.gohan.provider.evidence.BaiduCallContext(120000, true)) {
                    try {
                        Task task = mapper.readValue((String) tasks.get(0).get("payload"), Task.class);
                        List<Restaurant> remaining = task.restaurants().stream()
                                .filter(r -> !complete(lane, r)).toList();
                        if (!remaining.isEmpty()) aggregator.getObject().enrichInBackground(remaining, task.center(), task.radius());
                        boolean complete = task.restaurants().stream().allMatch(r -> complete(lane, r));
                        if (complete) lane.update("DELETE FROM baidu_enrichment_task WHERE task_key=?", key);
                        else lane.update("UPDATE baidu_enrichment_task SET available_at=now()+interval '10 seconds' WHERE task_key=?", key);
                        meters.counter("elma.baidu.enrichment", "outcome", complete ? "completed" : "remaining").increment();
                        log.info("Baidu enrichment complete={} restaurants={}", complete, task.restaurants().size());
                    } catch (Exception e) {
                        meters.counter("elma.baidu.enrichment", "outcome", "retry").increment();
                        log.warn("Baidu enrichment retry errorType={}", e.getClass().getSimpleName());
                    } finally {
                        if (attempt.hasOutboundFailure()) lane.update(
                                "UPDATE baidu_enrichment_task SET attempts=attempts+1 WHERE task_key=?", key);
                    }
                }
            } finally {
                try { lane.queryForObject("SELECT pg_advisory_unlock(?)", Boolean.class, LOCK); }
                catch (RuntimeException e) { connection.abort(Runnable::run); throw e; }
            }
        } catch (Exception e) { log.warn("Baidu worker unavailable errorType={}", e.getClass().getSimpleName()); }
    }
    private boolean complete(JdbcTemplate lane, Restaurant restaurant) {
        return Boolean.TRUE.equals(lane.queryForObject("SELECT EXISTS(SELECT 1 FROM external_entity_mapping "
                + "WHERE primary_source='AMAP' AND evidence_source='BAIDU' "
                + "AND primary_poi_id=? AND expires_at>(now() AT TIME ZONE 'UTC') AND match_algorithm_version=? "
                + "AND (match_status='MATCHED' OR COALESCE((match_features_json->>'reasonBudgetOrAvailability')::numeric,0)=0) "
                + "AND (match_status<>'MATCHED' OR evidence_observed_at>(now() AT TIME ZONE 'UTC')-(? * interval '1 hour'))) ",
                Boolean.class, restaurant.sourcePoiId(), resolutionProperties.getAlgorithmVersion(),
                resolutionProperties.getEvidenceTtlHours()));
    }
    public record Task(List<Restaurant> restaurants, Location center, int radius) { }
}
