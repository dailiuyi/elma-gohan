package com.elma.gohan.domain.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.elma.gohan.TestRestaurants;
import com.elma.gohan.config.RecommendationProperties;
import com.elma.gohan.domain.restaurant.SearchCondition;
import com.elma.gohan.domain.risk.RiskLevel;
import com.elma.gohan.domain.risk.RiskFactors;
import com.elma.gohan.domain.risk.RiskResult;
import java.util.List;
import java.util.Map;
import java.time.LocalDateTime;
import com.elma.gohan.domain.restaurant.BusinessStatus;
import com.elma.gohan.domain.restaurant.CategoryConfidence;
import com.elma.gohan.domain.restaurant.DataCompleteness;
import com.elma.gohan.domain.restaurant.Restaurant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LowRegretScorerTest {

    private final RecommendationProperties props = new RecommendationProperties();
    private final LowRegretScorer scorer = new LowRegretScorer(props);

    private RiskResult risk(int score) {
        return new RiskResult(score, RiskLevel.LOW, List.of("评分稳定"), "risk-v0.3");
    }

    @Test
    @DisplayName("分数范围 0~100,数据齐全近距离高分,数据缺失远距离低分")
    void scoreRangeAndOrdering() {
        var condition = new SearchCondition(1000, 40, "ANY", List.of());
        double near = scorer.score(TestRestaurants.full("a", 4.6, 100, 20), risk(0), condition);
        double far = scorer.score(TestRestaurants.full("b", 3.0, 1000, null), risk(60), condition);
        assertThat(near).isCloseTo(90.0, within(15.0));
        assertThat(far).isLessThan(50.0);
        assertThat(far).isGreaterThanOrEqualTo(0);
        assertThat(near).isLessThanOrEqualTo(100);
        assertThat(near).isGreaterThan(far);
    }

    @Test
    @DisplayName("同等条件下距离更近得分更高")
    void closerScoresHigher() {
        var condition = new SearchCondition(3000, null, "ANY", List.of());
        double near = scorer.score(TestRestaurants.full("a", 4.5, 500), risk(10), condition);
        double far = scorer.score(TestRestaurants.full("b", 4.5, 2500), risk(10), condition);
        assertThat(near).isGreaterThan(far);
    }

    @Test
    @DisplayName("推荐理由非空且不超过 5 条")
    void reasonsBounded() {
        var condition = new SearchCondition(1000, null, "ANY", List.of());
        var reasons = scorer.reasons(TestRestaurants.full("a", 4.6, 100), risk(0), condition);
        assertThat(reasons).isNotEmpty();
        assertThat(reasons).hasSizeLessThanOrEqualTo(5);
        assertThat(reasons).doesNotContain("预算合适");
    }

    @Test
    @DisplayName("距离在所选区间内按下界到上界归一化")
    void distanceBandIsNormalizedWithinSelectedRange() {
        var condition = new SearchCondition(500, 1000, null, null, "ANY", List.of());
        double nearLowerBound = scorer.score(
                TestRestaurants.full("a", 4.5, 501, 30), risk(10), condition);
        double nearUpperBound = scorer.score(
                TestRestaurants.full("b", 4.5, 1000, 30), risk(10), condition);

        assertThat(nearLowerBound).isGreaterThan(nearUpperBound);
    }

    @Test
    @DisplayName("价格缺失候选保留中性偏低预算分且不声称预算合适")
    void missingPriceIsDownWeightedWithoutBudgetReason() {
        var condition = new SearchCondition(null, 500, 20, 40, "ANY", List.of());
        Restaurant known = TestRestaurants.full("known", 4.5, 300, 21);
        Restaurant unknown = TestRestaurants.full("unknown", 4.5, 300, null);

        assertThat(scorer.score(known, risk(10), condition))
                .isGreaterThan(scorer.score(unknown, risk(10), condition));
        assertThat(scorer.reasons(known, risk(10), condition)).contains("预算合适");
        assertThat(scorer.reasons(unknown, risk(10), condition)).doesNotContain("预算合适");
    }

    @Test
    @DisplayName("无预算上界时 ¥70+ 已知价格视为匹配")
    void openEndedHighBudgetBandMatchesKnownPrice() {
        var condition = new SearchCondition(null, 500, 70, null, "ANY", List.of());
        Restaurant restaurant = TestRestaurants.full("high", 4.5, 300, 71);

        assertThat(scorer.reasons(restaurant, risk(10), condition)).contains("预算合适");
    }

    @Test
    @DisplayName("新用户排序中性，老用户历史品类偏好改变分数")
    void tasteProfileChangesOldUserRanking() {
        Restaurant chinese = category("c", "CHINESE");
        Restaurant foreign = category("f", "FOREIGN");
        var condition = new SearchCondition(1000, null, "ANY", List.of());
        double newChinese = scorer.score(chinese, risk(10), new UserPreference(condition));
        double newForeign = scorer.score(foreign, risk(10), new UserPreference(condition));
        assertThat(newChinese).isEqualTo(newForeign);

        TasteProfile profile = new TasteProfile(2, Map.of("CHINESE", 3.0, "FOREIGN", -3.0),
                Map.of(), Map.of(), 6, LocalDateTime.now());
        var oldUser = new UserPreference(condition, profile);
        assertThat(scorer.score(chinese, risk(10), oldUser))
                .isGreaterThan(scorer.score(foreign, risk(10), oldUser));
    }

    @Test
    @DisplayName("低可信的表面低风险会按不确定性风险校正")
    void lowConfidenceRiskDoesNotGainFalseAdvantage() {
        Restaurant restaurant = category("c", "CHINESE");
        var condition = new SearchCondition(1000, null, "ANY", List.of());
        RiskResult trusted = new RiskResult(0, RiskLevel.LOW, 1.0, RiskFactors.empty(),
                List.of("证据充分"), "risk-v0.3");
        RiskResult uncertain = new RiskResult(0, RiskLevel.LOW, 0.0, RiskFactors.empty(),
                List.of("证据不足"), "risk-v0.3");

        assertThat(scorer.score(restaurant, trusted, condition))
                .isGreaterThan(scorer.score(restaurant, uncertain, condition));
    }

    @Test
    @DisplayName("餐饮分类可信度按 0/6/18 降权且分数保持在范围内")
    void categoryConfidenceChangesScore() {
        var condition = new SearchCondition(1000, null, "ANY", List.of());
        Restaurant verified = category("v", "CHINESE", CategoryConfidence.VERIFIED);
        Restaurant supported = category("s", "CHINESE", CategoryConfidence.SUPPORTED);
        Restaurant inferred = category("i", "CHINESE", CategoryConfidence.INFERRED);

        double verifiedScore = scorer.score(verified, risk(10), condition);
        double supportedScore = scorer.score(supported, risk(10), condition);
        double inferredScore = scorer.score(inferred, risk(10), condition);

        assertThat(verifiedScore - supportedScore).isCloseTo(6.0, within(0.001));
        assertThat(verifiedScore - inferredScore).isCloseTo(18.0, within(0.001));
        assertThat(inferredScore).isBetween(0.0, 100.0);
        assertThat(scorer.reasons(inferred, risk(10), condition))
                .contains("餐饮信息相对有限");
    }

    private Restaurant category(String id, String category) {
        return category(id, category, CategoryConfidence.SUPPORTED);
    }

    private Restaurant category(String id, String category, CategoryConfidence confidence) {
        return new Restaurant(null, "AMAP", id, "餐厅" + id, 28, 112, 300,
                category, category, 4.5, 100, 50, BusinessStatus.OPEN,
                "09:00-21:00", "地址", null, DataCompleteness.FULL, confidence);
    }
}
