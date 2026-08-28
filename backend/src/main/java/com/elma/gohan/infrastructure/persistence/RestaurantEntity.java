package com.elma.gohan.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;
import com.elma.gohan.domain.restaurant.BusinessStatus;
import com.elma.gohan.domain.restaurant.DataCompleteness;
import com.elma.gohan.domain.restaurant.CategoryConfidence;

/** 标准化餐厅主实体。 */
@Entity
@Table(name = "restaurant")
public class RestaurantEntity {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "source", length = 16, nullable = false)
    private String source;

    @Column(name = "source_poi_id", length = 64, nullable = false)
    private String sourcePoiId;

    @Column(name = "name", length = 120, nullable = false)
    private String name;

    @Column(name = "latitude", nullable = false)
    private double latitude;

    @Column(name = "longitude", nullable = false)
    private double longitude;

    @Column(name = "category_code", length = 32, nullable = false)
    private String categoryCode;

    @Column(name = "category_label", length = 30, nullable = false)
    private String categoryLabel;

    @Column(name = "rating")
    private Double rating;

    @Column(name = "review_count")
    private Integer reviewCount;

    @Column(name = "average_price")
    private Integer averagePrice;

    @Column(name = "business_status", length = 16, nullable = false)
    @Enumerated(EnumType.STRING)
    private BusinessStatus businessStatus;

    @Column(name = "opening_hours", length = 255)
    private String openingHours;

    @Column(name = "address", length = 255)
    private String address;

    @Column(name = "telephone", length = 128)
    private String telephone;

    @Column(name = "data_completeness", length = 16, nullable = false)
    @Enumerated(EnumType.STRING)
    private DataCompleteness dataCompleteness;

    @Column(name = "category_confidence", length = 16, nullable = false)
    @Enumerated(EnumType.STRING)
    private CategoryConfidence categoryConfidence;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public RestaurantEntity() {
    }

    public RestaurantEntity(UUID id, String source, String sourcePoiId, String name, double latitude,
                            double longitude, String categoryCode, String categoryLabel, Double rating,
                            Integer reviewCount, Integer averagePrice, BusinessStatus businessStatus,
                            String openingHours, String address, String telephone,
                            DataCompleteness dataCompleteness, CategoryConfidence categoryConfidence,
                            LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.source = source;
        this.sourcePoiId = sourcePoiId;
        this.name = name;
        this.latitude = latitude;
        this.longitude = longitude;
        this.categoryCode = categoryCode;
        this.categoryLabel = categoryLabel;
        this.rating = rating;
        this.reviewCount = reviewCount;
        this.averagePrice = averagePrice;
        this.businessStatus = businessStatus;
        this.openingHours = openingHours;
        this.address = address;
        this.telephone = telephone;
        this.dataCompleteness = dataCompleteness;
        this.categoryConfidence = categoryConfidence;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public UUID getId() { return id; }
    public String getSource() { return source; }
    public String getSourcePoiId() { return sourcePoiId; }
    public String getName() { return name; }
    public double getLatitude() { return latitude; }
    public double getLongitude() { return longitude; }
    public String getCategoryCode() { return categoryCode; }
    public String getCategoryLabel() { return categoryLabel; }
    public Double getRating() { return rating; }
    public Integer getReviewCount() { return reviewCount; }
    public Integer getAveragePrice() { return averagePrice; }
    public BusinessStatus getBusinessStatus() { return businessStatus; }
    public String getOpeningHours() { return openingHours; }
    public String getAddress() { return address; }
    public String getTelephone() { return telephone; }
    public DataCompleteness getDataCompleteness() { return dataCompleteness; }
    public CategoryConfidence getCategoryConfidence() { return categoryConfidence; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
