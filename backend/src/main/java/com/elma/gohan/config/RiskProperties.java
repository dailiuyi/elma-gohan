package com.elma.gohan.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** risk-v0.3.1 的全部阈值与权重，规则实现中不保留业务数字。 */
@ConfigurationProperties(prefix = "elma.risk")
public class RiskProperties {

    private String algorithmVersion = "risk-v0.3.1";
    private Rating rating = new Rating();
    private Template template = new Template();
    private Burst burst = new Burst();
    private Trend trend = new Trend();
    private DataInsufficient dataInsufficient = new DataInsufficient();
    private Confidence confidence = new Confidence();
    private Weights weights = new Weights();
    private CrossPlatform crossPlatform = new CrossPlatform();
    private Levels levels = new Levels();
    private double priceAnomalyRatio = 1.5;
    private int priceAnomalyMinPoolSize = 5;
    private int trendTargetSample = 30;

    public String getAlgorithmVersion() { return algorithmVersion; }
    public void setAlgorithmVersion(String v) { algorithmVersion = v; }
    public Rating getRating() { return rating; }
    public void setRating(Rating v) { rating = v; }
    public Template getTemplate() { return template; }
    public void setTemplate(Template v) { template = v; }
    public Burst getBurst() { return burst; }
    public void setBurst(Burst v) { burst = v; }
    public Trend getTrend() { return trend; }
    public void setTrend(Trend v) { trend = v; }
    public DataInsufficient getDataInsufficient() { return dataInsufficient; }
    public void setDataInsufficient(DataInsufficient v) { dataInsufficient = v; }
    public Confidence getConfidence() { return confidence; }
    public void setConfidence(Confidence v) { confidence = v; }
    public Weights getWeights() { return weights; }
    public void setWeights(Weights v) { weights = v; }
    public CrossPlatform getCrossPlatform() { return crossPlatform; }
    public void setCrossPlatform(CrossPlatform v) { crossPlatform = v; }
    public Levels getLevels() { return levels; }
    public void setLevels(Levels v) { levels = v; }
    public double getPriceAnomalyRatio() { return priceAnomalyRatio; }
    public void setPriceAnomalyRatio(double v) { priceAnomalyRatio = v; }
    public int getPriceAnomalyMinPoolSize() { return priceAnomalyMinPoolSize; }
    public void setPriceAnomalyMinPoolSize(int v) { priceAnomalyMinPoolSize = v; }
    public int getTrendTargetSample() { return trendTargetSample; }
    public void setTrendTargetSample(int v) { trendTargetSample = v; }

    public static class Rating {
        private double fairMin = 4.0;
        private double goodMin = 4.2;
        private double excellentMin = 4.5;
        private int excellentRisk = 0;
        private int goodRisk = 20;
        private int fairRisk = 60;
        private int poorRisk = 100;
        private int missingRisk = 100;
        public double getFairMin() { return fairMin; }
        public void setFairMin(double v) { fairMin = v; }
        public double getGoodMin() { return goodMin; }
        public void setGoodMin(double v) { goodMin = v; }
        public double getExcellentMin() { return excellentMin; }
        public void setExcellentMin(double v) { excellentMin = v; }
        public int getExcellentRisk() { return excellentRisk; }
        public void setExcellentRisk(int v) { excellentRisk = v; }
        public int getGoodRisk() { return goodRisk; }
        public void setGoodRisk(int v) { goodRisk = v; }
        public int getFairRisk() { return fairRisk; }
        public void setFairRisk(int v) { fairRisk = v; }
        public int getPoorRisk() { return poorRisk; }
        public void setPoorRisk(int v) { poorRisk = v; }
        public int getMissingRisk() { return missingRisk; }
        public void setMissingRisk(int v) { missingRisk = v; }
    }

    public static class Template {
        private int minReviews = 10;
        private int ngramSize = 3;
        private int minTextLength = 8;
        private double similarityThreshold = 0.82;
        private double ratioStart = 0.10;
        private double ratioFull = 0.50;
        public int getMinReviews() { return minReviews; }
        public void setMinReviews(int v) { minReviews = v; }
        public int getNgramSize() { return ngramSize; }
        public void setNgramSize(int v) { ngramSize = v; }
        public int getMinTextLength() { return minTextLength; }
        public void setMinTextLength(int v) { minTextLength = v; }
        public double getSimilarityThreshold() { return similarityThreshold; }
        public void setSimilarityThreshold(double v) { similarityThreshold = v; }
        public double getRatioStart() { return ratioStart; }
        public void setRatioStart(double v) { ratioStart = v; }
        public double getRatioFull() { return ratioFull; }
        public void setRatioFull(double v) { ratioFull = v; }
    }

