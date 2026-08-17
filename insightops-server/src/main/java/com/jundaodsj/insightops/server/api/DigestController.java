package com.jundaodsj.insightops.server.api;

import com.jundaodsj.insightops.intelligence.application.IntelligenceStore;
import com.jundaodsj.insightops.server.auth.CurrentAccount;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/digests")
public class DigestController {
    private final IntelligenceStore store;
    public DigestController(IntelligenceStore store){this.store=store;}

    @GetMapping
    public ApiResponse<IntelligenceStore.DigestPage> list(@RequestParam(defaultValue="0")int page,
            @RequestParam(defaultValue="20")int size,HttpServletRequest request){
        return response(request,store.listDigests(CurrentAccount.actor(request),page,size));
    }
    @PostMapping("/{digestId}/read") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void read(@PathVariable UUID digestId,HttpServletRequest request){
        if(!store.markDigestRead(CurrentAccount.actor(request),digestId,Instant.now()))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Digest not found");
    }
    @GetMapping("/preference")
    public ApiResponse<IntelligenceStore.DigestPreference> preference(HttpServletRequest request){
        return response(request,store.getPreference(CurrentAccount.actor(request)));
    }
    @PutMapping("/preference")
    public ApiResponse<IntelligenceStore.DigestPreference> preference(@Valid @RequestBody PreferenceRequest body,HttpServletRequest request){
        try{return response(request,store.savePreference(CurrentAccount.actor(request),body.cadence(),body.timeZone(),body.deliveryHour(),body.projectIds(),Instant.now()));}
        catch(IllegalArgumentException exception){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,exception.getMessage());}
    }
    public record PreferenceRequest(@NotBlank String cadence,@NotBlank String timeZone,
            @Min(0)@Max(23)int deliveryHour,List<UUID> projectIds){public PreferenceRequest{projectIds=projectIds==null?List.of():List.copyOf(projectIds);}}
    private static <T> ApiResponse<T> response(HttpServletRequest request,T data){return new ApiResponse<>((String)request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE),data);}
}
