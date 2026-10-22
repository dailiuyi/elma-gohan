package com.elma.gohan.application;

import com.elma.gohan.controller.api.SearchNotice;
import com.elma.gohan.domain.risk.RiskResult;
import com.elma.gohan.provider.evidence.EvidenceSummary;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.springframework.stereotype.Component;

/** 推荐会话快照的 JSON 存储边界；保留历史会话的缺省字段语义。 */
@Component
public class RecommendationSnapshotJson {
    private final ObjectMapper mapper;

    public RecommendationSnapshotJson(ObjectMapper mapper) { this.mapper = mapper; }

    public String encode(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalStateException("JSON 序列化失败", e); }
    }

    public <T> T read(String json, Class<T> type) {
        try { return mapper.readValue(json, type); }
        catch (JsonProcessingException e) { throw new IllegalStateException("推荐快照反序列化失败", e); }
    }

    public List<String> strings(String json) {
        try { return mapper.readValue(json, new TypeReference<List<String>>() { }); }
        catch (JsonProcessingException e) { throw new IllegalStateException("JSON 反序列化失败", e); }
    }

    public String evidence(RiskResult risk) {
        return risk.evidenceSummary() == null ? "{}" : encode(risk.evidenceSummary());
    }

    public EvidenceSummary evidenceSummary(String json) {
        return json == null || json.isBlank() || "{}".equals(json) ? null : read(json, EvidenceSummary.class);
    }

    public SearchNotice searchNotice(String json) {
        if (json == null || json.isBlank() || "{}".equals(json)) return null;
        try {
            var notice = mapper.readTree(json).path("searchNotice");
            if (!notice.isObject()) return null;
            String code = notice.path("code").asText(null);
            String message = notice.path("message").asText(null);
            return code == null || message == null ? null : new SearchNotice(code, message);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("召回诊断反序列化失败", e);
        }
    }
}
