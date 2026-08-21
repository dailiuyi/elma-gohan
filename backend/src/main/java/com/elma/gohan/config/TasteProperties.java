package com.elma.gohan.config;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "elma.taste")
public class TasteProperties {
    private String algorithmVersion = "taste-v0.1";
    private int halfLifeDays = 90;
    private double maxAbsoluteWeight = 3.0;
    private int confidenceTargetSamples = 5;
    private int implicitWindowDays = 30;
    private int implicitThreshold = 3;
    private double maxImplicitBatchAdjustment = 0.25;
    private Feedback feedback = new Feedback();
    private Behavior behavior = new Behavior();
    private List<Integer> priceBandUpperBounds = new ArrayList<>(List.of(30, 60, 100));
    private List<Integer> distanceBandUpperBounds = new ArrayList<>(List.of(500, 1000, 2000));

    public String getAlgorithmVersion() { return algorithmVersion; }
    public void setAlgorithmVersion(String v) { algorithmVersion = v; }
    public int getHalfLifeDays() { return halfLifeDays; }
    public void setHalfLifeDays(int v) { halfLifeDays = v; }
    public double getMaxAbsoluteWeight() { return maxAbsoluteWeight; }
    public void setMaxAbsoluteWeight(double v) { maxAbsoluteWeight = v; }
    public int getConfidenceTargetSamples() { return confidenceTargetSamples; }
    public void setConfidenceTargetSamples(int v) { confidenceTargetSamples = v; }
    public int getImplicitWindowDays() { return implicitWindowDays; }
    public void setImplicitWindowDays(int v) { implicitWindowDays = v; }
    public int getImplicitThreshold() { return implicitThreshold; }
    public void setImplicitThreshold(int v) { implicitThreshold = v; }
    public double getMaxImplicitBatchAdjustment() { return maxImplicitBatchAdjustment; }
    public void setMaxImplicitBatchAdjustment(double v) { maxImplicitBatchAdjustment = v; }
    public Feedback getFeedback() { return feedback; }
    public void setFeedback(Feedback v) { feedback = v; }
    public Behavior getBehavior() { return behavior; }
    public void setBehavior(Behavior v) { behavior = v; }
    public List<Integer> getPriceBandUpperBounds() { return priceBandUpperBounds; }
    public void setPriceBandUpperBounds(List<Integer> v) { priceBandUpperBounds = v; }
    public List<Integer> getDistanceBandUpperBounds() { return distanceBandUpperBounds; }
    public void setDistanceBandUpperBounds(List<Integer> v) { distanceBandUpperBounds = v; }

    public static class Feedback {
        private double like = 1.0;
        private double normal = 0.1;
        private double dislike = -1.0;
        public double getLike() { return like; }
        public void setLike(double v) { like = v; }
        public double getNormal() { return normal; }
        public void setNormal(double v) { normal = v; }
        public double getDislike() { return dislike; }
        public void setDislike(double v) { dislike = v; }
    }

    public static class Behavior {
        private double accept = 0.1;
        private double navigate = 0.1;
        private double reroll = -0.1;
        private double skip = -0.05;
        public double getAccept() { return accept; }
        public void setAccept(double v) { accept = v; }
        public double getNavigate() { return navigate; }
        public void setNavigate(double v) { navigate = v; }
        public double getReroll() { return reroll; }
        public void setReroll(double v) { reroll = v; }
        public double getSkip() { return skip; }
        public void setSkip(double v) { skip = v; }
    }
}