    public static class Burst {
        private int minReviews = 20;
        private int windowDays = 3;
        private int minPeakCount = 12;
        private int minHistoryDays = 14;
        private int maxHistoryDays = 365;
        private double ratioStart = 3.0;
        private double ratioFull = 8.0;
        public int getMinReviews() { return minReviews; }
        public void setMinReviews(int v) { minReviews = v; }
        public int getWindowDays() { return windowDays; }
        public void setWindowDays(int v) { windowDays = v; }
        public int getMinPeakCount() { return minPeakCount; }
        public void setMinPeakCount(int v) { minPeakCount = v; }
        public int getMinHistoryDays() { return minHistoryDays; }
        public void setMinHistoryDays(int v) { minHistoryDays = v; }
        public int getMaxHistoryDays() { return maxHistoryDays; }
        public void setMaxHistoryDays(int v) { maxHistoryDays = v; }
        public double getRatioStart() { return ratioStart; }
        public void setRatioStart(double v) { ratioStart = v; }
        public double getRatioFull() { return ratioFull; }
        public void setRatioFull(double v) { ratioFull = v; }
    }

    public static class Trend {
        private int recentDays = 30;
        private int baselineDays = 90;
        private int minRecentReviews = 5;
        private int minBaselineReviews = 10;
        private double ratingDelta = 0.4;
        private double negativeRatingMax = 2.0;
        private double negativeRatioDelta = 0.2;
        private int upRisk = 0;
        private int stableRisk = 10;
        private int downRisk = 100;
        private int unknownRisk = 0;
        public int getRecentDays() { return recentDays; }
        public void setRecentDays(int v) { recentDays = v; }
        public int getBaselineDays() { return baselineDays; }
        public void setBaselineDays(int v) { baselineDays = v; }
        public int getMinRecentReviews() { return minRecentReviews; }
        public void setMinRecentReviews(int v) { minRecentReviews = v; }
        public int getMinBaselineReviews() { return minBaselineReviews; }
        public void setMinBaselineReviews(int v) { minBaselineReviews = v; }
        public double getRatingDelta() { return ratingDelta; }
        public void setRatingDelta(double v) { ratingDelta = v; }
        public double getNegativeRatingMax() { return negativeRatingMax; }
        public void setNegativeRatingMax(double v) { negativeRatingMax = v; }
        public double getNegativeRatioDelta() { return negativeRatioDelta; }
        public void setNegativeRatioDelta(double v) { negativeRatioDelta = v; }
        public int getUpRisk() { return upRisk; }
        public void setUpRisk(int v) { upRisk = v; }
        public int getStableRisk() { return stableRisk; }
        public void setStableRisk(int v) { stableRisk = v; }
        public int getDownRisk() { return downRisk; }
        public void setDownRisk(int v) { downRisk = v; }
        public int getUnknownRisk() { return unknownRisk; }
        public void setUnknownRisk(int v) { unknownRisk = v; }
    }

    public static class DataInsufficient {
        private int noDataRisk = 85;
        private int unavailableRisk = 90;
        private int targetReviews = 30;
        private int sampleShortageMaxRisk = 80;
        private int poiReviewCountThreshold = 10;
        private int reviewCountMissing = 15;
        private int openingHoursMissing = 10;
        private int priceMissing = 5;
        private int priceAnomaly = 20;
        private int baiduUnavailable = 30;
        private int baiduNoMatch = 25;
        private int baiduRatingMissing = 15;
        private int amapRatingMissing = 20;
        private int bothCommentCountMissing = 10;
        private int bothPriceMissing = 5;
        private int bothOpeningHoursMissing = 5;
        public int getNoDataRisk() { return noDataRisk; }
        public void setNoDataRisk(int v) { noDataRisk = v; }
        public int getUnavailableRisk() { return unavailableRisk; }
        public void setUnavailableRisk(int v) { unavailableRisk = v; }
        public int getTargetReviews() { return targetReviews; }
        public void setTargetReviews(int v) { targetReviews = v; }
        public int getSampleShortageMaxRisk() { return sampleShortageMaxRisk; }
        public void setSampleShortageMaxRisk(int v) { sampleShortageMaxRisk = v; }
        public int getPoiReviewCountThreshold() { return poiReviewCountThreshold; }
        public void setPoiReviewCountThreshold(int v) { poiReviewCountThreshold = v; }
        public int getReviewCountMissing() { return reviewCountMissing; }
        public void setReviewCountMissing(int v) { reviewCountMissing = v; }
        public int getOpeningHoursMissing() { return openingHoursMissing; }
        public void setOpeningHoursMissing(int v) { openingHoursMissing = v; }
        public int getPriceMissing() { return priceMissing; }
        public void setPriceMissing(int v) { priceMissing = v; }
        public int getPriceAnomaly() { return priceAnomaly; }
        public void setPriceAnomaly(int v) { priceAnomaly = v; }
        public int getBaiduUnavailable() { return baiduUnavailable; }
        public void setBaiduUnavailable(int v) { baiduUnavailable = v; }
        public int getBaiduNoMatch() { return baiduNoMatch; }
        public void setBaiduNoMatch(int v) { baiduNoMatch = v; }
        public int getBaiduRatingMissing() { return baiduRatingMissing; }
        public void setBaiduRatingMissing(int v) { baiduRatingMissing = v; }
        public int getAmapRatingMissing() { return amapRatingMissing; }
        public void setAmapRatingMissing(int v) { amapRatingMissing = v; }
        public int getBothCommentCountMissing() { return bothCommentCountMissing; }
        public void setBothCommentCountMissing(int v) { bothCommentCountMissing = v; }
        public int getBothPriceMissing() { return bothPriceMissing; }
        public void setBothPriceMissing(int v) { bothPriceMissing = v; }
        public int getBothOpeningHoursMissing() { return bothOpeningHoursMissing; }
        public void setBothOpeningHoursMissing(int v) { bothOpeningHoursMissing = v; }
    }

