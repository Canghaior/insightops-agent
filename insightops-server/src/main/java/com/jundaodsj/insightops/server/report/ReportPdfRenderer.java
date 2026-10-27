package com.jundaodsj.insightops.server.report;

import com.jundaodsj.insightops.report.application.ReportDeliveryStore;
import org.openpdf.text.Anchor;
import org.openpdf.text.Chunk;
import org.openpdf.text.Document;
import org.openpdf.text.Element;
import org.openpdf.text.Font;
import org.openpdf.text.PageSize;
import org.openpdf.text.Paragraph;
import org.openpdf.text.Phrase;
import org.openpdf.text.Rectangle;
import org.openpdf.text.pdf.BaseFont;
import org.openpdf.text.pdf.ColumnText;
import org.openpdf.text.pdf.PdfContentByte;
import org.openpdf.text.pdf.PdfPageEventHelper;
import org.openpdf.text.pdf.PdfPCell;
import org.openpdf.text.pdf.PdfPTable;
import org.openpdf.text.pdf.PdfWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Component
public class ReportPdfRenderer {
    private static final Color INK = new Color(27, 39, 42);
    private static final Color MUTED = new Color(88, 105, 109);
    private static final Color ACCENT = new Color(31, 151, 119);
    private static final Color SOFT = new Color(234, 246, 242);
    private static final Color HIGH = new Color(176, 47, 47);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private final String fontPath;

    public ReportPdfRenderer(@Value("${insightops.report.pdf-font-path:}") String fontPath) {
        this.fontPath = fontPath == null ? "" : fontPath.trim();
    }

