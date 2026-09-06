package com.elma.gohan.provider.evidence;

import com.elma.gohan.config.BaiduProperties;
import com.elma.gohan.domain.restaurant.Location;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.assertThat;

/** Real PostgreSQL, isolated disposable schema, local HTTP only. No map credentials needed. */
class BaiduCoordinationIntegrationTest {
    static DriverManagerDataSource ds;
    static JdbcTemplate db;
    static String schema;
    HttpServer server;
    ExecutorService httpExecutor;
    AtomicInteger calls = new AtomicInteger(), status = new AtomicInteger(), active = new AtomicInteger(), peak = new AtomicInteger();
    List<Long> starts = new CopyOnWriteArrayList<>();
    BaiduProperties properties;

    @BeforeAll static void database() {
        String database = System.getenv().getOrDefault("DB_TEST_NAME", "elma_test");
        if (!database.endsWith("_test")) throw new IllegalStateException("Integration database must end in _test");
        schema = "baidu_it_" + UUID.randomUUID().toString().replace("-", "");
        ds = new DriverManagerDataSource("jdbc:postgresql://" + System.getenv().getOrDefault("DB_HOST", "localhost")
                + ":" + System.getenv().getOrDefault("DB_PORT", "5432") + "/" + database + "?currentSchema=" + schema
                + "&options=-c%20TimeZone=Asia%2FShanghai",
                System.getenv().getOrDefault("DB_USERNAME", "postgres"), System.getenv().getOrDefault("DB_PASSWORD", ""));
        db = new JdbcTemplate(ds);
        db.execute("CREATE SCHEMA " + schema);
        Flyway.configure().dataSource(ds).defaultSchema(schema).schemas(schema).load().migrate();
    }
    @AfterAll static void cleanup() {
        if (db != null && schema != null && schema.matches("baidu_it_[a-f0-9]{32}")) db.execute("DROP SCHEMA " + schema + " CASCADE");
    }
    @BeforeEach void start() throws Exception {
        db.update("DELETE FROM baidu_query_cache");
        db.update("DELETE FROM baidu_enrichment_task");
        db.update("UPDATE baidu_call_state SET next_allowed_at=now(),blocked_until=now(),failure_reason=null");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpExecutor = Executors.newCachedThreadPool(); server.setExecutor(httpExecutor);
        server.createContext("/", exchange -> {
            starts.add(System.nanoTime()); calls.incrementAndGet();
            peak.accumulateAndGet(active.incrementAndGet(), Math::max);
            try { Thread.sleep(80); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            byte[] body = ("{\"status\":" + status.get() + ",\"results\":[],\"total\":0}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body);
            active.decrementAndGet(); exchange.close();
        }); server.start();
        properties = new BaiduProperties(); properties.setAk("test-only-key");
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
    }
    @AfterEach void stop() { server.stop(0); httpExecutor.shutdownNow(); }
    CoordinatedBaiduProvider provider() {
        return new CoordinatedBaiduProvider(ds, new BaiduPlaceApiProvider(properties, new BaiduPlaceRateLimiter(properties)),
                new ObjectMapper().findAndRegisterModules(), properties);
    }
    PlatformSearchResult query(CoordinatedBaiduProvider provider, String query) {
        return provider.searchNearby(new Location(28.2291, 112.9412), 500, query);
    }
    @Test void separateInstancesShareOneLaneAndQueryCache() throws Exception {
        var first = provider(); var second = provider(); var executor = Executors.newFixedThreadPool(6);
        try {
            List<Future<PlatformSearchResult>> pending = new ArrayList<>();
            for (int i = 0; i < 6; i++) { final int n = i;
                pending.add(executor.submit(() -> query(n % 2 == 0 ? first : second, "same-store"))); }
            for (var future : pending) assertThat(future.get(10, TimeUnit.SECONDS).status()).isEqualTo(EvidenceStatus.NO_DATA);
            assertThat(calls).hasValue(1);
            pending.clear(); starts.clear();
            for (int i = 0; i < 5; i++) { final int n = i;
                pending.add(executor.submit(() -> query(n % 2 == 0 ? first : second, "different-" + n))); }
            for (var future : pending) assertThat(future.get(10, TimeUnit.SECONDS).status()).isEqualTo(EvidenceStatus.NO_DATA);
            assertThat(peak).hasValue(1);
            for (int i = 3; i < starts.size(); i++) assertThat(starts.get(i) - starts.get(i - 3)).isGreaterThanOrEqualTo(1_000_000_000L);
        } finally { executor.shutdownNow(); }
    }
    @Test void cooldownAndDailyQuotaSurviveProviderRecreation() {
        status.set(401);
        assertThat(query(provider(), "a").failureReason()).isEqualTo("UPSTREAM_RATE_LIMIT");
        assertThat(query(provider(), "b").failureReason()).isEqualTo("UPSTREAM_RATE_LIMIT");
        assertThat(calls).hasValue(1);
        db.update("UPDATE baidu_call_state SET blocked_until=now()"); status.set(302);
        assertThat(query(provider(), "c").failureReason()).isEqualTo("DAILY_QUOTA");
        assertThat(query(provider(), "d").failureReason()).isEqualTo("DAILY_QUOTA");
        assertThat(calls).hasValue(2);
    }
    @Test void durableQueueDeduplicatesAndRecoversAfterLeaseExpiration() {
        var aggregator = org.mockito.Mockito.mock(com.elma.gohan.application.EvidenceAggregator.class);
        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<com.elma.gohan.application.EvidenceAggregator> factory =
                org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
        org.mockito.Mockito.when(factory.getObject()).thenReturn(aggregator);
        org.mockito.Mockito.when(aggregator.enrichInBackground(org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenThrow(new IllegalStateException("simulated worker failure")).thenReturn(java.util.Map.of());
        var mapper = new ObjectMapper().findAndRegisterModules();
        var queue = new com.elma.gohan.application.BaiduEnrichmentQueue(ds, mapper, factory, properties,
                new com.elma.gohan.config.EntityResolutionProperties());
        var restaurants = List.of(com.elma.gohan.TestRestaurants.full("queue-a", 4.5, 20));
        var center = new Location(28.2291, 112.9412);
        queue.enqueue(restaurants, center, 500); queue.enqueue(restaurants, center, 500);
        assertThat(db.queryForObject("SELECT count(*) FROM baidu_enrichment_task", Integer.class)).isEqualTo(1);
        queue.consume();
        var restarted = new com.elma.gohan.application.BaiduEnrichmentQueue(ds, mapper, factory, properties,
                new com.elma.gohan.config.EntityResolutionProperties());
        restarted.consume();
        assertThat(db.queryForObject("SELECT attempts FROM baidu_enrichment_task", Integer.class)).isEqualTo(1);
        db.update("UPDATE baidu_enrichment_task SET available_at=now()-interval '1 second'");
        restarted.consume();
        assertThat(db.queryForObject("SELECT attempts FROM baidu_enrichment_task", Integer.class)).isEqualTo(2);
        org.mockito.Mockito.verify(aggregator, org.mockito.Mockito.times(2)).enrichInBackground(
                org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt());
        db.update("INSERT INTO external_entity_mapping(id,primary_source,primary_poi_id,evidence_source,match_status,expires_at,created_at,updated_at) "
                + "VALUES (?,'AMAP',?,'BAIDU','NO_MATCH',(now() AT TIME ZONE 'UTC')+interval '10 minutes',now(),now())",
                java.util.UUID.randomUUID(), restaurants.get(0).sourcePoiId());
        db.update("UPDATE baidu_enrichment_task SET available_at=now()-interval '1 second'");
        restarted.consume();
        assertThat(db.queryForObject("SELECT count(*) FROM baidu_enrichment_task", Integer.class)).isEqualTo(1);
        db.update("UPDATE external_entity_mapping SET match_algorithm_version='entity-v0.4' WHERE primary_poi_id=?",
                restaurants.get(0).sourcePoiId());
        db.update("UPDATE baidu_enrichment_task SET available_at=now()-interval '1 second'");
        restarted.consume();
        assertThat(db.queryForObject("SELECT count(*) FROM baidu_enrichment_task", Integer.class)).isZero();
    }

    @Test void occupiedOutboundLaneRespectsForegroundDeadline() throws Exception {
        try (var connection = ds.getConnection()) {
            var holder = new JdbcTemplate(new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection, true));
            holder.queryForObject("SELECT pg_advisory_lock(741930101)", Object.class);
            try {
                long started = System.nanoTime();
                try (var ignored = new BaiduCallContext(150)) {
                    assertThat(query(provider(), "busy").failureReason()).isEqualTo("LOCAL_QUEUE_TIMEOUT");
                }
                assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(700);
                assertThat(calls).hasValue(0);
            } finally { holder.queryForObject("SELECT pg_advisory_unlock(741930101)", Boolean.class); }
        }
    }

    @Test void expiredBudgetDoesNotIssueHttpOrCreateNegativeCache() {
        try (var ignored = new BaiduCallContext(1)) {
            try { Thread.sleep(5); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            assertThat(query(provider(), "deadline").failureReason()).isEqualTo("LOCAL_QUEUE_TIMEOUT");
        }
        assertThat(calls).hasValue(0);
        assertThat(db.queryForObject("SELECT count(*) FROM baidu_query_cache", Integer.class)).isZero();
    }
}
