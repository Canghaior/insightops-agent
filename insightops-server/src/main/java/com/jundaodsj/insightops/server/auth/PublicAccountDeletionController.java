package com.jundaodsj.insightops.server.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/api/v1/identity/public-account-deletion")
public class PublicAccountDeletionController {
    private final PublicAccountDeletionService service;
    public PublicAccountDeletionController(PublicAccountDeletionService service) { this.service = service; }
    @PostMapping
    public Result request(@Valid @RequestBody Request body, HttpServletRequest request) {
        return new Result(service.request(CurrentAccount.account(request), body.password(), body.mfaCode()));
    }
    public record Request(@NotBlank @Size(max=72) String password, @Size(max=32) String mfaCode) { }
    public record Result(Instant scheduledAt) { }
}
