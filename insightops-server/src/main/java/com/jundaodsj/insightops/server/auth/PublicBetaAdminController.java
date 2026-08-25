package com.jundaodsj.insightops.server.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/public-beta")
public class PublicBetaAdminController {
    private final PublicBetaService service;

    public PublicBetaAdminController(PublicBetaService service) { this.service = service; }

    @GetMapping
    public PublicBetaService.AdminStatus status(HttpServletRequest request) {
        return service.adminStatus(CurrentAccount.account(request));
    }

    @PatchMapping
    public PublicBetaService.AdminStatus update(@Valid @RequestBody UpdateRequest body,
                                                HttpServletRequest request) {
        return service.updateControl(CurrentAccount.account(request), body.registrationEnabled(),
                body.runsEnabled(), body.statusMessage());
    }

    public record UpdateRequest(boolean registrationEnabled, boolean runsEnabled,
                                @Size(max = 500) String statusMessage) { }
}
