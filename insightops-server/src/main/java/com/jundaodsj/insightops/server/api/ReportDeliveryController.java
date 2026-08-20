package com.jundaodsj.insightops.server.api;

import com.jundaodsj.insightops.report.application.ReportDeliveryStore;
import com.jundaodsj.insightops.server.auth.CurrentAccount;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class ReportDeliveryController {
    private final ReportDeliveryStore store;

    public ReportDeliveryController(ReportDeliveryStore store) { this.store = store; }

    @GetMapping("/delivery-channels")
    public ApiResponse<List<ReportDeliveryStore.DeliveryChannel>> channels(HttpServletRequest request) {
        return response(request, store.listChannels(CurrentAccount.actor(request)));
    }

    @PostMapping("/delivery-channels")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ReportDeliveryStore.DeliveryChannel> createChannel(
            @Valid @RequestBody ChannelRequest body, HttpServletRequest request) {
        try {
            return response(request, store.createChannel(CurrentAccount.actor(request), UUID.randomUUID(),
                    body.name(), body.endpointUrl(), body.enabled(), Instant.now()));
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
    }

    @PutMapping("/delivery-channels/{channelId}")
    public ApiResponse<ReportDeliveryStore.DeliveryChannel> updateChannel(
            @PathVariable UUID channelId, @Valid @RequestBody ChannelRequest body,
            HttpServletRequest request) {
        try {
            return response(request, store.updateChannel(CurrentAccount.actor(request), channelId,
                    body.name(), body.endpointUrl(), body.enabled(), Instant.now()).orElseThrow(
                    () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Delivery channel not found")));
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
    }

    @DeleteMapping("/delivery-channels/{channelId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteChannel(@PathVariable UUID channelId, HttpServletRequest request) {
        if (!store.deleteChannel(CurrentAccount.actor(request), channelId, Instant.now())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Delivery channel not found");
        }
    }

    @PostMapping("/reports/{reportId}/deliveries")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<ReportDeliveryStore.DeliveryRecord> deliver(
            @PathVariable UUID reportId, @Valid @RequestBody DeliveryRequest body,
            HttpServletRequest request) {
        try {
            return response(request, store.enqueueDelivery(CurrentAccount.actor(request), reportId,
                    body.channelId(), Instant.now()).orElseThrow(() -> new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Report or delivery channel not found")));
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, exception.getMessage());
        }
    }

    @GetMapping("/report-deliveries")
    public ApiResponse<ReportDeliveryStore.DeliveryPage> deliveries(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int size,
            @RequestParam(required = false) UUID reportId, HttpServletRequest request) {
        return response(request, store.listDeliveries(CurrentAccount.actor(request), page, size, reportId));
    }

    @PostMapping("/report-deliveries/{deliveryId}/retry")
    public ApiResponse<ReportDeliveryStore.DeliveryRecord> retry(
            @PathVariable UUID deliveryId, HttpServletRequest request) {
        return response(request, store.retryDelivery(CurrentAccount.actor(request), deliveryId, Instant.now())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.CONFLICT, "Only a failed delivery can be retried")));
    }

    public record ChannelRequest(
            @NotBlank @Size(max = 100) String name,
            @Size(max = 2048) String endpointUrl,
            boolean enabled) { }

    public record DeliveryRequest(@jakarta.validation.constraints.NotNull UUID channelId) { }

    private static <T> ApiResponse<T> response(HttpServletRequest request, T data) {
        return new ApiResponse<>((String) request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE), data);
    }
}
