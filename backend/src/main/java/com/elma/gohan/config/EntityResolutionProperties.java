package com.elma.gohan.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 高德与百度门店实体匹配的权重、阈值和缓存时长。 */
@ConfigurationProperties(prefix = "elma.entity-resolution")
public class EntityResolutionProperties {

    private double nameWeight = 0.45;
    private double coordinateWeight = 0.30;
    private double addressWeight = 0.15;
    private double telephoneWeight = 0.10;
    private double acceptThreshold = 0.50;
    private double ambiguityMargin = 0.08;
    private double minimumNameSimilarity = 0.20;
    private int maximumDistanceMeters = 300;
    private double sparseMatchMinimumNameSimilarity = 0.40;
    private int sparseMatchMaximumDistanceMeters = 150;
    private int matchedTtlDays = 30;
    private int ambiguousTtlHours = 6;
    private int noMatchTtlMinutes = 30;
    private int evidenceTtlHours = 6;
    private int v2EvidenceTtlHours = 24;

    public double getNameWeight() { return nameWeight; }
    public void setNameWeight(double value) { nameWeight = value; }
    public double getCoordinateWeight() { return coordinateWeight; }
    public void setCoordinateWeight(double value) { coordinateWeight = value; }
    public double getAddressWeight() { return addressWeight; }
    public void setAddressWeight(double value) { addressWeight = value; }
    public double getTelephoneWeight() { return telephoneWeight; }
    public void setTelephoneWeight(double value) { telephoneWeight = value; }
    public double getAcceptThreshold() { return acceptThreshold; }
    public void setAcceptThreshold(double value) { acceptThreshold = value; }
    public double getAmbiguityMargin() { return ambiguityMargin; }
    public void setAmbiguityMargin(double value) { ambiguityMargin = value; }
    public double getMinimumNameSimilarity() { return minimumNameSimilarity; }
    public void setMinimumNameSimilarity(double value) { minimumNameSimilarity = value; }
    public int getMaximumDistanceMeters() { return maximumDistanceMeters; }
    public void setMaximumDistanceMeters(int value) { maximumDistanceMeters = value; }
    public double getSparseMatchMinimumNameSimilarity() {
        return sparseMatchMinimumNameSimilarity;
    }
    public void setSparseMatchMinimumNameSimilarity(double value) {
        sparseMatchMinimumNameSimilarity = value;
    }
    public int getSparseMatchMaximumDistanceMeters() {
        return sparseMatchMaximumDistanceMeters;
    }
    public void setSparseMatchMaximumDistanceMeters(int value) {
        sparseMatchMaximumDistanceMeters = value;
    }
    public int getMatchedTtlDays() { return matchedTtlDays; }
    public void setMatchedTtlDays(int value) { matchedTtlDays = value; }
    public int getAmbiguousTtlHours() { return ambiguousTtlHours; }
    public void setAmbiguousTtlHours(int value) { ambiguousTtlHours = value; }
    public int getNoMatchTtlMinutes() { return noMatchTtlMinutes; }
    public void setNoMatchTtlMinutes(int value) { noMatchTtlMinutes = value; }
    public int getEvidenceTtlHours() { return evidenceTtlHours; }
    public void setEvidenceTtlHours(int value) { evidenceTtlHours = value; }
    public int getV2EvidenceTtlHours() { return v2EvidenceTtlHours; }
    public void setV2EvidenceTtlHours(int value) { v2EvidenceTtlHours = value; }
}
