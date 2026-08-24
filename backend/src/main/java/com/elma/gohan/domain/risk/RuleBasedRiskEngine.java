package com.elma.gohan.domain.risk;

import com.elma.gohan.config.RiskProperties;
import com.elma.gohan.domain.restaurant.Restaurant;
import com.elma.gohan.provider.evidence.EvidenceStatus;
import com.elma.gohan.provider.evidence.EvidenceBundle;
import com.elma.gohan.provider.evidence.EntityMatchResult;
import com.elma.gohan.provider.evidence.EntityMatchStatus;
import com.elma.gohan.provider.evidence.PlatformEvidence;
import com.elma.gohan.provider.evidence.CrossPlatformConsistency;
import com.elma.gohan.provider.evidence.RestaurantEvidence;
import com.elma.gohan.provider.evidence.ReviewEvidence;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** risk-v0.3.1：多源评分、连续趋势、数据不足和跨平台冲突的透明规则模型。 */
@Component
public class RuleBasedRiskEngine implements RiskEngine {

    private final RiskProperties properties;
    private final TemplateCommentDetector templateDetector;
    private final ReviewBurstDetector burstDetector;
    private final RecentTrendDetector trendDetector;
    private final Clock clock;

    @Autowired
    public RuleBasedRiskEngine(RiskProperties properties,
                               TemplateCommentDetector templateDetector,
                               ReviewBurstDetector burstDetector,
                               RecentTrendDetector trendDetector) {
        this(properties, templateDetector, burstDetector, trendDetector, Clock.systemUTC());
    }

    public RuleBasedRiskEngine(RiskProperties properties,
                               TemplateCommentDetector templateDetector,
                               ReviewBurstDetector burstDetector,
                               RecentTrendDetector trendDetector,
                               Clock clock) {
        this.properties = properties;
        this.templateDetector = templateDetector;
        this.burstDetector = burstDetector;
        this.trendDetector = trendDetector;
        this.clock = clock;
    }

    /** 便于纯单元测试使用默认轻量分析器。 */
    public RuleBasedRiskEngine(RiskProperties properties) {
        this(properties, new JaccardTemplateCommentDetector(properties),
                new SlidingWindowBurstDetector(properties),
                new RuleBasedRecentTrendDetector(properties));
    }

    @Override
    public RiskResult evaluate(Restaurant restaurant, RestaurantEvidence suppliedEvidence) {
        PlatformEvidence amap = new PlatformEvidence("AMAP", restaurant.sourcePoiId(),
                EvidenceStatus.AVAILABLE, null, restaurant.name(), restaurant.address(),
                restaurant.latitude(), restaurant.longitude(), restaurant.rating(), null, null,
                null, restaurant.reviewCount(), restaurant.averagePrice(),
                restaurant.openingHours(), null, restaurant.telephone());
        return evaluate(restaurant, new EvidenceBundle(suppliedEvidence, amap, null,
                EntityMatchResult.noMatch(),
                CrossPlatformConsistency.unknown("百度暂未匹配到同一门店")));
    }

    @Override
    public RiskResult evaluate(Restaurant restaurant, EvidenceBundle suppliedBundle) {
        EvidenceBundle bundle = suppliedBundle == null ? legacyUnavailable(restaurant) : suppliedBundle;
        RestaurantEvidence evidence = bundle.reviewEvidence();
        Set<String> reasons = new LinkedHashSet<>();

        int ratingRisk = ratingRisk(consensusRating(bundle), reasons);
        TemplateDetectionResult template = templateDetector.detect(evidence.reviews());
        int templateRisk = linearRisk(template.templateRatio(),
                properties.getTemplate().getRatioStart(), properties.getTemplate().getRatioFull());
        if (templateRisk > 0) reasons.add("相似措辞评论偏多");

        BurstDetectionResult burst = burstDetector.detect(evidence.reviews());
        if (burst.burstRisk() > 0) reasons.add("评论在少数日期异常集中");

        TrendResult trend = trendDetector.detect(evidence.reviews());
        int trendRisk = trendRisk(trend);
        if (trend.trend() == RecentTrend.DOWN) reasons.add("近期口碑较历史明显下降");
        if (trend.trend() == RecentTrend.UP) reasons.add("近期口碑有所改善");

        int insufficientRisk = dataInsufficientRisk(bundle, reasons);
        int conflictRisk = bundle.consistency().crossPlatformConflictRisk();
        if (conflictRisk > 0) reasons.add(bundle.consistency().reason());
        RiskFactors factors = new RiskFactors(ratingRisk, templateRisk, burst.burstRisk(),
                trendRisk, insufficientRisk, conflictRisk);
        int score = weightedScore(factors);
        double confidence = confidence(bundle);

        if (evidence.status() == EvidenceStatus.AVAILABLE && templateRisk == 0
                && burst.burstRisk() == 0 && trend.trend() != RecentTrend.DOWN) {
            reasons.add("未发现明显评论异常");
        }
        if (reasons.isEmpty()) reasons.add("现有数据未发现明显风险");
        List<String> visibleReasons = new ArrayList<>(reasons);
        if (visibleReasons.size() > 5) visibleReasons = visibleReasons.subList(0, 5);

        return new RiskResult(score, levelOf(score), confidence, factors,
                visibleReasons, properties.getAlgorithmVersion(), bundle.summary());
    }

