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
    public int getConnectTimeoutMs() { return connectTimeoutMs; }
    public void setConnectTimeoutMs(int connectTimeoutMs) { this.connectTimeoutMs = connectTimeoutMs; }
    public int getReadTimeoutMs() { return readTimeoutMs; }
    public void setReadTimeoutMs(int readTimeoutMs) { this.readTimeoutMs = readTimeoutMs; }
}
