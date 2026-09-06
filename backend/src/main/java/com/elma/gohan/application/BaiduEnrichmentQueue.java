package com.elma.gohan.application;

import com.elma.gohan.config.BaiduProperties;
import com.elma.gohan.domain.restaurant.Location;
import com.elma.gohan.domain.restaurant.Restaurant;
import com.elma.gohan.provider.evidence.CoordinatedBaiduProvider;
import com.elma.gohan.provider.evidence.EntityMatchStatus;
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

/** Durable, bounded-retry tasks; single consumer across application instances. */
@Component
@EnableScheduling
public class BaiduEnrichmentQueue {
    private static final Logger log = LoggerFactory.getLogger(BaiduEnrichmentQueue.class);
    private static final long LOCK = 741930102L;
    private final DataSource dataSource;
    private final JdbcTemplate db;
    private final ObjectMapper mapper;
    private final ObjectProvider<EvidenceAggregator> aggregator;
    private final BaiduProperties properties;
    private final com.elma.gohan.config.EntityResolutionProperties resolutionProperties;
    public BaiduEnrichmentQueue(DataSource ds, ObjectMapper mapper,
            ObjectProvider<EvidenceAggregator> aggregator, BaiduProperties properties,
            com.elma.gohan.config.EntityResolutionProperties resolutionProperties) {
        this.dataSource = ds; this.db = new JdbcTemplate(ds); this.mapper = mapper;
        this.aggregator = aggregator; this.properties = properties;
        this.resolutionProperties = resolutionProperties;
    }
    public void enqueue(List<Restaurant> restaurants, Location center, int radius) {
        if (!properties.isAsyncEnrichmentEnabled() || !properties.isEnabled() || properties.getAk().isBlank()) return;
        try {
            var sorted = restaurants.stream().sorted(java.util.Comparator.comparing(Restaurant::sourcePoiId)).toList();
            String payload = mapper.writeValueAsString(new Task(sorted, center, radius));
            String key = CoordinatedBaiduProvider.hash(sorted.stream().map(Restaurant::sourcePoiId).toList()
                    + "|" + center + "|" + radius);
            db.update("INSERT INTO baidu_enrichment_task(task_key,payload) SELECT ?,?::jsonb "
                    + "WHERE (SELECT count(*) FROM baidu_enrichment_task)<1000 ON CONFLICT(task_key) DO NOTHING",
                    key, payload);
        } catch (Exception e) { throw new IllegalStateException("Cannot enqueue Baidu enrichment", e); }
    }

    @Scheduled(fixedDelayString = "${elma.baidu.worker-delay-ms:1000}", initialDelay = 10000)
    public void consume() {
        if (!properties.isAsyncEnrichmentEnabled() || !properties.isEnabled() || properties.getAk().isBlank()) return;
        try (Connection connection = dataSource.getConnection()) {
            JdbcTemplate lane = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            if (!Boolean.TRUE.equals(lane.queryForObject("SELECT pg_try_advisory_lock(?)", Boolean.class, LOCK))) return;
            try {
                lane.update("DELETE FROM baidu_query_cache WHERE expires_at<now()");
                lane.update("DELETE FROM baidu_enrichment_task WHERE expires_at<now() OR attempts>=20");
                if (Boolean.TRUE.equals(lane.queryForObject("SELECT blocked_until>now() FROM baidu_call_state WHERE id=1", Boolean.class))) return;
                var tasks = lane.queryForList("SELECT task_key,payload::text AS payload FROM baidu_enrichment_task "
                        + "WHERE available_at<=now() ORDER BY available_at,created_at LIMIT 1");
                if (tasks.isEmpty()) return;
                String key = (String) tasks.get(0).get("task_key");
                // Lease is committed before processing. A crash makes the task available again.
                lane.update("UPDATE baidu_enrichment_task SET attempts=attempts+1,available_at=now()+interval '2 minutes' WHERE task_key=?", key);
                try {
                    Task task = mapper.readValue((String) tasks.get(0).get("payload"), Task.class);
                    var bundles = aggregator.getObject().enrichInBackground(task.restaurants(), task.center(), task.radius());
                    boolean complete = task.restaurants().stream().allMatch(r -> Boolean.TRUE.equals(
                            lane.queryForObject("SELECT EXISTS(SELECT 1 FROM external_entity_mapping "
                                    + "WHERE primary_source='AMAP' AND evidence_source='BAIDU' "
                                    + "AND primary_poi_id=? AND expires_at>(now() AT TIME ZONE 'UTC') AND match_algorithm_version=? "
                                    + "AND (match_status<>'MATCHED' OR evidence_observed_at>(now() AT TIME ZONE 'UTC')-(? * interval '1 hour'))) ",
                                    Boolean.class, r.sourcePoiId(), resolutionProperties.getAlgorithmVersion(),
                                    resolutionProperties.getEvidenceTtlHours())));
                    if (complete) lane.update("DELETE FROM baidu_enrichment_task WHERE task_key=?", key);
                    else lane.update("UPDATE baidu_enrichment_task SET available_at=now()+interval '10 seconds' WHERE task_key=?", key);
                    log.info("Baidu enrichment complete={} restaurants={}", complete, task.restaurants().size());
                } catch (Exception e) { log.warn("Baidu enrichment retry errorType={}", e.getClass().getSimpleName()); }
            } finally { lane.queryForObject("SELECT pg_advisory_unlock(?)", Boolean.class, LOCK); }
        } catch (Exception e) { log.warn("Baidu worker unavailable errorType={}", e.getClass().getSimpleName()); }
    }
    public record Task(List<Restaurant> restaurants, Location center, int radius) { }
}
