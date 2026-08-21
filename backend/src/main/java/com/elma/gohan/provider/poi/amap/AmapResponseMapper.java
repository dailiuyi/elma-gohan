package com.elma.gohan.provider.poi.amap;

import com.elma.gohan.config.AmapProperties;
import com.elma.gohan.domain.restaurant.BusinessStatus;
import com.elma.gohan.domain.restaurant.CategoryConfidence;
import com.elma.gohan.domain.restaurant.DataCompleteness;
import com.elma.gohan.domain.restaurant.Restaurant;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.EnumMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 高德原始 POI -> 内部 Restaurant 标准模型。原始结构不得进入业务核心或接口响应。
 */
@Component
public class AmapResponseMapper {

    private static final Logger log = LoggerFactory.getLogger(AmapResponseMapper.class);
    public static final String SOURCE = "AMAP";
    private static final String FALLBACK_CATEGORY_CODE = "OTHER";

    private final AmapProperties props;

    public AmapResponseMapper(AmapProperties props) {
        this.props = props;
    }

    public Restaurant toRestaurant(JsonNode poi) {
        CategoryConfidence categoryConfidence = classifyRestaurant(poi);
        if (categoryConfidence == null) {
            throw new IllegalArgumentException("POI 不属于可确认的餐饮范围");
        }
        String poiId = poi.path("id").asText("");
        String name = truncate(poi.path("name").asText(""), 120);
        // 高德 location 格式为 "经度,纬度"(GCJ-02,直接透传)
        String[] lngLat = poi.path("location").asText(",").split(",");
        double longitude = parseDouble(lngLat[0]);
        double latitude = parseDouble(lngLat.length > 1 ? lngLat[1] : "0");
        int distance = parseInt(poi.path("distance").asText("0"));

        JsonNode bizExt = poi.path("biz_ext");
        Double rating = parseNullableDouble(bizExt.path("rating").asText(null));
        Integer averagePrice = parseNullableInt(bizExt.path("cost").asText(null));
        String openingHours = blankToNull(truncate(bizExt.path("opening_time").asText(""), 255));

        String address = buildAddress(poi);
        String telephone = blankToNull(truncate(poi.path("tel").asText(""), 128));
        Category category = resolveCategory(poi);

        DataCompleteness completeness = completeness(rating, averagePrice, openingHours, address);

        return new Restaurant(
                null, SOURCE, poiId, name, latitude, longitude, distance,
                category.code(), category.label(),
                rating, null, averagePrice,
                BusinessStatus.UNKNOWN, openingHours, address, telephone, completeness,
                categoryConfidence);
    }

    public List<Restaurant> toRestaurants(List<JsonNode> pois) {
        List<Restaurant> result = new ArrayList<>(pois.size());
        Map<CategoryConfidence, Integer> confidenceCounts = new EnumMap<>(CategoryConfidence.class);
        int invalidIdentity = 0;
        int invalidRequiredFields = 0;
        for (JsonNode poi : pois) {
            if (poi.path("id").asText("").isBlank() || poi.path("name").asText("").isBlank()) {
                invalidRequiredFields++;
                continue;
            }
            CategoryConfidence confidence = classifyRestaurant(poi);
            if (confidence == null) {
                invalidIdentity++;
                continue;
            }
            Restaurant restaurant = toRestaurant(poi);
            result.add(restaurant);
            confidenceCounts.merge(confidence, 1, Integer::sum);
        }
        log.info("高德 POI 分类汇总: rawCount={}, acceptedCount={}, nonRestaurantDropped={}, "
                        + "invalidRequiredFields={}, verifiedCount={}, supportedCount={}, inferredCount={}",
                pois.size(), result.size(), invalidIdentity, invalidRequiredFields,
                confidenceCounts.getOrDefault(CategoryConfidence.VERIFIED, 0),
                confidenceCounts.getOrDefault(CategoryConfidence.SUPPORTED, 0),
                confidenceCounts.getOrDefault(CategoryConfidence.INFERRED, 0));
        return result;
    }

