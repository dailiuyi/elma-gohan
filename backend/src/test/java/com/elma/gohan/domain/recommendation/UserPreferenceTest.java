package com.elma.gohan.domain.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.elma.gohan.TestRestaurants;
import com.elma.gohan.domain.restaurant.SearchCondition;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class UserPreferenceTest {
    @Test
    void freezesFlavorValuesWithoutChangingTheirIterationOrder() {
        var restaurant = TestRestaurants.full("p0", 4.8, 300);
        var tags = new LinkedHashSet<>(List.of(FlavorTag.SPICY, FlavorTag.SALTY, FlavorTag.SWEET));
        var now = LocalDateTime.of(2026,10,22,12,0);
        var preference = new UserPreference(new SearchCondition(1000, null, "ANY", List.of()),
                TasteProfile.empty(now), Map.of("AMAP:p0", tags), RecentFoodHistory.empty(now));
        tags.clear();
        assertThat(preference.flavorTags(restaurant)).containsExactly(FlavorTag.SPICY, FlavorTag.SALTY, FlavorTag.SWEET);
        assertThatThrownBy(() -> preference.flavorTags(restaurant).clear()).isInstanceOf(UnsupportedOperationException.class);
    }
}
