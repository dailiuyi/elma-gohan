package com.elma.gohan.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 评论型 Evidence Provider 的来源和加载上限配置。 */
@ConfigurationProperties(prefix = "elma.evidence")
public class EvidenceProperties {

    private String provider = "file";
    private String location = "classpath:evidence/restaurant-evidence.json";
    private int maxReviewsPerRestaurant = 200;

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }
    public int getMaxReviewsPerRestaurant() { return maxReviewsPerRestaurant; }
    public void setMaxReviewsPerRestaurant(int maxReviewsPerRestaurant) {
        this.maxReviewsPerRestaurant = maxReviewsPerRestaurant;
    }
}
