package com.elma.gohan.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 推荐排序、候选池和安全探索参数。 */
@ConfigurationProperties(prefix = "elma.recommendation")
public class RecommendationProperties {
    private String algorithmVersion = "recommendation-v0.4.1";
    private int topK = 10;
    private int poolSize = 6;
    private int walkingSpeedMetersPerMinute = 80;
    private double uncertaintyRisk = 50.0;
    private double explorationRate = 0.20;
    private double explorationMinimumConfidence = 0.60;
    private double explorationBonus = 5.0;
    private double explorationNegativePreferenceThreshold = -0.5;
    private Weights weights = new Weights();
    private CategoryConfidencePenalty categoryConfidencePenalty = new CategoryConfidencePenalty();

    public String getAlgorithmVersion() { return algorithmVersion; }
    public void setAlgorithmVersion(String v) { algorithmVersion = v; }
    public int getTopK() { return topK; }
    public void setTopK(int v) { topK = v; }
    public int getPoolSize() { return poolSize; }
    public void setPoolSize(int v) { poolSize = v; }
    public int getWalkingSpeedMetersPerMinute() { return walkingSpeedMetersPerMinute; }
    public void setWalkingSpeedMetersPerMinute(int v) { walkingSpeedMetersPerMinute = v; }
    public double getUncertaintyRisk() { return uncertaintyRisk; }
    public void setUncertaintyRisk(double v) { uncertaintyRisk = v; }
    public double getExplorationRate() { return explorationRate; }
    public void setExplorationRate(double v) { explorationRate = v; }
    public double getExplorationMinimumConfidence() { return explorationMinimumConfidence; }
    public void setExplorationMinimumConfidence(double v) { explorationMinimumConfidence = v; }
    public double getExplorationBonus() { return explorationBonus; }
    public void setExplorationBonus(double v) { explorationBonus = v; }
    public double getExplorationNegativePreferenceThreshold() { return explorationNegativePreferenceThreshold; }
    public void setExplorationNegativePreferenceThreshold(double v) { explorationNegativePreferenceThreshold = v; }
    public Weights getWeights() { return weights; }
    public void setWeights(Weights v) { weights = v; }
    public CategoryConfidencePenalty getCategoryConfidencePenalty() { return categoryConfidencePenalty; }
    public void setCategoryConfidencePenalty(CategoryConfidencePenalty v) { categoryConfidencePenalty = v; }

    /** LowRegretScore 各分项权重。 */
    public static class Weights {
        private double restaurantQuality = 25;
        private double riskSafety = 25;
        private double tasteMatch = 20;
        private double budgetFit = 12;
        private double distanceFit = 10;
        private double recentDiversity = 8;
        public double getRestaurantQuality() { return restaurantQuality; }
        public void setRestaurantQuality(double v) { restaurantQuality = v; }
        public double getRiskSafety() { return riskSafety; }
        public void setRiskSafety(double v) { riskSafety = v; }
        public double getTasteMatch() { return tasteMatch; }
        public void setTasteMatch(double v) { tasteMatch = v; }
        public double getBudgetFit() { return budgetFit; }
        public void setBudgetFit(double v) { budgetFit = v; }
        public double getDistanceFit() { return distanceFit; }
        public void setDistanceFit(double v) { distanceFit = v; }
        public double getRecentDiversity() { return recentDiversity; }
        public void setRecentDiversity(double v) { recentDiversity = v; }
    }

    /** 餐饮分类可信度对应的排序惩罚。 */
    public static class CategoryConfidencePenalty {
        private double verified = 0;
        private double supported = 6;
        private double inferred = 18;
        public double getVerified() { return verified; }
        public void setVerified(double v) { verified = v; }
        public double getSupported() { return supported; }
        public void setSupported(double v) { supported = v; }
        public double getInferred() { return inferred; }
        public void setInferred(double v) { inferred = v; }
    }
}
