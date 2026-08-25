package com.jundaodsj.insightops.server.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/public/identity/registration")
public class PublicBetaController {
    private final PublicBetaService service;
    private final IdentityActionRateLimiter rateLimiter;

    public PublicBetaController(PublicBetaService service, IdentityActionRateLimiter rateLimiter) {
        this.service = service;
        this.rateLimiter = rateLimiter;
    }

    @GetMapping("/status")
    public PublicBetaService.Status status() { return service.status(); }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public PublicBetaService.RegistrationResult register(@Valid @RequestBody RegisterRequest body,
                                                         HttpServletRequest request) {
        String remoteAddress = remoteAddress(request);
        rateLimiter.consume("PUBLIC_REGISTER", remoteAddress);
        return service.register(new PublicBetaService.RegistrationRequest(body.username(), body.displayName(),
                body.email(), body.password(), body.turnstileToken(), body.ageConfirmed(),
                body.termsAccepted(), body.privacyAccepted(), body.acceptableUseAccepted()),
                remoteAddress, request.getHeader("User-Agent"));
    }

    private static String remoteAddress(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded == null || forwarded.isBlank()) return request.getRemoteAddr();
        int comma = forwarded.lastIndexOf(',');
        return (comma < 0 ? forwarded : forwarded.substring(comma + 1)).trim();
    }

    public record RegisterRequest(
            @NotBlank @Size(min = 3, max = 64) String username,
            @NotBlank @Size(max = 128) String displayName,
            @NotBlank @Email @Size(max = 320) String email,
            @NotBlank @Size(min = 10, max = 72) String password,
            @NotBlank @Size(max = 4096) String turnstileToken,
            boolean ageConfirmed, boolean termsAccepted,
            boolean privacyAccepted, boolean acceptableUseAccepted) { }
}