    public byte[] render(ReportDeliveryStore.ReportRecord report) {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Document document = new Document(PageSize.A4, 52, 52, 62, 58);
            PdfWriter writer = PdfWriter.getInstance(document, output);
            BaseFont base = fontPath.isBlank()
                    ? BaseFont.createFont("STSong-Light", "UniGB-UCS2-H", false)
                    : BaseFont.createFont(fontPath, BaseFont.IDENTITY_H, BaseFont.EMBEDDED);
            writer.setPageEvent(new Footer(base));
            document.addTitle(report.title());
            document.addAuthor("InsightOps Agent");
            document.addSubject("AI 开源技术情报报告");
            document.open();

            Font eyebrow = font(base, 9, Font.BOLD, ACCENT);
            Font title = font(base, 24, Font.BOLD, INK);
            Font subtitle = font(base, 10, Font.NORMAL, MUTED);
            Font heading = font(base, 15, Font.BOLD, INK);
            Font subheading = font(base, 11, Font.BOLD, ACCENT);
            Font body = font(base, 9.5f, Font.NORMAL, INK);
            Font small = font(base, 8.5f, Font.NORMAL, MUTED);
            Font link = font(base, 8.5f, Font.UNDERLINE, ACCENT);

            if (report.research() != null) {
                renderResearch(document, writer, report.markdown(), base);
                document.close();
                return output.toByteArray();
            }

            Paragraph brand = paragraph("INSIGHTOPS · 技术情报报告", eyebrow, 0, 7);
            document.add(brand);
            document.add(paragraph(report.title(), title, 0, 9));
            document.add(paragraph("报告周期  " + time(report.periodStart()) + " - " + time(report.periodEnd())
                    + "  |  生成时间  " + time(report.createdAt()), subtitle, 0, 18));

            PdfPTable metrics = new PdfPTable(3);
            metrics.setWidthPercentage(100);
            metrics.setWidths(new float[]{1, 1, 1});
            metric(metrics, "情报数量", Integer.toString(report.itemCount()), base, false);
            metric(metrics, "高风险", Integer.toString(report.highRiskCount()), base, report.highRiskCount() > 0);
            metric(metrics, "报告类型", "专题快照", base, false);
            document.add(metrics);
            document.add(paragraph("本报告保存生成时的情报分析和官方证据快照。所有关键结论均应通过文末链接复核。",
                    body, 14, 18));

            int index = 1;
            for (var item : report.items()) {
                Paragraph itemTitle = new Paragraph();
                itemTitle.setSpacingBefore(8);
                itemTitle.setSpacingAfter(5);
                itemTitle.setKeepTogether(true);
                itemTitle.add(new Chunk(index++ + ". " + safe(item.projectName()), heading));
                if (item.versionTag() != null && !item.versionTag().isBlank()) {
                    itemTitle.add(new Chunk("  " + safe(item.versionTag()), subheading));
                }
                document.add(itemTitle);
                Color riskColor = "HIGH".equals(item.riskLevel()) ? HIGH : ACCENT;
                document.add(paragraph("类型 " + safe(item.eventType()) + "  |  风险 " + safe(item.riskLevel())
                        + "  |  建议 " + safe(item.recommendation()) + "  |  " + time(item.occurredAt()),
                        font(base, 8.5f, Font.BOLD, riskColor), 0, 7));
                document.add(paragraph(safe(item.oneLineSummary()), body, 0, 8));
                addList(document, "主要变化", item.majorChanges(), subheading, body);
                addParagraph(document, "Java 影响", item.javaImpact(), subheading, body);
                addParagraph(document, "升级价值", item.upgradeValue(), subheading, body);
                addList(document, "风险", item.risks(), subheading, body);
                addList(document, "建议行动", item.recommendedActions(), subheading, body);
                document.add(paragraph("官方证据", subheading, 5, 3));
                List<String> evidence = item.evidenceUrls().isEmpty()
                        ? List.of(item.sourceUrl()) : item.evidenceUrls();
                for (String url : evidence) {
                    if (url == null || url.isBlank()) continue;
                    Anchor anchor = new Anchor(url.trim(), link);
                    anchor.setReference(url.trim());
                    Paragraph source = new Paragraph(anchor);
                    source.setLeading(12);
                    source.setSpacingAfter(2);
                    document.add(source);
                }
                Paragraph divider = new Paragraph(" ");
                divider.setSpacingAfter(4);
                document.add(divider);
            }
            document.add(paragraph("由 InsightOps Agent 生成 · 证据优先 · 可追溯", small, 12, 0));
            document.close();
            return output.toByteArray();
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to render report PDF", exception);
        }
    }

    private static void renderResearch(Document document, PdfWriter writer, String markdown, BaseFont base) throws Exception {
        boolean code = false;
        String[] lines = markdown.split("\\R");
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index];
            if (line.startsWith("```")) { code = !code; continue; }
            if (line.isBlank()) continue;
            if (!code && line.strip().startsWith("|") && index + 1 < lines.length
                    && lines[index + 1].matches("\\s*\\|?\\s*:?-{3,}:?\\s*\\|[\\s:|\\-]*")) {
                String[] headers = tableCells(line);
                PdfPTable table = new PdfPTable(headers.length);
                table.setWidthPercentage(100);
                table.setSpacingBefore(7);
                table.setSpacingAfter(10);
                table.setHeaderRows(1);
                table.setSplitLate(false);
                addResearchRow(table, headers, base, true);
                index += 2;
                while (index < lines.length && lines[index].strip().startsWith("|")) {
                    String[] cells = tableCells(lines[index]);
                    if (cells.length != headers.length) break;
                    addResearchRow(table, cells, base, false);
                    index++;
                }
                index--;
                document.add(table);
                continue;
            }
            if (!code && line.matches("\\s*[-*_]{3,}\\s*")) continue;
            int level = line.startsWith("# ") ? 1 : line.startsWith("## ") ? 2 : line.startsWith("### ") ? 3 : 0;
            if (level > 0 && writer.getVerticalPosition(true) < 160) document.newPage();
            String content = code ? line : line.replaceFirst("^#{1,6}\\s+", "").replaceFirst("^>\\s?", "");
            Font normal = font(base, level == 1 ? (index == 0 ? 20 : 15) : level == 2 ? 14 : level == 3 ? 11 : 9.5f,
                    level > 0 ? Font.BOLD : Font.NORMAL, level > 0 ? ACCENT : INK);
            Paragraph paragraph = new Paragraph();
            paragraph.setLeading(normal.getSize() * 1.55f);
            paragraph.setSpacingBefore(level > 0 ? 12 : 0);
            paragraph.setSpacingAfter(level > 0 ? 7 : 5);
            paragraph.setKeepTogether(level > 0);
            if (code) paragraph.add(new Chunk(content, normal));
            else appendResearchInline(paragraph, content, normal, base);
            document.add(paragraph);
        }
    }

    private static String[] tableCells(String line) {
        return line.strip().replaceAll("^\\||\\|$", "").split("\\|", -1);
    }

    private static void addResearchRow(PdfPTable table, String[] values, BaseFont base, boolean header) {
        for (String value : values) {
            Paragraph paragraph = new Paragraph();
            paragraph.setLeading(12);
            appendResearchInline(paragraph, value.strip(), font(base, 8, header ? Font.BOLD : Font.NORMAL, INK), base);
            PdfPCell cell = new PdfPCell();
            cell.addElement(paragraph);
            cell.setPadding(6);
            cell.setBorderColor(new Color(208, 222, 217));
            if (header) cell.setBackgroundColor(SOFT);
            table.addCell(cell);
        }
    }

    private static void appendResearchInline(Paragraph paragraph, String text, Font normal, BaseFont base) {
        var pattern = java.util.regex.Pattern.compile(
                "\\[([^\\]\\n]+)]\\((https?://[^\\s)]+)\\)|<(https?://[^\\s>]+)>|https?://[^\\s<>\\])）},;，。]+|\\*\\*([^*\\n]+)\\*\\*|`([^`\\n]+)`");
        var matcher = pattern.matcher(text);
        int position = 0;
        while (matcher.find()) {
            paragraph.add(new Chunk(text.substring(position, matcher.start()), normal));
            if (matcher.group(4) != null) {
                paragraph.add(new Chunk(matcher.group(4), font(base, normal.getSize(), Font.BOLD, INK)));
            } else if (matcher.group(5) != null) {
                paragraph.add(new Chunk(matcher.group(5), normal));
            } else {
                String url = matcher.group(2) != null ? matcher.group(2) : matcher.group(3) != null ? matcher.group(3) : matcher.group();
                String label = matcher.group(1) != null ? "[" + matcher.group(1) + "]" : url;
                Anchor anchor = new Anchor(label, font(base, normal.getSize(), Font.UNDERLINE, ACCENT));
                anchor.setReference(url);
                paragraph.add(anchor);
            }
            position = matcher.end();
        }
        paragraph.add(new Chunk(text.substring(position), normal));
    }

    private static void metric(PdfPTable table, String label, String value, BaseFont base, boolean danger) {
        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setBackgroundColor(SOFT);
        cell.setPadding(11);
        Paragraph labelText = new Paragraph(label, font(base, 8, Font.NORMAL, MUTED));
        labelText.setSpacingAfter(3);
        cell.addElement(labelText);
        cell.addElement(new Paragraph(value, font(base, 16, Font.BOLD, danger ? HIGH : ACCENT)));
        table.addCell(cell);
    }

    private static void addParagraph(Document document, String title, String value,
                                     Font heading, Font body) throws Exception {
        if (value == null || value.isBlank()) return;
        document.add(paragraph(title, heading, 4, 2));
        document.add(paragraph(safe(value), body, 0, 5));
    }

    private static void addList(Document document, String title, List<String> values,
                                Font heading, Font body) throws Exception {
        if (values == null || values.isEmpty()) return;
        document.add(paragraph(title, heading, 4, 2));
        org.openpdf.text.List list = new org.openpdf.text.List(org.openpdf.text.List.UNORDERED);
        list.setListSymbol(new Chunk("- ", heading));
        list.setIndentationLeft(13);
        for (String value : values) {
            org.openpdf.text.ListItem item = new org.openpdf.text.ListItem(safe(value), body);
            item.setLeading(14);
            item.setSpacingAfter(2);
            list.add(item);
        }
        document.add(list);
    }

    private static Paragraph paragraph(String value, Font font, float before, float after) {
        Paragraph paragraph = new Paragraph(safe(value), font);
        paragraph.setLeading(font.getSize() * 1.55f);
        paragraph.setSpacingBefore(before);
        paragraph.setSpacingAfter(after);
        return paragraph;
    }

    private static Font font(BaseFont base, float size, int style, Color color) {
        return new Font(base, size, style, color);
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "未提供" : value.replace("\u0000", "").trim();
    }

    private static String time(java.time.Instant value) {
        return value == null ? "未提供" : DATE_TIME.format(value.atZone(ZONE));
    }

    private static final class Footer extends PdfPageEventHelper {
        private final BaseFont base;
        private Footer(BaseFont base) { this.base = base; }

        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            PdfContentByte canvas = writer.getDirectContent();
            canvas.saveState();
            canvas.setColorStroke(new Color(210, 224, 220));
            canvas.moveTo(document.left(), 38);
            canvas.lineTo(document.right(), 38);
            canvas.stroke();
            canvas.restoreState();
            Phrase footer = new Phrase("InsightOps Agent  ·  第 " + writer.getPageNumber() + " 页",
                    font(base, 8, Font.NORMAL, MUTED));
            ColumnText.showTextAligned(canvas, Element.ALIGN_RIGHT, footer,
                    document.right(), 24, 0);
        }
    }
}
