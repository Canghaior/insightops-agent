package com.jundaodsj.insightops.server.api;

import com.jundaodsj.insightops.memory.application.UserMemoryStore;
import com.jundaodsj.insightops.server.auth.CurrentAccount;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/memories")
public class UserMemoryController {
    private static final Set<String> CATEGORIES = Set.of("PROFILE", "PREFERENCE", "INTEREST", "CONSTRAINT");
    private final UserMemoryStore store;

    public UserMemoryController(UserMemoryStore store) { this.store = store; }

    @GetMapping
    public ApiResponse<List<UserMemoryStore.UserMemory>> list(HttpServletRequest request) {
        return new ApiResponse<>(traceId(request), store.list(CurrentAccount.actor(request)));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<UserMemoryStore.UserMemory> create(
            @Valid @RequestBody CreateMemoryRequest body, HttpServletRequest request) {
        try {
            var memory = store.create(
                    CurrentAccount.actor(request), UUID.randomUUID(), body.key().trim(), body.value().trim(),
                    category(body.category()), Instant.now());
            return new ApiResponse<>(traceId(request), memory);
        } catch (DataIntegrityViolationException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Memory key already exists");
        }
    }

    @PutMapping("/{memoryId}")
    public ApiResponse<UserMemoryStore.UserMemory> update(
            @PathVariable UUID memoryId, @Valid @RequestBody UpdateMemoryRequest body,
            HttpServletRequest request) {
        var memory = store.update(
                        CurrentAccount.actor(request), memoryId, body.value().trim(),
                        category(body.category()), body.enabled(), Instant.now())
                .orElseThrow(UserMemoryController::notFound);
        return new ApiResponse<>(traceId(request), memory);
    }

    @DeleteMapping("/{memoryId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID memoryId, HttpServletRequest request) {
        if (!store.delete(CurrentAccount.actor(request), memoryId)) throw notFound();
    }

    private static String category(String value) {
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!CATEGORIES.contains(normalized))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported memory category");
        return normalized;
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Memory not found");
    }

    private static String traceId(HttpServletRequest request) {
        return (String) request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);
    }

    public record CreateMemoryRequest(
            @NotBlank @Size(max = 80) String key,
            @NotBlank @Size(max = 1000) String value,
            @NotBlank String category) {}
    public record UpdateMemoryRequest(
            @NotBlank @Size(max = 1000) String value,
            @NotBlank String category,
            @NotNull Boolean enabled) {}
}
