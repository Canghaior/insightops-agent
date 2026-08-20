package com.jundaodsj.insightops.server.report;

import com.jundaodsj.insightops.identity.application.ActorContext;
import com.jundaodsj.insightops.report.application.ReportDeliveryStore;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class ReportService {
    private static final List<String> EVENT_TYPES = List.of(
            "GITHUB_RELEASE", "GITHUB_ISSUE", "GITHUB_PULL_REQUEST", "GITHUB_SECURITY_ADVISORY");
    private final ReportDeliveryStore store;
    private final ReportMarkdownRenderer markdown = new ReportMarkdownRenderer();
    private final ReportPdfRenderer pdf;

    public ReportService(ReportDeliveryStore store, ReportPdfRenderer pdf) {
        this.store = store;
        this.pdf = pdf;
    }

    public ReportDeliveryStore.ReportRecord create(ActorContext actor, String title,
                                                    Instant periodStart, Instant periodEnd,
                                                    List<UUID> projectIds, List<String> eventTypes,
                                                    int maxItems, Instant now) {
        String safeTitle = title == null ? "" : title.replace("\u0000", "").trim();
        if (safeTitle.isBlank() || safeTitle.length() > 200) {
            throw new IllegalArgumentException("Report title must contain 1 to 200 characters");
        }
        if (periodStart == null || periodEnd == null || !periodEnd.isAfter(periodStart)) {
            throw new IllegalArgumentException("Report period is invalid");
        }
        if (Duration.between(periodStart, periodEnd).compareTo(Duration.ofDays(366)) > 0) {
            throw new IllegalArgumentException("Report period cannot exceed 366 days");
        }
        int safeMax = Math.max(1, Math.min(100, maxItems));
        List<String> safeTypes = eventTypes == null ? List.of() : eventTypes.stream()
                .map(value -> value == null ? "" : value.trim().toUpperCase(java.util.Locale.ROOT))
                .distinct().toList();
        if (!EVENT_TYPES.containsAll(safeTypes)) throw new IllegalArgumentException("Unsupported report event type");
        var query = new ReportDeliveryStore.ReportQuery(safeTitle, periodStart, periodEnd,
                projectIds, safeTypes, safeMax);
        var items = store.selectReportItems(actor, query);
        if (items.isEmpty()) throw new IllegalArgumentException("No completed intelligence items match this report period");
        return store.createReport(actor, UUID.randomUUID(), query, items,
                markdown.render(query, items), now);
    }

    public byte[] pdf(ActorContext actor, UUID reportId) {
        return pdf.render(store.findReport(actor, reportId).orElseThrow(ReportNotFoundException::new));
    }

    public String markdown(ActorContext actor, UUID reportId) {
        return store.findReport(actor, reportId).orElseThrow(ReportNotFoundException::new).markdown();
    }

    public static final class ReportNotFoundException extends RuntimeException { }
}
