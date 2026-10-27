package com.jundaodsj.insightops.server.api;

import com.jundaodsj.insightops.server.auth.CurrentAccount;
import com.jundaodsj.insightops.server.workflow.UpgradeAssessmentService;
import com.jundaodsj.insightops.tool.application.registry.AgentToolDefinition;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/upgrade-assessments")
public class UpgradeAssessmentController {
    private final UpgradeAssessmentService service;
    public UpgradeAssessmentController(UpgradeAssessmentService service) { this.service = service; }
    @PostMapping("/setup")
    public ApiResponse<?> setup(HttpServletRequest request) {
        return response(request, service.setup(CurrentAccount.actor(request)));
    }
    @GetMapping("/versions")
    public ApiResponse<?> versions(@RequestParam UUID projectId, HttpServletRequest request) {
        try { return response(request, service.versions(CurrentAccount.actor(request), projectId)); }
        catch (IllegalArgumentException exception) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage()); }
        catch (com.jundaodsj.insightops.tool.application.github.GitHubToolException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Release 列表暂时无法读取，可以手动填写目标版本。");
        }
    }
    @GetMapping("/presets")
    public ApiResponse<?> presets(HttpServletRequest request) {
        return response(request, service.presets(CurrentAccount.actor(request)));
    }
    @PostMapping("/presets")
    public ApiResponse<?> savePreset(@RequestBody SavePreset input, HttpServletRequest request) {
        try { return response(request, service.savePreset(CurrentAccount.actor(request), input.name(), input.input())); }
        catch (IllegalArgumentException exception) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage()); }
    }
    public record SavePreset(String name, UpgradeAssessmentService.Command input) { }
    @PostMapping("/runs")
    public ApiResponse<?> launch(@RequestBody UpgradeAssessmentService.Command command, HttpServletRequest request) {
        var account = CurrentAccount.account(request);
        var access = "SYSTEM_ADMIN".equals(account.systemRole()) ? AgentToolDefinition.AccessLevel.SYSTEM_ADMIN
                : "OWNER".equals(account.role()) ? AgentToolDefinition.AccessLevel.WORKSPACE_OWNER : AgentToolDefinition.AccessLevel.WORKSPACE_MEMBER;
        if (command.requestId() == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少请求标识，请刷新页面重试。");
        try { return response(request, service.launch(account.actor(), "SYSTEM_ADMIN".equals(account.systemRole()), access,
                command, (String) request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE))); }
        catch (IllegalArgumentException exception) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage()); }
    }
    private ApiResponse<?> response(HttpServletRequest request, Object value) {
        return new ApiResponse<>((String) request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE), value);
    }
}