    public static class Confidence {
        private double poiWeight = 0.25;
        private double evidenceWeight = 0.75;
        private int targetReviews = 30;
        private double amapWeight = 0.35;
        private double baiduWeight = 0.35;
        private double matchWeight = 0.30;
        private double singleSourceWeight = 0.50;
        private double singleSourceCap = 0.50;
        private int freshnessWindowDays = 120;
        public double getPoiWeight() { return poiWeight; }
        public void setPoiWeight(double v) { poiWeight = v; }
        public double getEvidenceWeight() { return evidenceWeight; }
        public void setEvidenceWeight(double v) { evidenceWeight = v; }
        public int getTargetReviews() { return targetReviews; }
        public void setTargetReviews(int v) { targetReviews = v; }
        public double getAmapWeight() { return amapWeight; }
        public void setAmapWeight(double v) { amapWeight = v; }
        public double getBaiduWeight() { return baiduWeight; }
        public void setBaiduWeight(double v) { baiduWeight = v; }
        public double getMatchWeight() { return matchWeight; }
        public void setMatchWeight(double v) { matchWeight = v; }
        public double getSingleSourceWeight() { return singleSourceWeight; }
        public void setSingleSourceWeight(double v) { singleSourceWeight = v; }
        public double getSingleSourceCap() { return singleSourceCap; }
        public void setSingleSourceCap(double v) { singleSourceCap = v; }
        public int getFreshnessWindowDays() { return freshnessWindowDays; }
        public void setFreshnessWindowDays(int v) { freshnessWindowDays = v; }
    }

    public static class Weights {
        private double rating = 0.20;
        private double template = 0.15;
        private double burst = 0.10;
        private double trend = 0.10;
        private double dataInsufficient = 0.25;
        private double crossPlatformConflict = 0.20;
        public double getRating() { return rating; }
        public void setRating(double v) { rating = v; }
        public double getTemplate() { return template; }
        public void setTemplate(double v) { template = v; }
        public double getBurst() { return burst; }
        public void setBurst(double v) { burst = v; }
        public double getTrend() { return trend; }
        public void setTrend(double v) { trend = v; }
        public double getDataInsufficient() { return dataInsufficient; }
        public void setDataInsufficient(double v) { dataInsufficient = v; }
        public double getCrossPlatformConflict() { return crossPlatformConflict; }
        public void setCrossPlatformConflict(double v) { crossPlatformConflict = v; }
    }

    public static class CrossPlatform {
        private double consistentMaxDifference = 0.2;
        private double conflictStartDifference = 0.7;
        private double fullRiskDifference = 1.0;
        private int conflictStartRisk = 60;
        public double getConsistentMaxDifference() { return consistentMaxDifference; }
        public void setConsistentMaxDifference(double v) { consistentMaxDifference = v; }
        public double getConflictStartDifference() { return conflictStartDifference; }
        public void setConflictStartDifference(double v) { conflictStartDifference = v; }
        public double getFullRiskDifference() { return fullRiskDifference; }
        public void setFullRiskDifference(double v) { fullRiskDifference = v; }
        public int getConflictStartRisk() { return conflictStartRisk; }
        public void setConflictStartRisk(int v) { conflictStartRisk = v; }
    }

    public static class Levels {
        private int lowMaxInclusive = 20;
        private int mediumLowMaxInclusive = 40;
        private int mediumMaxInclusive = 60;
        public int getLowMaxInclusive() { return lowMaxInclusive; }
        public void setLowMaxInclusive(int v) { lowMaxInclusive = v; }
        public int getMediumLowMaxInclusive() { return mediumLowMaxInclusive; }
        public void setMediumLowMaxInclusive(int v) { mediumLowMaxInclusive = v; }
        public int getMediumMaxInclusive() { return mediumMaxInclusive; }
        public void setMediumMaxInclusive(int v) { mediumMaxInclusive = v; }
    }
}
