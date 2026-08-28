package com.elma.gohan.provider.poi;

/** POI 召回停止原因；只有后三种表示本次搜索未完整覆盖目标环带。 */
public enum PoiSearchCompletionReason {
    RESULTS_EXHAUSTED,
    TARGET_REACHED,
    MAX_PAGES_REACHED,
    RECALL_DEADLINE_REACHED,
    UPSTREAM_RETRY_EXHAUSTED;

    public boolean incomplete() {
        return this == MAX_PAGES_REACHED
                || this == RECALL_DEADLINE_REACHED
                || this == UPSTREAM_RETRY_EXHAUSTED;
    }
}
