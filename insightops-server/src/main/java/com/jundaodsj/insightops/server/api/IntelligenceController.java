package com.jundaodsj.insightops.server.api;

import com.jundaodsj.insightops.intelligence.application.IntelligenceStore;
import com.jundaodsj.insightops.server.auth.CurrentAccount;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

import static org.springframework.http.HttpStatus.NOT_FOUND;

@RestController
@RequestMapping("/api/v1/intelligence")
public class IntelligenceController {
    private final IntelligenceStore store;
    public IntelligenceController(IntelligenceStore store){this.store=store;}

    @GetMapping
    public ApiResponse<IntelligenceStore.AnalysisPage> list(
            @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size,
            @RequestParam(required=false) UUID projectId,@RequestParam(required=false) String riskLevel,
            HttpServletRequest request){
        String risk=normalizeRisk(riskLevel);
        return response(request,store.listAnalyses(CurrentAccount.actor(request),page,size,projectId,risk));
    }

    @GetMapping("/{analysisId}")
    public ApiResponse<IntelligenceStore.AnalysisDetail> detail(@PathVariable UUID analysisId,HttpServletRequest request){
        return response(request,store.findAnalysis(CurrentAccount.actor(request),analysisId)
                .orElseThrow(()->new ResponseStatusException(NOT_FOUND,"Intelligence analysis not found")));
    }

    private static String normalizeRisk(String value){
        if(value==null||value.isBlank())return null;
        String risk=value.trim().toUpperCase(java.util.Locale.ROOT);
        if(!java.util.List.of("LOW","MEDIUM","HIGH").contains(risk))
            throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,"Unsupported risk level");
        return risk;
    }
    private static <T> ApiResponse<T> response(HttpServletRequest request,T data){return new ApiResponse<>((String)request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE),data);}
}
