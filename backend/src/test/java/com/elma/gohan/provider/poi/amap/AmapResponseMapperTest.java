package com.elma.gohan.provider.poi.amap;

import static org.assertj.core.api.Assertions.assertThat;

import com.elma.gohan.config.AmapProperties;
import com.elma.gohan.domain.restaurant.CategoryConfidence;
import com.elma.gohan.domain.restaurant.DataCompleteness;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AmapResponseMapperTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AmapProperties props = defaultProps();
    private final AmapResponseMapper mapper = new AmapResponseMapper(props);

    private static AmapProperties defaultProps() {
        AmapProperties p = new AmapProperties();
        p.setCategoryMap(Map.of("050100", mapping("CHINESE", "中餐厅"),
                "050300", mapping("SNACK", "小吃快餐")));
        p.setCategoryRules(List.of(
                rule("HOT_POT", "火锅", "火锅", "麻辣烫"),
                rule("NOODLES", "粉面", "粉面", "米粉", "面馆"),
                rule("WESTERN", "西餐", "西餐", "牛排")));
        return p;
    }

    private static AmapProperties.CategoryMapping mapping(String code, String label) {
        AmapProperties.CategoryMapping m = new AmapProperties.CategoryMapping();
        m.setCode(code);
        m.setLabel(label);
        return m;
    }

    private static AmapProperties.CategoryRule rule(String code, String label, String... keywords) {
        AmapProperties.CategoryRule rule = new AmapProperties.CategoryRule();
        rule.setCode(code);
        rule.setLabel(label);
        rule.setKeywords(List.of(keywords));
        return rule;
    }

    @Test
    @DisplayName("完整 POI:名称和 type 文本优先识别细品类,location 为 经度,纬度 顺序")
    void fullMapping() throws Exception {
        var poi = objectMapper.readTree("""
                {
                  "id": "B00190.123",
                  "name": "老街牛肉粉",
                  "type": "餐饮服务;中餐厅;粉面馆",
                  "typecode": "050101",
                  "address": "麓山南路 123 号",
                  "tel": "0731-12345678",
                  "location": "112.9412,28.2291",
                  "distance": "620",
                  "biz_ext": {"rating": "4.5", "cost": "26", "opening_time": "09:00-21:00"}
                }
                """);
        var r = mapper.toRestaurant(poi);
        assertThat(r.source()).isEqualTo("AMAP");
        assertThat(r.sourcePoiId()).isEqualTo("B00190.123");
        assertThat(r.latitude()).isEqualTo(28.2291);
        assertThat(r.longitude()).isEqualTo(112.9412);
        assertThat(r.distanceMeters()).isEqualTo(620);
        assertThat(r.categoryCode()).isEqualTo("NOODLES");
        assertThat(r.categoryLabel()).isEqualTo("粉面");
        assertThat(r.categoryConfidence()).isEqualTo(CategoryConfidence.VERIFIED);
        assertThat(r.rating()).isEqualTo(4.5);
        assertThat(r.averagePrice()).isEqualTo(26);
        assertThat(r.openingHours()).isEqualTo("09:00-21:00");
        assertThat(r.telephone()).isEqualTo("0731-12345678");
        assertThat(r.dataCompleteness()).isEqualTo(DataCompleteness.FULL);
    }


    @Test
    @DisplayName("细品类未命中时仍按 typecode 父级映射")
    void fallsBackToParentCategory() throws Exception {
        var poi = objectMapper.readTree("""
                {"id":"B3","name":"家常菜馆","type":"餐饮服务;中餐厅;综合酒楼",
                 "typecode":"050199","location":"112.9,28.2","distance":"100"}
                """);

        var restaurant = mapper.toRestaurant(poi);

        assertThat(restaurant.categoryCode()).isEqualTo("CHINESE");
        assertThat(restaurant.categoryLabel()).isEqualTo("中餐厅");
    }

    @Test
    @DisplayName("biz_ext 缺失:rating/price/hours 为 null,完整度降级")
    void missingBizExt() throws Exception {
        var poi = objectMapper.readTree("""
                {
                  "id": "B2",
                  "name": "无名小店",
                  "type": "餐饮服务;小吃快餐店;米粉店",
                  "typecode": "050999",
                  "address": "",
                  "location": "112.9,28.2",
                  "distance": "100",
                  "pname": "湖南省", "cityname": "长沙市", "adname": "岳麓区"
                }
                """);
        var r = mapper.toRestaurant(poi);
        assertThat(r.rating()).isNull();
        assertThat(r.averagePrice()).isNull();
        assertThat(r.openingHours()).isNull();
        assertThat(r.address()).isEqualTo("湖南省长沙市岳麓区");
        assertThat(r.categoryCode()).isEqualTo("NOODLES");
        assertThat(r.categoryLabel()).isEqualTo("粉面");
        // rating/price/hours 三项缺失 -> MINIMAL(<=2 项缺失才是 PARTIAL)
        assertThat(r.dataCompleteness()).isEqualTo(DataCompleteness.MINIMAL);
    }

    @Test
    @DisplayName("缺少 id 或 name 的脏数据被跳过")
    void skipsInvalidEntries() throws Exception {
        var pois = List.of(
                objectMapper.readTree("{\"id\":\"\",\"name\":\"x\",\"location\":\"1,1\"}"),
                objectMapper.readTree("{\"id\":\"y\",\"name\":\"\",\"location\":\"1,1\"}"),
                objectMapper.readTree("{\"id\":\"z\",\"name\":\"ok\",\"typecode\":\"050100\","
                        + "\"location\":\"1,1\",\"distance\":\"5\"}"));
        assertThat(mapper.toRestaurants(pois)).hasSize(1);
    }

    @Test
    @DisplayName("只有一项官方餐饮分类时标记 SUPPORTED")
    void supportsSingleOfficialRestaurantSignal() throws Exception {
        var poi = objectMapper.readTree("""
                {"id":"S1","name":"老街饭店","typecode":"050101",
                 "location":"112.9,28.2","distance":"80"}
                """);

        assertThat(mapper.toRestaurant(poi).categoryConfidence())
                .isEqualTo(CategoryConfidence.SUPPORTED);
    }

    @Test
    @DisplayName("官方分类缺失但两个餐饮信号成立时标记 INFERRED")
    void infersRestaurantFromTwoSoftSignals() throws Exception {
        var poi = objectMapper.readTree("""
                {"id":"I1","name":"老街火锅","type":"火锅店",
                 "location":"112.9,28.2","distance":"80",
                 "biz_ext":{"opening_time":"10:00-22:00"}}
                """);

        assertThat(mapper.toRestaurant(poi).categoryConfidence())
                .isEqualTo(CategoryConfidence.INFERRED);
        assertThat(mapper.toRestaurant(poi).categoryCode()).isEqualTo("HOT_POT");
    }

    @Test
    @DisplayName("明确非餐饮 typecode 不得被名称中的烧烤关键词覆盖")
    void rejectsNonRestaurantBeforeKeywordMapping() throws Exception {
        var clothing = objectMapper.readTree("""
                {"id":"C1","name":"火锅少年服装店","type":"购物服务;服装鞋帽皮具店;服装店",
                 "typecode":"061200","location":"112.9,28.2","distance":"30",
                 "biz_ext":{"rating":"4.9","cost":"49"}}
                """);
        var mall = objectMapper.readTree("""
                {"id":"M1","name":"烧烤主题商场","type":"购物服务;商场;购物中心",
                 "typecode":"060100","location":"112.9,28.2","distance":"40"}
                """);
        var sparseClothing = objectMapper.readTree("""
                {"id":"C2","name":"火锅少年服装店","type":"服装店",
                 "location":"112.9,28.2","distance":"35",
                 "biz_ext":{"cost":"49","opening_time":"10:00-22:00"}}
                """);

        assertThat(mapper.toRestaurants(List.of(clothing, mall, sparseClothing))).isEmpty();
    }
}
