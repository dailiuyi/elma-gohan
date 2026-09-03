package com.elma.gohan.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 百度地点检索开关、凭据与超时配置。 */
@ConfigurationProperties(prefix = "elma.baidu")
public class BaiduProperties {

    private boolean enabled = true;
    private String ak = "";
    private String baseUrl = "https://api.map.baidu.com";
    private String query = "餐饮";
    private int pageSize = 20;
    private int nameRecallMaxRestaurants = 10;
    private int nameRecallMaxRequests = 20;
    private int nameRecallRadiusMeters = 500;
    private int recallMaxCalls = 22;
    private int recallTimeBudgetMs = 8000;
    private int rateLimitPerSecond = 3;
    private int rateLimitMaxWaitMs = 1000;
    private String region = "长沙";
    private int connectTimeoutMs = 1500;
    private int readTimeoutMs = 2500;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getAk() { return ak; }
    public void setAk(String ak) { this.ak = ak; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getQuery() { return query; }
    public void setQuery(String query) { this.query = query; }
    public int getPageSize() { return pageSize; }
    public void setPageSize(int pageSize) { this.pageSize = pageSize; }
    public int getNameRecallMaxRestaurants() { return nameRecallMaxRestaurants; }
    public void setNameRecallMaxRestaurants(int nameRecallMaxRestaurants) {
        this.nameRecallMaxRestaurants = nameRecallMaxRestaurants;
    }
    public int getNameRecallMaxRequests() { return nameRecallMaxRequests; }
    public void setNameRecallMaxRequests(int nameRecallMaxRequests) {
        this.nameRecallMaxRequests = nameRecallMaxRequests;
    }
    public int getNameRecallRadiusMeters() { return nameRecallRadiusMeters; }
    public void setNameRecallRadiusMeters(int nameRecallRadiusMeters) {
        this.nameRecallRadiusMeters = nameRecallRadiusMeters;
    }
    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region == null ? "" : region; }
    public int getRecallMaxCalls() { return recallMaxCalls; }
    public void setRecallMaxCalls(int recallMaxCalls) { this.recallMaxCalls = recallMaxCalls; }
    public int getRecallTimeBudgetMs() { return recallTimeBudgetMs; }
    public void setRecallTimeBudgetMs(int recallTimeBudgetMs) {
        this.recallTimeBudgetMs = recallTimeBudgetMs;
    }
    public int getRateLimitPerSecond() { return rateLimitPerSecond; }
    public void setRateLimitPerSecond(int rateLimitPerSecond) {
        this.rateLimitPerSecond = rateLimitPerSecond;
    }
    public int getRateLimitMaxWaitMs() { return rateLimitMaxWaitMs; }
    public void setRateLimitMaxWaitMs(int rateLimitMaxWaitMs) {
        this.rateLimitMaxWaitMs = rateLimitMaxWaitMs;
    }
    public int getConnectTimeoutMs() { return connectTimeoutMs; }
    public void setConnectTimeoutMs(int connectTimeoutMs) { this.connectTimeoutMs = connectTimeoutMs; }
    public int getReadTimeoutMs() { return readTimeoutMs; }
    public void setReadTimeoutMs(int readTimeoutMs) { this.readTimeoutMs = readTimeoutMs; }
}
