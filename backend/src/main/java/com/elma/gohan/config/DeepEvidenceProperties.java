package com.elma.gohan.config;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Web 深挖查询、缓存和信号词表配置。 */
@ConfigurationProperties(prefix = "elma.deep-evidence")
public class DeepEvidenceProperties {

    private boolean enabled;
    private String apiKey = "";
    private String baseUrl = "https://api.search.brave.com";
    private int connectTimeoutMs = 1500;
    private int readTimeoutMs = 3000;
    private int overallTimeoutMs = 4000;
    private int resultCount = 10;
    private int evidenceCacheHours = 12;
    private int failureCacheMinutes = 5;
    private int analysisCacheHours = 6;
    private int maxLinksPerSource = 3;
    private double entityMatchThreshold = 0.72;
    private String queryVersion = "brave-query-v0.2";
    private String analysisAlgorithmVersion = "deep-evidence-v0.1";
    private String riskAlgorithmVersion = "deep-risk-v0.1";
    private List<String> storeSuffixes = new ArrayList<>(List.of(
            "旗舰店", "总店", "分店", "门店", "店"));
    private List<String> positivePhrases = new ArrayList<>(List.of(
            "好吃", "推荐", "回购", "性价比", "分量足", "新鲜", "稳定"));
    private List<String> negativePhrases = new ArrayList<>(List.of(
            "不好吃", "踩雷", "翻车", "难吃", "不新鲜", "偏咸", "偏油", "量少", "服务差", "太贵"));
    private List<String> operationalPhrases = new ArrayList<>(List.of(
            "排队", "等位", "上菜慢", "人多"));
    private List<String> marketingPhrases = new ArrayList<>(List.of(
            "探店", "打卡", "网红", "种草", "必吃", "团购"));

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey == null ? "" : apiKey; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public int getConnectTimeoutMs() { return connectTimeoutMs; }
    public void setConnectTimeoutMs(int connectTimeoutMs) { this.connectTimeoutMs = connectTimeoutMs; }
    public int getReadTimeoutMs() { return readTimeoutMs; }
    public void setReadTimeoutMs(int readTimeoutMs) { this.readTimeoutMs = readTimeoutMs; }
    public int getOverallTimeoutMs() { return overallTimeoutMs; }
    public void setOverallTimeoutMs(int overallTimeoutMs) { this.overallTimeoutMs = overallTimeoutMs; }
    public int getResultCount() { return resultCount; }
    public void setResultCount(int resultCount) { this.resultCount = resultCount; }
    public int getEvidenceCacheHours() { return evidenceCacheHours; }
    public void setEvidenceCacheHours(int evidenceCacheHours) { this.evidenceCacheHours = evidenceCacheHours; }
    public int getFailureCacheMinutes() { return failureCacheMinutes; }
    public void setFailureCacheMinutes(int failureCacheMinutes) { this.failureCacheMinutes = failureCacheMinutes; }
    public int getAnalysisCacheHours() { return analysisCacheHours; }
    public void setAnalysisCacheHours(int analysisCacheHours) { this.analysisCacheHours = analysisCacheHours; }
    public int getMaxLinksPerSource() { return maxLinksPerSource; }
    public void setMaxLinksPerSource(int maxLinksPerSource) { this.maxLinksPerSource = maxLinksPerSource; }
    public double getEntityMatchThreshold() { return entityMatchThreshold; }
    public void setEntityMatchThreshold(double entityMatchThreshold) { this.entityMatchThreshold = entityMatchThreshold; }
    public String getQueryVersion() { return queryVersion; }
    public void setQueryVersion(String queryVersion) { this.queryVersion = queryVersion; }
    public String getAnalysisAlgorithmVersion() { return analysisAlgorithmVersion; }
    public void setAnalysisAlgorithmVersion(String analysisAlgorithmVersion) { this.analysisAlgorithmVersion = analysisAlgorithmVersion; }
    public String getRiskAlgorithmVersion() { return riskAlgorithmVersion; }
    public void setRiskAlgorithmVersion(String riskAlgorithmVersion) { this.riskAlgorithmVersion = riskAlgorithmVersion; }
    public List<String> getStoreSuffixes() { return storeSuffixes; }
    public void setStoreSuffixes(List<String> storeSuffixes) { this.storeSuffixes = storeSuffixes; }
    public List<String> getPositivePhrases() { return positivePhrases; }
    public void setPositivePhrases(List<String> positivePhrases) { this.positivePhrases = positivePhrases; }
    public List<String> getNegativePhrases() { return negativePhrases; }
    public void setNegativePhrases(List<String> negativePhrases) { this.negativePhrases = negativePhrases; }
    public List<String> getOperationalPhrases() { return operationalPhrases; }
    public void setOperationalPhrases(List<String> operationalPhrases) { this.operationalPhrases = operationalPhrases; }
    public List<String> getMarketingPhrases() { return marketingPhrases; }
    public void setMarketingPhrases(List<String> marketingPhrases) { this.marketingPhrases = marketingPhrases; }
}
