package com.elma.gohan.application;

import com.elma.gohan.controller.api.DeepEvidenceResponse.ConsumptionReference;
import com.elma.gohan.domain.restaurant.Restaurant;
import com.elma.gohan.provider.deep.WebEvidenceItem;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Claims stay attributable; snippet keywords are mentions, not verified store facts. */
public final class ConsumptionReferenceBuilder {
    private ConsumptionReferenceBuilder() { }
    public static List<ConsumptionReference> build(Restaurant restaurant, Instant observedAt,
                                                   List<WebEvidenceItem> items) {
        List<ConsumptionReference> references = new ArrayList<>();
        String observed = observedAt == null ? null : observedAt.toString();
        if (restaurant.averagePrice() != null && restaurant.averagePrice() > 0)
            references.add(new ConsumptionReference("PRICE", "平台参考人均约 ¥" + restaurant.averagePrice(),
                    "AMAP", null, null, observed));
        if (restaurant.openingHours() != null && !restaurant.openingHours().isBlank())
            references.add(new ConsumptionReference("HOURS", "平台营业时间：" + restaurant.openingHours().substring(0, Math.min(200, restaurant.openingHours().length())),
                    "AMAP", null, null, observed));
        for (WebEvidenceItem item : items) {
            String snippet = item.snippet() == null ? "" : item.snippet();
            var mentions = List.of("排队", "等位", "上菜慢", "口味", "偏咸", "偏油", "分量", "服务", "人均")
                    .stream().filter(snippet::contains).limit(3).toList();
            if (!mentions.isEmpty()) references.add(new ConsumptionReference("PUBLIC_CLUE",
                    "公开摘要提到：“" + String.join("、", mentions) + "”", item.source().name(), item.url(),
                    item.publishedAt() == null ? null : item.publishedAt().toString(),
                    item.fetchedAt() == null ? null : item.fetchedAt().toString()));
        }
        return List.copyOf(references);
    }
}
