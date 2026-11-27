package com.jundaodsj.insightops.server.api;

import com.jundaodsj.insightops.infrastructure.config.ResearchModelCatalog;
import com.jundaodsj.insightops.server.auth.CurrentAccount;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class ResearchModelController {
    private final ResearchModelCatalog catalog;

    public ResearchModelController(ResearchModelCatalog catalog) { this.catalog = catalog; }

    @GetMapping("/api/v1/research-models")
    public ApiResponse<Catalog> list(HttpServletRequest request) {
        CurrentAccount.actor(request);
        var options = catalog.available().stream().map(m -> new Option(m.id(), m.label(), m.provider())).toList();
        return new ApiResponse<>((String) request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE),
                new Catalog(catalog.defaultLabel(), options));
    }

    public record Option(String id, String label, String provider) { }
    public record Catalog(String defaultLabel, List<Option> options) { }
}
