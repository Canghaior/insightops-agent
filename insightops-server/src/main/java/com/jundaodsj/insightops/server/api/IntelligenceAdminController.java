package com.jundaodsj.insightops.server.api;

import com.jundaodsj.insightops.identity.application.AccountWorkspaceStore;
import com.jundaodsj.insightops.intelligence.application.IntelligenceStore;
import com.jundaodsj.insightops.server.auth.CurrentAccount;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/intelligence")
public class IntelligenceAdminController {
    private final IntelligenceStore store;
    public IntelligenceAdminController(IntelligenceStore store){this.store=store;}
    @GetMapping public ApiResponse<AdminOverview> overview(@RequestParam(defaultValue="100")int limit,HttpServletRequest request){
        var account=requireAdmin(request);
        Instant dayStart=java.time.LocalDate.now(ZoneOffset.UTC).atStartOfDay().toInstant(ZoneOffset.UTC);
        return response(request,new AdminOverview(store.analysisMetrics(account.workspaceId(),dayStart),store.adminStatuses(account.workspaceId(),limit)));
    }
    @PostMapping("/events/{eventId}/analyze") @ResponseStatus(HttpStatus.ACCEPTED)
    public void analyze(@PathVariable UUID eventId,HttpServletRequest request){
        var account=requireAdmin(request);
        if(!store.requestAnalysis(account.workspaceId(),eventId,account.userId(),Instant.now()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Analysis already completed or running, or event was not found");
    }
    private static AccountWorkspaceStore.AccountRecord requireAdmin(HttpServletRequest request){
        var account=CurrentAccount.account(request);
        if(!"SYSTEM_ADMIN".equals(account.systemRole()))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Intelligence administration requires a system administrator");
        return account;
    }
    public record AdminOverview(IntelligenceStore.AnalysisMetrics metrics,List<IntelligenceStore.AdminAnalysisStatus> items){}
    private static <T> ApiResponse<T> response(HttpServletRequest request,T data){return new ApiResponse<>((String)request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE),data);}
}
