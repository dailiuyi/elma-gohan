package com.elma.gohan.controller;

import com.elma.gohan.application.UserDataDeletionService;
import com.elma.gohan.application.ValidationFailedException;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 匿名用户隐私数据管理接口。 */
@RestController
@RequestMapping("/api/v1/users/me")
public class UserDataController {

    private final UserDataDeletionService deletionService;

    public UserDataController(UserDataDeletionService deletionService) {
        this.deletionService = deletionService;
    }

    @DeleteMapping("/data")
    public ResponseEntity<Void> deleteData(
            @RequestHeader("X-Anonymous-User-Id") String anonymousUserIdHeader) {
        deletionService.deleteAll(parseUserId(anonymousUserIdHeader));
        return ResponseEntity.noContent().build();
    }

    private UUID parseUserId(String header) {
        try {
            return UUID.fromString(header);
        } catch (IllegalArgumentException e) {
            throw new ValidationFailedException("X-Anonymous-User-Id", "必须是合法的 UUID");
        }
    }
}
