package com.jundaodsj.insightops.server.api;

import com.jundaodsj.insightops.intelligence.application.IntelligenceStore;
import com.jundaodsj.insightops.server.auth.CurrentAccount;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {
    private final IntelligenceStore store;
    public NotificationController(IntelligenceStore store){this.store=store;}
    @GetMapping public ApiResponse<IntelligenceStore.NotificationPage> list(@RequestParam(defaultValue="0")int page,
            @RequestParam(defaultValue="20")int size,@RequestParam(defaultValue="false")boolean unreadOnly,HttpServletRequest request){
        return response(request,store.listNotifications(CurrentAccount.actor(request),page,size,unreadOnly));
    }
    @GetMapping("/unread-count") public ApiResponse<Unread> unread(HttpServletRequest request){return response(request,new Unread(store.unreadNotifications(CurrentAccount.actor(request))));}
    @PostMapping("/{id}/read") @ResponseStatus(HttpStatus.NO_CONTENT) public void read(@PathVariable UUID id,HttpServletRequest request){
        if(!store.markNotificationRead(CurrentAccount.actor(request),id,Instant.now()))throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Notification not found");
    }
    public record Unread(long count){}
    private static <T> ApiResponse<T> response(HttpServletRequest request,T data){return new ApiResponse<>((String)request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE),data);}
}
