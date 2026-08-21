package com.elma.gohan.controller;

import com.elma.gohan.application.RecommendationService;
import com.elma.gohan.application.DeepEvidenceService;
import com.elma.gohan.application.ValidationFailedException;
import com.elma.gohan.application.BehaviorService;
import com.elma.gohan.controller.api.CreateRecommendationRequest;
import com.elma.gohan.controller.api.DeepEvidenceResponse;
import com.elma.gohan.controller.api.FeedbackResponse;
import com.elma.gohan.controller.api.RecommendationResponse;
import com.elma.gohan.controller.api.SubmitFeedbackRequest;
import com.elma.gohan.controller.api.SubmitBehaviorRequest;
import com.elma.gohan.controller.api.BehaviorResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 四个接口严格按 contracts/openapi.yaml 的路径、请求头与响应码实现。
 */
@RestController
@RequestMapping("/api/v1")
public class RecommendationController {

    private final RecommendationService service;
    private final DeepEvidenceService deepEvidenceService;
    private final BehaviorService behaviorService;

    public RecommendationController(RecommendationService service,
                                    DeepEvidenceService deepEvidenceService,
                                    BehaviorService behaviorService) {
        this.service = service;
        this.deepEvidenceService = deepEvidenceService;
        this.behaviorService = behaviorService;
    }

    @PostMapping("/recommendations")
    public ResponseEntity<RecommendationResponse> create(
            @RequestHeader("X-Anonymous-User-Id") String anonymousUserIdHeader,
            @Valid @RequestBody CreateRecommendationRequest request) {
        UUID anonymousUserId = parseUserId(anonymousUserIdHeader);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.create(anonymousUserId, request));
    }

    @PostMapping("/recommendations/{id}/reroll")
    public RecommendationResponse reroll(
            @RequestHeader("X-Anonymous-User-Id") String anonymousUserIdHeader,
            @PathVariable("id") String id) {
        UUID anonymousUserId = parseUserId(anonymousUserIdHeader);
        return service.reroll(anonymousUserId, parseRecommendationId(id));
    }

    @PostMapping("/recommendations/{id}/feedback")
    public ResponseEntity<FeedbackResponse> feedback(
            @RequestHeader("X-Anonymous-User-Id") String anonymousUserIdHeader,
            @PathVariable("id") String id,
            @Valid @RequestBody SubmitFeedbackRequest request) {
        UUID anonymousUserId = parseUserId(anonymousUserIdHeader);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.submitFeedback(anonymousUserId, parseRecommendationId(id), request));
    }

    @PostMapping("/recommendations/{id}/behaviors")
    public ResponseEntity<BehaviorResponse> behavior(
            @RequestHeader("X-Anonymous-User-Id") String anonymousUserIdHeader,
            @PathVariable("id") String id,
            @Valid @RequestBody SubmitBehaviorRequest request) {
        UUID userId = parseUserId(anonymousUserIdHeader);
        var result = behaviorService.recordClient(userId, parseRecommendationId(id), request);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(result.response());
    }

    @PostMapping("/recommendations/{id}/deep-evidence")
    public DeepEvidenceResponse deepEvidence(
            @RequestHeader("X-Anonymous-User-Id") String anonymousUserIdHeader,
            @PathVariable("id") String id) {
        UUID anonymousUserId = parseUserId(anonymousUserIdHeader);
        return deepEvidenceService.deepen(anonymousUserId, parseRecommendationId(id));
    }

    private UUID parseUserId(String header) {
        try {
            return UUID.fromString(header);
        } catch (IllegalArgumentException e) {
            throw new ValidationFailedException("X-Anonymous-User-Id", "必须是合法的 UUID");
        }
    }

    private UUID parseRecommendationId(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new ValidationFailedException("id", "必须是合法的 UUID");
        }
    }
}
