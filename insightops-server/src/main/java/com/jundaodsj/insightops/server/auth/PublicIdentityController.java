package com.jundaodsj.insightops.server.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/public/identity")
public class PublicIdentityController {
    private final IdentityLifecycleService service;
    private final IdentityActionRateLimiter rateLimiter;

    public PublicIdentityController(IdentityLifecycleService service,
                                    IdentityActionRateLimiter rateLimiter) {
        this.service = service;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping("/email/verify")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void verify(@Valid @RequestBody TokenRequest body, HttpServletRequest request) {
        rateLimiter.consume("EMAIL_VERIFY", request.getRemoteAddr());
        service.verifyEmail(body.token());
    }

    @PostMapping("/password/forgot")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void forgot(@Valid @RequestBody ForgotRequest body, HttpServletRequest request) {
        rateLimiter.consume("PASSWORD_FORGOT", request.getRemoteAddr());
        service.requestPasswordReset(body.email());
    }

    @PostMapping("/password/reset")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reset(@Valid @RequestBody ResetRequest body, HttpServletRequest request) {
        rateLimiter.consume("PASSWORD_RESET", request.getRemoteAddr());
        service.resetPassword(body.token(), body.newPassword());
    }

    public record TokenRequest(@NotBlank @Size(min = 32, max = 200) String token) { }
    public record ForgotRequest(@NotBlank @Email @Size(max = 320) String email) { }
    public record ResetRequest(@NotBlank @Size(min = 32, max = 200) String token,
                               @NotBlank @Size(min = 10, max = 72) String newPassword) { }
}
