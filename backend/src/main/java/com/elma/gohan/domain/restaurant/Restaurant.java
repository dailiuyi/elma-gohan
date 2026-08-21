package com.elma.gohan.domain.restaurant;

import java.util.UUID;

/**
 * 内部标准餐厅模型:第三方数据必须先转成本模型才能进入业务核心。
 * rating/reviewCount/averagePrice/openingHours 为 null 表示上游缺失。
 * id 在持久化时分配(新建或命中已有 source+sourcePoiId);来自 Provider 时可为 null。
 */
public record Restaurant(
        UUID id,
        String source,
        String sourcePoiId,
        String name,
        double latitude,
        double longitude,
        int distanceMeters,
        String categoryCode,
        String categoryLabel,
        Double rating,
        Integer reviewCount,
        Integer averagePrice,
        BusinessStatus businessStatus,
        String openingHours,
        String address,
        String telephone,
        DataCompleteness dataCompleteness,
        CategoryConfidence categoryConfidence
) {
    public Restaurant {
        categoryConfidence = categoryConfidence == null
                ? CategoryConfidence.SUPPORTED : categoryConfidence;
    }

    /** V0.3.1 兼容构造器；既有调用默认视为有一项官方餐饮分类依据。 */
    public Restaurant(UUID id, String source, String sourcePoiId, String name,
                      double latitude, double longitude, int distanceMeters,
                      String categoryCode, String categoryLabel, Double rating,
                      Integer reviewCount, Integer averagePrice, BusinessStatus businessStatus,
                      String openingHours, String address, String telephone,
                      DataCompleteness dataCompleteness) {
        this(id, source, sourcePoiId, name, latitude, longitude, distanceMeters,
                categoryCode, categoryLabel, rating, reviewCount, averagePrice, businessStatus,
                openingHours, address, telephone, dataCompleteness, CategoryConfidence.SUPPORTED);
    }

    /** V0.2 兼容构造器；旧测试夹具和 File Evidence 不需要提供电话。 */
    public Restaurant(UUID id, String source, String sourcePoiId, String name,
                      double latitude, double longitude, int distanceMeters,
                      String categoryCode, String categoryLabel, Double rating,
                      Integer reviewCount, Integer averagePrice, BusinessStatus businessStatus,
                      String openingHours, String address, DataCompleteness dataCompleteness) {
        this(id, source, sourcePoiId, name, latitude, longitude, distanceMeters,
                categoryCode, categoryLabel, rating, reviewCount, averagePrice, businessStatus,
                openingHours, address, null, dataCompleteness, CategoryConfidence.SUPPORTED);
    }

    public Restaurant withId(UUID newId) {
        return new Restaurant(newId, source, sourcePoiId, name, latitude, longitude, distanceMeters,
                categoryCode, categoryLabel, rating, reviewCount, averagePrice, businessStatus,
                openingHours, address, telephone, dataCompleteness, categoryConfidence);
    }

    public Restaurant withDistance(int newDistance) {
        return new Restaurant(id, source, sourcePoiId, name, latitude, longitude, newDistance,
                categoryCode, categoryLabel, rating, reviewCount, averagePrice, businessStatus,
                openingHours, address, telephone, dataCompleteness, categoryConfidence);
    }

    public Restaurant withRating(Double newRating) {
        return new Restaurant(id, source, sourcePoiId, name, latitude, longitude, distanceMeters,
                categoryCode, categoryLabel, newRating, reviewCount, averagePrice, businessStatus,
                openingHours, address, telephone, dataCompleteness, categoryConfidence);
    }
}