    private int trendRisk(TrendResult trend) {
        RiskProperties.Trend config = properties.getTrend();
        int target = Math.max(1, properties.getTrendTargetSample());
        double sampleRatio = Math.min(1.0,
                Math.min(trend.recentCount(), trend.baselineCount()) / (double) target);
        return switch (trend.trend()) {
            case UP -> config.getUpRisk();
            case STABLE -> config.getStableRisk();
            case DOWN -> clamp((int) Math.round(config.getStableRisk()
                    + (config.getDownRisk() - config.getStableRisk())
                    * trend.severity() * sampleRatio));
            case UNKNOWN -> config.getUnknownRisk();
        };
    }

    private int ratingRisk(Double effectiveRating, Set<String> reasons) {
        RiskProperties.Rating rating = properties.getRating();
        if (effectiveRating == null) {
            reasons.add("评分数据缺失");
            return rating.getMissingRisk();
        }
        if (effectiveRating >= rating.getExcellentMin()) return rating.getExcellentRisk();
        if (effectiveRating >= rating.getGoodMin()) {
            reasons.add("基础评分良好但未达优秀");
            return rating.getGoodRisk();
        }
        if (effectiveRating >= rating.getFairMin()) {
            reasons.add("基础评分一般");
            return rating.getFairRisk();
        }
        reasons.add("基础评分偏低");
        return rating.getPoorRisk();
    }

    private int dataInsufficientRisk(EvidenceBundle bundle, Set<String> reasons) {
        RiskProperties.DataInsufficient data = properties.getDataInsufficient();
        PlatformEvidence amap = bundle.amap();
        PlatformEvidence baidu = bundle.baidu();
        int risk = 0;
        if (bundle.entityMatch().status() == EntityMatchStatus.UNAVAILABLE) {
            risk += data.getBaiduUnavailable();
            reasons.add("百度证据暂不可用，已按高德数据继续判断");
        } else if (bundle.entityMatch().status() != EntityMatchStatus.MATCHED) {
            risk += data.getBaiduNoMatch();
            reasons.add(bundle.entityMatch().status() == EntityMatchStatus.AMBIGUOUS
                    ? "百度存在相似门店，暂不合并评分" : "百度暂未匹配到同一门店");
        } else if (baidu == null || baidu.overallRating() == null) {
            risk += data.getBaiduRatingMissing();
            reasons.add("百度综合评分缺失");
        }
        if (amap == null || amap.overallRating() == null) {
            risk += data.getAmapRatingMissing();
            reasons.add("高德评分数据缺失");
        }
        if ((amap == null || amap.commentCount() == null)
                && (baidu == null || baidu.commentCount() == null)) {
            risk += data.getBothCommentCountMissing();
            reasons.add("两平台评价数量均缺失");
        }
        if ((amap == null || amap.averagePrice() == null)
                && (baidu == null || baidu.averagePrice() == null)) {
            risk += data.getBothPriceMissing();
            reasons.add("两平台价格信息均缺失");
        }
        if ((amap == null || amap.openingHours() == null || amap.openingHours().isBlank())
                && (baidu == null || baidu.openingHours() == null || baidu.openingHours().isBlank())) {
            risk += data.getBothOpeningHoursMissing();
            reasons.add("两平台营业信息均缺失");
        }
        RestaurantEvidence reviews = bundle.reviewEvidence();
        if (amap != null && amap.averagePrice() != null && reviews.poolAveragePrice() != null
                && reviews.poolAveragePrice() > 0
                && amap.averagePrice() > reviews.poolAveragePrice() * properties.getPriceAnomalyRatio()) {
            risk += data.getPriceAnomaly();
            reasons.add("价格明显高于同批候选");
        }
        return clamp(risk);
    }

    private int weightedScore(RiskFactors factors) {
        RiskProperties.Weights weights = properties.getWeights();
        double score = factors.ratingRisk() * weights.getRating()
                + factors.templateRisk() * weights.getTemplate()
                + factors.burstRisk() * weights.getBurst()
                + factors.trendRisk() * weights.getTrend()
                + factors.dataInsufficientRisk() * weights.getDataInsufficient()
                + factors.crossPlatformConflictRisk() * weights.getCrossPlatformConflict();
        return clamp((int) Math.round(score));
    }