    private CategoryConfidence classifyRestaurant(JsonNode poi) {
        String typecode = poi.path("typecode").asText("").trim();
        String type = poi.path("type").asText("").trim();
        boolean hasTypeHierarchy = type.contains(";");
        String rootType = hasTypeHierarchy ? type.substring(0, type.indexOf(';')).trim() : type;
        boolean hasTypecode = !typecode.isBlank();
        boolean typecodeFood = hasTypecode
                && typecode.startsWith(props.getRestaurantTypecodePrefix());
        boolean hasRootType = hasTypeHierarchy || props.getRestaurantTypeRoot().equals(rootType);
        boolean rootTypeFood = hasRootType && props.getRestaurantTypeRoot().equals(rootType);

        // 明确的非餐饮官方分类优先级最高，名称关键词不能覆盖。
        if ((hasTypecode && !typecodeFood) || (hasRootType && !rootTypeFood)
                || containsNonRestaurantTypeKeyword(type)) {
            return null;
        }
        if (typecodeFood && rootTypeFood) return CategoryConfidence.VERIFIED;
        if (typecodeFood || rootTypeFood) return CategoryConfidence.SUPPORTED;

        int softSignals = 0;
        if (containsRestaurantKeyword(poi.path("name").asText(""))) softSignals++;
        if (containsRestaurantKeyword(type)) softSignals++;
        JsonNode bizExt = poi.path("biz_ext");
        if (hasText(bizExt, "cost") || hasText(bizExt, "opening_time")) softSignals++;
        return softSignals >= props.getInferenceMinSignals()
                ? CategoryConfidence.INFERRED : null;
    }

    private boolean containsRestaurantKeyword(String value) {
        if (value == null || value.isBlank()) return false;
        return props.getRestaurantKeywords().stream()
                .filter(keyword -> keyword != null && !keyword.isBlank())
                .anyMatch(value::contains);
    }

    private boolean containsNonRestaurantTypeKeyword(String value) {
        if (value == null || value.isBlank()) return false;
        return props.getNonRestaurantTypeKeywords().stream()
                .filter(keyword -> keyword != null && !keyword.isBlank())
                .anyMatch(value::contains);
    }

    private boolean hasText(JsonNode node, String field) {
        String value = node.path(field).asText("");
        return !value.isBlank() && !"[]".equals(value);
    }

    private Category resolveCategory(JsonNode poi) {
        String typecode = poi.path("typecode").asText("");
        String searchable = poi.path("name").asText("") + ";" + poi.path("type").asText("");
        AmapProperties.CategoryMapping mapping = props.getCategoryRules().stream()
                .filter(rule -> rule.getKeywords().stream()
                        .filter(keyword -> keyword != null && !keyword.isBlank())
                        .anyMatch(searchable::contains))
                .findFirst()
                .orElse(null);
        if (mapping == null) mapping = props.getCategoryMap().get(typecode);
        if (mapping == null && typecode.length() >= 6) {
            // 高德常返回 050101 等叶子编码;产品映射配置使用 050100 级父编码。
            String parentTypecode = typecode.substring(0, 4) + "00";
            mapping = props.getCategoryMap().get(parentTypecode);
        }
        if (mapping != null) {
            return new Category(mapping.getCode(), truncate(mapping.getLabel(), 30));
        }
        String type = poi.path("type").asText("");
        String label = type.contains(";") ? type.substring(0, type.indexOf(';')) : type;
        if (label.isBlank()) {
            label = "餐饮";
        }
        return new Category(FALLBACK_CATEGORY_CODE, truncate(label, 30));
    }

    private String buildAddress(JsonNode poi) {
        String address = poi.path("address").asText("");
        if (!address.isBlank()) {
            return truncate(address, 255);
        }
        StringBuilder sb = new StringBuilder();
        for (String field : List.of("pname", "cityname", "adname")) {
            String part = poi.path(field).asText("");
            if (!part.isBlank() && sb.indexOf(part) < 0) {
                sb.append(part);
            }
        }
        return truncate(sb.toString(), 255);
    }

    private static DataCompleteness completeness(Double rating, Integer price, String hours, String address) {
        int missing = 0;
        if (rating == null) missing++;
        if (price == null) missing++;
        if (hours == null) missing++;
        if (address == null || address.isBlank()) missing++;
        if (missing == 0) return DataCompleteness.FULL;
        return missing <= 2 ? DataCompleteness.PARTIAL : DataCompleteness.MINIMAL;
    }

    private record Category(String code, String label) { }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static double parseDouble(String s) {
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static Double parseNullableDouble(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer parseNullableInt(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return (int) Math.round(Double.parseDouble(s));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
