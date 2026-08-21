package com.elma.gohan.config;

import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 高德 Web Service 配置。key 只从环境变量 AMAP_KEY 读取,禁止写进仓库或日志。
 */
@ConfigurationProperties(prefix = "elma.amap")
public class AmapProperties {

    private String key = "";
    private String baseUrl = "https://restapi.amap.com";
    /** 高德分类码:餐饮服务大类。 */
    private String types = "050000";
    private int pageSize = 25;
    private int maxPages = 2;
    private int connectTimeoutMs = 3000;
    private int readTimeoutMs = 5000;
    /** 高德 typecode -> ELMA 内部品类(code 遵循 ^[A-Z][A-Z0-9_]{0,31}$)。未命中走 OTHER 兜底。 */
    private Map<String, CategoryMapping> categoryMap = Map.of();
    /** 按顺序匹配 POI 名称和 type 文本，用于在高德父分类内识别产品细品类。 */
    private List<CategoryRule> categoryRules = List.of();
    /** 餐饮身份判定；先验证大类，再允许名称关键词细分。 */
    private String restaurantTypecodePrefix = "05";
    private String restaurantTypeRoot = "餐饮服务";
    private int inferenceMinSignals = 2;
    private List<String> restaurantKeywords = List.of(
            "餐厅", "饭店", "菜馆", "酒楼", "火锅", "烧烤", "粉面", "米粉",
            "米线", "面馆", "小吃", "快餐", "咖啡", "茶饮", "甜品");
    private List<String> nonRestaurantTypeKeywords = List.of(
            "购物服务", "服装", "鞋帽", "商场", "购物中心", "超市", "便利店",
            "生活服务", "汽车服务", "住宿服务", "医疗保健", "公司企业");

    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getTypes() { return types; }
    public void setTypes(String types) { this.types = types; }
    public int getPageSize() { return pageSize; }
    public void setPageSize(int pageSize) { this.pageSize = pageSize; }
    public int getMaxPages() { return maxPages; }
    public void setMaxPages(int maxPages) { this.maxPages = maxPages; }
    public int getConnectTimeoutMs() { return connectTimeoutMs; }
    public void setConnectTimeoutMs(int connectTimeoutMs) { this.connectTimeoutMs = connectTimeoutMs; }
    public int getReadTimeoutMs() { return readTimeoutMs; }
    public void setReadTimeoutMs(int readTimeoutMs) { this.readTimeoutMs = readTimeoutMs; }
    public Map<String, CategoryMapping> getCategoryMap() { return categoryMap; }
    public void setCategoryMap(Map<String, CategoryMapping> categoryMap) { this.categoryMap = categoryMap; }
    public List<CategoryRule> getCategoryRules() { return categoryRules; }
    public void setCategoryRules(List<CategoryRule> categoryRules) { this.categoryRules = categoryRules; }
    public String getRestaurantTypecodePrefix() { return restaurantTypecodePrefix; }
    public void setRestaurantTypecodePrefix(String value) { restaurantTypecodePrefix = value; }
    public String getRestaurantTypeRoot() { return restaurantTypeRoot; }
    public void setRestaurantTypeRoot(String value) { restaurantTypeRoot = value; }
    public int getInferenceMinSignals() { return inferenceMinSignals; }
    public void setInferenceMinSignals(int value) { inferenceMinSignals = value; }
    public List<String> getRestaurantKeywords() { return restaurantKeywords; }
    public void setRestaurantKeywords(List<String> value) { restaurantKeywords = value; }
    public List<String> getNonRestaurantTypeKeywords() { return nonRestaurantTypeKeywords; }
    public void setNonRestaurantTypeKeywords(List<String> value) {
        nonRestaurantTypeKeywords = value;
    }

    public static class CategoryMapping {
        private String code;
        private String label;

        public String getCode() { return code; }
        public void setCode(String code) { this.code = code; }
        public String getLabel() { return label; }
        public void setLabel(String label) { this.label = label; }
    }

    public static class CategoryRule extends CategoryMapping {
        private List<String> keywords = List.of();

        public List<String> getKeywords() { return keywords; }
        public void setKeywords(List<String> keywords) { this.keywords = keywords; }
    }
}