    private double confidence(EvidenceBundle bundle) {
        RiskProperties.Confidence config = properties.getConfidence();
        double amapCompleteness = amapCompleteness(bundle.amap());

        double value;
        if (bundle.entityMatch().status() == EntityMatchStatus.MATCHED
                && bundle.baidu() != null && bundle.entityMatch().confidence() != null) {
            value = config.getAmapWeight() * amapCompleteness
                    + config.getBaiduWeight() * baiduCompleteness(bundle.baidu())
                    + config.getMatchWeight() * bundle.entityMatch().confidence();
        } else {
            value = Math.min(config.getSingleSourceCap(),
                    config.getSingleSourceWeight() * amapCompleteness);
        }

        // 可选 File Evidence 仍能提高可信度，但默认真实链路不依赖它。
        RestaurantEvidence evidence = bundle.reviewEvidence();
        if (evidence.status() == EvidenceStatus.AVAILABLE && !evidence.reviews().isEmpty()) {
            List<ReviewEvidence> reviews = evidence.reviews();
            double volume = Math.min(1.0,
                    (double) reviews.size() / Math.max(1, config.getTargetReviews()));
            double text = coverage(reviews, r -> r.text() != null && !r.text().isBlank());
            double rating = coverage(reviews, r -> r.rating() != null);
            double time = coverage(reviews, r -> r.createdAt() != null);
            double reviewConfidence = volume * (text + rating + time) / 3.0
                    * freshness(evidence, config.getFreshnessWindowDays());
            value = Math.max(value, config.getPoiWeight() * amapCompleteness
                    + config.getEvidenceWeight() * reviewConfidence);
        }
        return Math.round(Math.max(0.0, Math.min(1.0, value)) * 1000.0) / 1000.0;
    }

    private double amapCompleteness(PlatformEvidence evidence) {
        if (evidence == null) return 0.0;
        int present = 0;
        if (evidence.overallRating() != null) present++;
        if (evidence.averagePrice() != null) present++;
        if (evidence.openingHours() != null && !evidence.openingHours().isBlank()) present++;
        if (evidence.address() != null && !evidence.address().isBlank()) present++;
        return present / 4.0;
    }

    private double baiduCompleteness(PlatformEvidence evidence) {
        if (evidence == null || evidence.status() != EvidenceStatus.AVAILABLE) return 0.0;
        double score = 0.0;
        if (evidence.overallRating() != null) score += 0.40;
        if (evidence.commentCount() != null) score += 0.20;
        if (evidence.averagePrice() != null) score += 0.15;
        if (evidence.openingHours() != null && !evidence.openingHours().isBlank()) score += 0.10;
        if (evidence.tasteRating() != null || evidence.serviceRating() != null
                || evidence.environmentRating() != null) score += 0.15;
        return score;
    }

    /** 仅修正可选评论 Evidence 的增量可信度，不降低高德/百度结构化基础值。 */
    private double freshness(RestaurantEvidence evidence, int windowDays) {
        if (windowDays <= 0) return 1.0;
        Instant now = clock.instant();
        Instant windowStart = now.minus(windowDays, ChronoUnit.DAYS);
        long freshReviews = evidence.reviews().stream()
                .filter(review -> isFreshTimestamp(review.createdAt(), windowStart, now))
                .count();
        double reviewFreshness = (double) freshReviews / evidence.reviews().size();
        double fetchFreshness = isFreshTimestamp(evidence.fetchedAt(), windowStart, now)
                ? 1.0 : 0.0;
        return reviewFreshness * fetchFreshness;
    }

    private boolean isFreshTimestamp(Instant value, Instant windowStart, Instant now) {
        return value != null && !value.isBefore(windowStart) && !value.isAfter(now);
    }

    private Double consensusRating(EvidenceBundle bundle) {
        Double amap = bundle.amap() == null ? null : bundle.amap().overallRating();
        Double baidu = bundle.entityMatch().status() == EntityMatchStatus.MATCHED
                && bundle.baidu() != null ? bundle.baidu().overallRating() : null;
        if (amap == null) return baidu;
        if (baidu == null) return amap;
        return (amap + baidu) / 2.0;
    }

    private EvidenceBundle legacyUnavailable(Restaurant restaurant) {
        PlatformEvidence amap = new PlatformEvidence("AMAP", restaurant.sourcePoiId(),
                EvidenceStatus.AVAILABLE, null, restaurant.name(), restaurant.address(),
                restaurant.latitude(), restaurant.longitude(), restaurant.rating(), null, null,
                null, restaurant.reviewCount(), restaurant.averagePrice(),
                restaurant.openingHours(), null, restaurant.telephone());
        return new EvidenceBundle(RestaurantEvidence.unavailable("UNKNOWN"), amap, null,
                EntityMatchResult.unavailable(),
                CrossPlatformConsistency.unknown("百度证据服务暂不可用"));
    }

    private double coverage(List<ReviewEvidence> reviews,
                            java.util.function.Predicate<ReviewEvidence> predicate) {
        return (double) reviews.stream().filter(predicate).count() / reviews.size();
    }

    private int linearRisk(double value, double start, double full) {
        if (value <= start) return 0;
        if (value >= full) return 100;
        return clamp((int) Math.round((value - start) * 100.0 / (full - start)));
    }

    private RiskLevel levelOf(int score) {
        RiskProperties.Levels levels = properties.getLevels();
        if (score <= levels.getLowMaxInclusive()) return RiskLevel.LOW;
        if (score <= levels.getMediumLowMaxInclusive()) return RiskLevel.MEDIUM_LOW;
        if (score <= levels.getMediumMaxInclusive()) return RiskLevel.MEDIUM;
        return RiskLevel.HIGH;
    }

    private int clamp(int value) { return Math.max(0, Math.min(100, value)); }
}
