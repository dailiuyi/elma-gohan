package com.elma.gohan.application;

import com.elma.gohan.TestRestaurants;
import com.elma.gohan.provider.deep.*;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ConsumptionReferenceBuilderTest {
    @Test void attachesActualSourceAndDatesToMentions() {
        Instant date = Instant.parse("2025-01-01T00:00:00Z");
        var item = new WebEvidenceItem(DeepEvidenceSource.BILIBILI, "探店", "https://www.bilibili.com/video/BV1",
                "不需要排队，口味偏咸", date, null, .95, List.of());
        var refs = ConsumptionReferenceBuilder.build(TestRestaurants.full("a", 4.5, 30), null, List.of(item));
        var clue = refs.stream().filter(r -> r.kind().equals("PUBLIC_CLUE")).findFirst().orElseThrow();
        assertThat(clue.text()).startsWith("公开摘要提到").doesNotContain("需要排队");
        assertThat(clue.publishedAt()).isEqualTo(date.toString());
        assertThat(clue.observedAt()).isNull();
        assertThat(clue.url()).isEqualTo(item.url());
    }
}
