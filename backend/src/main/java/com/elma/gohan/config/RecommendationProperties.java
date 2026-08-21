package com.elma.gohan.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * RecommendationEngine 排序权重与 Top-K / 候选池参数。
 */
@ConfigurationProperties(prefix = "elma.recommendation")
public class RecommendationProperties {

    private String algorithmVersion = "recommendation-v0.3.2";
    private int topK = 10;
    /** 会话候选池大小:首次推荐 + 最多 5 次重新选择。 */
    private int poolSize = 6;
    private int walkingSpeedMetersPerMinute = 80;
    private Weights weights = new Weights();
    private Taste taste = new Taste();
    private CategoryConfidencePenalty categoryConfidencePenalty = new CategoryConfidencePenalty();
    private double uncertaintyRisk = 50.0;

    public String getAlgorithmVersion() { return algorithmVersion; }
    public void setAlgorithmVersion(String algorithmVersion) { this.algorithmVersion = algorithmVersion; }
    public int getTopK() { return topK; }
    public void setTopK(int topK) { this.topK = topK; }
    public int getPoolSize() { return poolSize; }
    public void setPoolSize(int poolSize) { this.poolSize = poolSize; }
    public int getWalkingSpeedMetersPerMinute() { return walkingSpeedMetersPerMinute; }
    public void setWalkingSpeedMetersPerMinute(int walkingSpeedMetersPerMinute) {
        this.walkingSpeedMetersPerMinute = walkingSpeedMetersPerMinute;
    }
    public Weights getWeights() { return weights; }
    public void setWeights(Weights weights) { this.weights = weights; }
    public Taste getTaste() { return taste; }
    public void setTaste(Taste taste) { this.taste = taste; }
    public CategoryConfidencePenalty getCategoryConfidencePenalty() { return categoryConfidencePenalty; }
    public void setCategoryConfidencePenalty(CategoryConfidencePenalty value) {
        categoryConfidencePenalty = value;
    }
    public double getUncertaintyRisk() { return uncertaintyRisk; }
    public void setUncertaintyRisk(double value) { uncertaintyRisk = value; }

    public static class Weights {
        private double rating = 25;
        private double distance = 20;
        private double budget = 15;
        private double category = 10;
        private double completeness = 10;
        private double risk = 20;

        public double getRating() { return rating; }
        public void setRating(double rating) { this.rating = rating; }
        public double getDistance() { return distance; }
        public void setDistance(double distance) { this.distance = distance; }
        public double getBudget() { return budget; }
        public void setBudget(double budget) { this.budget = budget; }
        public double getCategory() { return category; }
        public void setCategory(double category) { this.category = category; }
        public double getCompleteness() { return completeness; }
        public void setCompleteness(double completeness) { this.completeness = completeness; }
        public double getRisk() { return risk; }
        public void setRisk(double risk) { this.risk = risk; }
    }

    public static class Taste {
        private double maxAdjustment = 15;
        private double categoryWeight = 0.50;
        private double priceWeight = 0.25;
        private double distanceWeight = 0.25;
        public double getMaxAdjustment() { return maxAdjustment; }
        public void setMaxAdjustment(double v) { maxAdjustment = v; }
        public double getCategoryWeight() { return categoryWeight; }
        public void setCategoryWeight(double v) { categoryWeight = v; }
        public double getPriceWeight() { return priceWeight; }
        public void setPriceWeight(double v) { priceWeight = v; }
        public double getDistanceWeight() { return distanceWeight; }
        public void setDistanceWeight(double v) { distanceWeight = v; }
    }

    public static class CategoryConfidencePenalty {
        private double verified = 0;
        private double supported = 6;
        private double inferred = 18;

        public double getVerified() { return verified; }
        public void setVerified(double value) { verified = value; }
        public double getSupported() { return supported; }
        public void setSupported(double value) { supported = value; }
        public double getInferred() { return inferred; }
        public void setInferred(double value) { inferred = value; }
    }
}
