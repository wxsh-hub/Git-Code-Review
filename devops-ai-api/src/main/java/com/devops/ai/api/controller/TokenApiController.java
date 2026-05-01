package com.devops.ai.api.controller;

import com.devops.ai.api.dto.ApiResponse;
import com.devops.ai.infrastructure.entity.ApiToken;
import com.devops.ai.infrastructure.repository.ApiTokenRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/tokens")
@Tag(name = "Token 管理", description = "API Token 管理接口")
public class TokenApiController {

    private final ApiTokenRepository apiTokenRepository;

    public TokenApiController(ApiTokenRepository apiTokenRepository) {
        this.apiTokenRepository = apiTokenRepository;
    }

    @PostMapping
    @Operation(summary = "创建 API Token")
    public ResponseEntity<ApiResponse<ApiToken>> createToken(
            @RequestParam(defaultValue = "API Token") String description,
            @RequestParam(defaultValue = "365") int expireDays) {

        ApiToken token = new ApiToken();
        token.setToken(UUID.randomUUID().toString().replace("-", ""));
        token.setDescription(description);
        token.setActive(true);

        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_YEAR, expireDays);
        token.setExpiresAt(cal.getTime());

        apiTokenRepository.save(token);

        return ResponseEntity.ok(ApiResponse.success("Token 创建成功", token));
    }

    @GetMapping
    @Operation(summary = "获取所有 Token")
    public ResponseEntity<ApiResponse<List<ApiToken>>> listTokens() {
        List<ApiToken> tokens = apiTokenRepository.findAll();
        return ResponseEntity.ok(ApiResponse.success(tokens));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除 Token")
    public ResponseEntity<ApiResponse<Void>> deleteToken(@PathVariable Long id) {
        apiTokenRepository.deleteById(id);
        return ResponseEntity.ok(ApiResponse.success("Token 已删除", null));
    }

    @PostMapping("/{id}/revoke")
    @Operation(summary = "吊销 Token")
    public ResponseEntity<ApiResponse<Void>> revokeToken(@PathVariable Long id) {
        ApiToken token = apiTokenRepository.findById(id).orElse(null);
        if (token == null) {
            return ResponseEntity.status(404).body(ApiResponse.error("Token 不存在"));
        }
        token.setActive(false);
        apiTokenRepository.save(token);
        return ResponseEntity.ok(ApiResponse.success("Token 已吊销", null));
    }
}
