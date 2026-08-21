package com.elma.gohan;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Import;
import com.elma.gohan.provider.evidence.EvidenceProvider;
import com.elma.gohan.provider.evidence.EvidenceStatus;
import com.elma.gohan.provider.evidence.RestaurantEvidence;
import com.elma.gohan.provider.deep.DeepEvidenceBatch;
import com.elma.gohan.provider.deep.DeepEvidenceProvider;
import com.elma.gohan.provider.deep.DeepEvidenceSource;
import com.elma.gohan.provider.deep.WebEvidenceItem;

/**
 * 接口集成测试:连本机 elma_test 库,用本地 HTTP stub 替代高德(不依赖真实 Key)。
 * 验证状态码与响应体形状与 contracts/openapi.yaml 一致。
 * stub 在静态块中启动,确保早于 Spring 上下文读取 elma.amap.base-url。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(RecommendationApiTest.ThrowingEvidenceConfiguration.class)
class RecommendationApiTest {

    private static final String USER = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER_USER = "22222222-2222-2222-2222-222222222222";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbc;

    private static final HttpServer amapStub;
    private static final AtomicReference<String> amapResponse = new AtomicReference<>("{}");
    private static final AtomicBoolean evidenceFailure = new AtomicBoolean();
    private static final AtomicInteger deepEvidenceCalls = new AtomicInteger();
    private static final AtomicReference<DeepEvidenceSource> deepEvidenceFailure =
            new AtomicReference<>();

    @TestConfiguration
    static class ThrowingEvidenceConfiguration {
        @Bean
        @Primary
        EvidenceProvider controllableEvidenceProvider() {
            return restaurant -> {
                if (evidenceFailure.get()) throw new IllegalStateException("test evidence failure");
                return RestaurantEvidence.empty();
            };
        }

        @Bean
        @Primary
        DeepEvidenceProvider controllableDeepEvidenceProvider() {
            return (source, restaurant) -> {
                deepEvidenceCalls.incrementAndGet();
                if (source == deepEvidenceFailure.get()) {
                    return DeepEvidenceBatch.unavailable(source,
                            Instant.parse("2026-08-20T08:00:00Z"));
                }
                String url = switch (source) {
                    case BILIBILI -> "https://www.bilibili.com/video/BV1test";
                    case XIAOHONGSHU -> "https://www.xiaohongshu.com/explore/test";
                    case DIANPING -> "https://www.dianping.com/shop/test";
                };
                Instant now = Instant.parse("2026-08-20T08:00:00Z");
                WebEvidenceItem item = new WebEvidenceItem(source,
                        restaurant.name() + "值得推荐", url,
                        "分量足，性价比不错，高峰期需要排队", now.minusSeconds(3600),
                        now, 0.96, List.of());
                return new DeepEvidenceBatch(source, EvidenceStatus.AVAILABLE,
                        List.of(item), now);
            };
        }
    }

    static {
        try {
            amapStub = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            amapStub.createContext("/v3/place/around", exchange -> {
                byte[] body = amapResponse.get().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            amapStub.start();
            amapResponse.set(eightPois());
        } catch (IOException e) {
            throw new IllegalStateException("无法启动高德 stub", e);
        }
    }

    @DynamicPropertySource
    static void amapBaseUrl(DynamicPropertyRegistry registry) {
        registry.add("elma.amap.base-url",
                () -> "http://localhost:" + amapStub.getAddress().getPort());
    }

    @AfterAll
    static void stopStub() {
        amapStub.stop(0);
    }

    @AfterEach
    void cleanDb() {
        evidenceFailure.set(false);
        deepEvidenceCalls.set(0);
        deepEvidenceFailure.set(null);
        jdbc.update("DELETE FROM restaurant_flavor_observation");
        jdbc.update("DELETE FROM user_food_history");
        jdbc.update("DELETE FROM user_behavior");
        jdbc.update("DELETE FROM user_taste_profile");
        jdbc.update("DELETE FROM user_preference");
        jdbc.update("DELETE FROM user_feedback");
        jdbc.update("DELETE FROM recommendation_candidate");
        jdbc.update("DELETE FROM recommendation_log");
        jdbc.update("DELETE FROM risk_result");
        jdbc.update("DELETE FROM restaurant_deep_analysis");
        jdbc.update("DELETE FROM restaurant_deep_evidence");
        jdbc.update("DELETE FROM external_entity_mapping");
        jdbc.update("DELETE FROM restaurant");
    }

    @Test
    void createReturns201WithContractShape() throws Exception {
        ResponseEntity<String> response = create(USER, """
                {"latitude": 28.2282, "longitude": 112.9388,
                 "minDistance": 500, "radius": 1000,
                 "minBudget": 20, "maxBudget": 40,
                 "category": "ANY", "dislikes": []}
                """);
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        JsonNode body = JSON.readTree(response.getBody());
        assertThat(body.get("recommendationId")).isNotNull();
        JsonNode restaurant = body.get("restaurant");
        assertThat(restaurant.get("id")).isNotNull();
        assertThat(restaurant.get("name").asText()).isNotBlank();
        assertThat(restaurant.get("category").get("code")).isNotNull();
        assertThat(restaurant.get("category").get("label")).isNotNull();
        assertThat(restaurant.get("distanceMeters").asInt()).isBetween(501, 1000);
        assertThat(restaurant.get("averagePrice").asInt()).isBetween(21, 40);
        assertThat(restaurant.get("walkingMinutes").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(restaurant.get("businessStatus").asText()).isIn("OPEN", "CLOSED", "UNKNOWN");
        JsonNode risk = body.get("risk");
        assertThat(risk.get("riskScore").asInt()).isBetween(0, 100);
        assertThat(risk.get("riskLevel").asText()).isIn("LOW", "MEDIUM_LOW", "MEDIUM", "HIGH");
        assertThat(risk.get("confidence").asDouble()).isBetween(0.0, 1.0);
        assertThat(risk.get("reasons").size()).isGreaterThanOrEqualTo(1);
        assertThat(risk.get("algorithmVersion").asText()).isEqualTo("risk-v0.3");
        JsonNode evidenceSummary = body.get("evidenceSummary");
        assertThat(evidenceSummary).isNotNull();
        assertThat(evidenceSummary.get("matchStatus").asText()).isEqualTo("UNAVAILABLE");
        assertThat(evidenceSummary.get("amap").get("status").asText()).isEqualTo("AVAILABLE");
        assertThat(evidenceSummary.get("baidu").get("status").asText()).isEqualTo("UNAVAILABLE");
        assertThat(body.get("reasons").size()).isBetween(1, 5);
        assertThat(body.get("alternativesRemaining").asInt()).isBetween(0, 5);
        JsonNode personalization = body.get("personalization");
        assertThat(personalization.get("tasteMatchScore").asDouble()).isBetween(0.0, 100.0);
        assertThat(personalization.get("confidence").asDouble()).isBetween(0.0, 1.0);
        assertThat(personalization.get("algorithmVersion").asText()).isEqualTo("taste-v0.1");
        // 落库校验:推荐日志含条件快照与双算法版本
        Integer logs = jdbc.queryForObject(
                "SELECT count(*) FROM recommendation_log WHERE request_condition_json IS NOT NULL "
                        + "AND recommended_restaurant_id = current_restaurant_id "
                        + "AND risk_algorithm_version = 'risk-v0.3' "
                        + "AND recommendation_algorithm_version = 'recommendation-v0.4' "
                        + "AND taste_algorithm_version = 'taste-v0.1'", Integer.class);
        assertThat(logs).isEqualTo(1);
        String requestSnapshot = jdbc.queryForObject(
                "SELECT request_condition_json::text FROM recommendation_log WHERE id = ?::uuid",
                String.class, body.get("recommendationId").asText());
        JsonNode snapshot = JSON.readTree(requestSnapshot);
        assertThat(snapshot.get("minDistance").asInt()).isEqualTo(500);
        assertThat(snapshot.get("radius").asInt()).isEqualTo(1000);
        assertThat(snapshot.get("minBudget").asInt()).isEqualTo(20);
        assertThat(snapshot.get("maxBudget").asInt()).isEqualTo(40);
        assertThat(deepEvidenceCalls).hasValue(0);
    }

    @Test
    void rerollFlowOffersFiveAlternativesThenBackToInitial() throws Exception {
        String createBody = create(USER, """
                {"latitude": 28.2282, "longitude": 112.9388}
                """).getBody();
        JsonNode created = JSON.readTree(createBody);
        String recommendationId = created.get("recommendationId").asText();
        assertThat(created.get("alternativesRemaining").asInt()).isEqualTo(5);

        String a = created.get("restaurant").get("id").asText();
        java.util.Set<String> shown = new java.util.HashSet<>();
        shown.add(a);
        for (int remaining = 4; remaining >= 0; remaining--) {
            JsonNode next = reroll(USER, recommendationId);
            assertThat(next.get("alternativesRemaining").asInt()).isEqualTo(remaining);
            assertThat(shown.add(next.get("restaurant").get("id").asText())).isTrue();
            if (remaining == 4) {
                String recommended = jdbc.queryForObject(
                        "SELECT recommended_restaurant_id::text FROM recommendation_log WHERE id = ?::uuid",
                        String.class, recommendationId);
                String current = jdbc.queryForObject(
                        "SELECT current_restaurant_id::text FROM recommendation_log WHERE id = ?::uuid",
                        String.class, recommendationId);
                assertThat(recommended).isEqualTo(a);
                assertThat(current).isNotEqualTo(recommended);
            }
        }
        assertThat(shown).hasSize(6);

        // 耗尽后返回初始推荐,不产生第七家
        JsonNode exhausted = reroll(USER, recommendationId);
        assertThat(exhausted.get("restaurant").get("id").asText()).isEqualTo(a);
        assertThat(exhausted.get("alternativesRemaining").asInt()).isEqualTo(0);
        assertThat(deepEvidenceCalls).hasValue(0);
    }

    @Test
    void feedbackRecordsCurrentDisplayedRestaurant() throws Exception {
        String recommendationId = JSON.readTree(
                create(USER, "{\"latitude\": 28.2282, \"longitude\": 112.9388}").getBody())
                .get("recommendationId").asText();
        JsonNode rerolled = reroll(USER, recommendationId);
        String displayed = rerolled.get("restaurant").get("id").asText();

        ResponseEntity<String> response = post(
                "/api/v1/recommendations/glm-5.3_common/feedback".replace("glm-5.3_common", recommendationId),
                USER, "{\"result\": \"LIKE\"}");
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        JsonNode feedback = JSON.readTree(response.getBody());
        assertThat(feedback.get("result").asText()).isEqualTo("LIKE");
        assertThat(feedback.get("recommendationId").asText()).isEqualTo(recommendationId);
        assertThat(feedback.get("restaurantId").asText()).isEqualTo(displayed);
        assertThat(feedback.get("recordedAt")).isNotNull();
        Integer profiles = jdbc.queryForObject(
                "SELECT count(*) FROM user_taste_profile WHERE schema_version = 3", Integer.class);
        assertThat(profiles).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_food_history", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM user_behavior WHERE behavior_type = 'LIKE'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void oldFeedbackRebuildsV2TasteProfileWhenSnapshotIsMissing() throws Exception {
        String recommendationId = JSON.readTree(
                create(USER, "{\"latitude\": 28.2282, \"longitude\": 112.9388}").getBody())
                .get("recommendationId").asText();
        assertThat(post("/api/v1/recommendations/" + recommendationId + "/feedback",
                USER, "{\"result\": \"DISLIKE\"}").getStatusCode().value()).isEqualTo(201);
        jdbc.update("DELETE FROM user_taste_profile WHERE anonymous_user_id = ?::uuid", USER);

        assertThat(create(USER,
                "{\"latitude\": 28.2282, \"longitude\": 112.9388}").getStatusCode().value())
                .isEqualTo(201);
        String snapshot = jdbc.queryForObject(
                "SELECT category_weights_json::text FROM user_taste_profile "
                        + "WHERE anonymous_user_id = ?::uuid",
                String.class, USER);
        JsonNode profile = JSON.readTree(snapshot);
        assertThat(profile.elements().next().asDouble()).isNegative();
        assertThat(jdbc.queryForObject(
                "SELECT explicit_feedback_count FROM user_taste_profile WHERE anonymous_user_id = ?::uuid",
                Integer.class, USER)).isEqualTo(1);
    }

    @Test
    void behaviorEndpointIsIdempotentAndClientTypeIsRestricted() throws Exception {
        JsonNode created = JSON.readTree(create(USER,
                "{\"latitude\": 28.2282, \"longitude\": 112.9388}").getBody());
        String recommendationId = created.get("recommendationId").asText();
        String restaurantId = created.get("restaurant").get("id").asText();
        String eventId = UUID.randomUUID().toString();
        String payload = "{\"eventId\":\"" + eventId + "\",\"restaurantId\":\""
                + restaurantId + "\",\"type\":\"ACCEPT\"}";

        ResponseEntity<String> first = post("/api/v1/recommendations/" + recommendationId
                + "/behaviors", USER, payload);
        ResponseEntity<String> duplicate = post("/api/v1/recommendations/" + recommendationId
                + "/behaviors", USER, payload);

        assertThat(first.getStatusCode().value()).isEqualTo(201);
        assertThat(duplicate.getStatusCode().value()).isEqualTo(200);
        assertThat(JSON.readTree(duplicate.getBody()).get("deduplicated").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_behavior WHERE id = ?::uuid",
                Integer.class, eventId)).isEqualTo(1);

        ResponseEntity<String> forbidden = post("/api/v1/recommendations/" + recommendationId
                + "/behaviors", USER, "{\"eventId\":\"" + UUID.randomUUID()
                + "\",\"restaurantId\":\"" + restaurantId
                + "\",\"type\":\"RECOMMENDED\"}");
        assertThat(forbidden.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void feedbackStoresOptionalFlavorTagsAndRejectsConflictingSecondSubmission() throws Exception {
        JsonNode created = JSON.readTree(create(USER,
                "{\"latitude\": 28.2282, \"longitude\": 112.9388}").getBody());
        String recommendationId = created.get("recommendationId").asText();
        ResponseEntity<String> first = post("/api/v1/recommendations/" + recommendationId
                + "/feedback", USER,
                "{\"result\":\"DISLIKE\",\"flavorTags\":[\"SPICY\",\"OILY\"]}");
        assertThat(first.getStatusCode().value()).isEqualTo(201);
        String stored = jdbc.queryForObject("SELECT flavor_tags_json::text FROM user_feedback",
                String.class);
        assertThat(JSON.readTree(stored).size()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM restaurant_flavor_observation",
                Integer.class)).isEqualTo(2);

        ResponseEntity<String> conflict = post("/api/v1/recommendations/" + recommendationId
                + "/feedback", USER, "{\"result\":\"LIKE\"}");
        assertThat(conflict.getStatusCode().value()).isEqualTo(409);
        assertThat(JSON.readTree(conflict.getBody()).get("code").asText())
                .isEqualTo("FEEDBACK_ALREADY_RECORDED");
    }

    @Test
    void invalidRadiusReturns400WithFieldError() throws Exception {
        ResponseEntity<String> response = create(USER, """
                {"latitude": 28.2282, "longitude": 112.9388, "radius": 800}
                """);
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        JsonNode body = JSON.readTree(response.getBody());
        assertThat(body.get("code").asText()).isEqualTo("VALIDATION_FAILED");
        assertThat(body.get("fieldErrors").get(0).get("field").asText()).isEqualTo("radius");
        assertThat(body.get("traceId")).isNotNull();
    }

    @Test
    void invalidRangeLowerBoundsReturn400WithFieldError() throws Exception {
        ResponseEntity<String> distanceResponse = create(USER, """
                {"latitude": 28.2282, "longitude": 112.9388,
                 "minDistance": 1000, "radius": 1000}
                """);
        assertThat(distanceResponse.getStatusCode().value()).isEqualTo(400);
        assertThat(JSON.readTree(distanceResponse.getBody()).get("fieldErrors").get(0)
                .get("field").asText()).isEqualTo("minDistance");

        ResponseEntity<String> budgetResponse = create(USER, """
                {"latitude": 28.2282, "longitude": 112.9388,
                 "minBudget": 40, "maxBudget": 40}
                """);
        assertThat(budgetResponse.getStatusCode().value()).isEqualTo(400);
        assertThat(JSON.readTree(budgetResponse.getBody()).get("fieldErrors").get(0)
                .get("field").asText()).isEqualTo("minBudget");
    }

    @Test
    void unknownCategoryReturns400WithFieldError() throws Exception {
        ResponseEntity<String> response = create(USER, """
                {"latitude": 28.2282, "longitude": 112.9388, "category": "UNSUPPORTED"}
                """);
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        JsonNode body = JSON.readTree(response.getBody());
        assertThat(body.get("code").asText()).isEqualTo("VALIDATION_FAILED");
        assertThat(body.get("fieldErrors").get(0).get("field").asText()).isEqualTo("category");
    }

    @Test
    void invalidUserHeaderReturns400() throws Exception {
        ResponseEntity<String> response = create("not-a-uuid",
                "{\"latitude\": 28.2282, \"longitude\": 112.9388}");
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(JSON.readTree(response.getBody()).get("code").asText())
                .isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void missingHeaderReturns400() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = rest.postForEntity("/api/v1/recommendations",
                new HttpEntity<>("{\"latitude\": 28.2282, \"longitude\": 112.9388}", headers),
                String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void unknownRecommendationReturns404() {
        ResponseEntity<String> response = post(
                "/api/v1/recommendations/glm-5.3_common/reroll".replace("glm-5.3_common",
                        UUID.randomUUID().toString()), USER, null);
        assertThat(response.getStatusCode().value()).isEqualTo(404);
        try {
            assertThat(JSON.readTree(response.getBody()).get("code").asText())
                    .isEqualTo("RECOMMENDATION_NOT_FOUND");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void rerollFromAnotherUserReturns404() throws Exception {
        String recommendationId = JSON.readTree(
                create(USER, "{\"latitude\": 28.2282, \"longitude\": 112.9388}").getBody())
                .get("recommendationId").asText();
        ResponseEntity<String> response = post(
                "/api/v1/recommendations/glm-5.3_common/reroll".replace("glm-5.3_common", recommendationId),
                OTHER_USER, null);
        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void emptyPoisReturns422() {
        amapResponse.set("{\"status\":\"1\",\"pois\":[]}");
        try {
            ResponseEntity<String> response = create(USER,
                    "{\"latitude\": 28.2282, \"longitude\": 112.9388}");
            assertThat(response.getStatusCode().value()).isEqualTo(422);
            try {
                assertThat(JSON.readTree(response.getBody()).get("code").asText())
                        .isEqualTo("NO_RECOMMENDATION_AVAILABLE");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        } finally {
            amapResponse.set(eightPois());
        }
    }

    @Test
    void amapFailureReturns502() {
        amapResponse.set("{\"status\":\"0\",\"info\":\"INVALID_USER_KEY\"}");
        try {
            ResponseEntity<String> response = create(USER,
                    "{\"latitude\": 28.2282, \"longitude\": 112.9388}");
            assertThat(response.getStatusCode().value()).isEqualTo(502);
            try {
                assertThat(JSON.readTree(response.getBody()).get("code").asText())
                        .isEqualTo("POI_PROVIDER_UNAVAILABLE");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        } finally {
            amapResponse.set(eightPois());
        }
    }

    @Test
    void evidenceProviderFailureDoesNotBreakRecommendation() {
        evidenceFailure.set(true);
        ResponseEntity<String> response = create(USER,
                "{\"latitude\": 28.2282, \"longitude\": 112.9388}");
        assertThat(response.getStatusCode().value()).isEqualTo(201);
    }

    @Test
    void deepEvidenceIsOwnedCachedAndDoesNotExposeSnippets() throws Exception {
        JsonNode created = JSON.readTree(create(USER,
                "{\"latitude\": 28.2282, \"longitude\": 112.9388}").getBody());
        String recommendationId = created.get("recommendationId").asText();
        String restaurantId = created.get("restaurant").get("id").asText();
        String path = "/api/v1/recommendations/" + recommendationId + "/deep-evidence";

        ResponseEntity<String> firstResponse = post(path, USER, null);
        assertThat(firstResponse.getStatusCode().value()).isEqualTo(200);
        JsonNode first = JSON.readTree(firstResponse.getBody());
        assertThat(first.get("restaurantId").asText()).isEqualTo(restaurantId);
        assertThat(first.get("baseRisk").get("algorithmVersion").asText()).isEqualTo("risk-v0.3");
        assertThat(first.get("deepRisk").get("algorithmVersion").asText())
                .isEqualTo("deep-risk-v0.1");
        assertThat(first.get("sourceCoverage")).hasSize(5);
        assertThat(first.get("links").size()).isLessThanOrEqualTo(9);
        assertThat(firstResponse.getBody()).doesNotContain("snippet");
        assertThat(first.get("cacheStatus").asText()).isEqualTo("MISS");
        assertThat(deepEvidenceCalls).hasValue(3);

        JsonNode cached = JSON.readTree(post(path, USER, null).getBody());
        assertThat(cached.get("cacheStatus").asText()).isEqualTo("HIT");
        assertThat(deepEvidenceCalls).hasValue(3);

        assertThat(post(path, OTHER_USER, null).getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void partialDeepEvidenceFailureStillReturnsBaseConclusion() throws Exception {
        JsonNode created = JSON.readTree(create(USER,
                "{\"latitude\": 28.2282, \"longitude\": 112.9388}").getBody());
        deepEvidenceFailure.set(DeepEvidenceSource.XIAOHONGSHU);

        ResponseEntity<String> response = post("/api/v1/recommendations/"
                + created.get("recommendationId").asText() + "/deep-evidence", USER, null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode body = JSON.readTree(response.getBody());
        assertThat(body.get("baseRisk")).isNotNull();
        assertThat(body.get("sourceCoverage").toString())
                .contains("XIAOHONGSHU", "UNAVAILABLE");
        assertThat(deepEvidenceCalls).hasValue(3);
    }

    @Test
    void calibrationAndRecommendationMetricViewsAreQueryable() throws Exception {
        JsonNode created = JSON.readTree(create(USER,
                "{\"latitude\": 28.2282, \"longitude\": 112.9388}").getBody());
        String recommendationId = created.get("recommendationId").asText();

        ResponseEntity<String> feedback = post(
                "/api/v1/recommendations/" + recommendationId + "/feedback",
                USER,
                "{\"result\":\"DISLIKE\"}");
        assertThat(feedback.getStatusCode().value()).isEqualTo(201);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM v_risk_calibration", Integer.class))
                .isGreaterThan(0);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM v_recommendation_metrics", Integer.class))
                .isGreaterThan(0);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM v_recommendation_metrics "
                        + "WHERE recommendation_algorithm_version = 'recommendation-v0.4' "
                        + "AND taste_algorithm_version = 'taste-v0.1'",
                Integer.class)).isGreaterThan(0);
    }

    private ResponseEntity<String> create(String userId, String body) {
        return post("/api/v1/recommendations", userId, body);
    }

    private JsonNode reroll(String userId, String recommendationId) throws Exception {
        ResponseEntity<String> response = post(
                "/api/v1/recommendations/glm-5.3_common/reroll".replace("glm-5.3_common", recommendationId),
                userId, null);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return JSON.readTree(response.getBody());
    }

    private ResponseEntity<String> post(String path, String userId, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Anonymous-User-Id", userId);
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private static String eightPois() {
        StringBuilder pois = new StringBuilder("[");
        for (int i = 1; i <= 8; i++) {
            if (i > 1) {
                pois.append(',');
            }
            pois.append("""
                    {"id": "POI-%d", "name": "测试餐厅%d", "type": "餐饮服务;中餐厅;家常菜",
                     "typecode": "050100", "address": "麓山南路 %d 号",
                     "location": "112.9412,28.2291", "distance": "%d",
                     "pname": "湖南省", "cityname": "长沙市", "adname": "岳麓区",
                     "biz_ext": {"rating": "4.%d", "cost": "%d", "opening_time": "09:00-21:00"}}
                    """.formatted(i, i, i, 200 + i * 80, 9 - i, 20 + i));
        }
        pois.append(']');
        return "{\"status\":\"1\",\"info\":\"OK\",\"pois\":" + pois + "}";
    }
}
