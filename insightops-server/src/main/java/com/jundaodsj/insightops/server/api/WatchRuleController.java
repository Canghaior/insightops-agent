package com.jundaodsj.insightops.server.api;

import com.jundaodsj.insightops.intelligence.application.WatchRuleStore;
import com.jundaodsj.insightops.server.auth.CurrentAccount;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/watch-rules")
public class WatchRuleController {

    private final WatchRuleStore store;
    public WatchRuleController(WatchRuleStore store) { this.store = store; }

    @GetMapping
    public ApiResponse<List<WatchRuleStore.WatchRule>> list(HttpServletRequest request) {
        return response(request, store.list(CurrentAccount.actor(request)));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<WatchRuleStore.WatchRule> create(
            @Valid @RequestBody RuleRequest body, HttpServletRequest request) {
        try {
            return response(request, store.create(CurrentAccount.actor(request), body.command(), Instant.now()));
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
    }

    @PutMapping("/{ruleId}")
    public ApiResponse<WatchRuleStore.WatchRule> update(
            @PathVariable UUID ruleId, @Valid @RequestBody RuleRequest body, HttpServletRequest request) {
        try {
            return response(request, store.update(CurrentAccount.actor(request), ruleId, body.command(), Instant.now())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Watch rule not found")));
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
    }

    @DeleteMapping("/{ruleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID ruleId, HttpServletRequest request) {
        if (!store.delete(CurrentAccount.actor(request), ruleId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Watch rule not found");
        }
    }

    public record RuleRequest(
            @NotBlank @Size(max=128) String name,
            UUID projectId,
            @Size(max=20) List<@Size(max=80) String> keywords,
            @Size(max=20) List<@Size(max=80) String> excludedKeywords,
            @Size(max=4) List<@Size(max=48) String> eventTypes,
            int minimumImportance,
            boolean immediateNotification,
            boolean includeInDigest,
            boolean enabled) {
        WatchRuleStore.RuleCommand command() {
            return new WatchRuleStore.RuleCommand(name,projectId,keywords,excludedKeywords,eventTypes,
                    minimumImportance,immediateNotification,includeInDigest,enabled);
        }
    }

    private static <T> ApiResponse<T> response(HttpServletRequest request,T data) {
        return new ApiResponse<>((String)request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE),data);
    }
}
