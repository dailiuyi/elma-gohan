package com.elma.gohan.provider.deep;

/** 深挖流程支持的公开内容来源。 */
public enum DeepEvidenceSource {
    BILIBILI("site:bilibili.com/video"),
    XIAOHONGSHU("site:xiaohongshu.com"),
    DIANPING("site:dianping.com");

    private final String siteQuery;

    DeepEvidenceSource(String siteQuery) {
        this.siteQuery = siteQuery;
    }

    public String siteQuery() {
        return siteQuery;
    }
}
