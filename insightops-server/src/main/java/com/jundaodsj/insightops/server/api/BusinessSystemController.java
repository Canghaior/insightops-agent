package com.jundaodsj.insightops.server.api;

import com.jundaodsj.insightops.businesssystem.application.BusinessSystemStore.Summary;
import com.jundaodsj.insightops.server.auth.CurrentAccount;
import com.jundaodsj.insightops.server.businesssystem.BusinessSystemService;
import com.jundaodsj.insightops.server.workflow.ResearchMaterials.Attachment;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ProblemDetail;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/business-systems")
public class BusinessSystemController {
    private final BusinessSystemService systems;

    public BusinessSystemController(BusinessSystemService systems) { this.systems = systems; }

    @GetMapping
    public ApiResponse<List<Summary>> list(HttpServletRequest request) {
        return response(request, systems.list(CurrentAccount.actor(request)));
    }

    @GetMapping("/available-uploads")
    public ApiResponse<List<Attachment>> uploads(HttpServletRequest request) {
        return response(request, systems.availableUploads(CurrentAccount.actor(request)));
    }

    @GetMapping("/{id}")
    public ApiResponse<BusinessSystemService.Detail> detail(@PathVariable UUID id, HttpServletRequest request) {
        return response(request, systems.detail(CurrentAccount.actor(request), id));
    }

    @PostMapping
    public ApiResponse<BusinessSystemService.Detail> create(@RequestBody BusinessSystemService.Draft draft, HttpServletRequest request) {
        return response(request, systems.create(CurrentAccount.actor(request), draft));
    }

    @PutMapping("/{id}")
    public ApiResponse<BusinessSystemService.Detail> update(@PathVariable UUID id,
            @RequestBody BusinessSystemService.Draft draft, HttpServletRequest request) {
        return response(request, systems.update(CurrentAccount.actor(request), id, draft));
    }

    @GetMapping("/{id}/runs")
    public ApiResponse<BusinessSystemService.History> history(@PathVariable UUID id,
            @RequestParam(defaultValue = "0") int page, HttpServletRequest request) {
        return response(request, systems.history(CurrentAccount.actor(request), id, page));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ProblemDetail invalid(IllegalArgumentException error) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, error.getMessage());
    }

    private static <T> ApiResponse<T> response(HttpServletRequest request, T value) {
        return new ApiResponse<>((String) request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE), value);
    }
}
