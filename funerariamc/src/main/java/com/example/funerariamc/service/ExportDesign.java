package com.example.funerariamc.service;

import com.example.funerariamc.service.BrandingService.BrandView;
import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.Image;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.ColumnText;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfGState;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPCellEvent;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfPageEventHelper;
import com.lowagie.text.pdf.PdfWriter;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.ClientAnchor;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFClientAnchor;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFDrawing;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import java.awt.Color;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Diseño de los archivos exportados (PDF y Excel) igual al de las pantallas: banda oscura con el lirio y
 * el logo de la funeraria, tarjetas de indicadores, tarjetas blancas redondeadas para cada sección y
 * tablas con encabezado suave. Toma los colores, el nombre y el logo de la marca guardada en Parámetros,
 * así que una funeraria con colores propios recibe sus reportes con esos colores.
 */
public final class ExportDesign {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    /** Excel no incrusta fuentes: se usa la misma que la app tiene de respaldo y que trae Windows. */
    private static final String EXCEL_FONT = "Segoe UI";
    private static volatile Fonts fonts;

    private ExportDesign() {
    }

    /** Tono del detalle de un indicador: verde si mejora, terracota si empeora. */
    public enum Tone { NEUTRAL, UP, DOWN }

    /** Un indicador como las tarjetas de la banda superior de cada módulo. */
    public record Kpi(String caption, String value, String detail, Tone tone) {

        public Kpi(String caption, String value, String detail) {
            this(caption, value, detail, Tone.NEUTRAL);
        }
    }

    /** Colores del reporte sacados de la paleta de la marca (los mismos de app.css). */
    record Palette(Color primary, Color accent, Color detail, Color ink, Color muted, Color faint, Color line,
                   Color soft, Color card, Color background, Color goldWash, Color goldText, Color up, Color down,
                   Color alert) {

        static Palette of(BrandView brand) {
            BrandPalette p = brand == null ? BrandPalette.defaults() : brand.palette();
            return new Palette(color(p, "-color-primary", "#182F25"), color(p, "-color-accent", "#465E47"),
                    color(p, "-color-detail", "#C4AF84"), color(p, "-color-ink", "#1E2A22"),
                    color(p, "-color-text-muted", "#6A7366"), color(p, "-color-text-faint", "#8A917F"),
                    color(p, "-color-line", "#D5DACB"), color(p, "-color-soft", "#EEF0E6"),
                    color(p, "-color-card", "#FAF9F6"), color(p, "-color-background", "#F4F1EC"),
                    color(p, "-color-gold-wash", "#F1EAD9"), color(p, "-color-gold-text", "#8C7648"),
                    color(p, "-color-success-bg", "#CFE3C4"), color(p, "-color-alert-soft", "#F0C9BD"),
                    color(p, "-color-alert", "#8A4B3C"));
        }

        private static Color color(BrandPalette palette, String name, String fallback) {
            String value = palette.get(name);
            return hex(value != null && value.startsWith("#") && value.length() == 7 ? value : fallback);
        }
    }

    static Color hex(String value) {
        return new Color(Integer.parseInt(value.substring(1), 16));
    }

    /** Mezcla dos colores: amount 0 = base, 1 = over. Sirve para los blancos translúcidos de la banda. */
    static Color mix(Color base, Color over, double amount) {
        return new Color((int) Math.round(base.getRed() + (over.getRed() - base.getRed()) * amount),
                (int) Math.round(base.getGreen() + (over.getGreen() - base.getGreen()) * amount),
                (int) Math.round(base.getBlue() + (over.getBlue() - base.getBlue()) * amount));
    }

    /** Logo de la funeraria; si no tiene uno propio, el emblema del lirio. */
    static byte[] logoBytes(BrandView brand) {
        if (brand != null && brand.hasCustomLogo()) {
            return brand.logo();
        }
        return resource("/images/emblema.png");
    }

    /** Lirio de la banda: el de El Edén, el propio de la funeraria o ninguno, como en las pantallas. */
    static byte[] bandBytes(BrandView brand) {
        if (brand == null) {
            return resource("/images/lirio-banda.png");
        }
        if (!brand.showBandImage()) {
            return null;
        }
        return brand.hasCustomBandImage() ? brand.bandImage() : resource("/images/lirio-banda.png");
    }

    static byte[] resource(String path) {
        try (InputStream in = ExportDesign.class.getResourceAsStream(path)) {
            return in == null ? null : in.readAllBytes();
        } catch (IOException e) {
            return null;
        }
    }

    // =====================================================================================
    // PDF
    // =====================================================================================

    /** Abre un PDF A4 con la banda de la marca, el pie de página y las fuentes de la app. */
    public static PdfReport pdf(BrandView brand, String fullName, OutputStream out, String reportName) {
        BrandView b = brand == null ? BrandingService.defaults() : brand;
        return new PdfReport(b, fullName == null ? b.fullName() : fullName, out, reportName, 30);
    }

    /**
     * Abre un PDF A4 con márgenes de documento, para contratos: se arma con membrete(), párrafos,
     * datos y cláusulas en vez de la banda y las tarjetas de los reportes.
     */
    public static PdfReport document(BrandView brand, String fullName, OutputStream out, String reportName) {
        BrandView b = brand == null ? BrandingService.defaults() : brand;
        return new PdfReport(b, fullName == null ? b.fullName() : fullName, out, reportName, 44);
    }

    /** Marca guardada; si el servicio no está o no responde, la de El Edén. */
    public static BrandView brandOf(BrandingService service) {
        BrandView brand = null;
        try {
            brand = service == null ? null : service.current();
        } catch (RuntimeException ignored) {
            // Sin marca guardada el reporte sale con la de El Edén.
        }
        return brand == null ? BrandingService.defaults() : brand;
    }

    /** "Funeraria Jardines de Paz" -> "Jardines de Paz" para el nombre grande de la banda. */
    static String shortName(String fullName) {
        return fullName.toLowerCase(java.util.Locale.ROOT).startsWith("funeraria ")
                ? fullName.substring("funeraria ".length()) : fullName;
    }

    /** Fuentes de la app incrustadas en el PDF (Montserrat); si no cargan se usa Helvetica. */
    private record Fonts(BaseFont regular, BaseFont medium, BaseFont semi, BaseFont bold) {
    }

    private static Fonts fonts() {
        Fonts loaded = fonts;
        if (loaded == null) {
            synchronized (ExportDesign.class) {
                if (fonts == null) {
                    fonts = new Fonts(base("Montserrat-Regular.ttf"), base("Montserrat-Medium.ttf"),
                            base("Montserrat-SemiBold.ttf"), base("Montserrat-Bold.ttf"));
                }
                loaded = fonts;
            }
        }
        return loaded;
    }

    private static BaseFont base(String file) {
        try {
            byte[] bytes = resource("/fonts/" + file);
            if (bytes != null) {
                return BaseFont.createFont(file, BaseFont.IDENTITY_H, BaseFont.EMBEDDED, true, bytes, null);
            }
        } catch (Exception ignored) {
            // Sin la fuente el reporte sale igual con Helvetica.
        }
        try {
            return BaseFont.createFont(BaseFont.HELVETICA, BaseFont.WINANSI, BaseFont.NOT_EMBEDDED);
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo cargar ninguna fuente para el PDF.", e);
        }
    }

    /** Reporte PDF en construcción: banda, secciones en tarjetas y tablas. */
    public static final class PdfReport {

        private final Document document;
        private final Palette c;
        private final BrandView brand;
        private final String fullName;
        private final String reportName;
        private final Fonts f = fonts();

        private PdfReport(BrandView brand, String fullName, OutputStream out, String reportName, float margin) {
            this.brand = brand;
            this.fullName = fullName;
            this.reportName = reportName;
            this.c = Palette.of(brand);
            this.document = new Document(PageSize.A4, margin, margin, margin, 42 + margin - 30);
            PdfWriter writer = PdfWriter.getInstance(document, out);
            writer.setPageEvent(new Footer());
            document.addTitle(fullName + " · " + reportName);
            document.addCreator(fullName);
            document.open();
        }

        Font font(BaseFont base, float size, Color color) {
            return new Font(base, size, Font.NORMAL, color);
        }

        /**
         * Banda superior como la de cada módulo: logo, nombre de la funeraria, título, período y las
         * tarjetas de indicadores. La primera tarjeta va destacada (fondo claro) si highlightFirst es true.
         */
        public void hero(String title, String subtitle, List<Kpi> kpis, boolean highlightFirst) {
            PdfPTable band = new PdfPTable(1);
            band.setWidthPercentage(100);
            PdfPCell cell = new PdfPCell();
            cell.setBorder(Rectangle.NO_BORDER);
            cell.setPadding(18);
            cell.setPaddingBottom(20);
            cell.setCellEvent(new Band(c.primary(), bandBytes(brand)));

            PdfPTable top = new PdfPTable(new float[]{0.55f, 5f, 3.2f});
            top.setWidthPercentage(100);
            PdfPCell logo = new PdfPCell();
            logo.setBorder(Rectangle.NO_BORDER);
            logo.setPadding(0);
            logo.setVerticalAlignment(Element.ALIGN_MIDDLE);
            Image image = image(logoBytes(brand));
            if (image != null) {
                image.scaleToFit(30, 30);
                logo.addElement(image);
            }
            top.addCell(logo);
            PdfPCell name = new PdfPCell();
            name.setBorder(Rectangle.NO_BORDER);
            name.setPaddingLeft(6);
            name.setVerticalAlignment(Element.ALIGN_MIDDLE);
            Paragraph caption = new Paragraph(spaced("FUNERARIA"), font(f.bold(), 6.5f, c.detail()));
            caption.setLeading(8);
            name.addElement(caption);
            String commercial = shortName(fullName);
            Paragraph brandName = new Paragraph(commercial, font(f.semi(), 13, mix(c.primary(), Color.WHITE, 0.96)));
            brandName.setLeading(15);
            name.addElement(brandName);
            top.addCell(name);
            PdfPCell report = new PdfPCell(new Phrase(reportName, font(f.medium(), 8.5f, mix(c.primary(), Color.WHITE, 0.85))));
            report.setBorder(Rectangle.NO_BORDER);
            report.setHorizontalAlignment(Element.ALIGN_RIGHT);
            report.setVerticalAlignment(Element.ALIGN_MIDDLE);
            report.setCellEvent(new Pill(mix(c.primary(), Color.WHITE, 0.10), mix(c.primary(), Color.WHITE, 0.24), true));
            report.setPaddingRight(10);
            report.setPaddingBottom(5);
            top.addCell(report);
            cell.addElement(top);

            Paragraph heading = new Paragraph(title, font(f.semi(), 22, mix(c.primary(), Color.WHITE, 0.96)));
            heading.setSpacingBefore(14);
            heading.setLeading(26);
            cell.addElement(heading);
            Paragraph sub = new Paragraph(subtitle, font(f.regular(), 8.5f, mix(c.primary(), Color.WHITE, 0.72)));
            sub.setLeading(12);
            sub.setSpacingAfter(12);
            cell.addElement(sub);

            if (kpis != null && !kpis.isEmpty()) {
                PdfPTable row = new PdfPTable(kpis.size());
                row.setWidthPercentage(100);
                for (int i = 0; i < kpis.size(); i++) {
                    row.addCell(kpiCell(kpis.get(i), highlightFirst && i == 0, kpis.size()));
                }
                cell.addElement(row);
            }
            band.addCell(cell);
            add(band);
        }

        private PdfPCell kpiCell(Kpi kpi, boolean highlight, int count) {
            Color fill = highlight ? mix(c.card(), c.detail(), 0.18) : mix(c.primary(), Color.WHITE, 0.07);
            Color stroke = highlight ? mix(c.card(), c.detail(), 0.45) : mix(c.primary(), Color.WHITE, 0.20);
            Color captionColor = highlight ? c.goldText() : mix(c.primary(), Color.WHITE, 0.88);
            Color valueColor = highlight ? c.ink() : mix(c.primary(), Color.WHITE, 0.97);
            Color detailColor = highlight ? c.muted() : mix(c.primary(), Color.WHITE, 0.72);
            if (kpi.tone() == Tone.UP) {
                detailColor = highlight ? c.accent() : c.up();
            } else if (kpi.tone() == Tone.DOWN) {
                detailColor = highlight ? c.alert() : c.down();
            }
            PdfPCell cell = new PdfPCell();
            cell.setBorder(Rectangle.NO_BORDER);
            cell.setPadding(10);
            cell.setPaddingTop(9);
            cell.setPaddingBottom(11);
            cell.setCellEvent(new Rounded(fill, stroke, 9, 3, PdfPTable.BACKGROUNDCANVAS));
            Paragraph caption = new Paragraph(kpi.caption(), font(f.bold(), 6.5f, captionColor));
            caption.setLeading(9);
            cell.addElement(caption);
            // El valor va en una sola línea: se achica la letra hasta que quepa en la tarjeta.
            String text = kpi.value().replace(' ', '\u00A0');
            float available = (document.right() - document.left() - 36) / count - 26;
            float size = count >= 5 ? 15 : 18;
            while (size > 9 && f.semi().getWidthPoint(text, size) > available) {
                size -= 0.5f;
            }
            Paragraph value = new Paragraph(text, font(f.semi(), size, valueColor));
            value.setLeading(size + 4);
            cell.addElement(value);
            Paragraph detail = new Paragraph(kpi.detail() == null ? "" : kpi.detail(), font(f.regular(), 7, detailColor));
            detail.setLeading(9);
            cell.addElement(detail);
            return cell;
        }

        /** Nota corta en gris debajo de la banda (por ejemplo, qué no cuenta en los totales). */
        public void note(String text) {
            Paragraph p = new Paragraph(text, font(f.regular(), 7.5f, c.muted()));
            p.setSpacingBefore(8);
            add(p);
        }

        /** Empieza una tarjeta blanca con título y subtítulo y una tabla con estos encabezados. */
        public PdfTable table(String title, String subtitle, float[] widths, String... headers) {
            return new PdfTable(this, title, subtitle, widths, headers);
        }

        /**
         * Membrete de documento: logo y nombre de la funeraria a la izquierda, el número a la derecha,
         * una línea del color de la marca y el título centrado.
         */
        public void letterhead(String title, String subtitle) {
            PdfPTable top = new PdfPTable(new float[]{0.5f, 5f, 3f});
            top.setWidthPercentage(100);
            PdfPCell logo = new PdfPCell();
            logo.setBorder(Rectangle.NO_BORDER);
            logo.setPadding(0);
            logo.setVerticalAlignment(Element.ALIGN_MIDDLE);
            Image image = image(logoBytes(brand));
            if (image != null) {
                image.scaleToFit(28, 28);
                logo.addElement(image);
            }
            top.addCell(logo);
            PdfPCell name = new PdfPCell();
            name.setBorder(Rectangle.NO_BORDER);
            name.setPaddingLeft(6);
            name.setVerticalAlignment(Element.ALIGN_MIDDLE);
            Paragraph caption = new Paragraph(spaced("FUNERARIA"), font(f.bold(), 6.5f, c.goldText()));
            caption.setLeading(8);
            name.addElement(caption);
            Paragraph brandName = new Paragraph(shortName(fullName), font(f.semi(), 13, c.primary()));
            brandName.setLeading(15);
            name.addElement(brandName);
            top.addCell(name);
            PdfPCell number = new PdfPCell(new Phrase(reportName, font(f.medium(), 9, c.muted())));
            number.setBorder(Rectangle.NO_BORDER);
            number.setHorizontalAlignment(Element.ALIGN_RIGHT);
            number.setVerticalAlignment(Element.ALIGN_MIDDLE);
            top.addCell(number);
            PdfPCell rule = new PdfPCell();
            rule.setColspan(3);
            rule.setBorder(Rectangle.BOTTOM);
            rule.setBorderColor(c.primary());
            rule.setBorderWidth(1.2f);
            rule.setFixedHeight(10);
            top.addCell(rule);
            add(top);

            Paragraph heading = new Paragraph(title.toUpperCase(java.util.Locale.ROOT), font(f.semi(), 14, c.primary()));
            heading.setAlignment(Element.ALIGN_CENTER);
            heading.setSpacingBefore(12);
            heading.setLeading(18);
            add(heading);
            if (subtitle != null && !subtitle.isBlank()) {
                Paragraph sub = new Paragraph(subtitle, font(f.regular(), 9, c.muted()));
                sub.setAlignment(Element.ALIGN_CENTER);
                sub.setLeading(13);
                add(sub);
            }
        }

        /** Título de una parte del documento, en mayúsculas y con el color de la marca. */
        public void section(String text) {
            Paragraph p = new Paragraph(text.toUpperCase(java.util.Locale.ROOT), font(f.bold(), 8.5f, c.primary()));
            p.setSpacingBefore(8);
            p.setSpacingAfter(2);
            add(p);
        }

        /**
         * Datos como en un formulario impreso: "Nombre: valor" sobre una línea delgada, de a dos por
         * renglón. Se pasan en pares: etiqueta, valor, etiqueta, valor...
         */
        public void fields(String... labelsAndValues) {
            PdfPTable t = new PdfPTable(2);
            t.setWidthPercentage(100);
            t.setSpacingBefore(2);
            int count = labelsAndValues.length / 2;
            for (int i = 0; i < count; i++) {
                Phrase phrase = new Phrase();
                phrase.add(new Phrase(labelsAndValues[i * 2] + ": ", font(f.semi(), 8.5f, c.ink())));
                String value = labelsAndValues[i * 2 + 1];
                phrase.add(new Phrase(value == null || value.isBlank() ? "—" : value, font(f.regular(), 8.5f, c.ink())));
                PdfPCell cell = new PdfPCell(phrase);
                cell.setBorder(Rectangle.BOTTOM);
                cell.setBorderColor(c.line());
                cell.setBorderWidth(0.5f);
                cell.setPaddingLeft(0);
                cell.setPaddingRight(14);
                cell.setPaddingTop(4);
                cell.setPaddingBottom(3);
                // un dato que queda solo al final ocupa todo el renglón
                if (i == count - 1 && count % 2 == 1) {
                    cell.setColspan(2);
                }
                t.addCell(cell);
            }
            add(t);
        }

        /** Lista numerada (1., 2., 3.) con sangría, por ejemplo las personas cubiertas. */
        public void numbered(List<String> items) {
            for (int i = 0; i < items.size(); i++) {
                Paragraph p = new Paragraph();
                p.setLeading(12);
                p.setSpacingBefore(i == 0 ? 3 : 1);
                p.setIndentationLeft(14);
                p.setFirstLineIndent(-14);
                p.add(new Phrase((i + 1) + ".  ", font(f.semi(), 8.5f, c.primary())));
                p.add(new Phrase(items.get(i).replace("$ ", "$\u00A0"), font(f.regular(), 8.5f, c.ink())));
                add(p);
            }
        }

        /** Renglón con el valor a la derecha unido por puntos, como en una factura escrita. */
        public void amountLine(String text, String amount, boolean strong) {
            Paragraph p = new Paragraph();
            p.setLeading(13);
            p.setSpacingBefore(strong ? 6 : 2);
            Font font = strong ? font(f.semi(), 9.5f, c.ink()) : font(f.regular(), 9, c.ink());
            p.add(new Phrase(text + " ", font));
            com.lowagie.text.pdf.draw.DottedLineSeparator dots = new com.lowagie.text.pdf.draw.DottedLineSeparator();
            dots.setGap(2.5f);
            dots.setLineColor(c.line());
            dots.setOffset(2.5f);
            p.add(new com.lowagie.text.Chunk(dots));
            p.add(new Phrase(" " + amount.replace("$ ", "$\u00A0"), font));
            add(p);
        }

        /** Título de un bloque de texto, del mismo tamaño que el de las tarjetas. */
        public void heading(String text) {
            Paragraph p = new Paragraph(text, font(f.semi(), 12, c.ink()));
            p.setSpacingBefore(16);
            p.setSpacingAfter(2);
            add(p);
        }

        /** Texto corrido justificado, por ejemplo la presentación de las partes de un contrato. */
        public void paragraph(String text) {
            Paragraph p = new Paragraph(text, font(f.regular(), 8.5f, c.ink()));
            p.setLeading(12);
            p.setSpacingBefore(8);
            p.setAlignment(Element.ALIGN_JUSTIFIED);
            add(p);
        }

        /** Cláusula numerada: "PRIMERA. Objeto." resaltado y luego el texto en la misma línea. */
        public void clause(String number, String title, String text) {
            Paragraph p = new Paragraph();
            p.setLeading(12);
            p.setSpacingBefore(4);
            p.setAlignment(Element.ALIGN_JUSTIFIED);
            p.add(new Phrase(number + ". " + title + ". ", font(f.bold(), 8.5f, c.primary())));
            p.add(new Phrase(text.replace("$ ", "$\u00A0"), font(f.regular(), 8.5f, c.ink())));
            add(p);
        }

        /**
         * Firmas: una columna por parte, con espacio para firmar, línea, nombre y los datos debajo. La
         * frase de cierre va encima y en la misma hoja que las firmas.
         */
        public void signatures(String closing, List<List<String>> parties) {
            // entre parte y parte va una columna vacía para que las líneas de firma no se toquen
            float[] widths = new float[parties.size() * 2 - 1];
            for (int i = 0; i < widths.length; i++) {
                widths[i] = i % 2 == 0 ? 1f : 0.18f;
            }
            PdfPTable t = new PdfPTable(widths);
            t.setWidthPercentage(100);
            t.setSpacingBefore(6);
            t.setKeepTogether(true);
            PdfPCell lead = new PdfPCell(new Phrase(closing, font(f.regular(), 8.5f, c.ink())));
            lead.setColspan(widths.length);
            lead.setBorder(Rectangle.NO_BORDER);
            lead.setPadding(0);
            lead.setPaddingBottom(26);
            t.addCell(lead);
            for (List<String> party : parties) {
                if (party != parties.get(0)) {
                    PdfPCell gap = new PdfPCell();
                    gap.setBorder(Rectangle.NO_BORDER);
                    t.addCell(gap);
                }
                PdfPCell cell = new PdfPCell();
                cell.setBorder(Rectangle.TOP);
                cell.setBorderColor(c.ink());
                cell.setBorderWidth(0.8f);
                cell.setPaddingTop(5);
                cell.setPaddingLeft(0);
                cell.setPaddingRight(0);
                for (int i = 0; i < party.size(); i++) {
                    Paragraph p = new Paragraph(party.get(i), i == 0 ? font(f.bold(), 7, c.muted())
                            : i == 1 ? font(f.semi(), 9.5f, c.ink()) : font(f.regular(), 8, c.muted()));
                    p.setLeading(i == 1 ? 13 : 11);
                    cell.addElement(p);
                }
                t.addCell(cell);
            }
            add(t);
        }

        void add(Element element) {
            document.add(element);
        }

        public void close() {
            document.close();
        }

        /** Pie de página en todas las hojas y encabezado corto desde la segunda. */
        private final class Footer extends PdfPageEventHelper {

            @Override
            public void onEndPage(PdfWriter writer, Document doc) {
                PdfContentByte canvas = writer.getDirectContent();
                float left = doc.left();
                float right = doc.right();
                canvas.saveState();
                canvas.setColorStroke(c.line());
                canvas.setLineWidth(0.6f);
                canvas.moveTo(left, 30);
                canvas.lineTo(right, 30);
                canvas.stroke();
                canvas.restoreState();
                Font small = font(f.regular(), 7, c.muted());
                ColumnText.showTextAligned(canvas, Element.ALIGN_LEFT,
                        new Phrase(fullName + " · " + reportName, small), left, 19, 0);
                ColumnText.showTextAligned(canvas, Element.ALIGN_RIGHT,
                        new Phrase("Generado el " + LocalDateTime.now().format(STAMP) + "  ·  Página "
                                + writer.getPageNumber(), small), right, 19, 0);
            }
        }
    }

    /** Tabla dentro de una tarjeta redondeada, con encabezado suave y filas separadas por líneas finas. */
    public static final class PdfTable {

        private final PdfReport r;
        private final PdfPTable card;
        private final PdfPTable table;
        private final int columns;

        private PdfTable(PdfReport r, String title, String subtitle, float[] widths, String... headers) {
            this.r = r;
            this.columns = headers.length;
            Palette c = r.c;
            card = new PdfPTable(1);
            card.setWidthPercentage(100);
            card.setSpacingBefore(12);
            card.setSplitLate(false);
            table = new PdfPTable(widths);
            table.setWidthPercentage(100);
            table.setSplitLate(false);
            PdfPCell head = new PdfPCell();
            head.setColspan(columns);
            head.setBorder(Rectangle.NO_BORDER);
            head.setPadding(0);
            head.setPaddingBottom(8);
            Paragraph t = new Paragraph(title, r.font(r.f.semi(), 12, c.ink()));
            t.setLeading(15);
            head.addElement(t);
            if (subtitle != null && !subtitle.isBlank()) {
                Paragraph s = new Paragraph(subtitle, r.font(r.f.regular(), 7.5f, c.muted()));
                s.setLeading(10);
                head.addElement(s);
            }
            table.addCell(head);
            for (int i = 0; i < headers.length; i++) {
                PdfPCell h = new PdfPCell(new Phrase(headers[i].toUpperCase(), r.font(r.f.bold(), 6.5f, c.muted())));
                h.setBorder(Rectangle.NO_BORDER);
                h.setBackgroundColor(c.soft());
                h.setPaddingTop(6);
                h.setPaddingBottom(6);
                h.setPaddingLeft(i == 0 ? 8 : 5);
                h.setPaddingRight(5);
                h.setHorizontalAlignment(i > 0 && numericHeader(headers[i]) ? Element.ALIGN_RIGHT : Element.ALIGN_LEFT);
                table.addCell(h);
            }
            table.setHeaderRows(2);
        }

        private static boolean numericHeader(String header) {
            String h = header.toLowerCase();
            return h.equals("%") || h.contains("valor") || h.contains("total") || h.contains("ingresos")
                    || h.contains("egresos") || h.contains("saldo") || h.contains("abonado") || h.contains("ventas")
                    || h.contains("comisión") || h.contains("promedio") || h.contains("bóvedas")
                    || h.contains("ocupadas") || h.contains("mora") || h.contains("disponibles")
                    || h.contains("preparaciones") || h.contains("período");
        }

        /** Fila normal. Los valores en pesos, porcentajes y números van alineados a la derecha. */
        public PdfTable row(String... values) {
            return add(values, false, false, -1);
        }

        /** Fila de un registro anulado: texto tenue, como en las tablas de la app. */
        public PdfTable voided(String... values) {
            return add(values, false, true, -1);
        }

        /** Fila de totales: fondo dorado claro y negrita. */
        public PdfTable total(String... values) {
            return add(values, true, false, -1);
        }

        /** Fila con una barra de proporción (0 a 1) dibujada en la última columna. */
        public PdfTable bar(double fraction, String... values) {
            return add(values, false, false, Math.max(0, Math.min(1, fraction)));
        }

        /**
         * Fila de mapa de calor: el primer valor es el texto y el resto porcentajes (0 a 100); cada celda
         * se tiñe con el color de acento según el porcentaje, como el mapa de ocupación de la app.
         */
        public PdfTable heat(String label, int[] percents) {
            Palette c = r.c;
            PdfPCell first = new PdfPCell(new Phrase(label, r.font(r.f.medium(), 7.8f, c.ink())));
            first.setBorder(Rectangle.BOTTOM);
            first.setBorderColor(c.line());
            first.setBorderWidth(0.5f);
            first.setPadding(6);
            first.setPaddingLeft(8);
            table.addCell(first);
            for (int p : percents) {
                double t = Math.max(0, Math.min(100, p)) / 100.0;
                Color bg = mix(c.soft(), c.accent(), 0.08 + t * 0.85);
                Color fg = t > 0.45 ? mix(c.accent(), Color.WHITE, 0.95) : c.ink();
                PdfPCell cell = new PdfPCell(new Phrase(p + " %", r.font(r.f.semi(), 7.5f, fg)));
                cell.setBorder(Rectangle.NO_BORDER);
                cell.setPadding(6);
                cell.setHorizontalAlignment(Element.ALIGN_CENTER);
                cell.setCellEvent(new Rounded(bg, bg, 4, 1.5f, PdfPTable.BACKGROUNDCANVAS));
                table.addCell(cell);
            }
            return this;
        }

        /** Mensaje de tabla vacía en una sola celda. */
        public PdfTable empty(String text) {
            PdfPCell cell = new PdfPCell(new Phrase(text, r.font(r.f.regular(), 8, r.c.faint())));
            cell.setColspan(columns);
            cell.setBorder(Rectangle.NO_BORDER);
            cell.setPadding(10);
            cell.setHorizontalAlignment(Element.ALIGN_CENTER);
            table.addCell(cell);
            return this;
        }

        private PdfTable add(String[] values, boolean total, boolean faded, double bar) {
            Palette c = r.c;
            for (int i = 0; i < values.length; i++) {
                String v = values[i] == null ? "" : values[i];
                Color color = faded ? c.faint() : c.ink();
                if ("Anulado".equals(v) || "Anulada".equals(v)) {
                    color = c.alert();
                } else if ("Vigente".equals(v)) {
                    color = c.accent();
                }
                BaseFont base = total ? r.f.semi() : (i == 0 ? r.f.medium() : r.f.regular());
                PdfPCell cell = new PdfPCell(new Phrase(v, r.font(base, 7.8f, color)));
                cell.setBorder(Rectangle.BOTTOM);
                cell.setBorderColor(c.line());
                cell.setBorderWidth(0.5f);
                cell.setPaddingTop(5);
                cell.setPaddingBottom(6);
                cell.setPaddingLeft(i == 0 ? 8 : 5);
                cell.setPaddingRight(5);
                if (total) {
                    cell.setBackgroundColor(c.goldWash());
                    cell.setBorder(Rectangle.NO_BORDER);
                }
                if (i > 0 && numeric(v)) {
                    cell.setHorizontalAlignment(Element.ALIGN_RIGHT);
                }
                if (bar >= 0 && i == values.length - 1) {
                    cell.setCellEvent(new Bar(bar, c.soft(), c.accent()));
                    cell.setPaddingBottom(10);
                }
                table.addCell(cell);
            }
            return this;
        }

        private static boolean numeric(String v) {
            return v.startsWith("$") || v.startsWith("-$") || v.matches("[-+]?[\\d.,]+( %)?");
        }

        /** Cierra la tarjeta y la agrega al documento. */
        public void end() {
            PdfPCell cell = new PdfPCell();
            cell.setBorder(Rectangle.NO_BORDER);
            cell.setPadding(14);
            cell.setPaddingBottom(12);
            cell.setCellEvent(new Rounded(r.c.card(), r.c.line(), 12, 0, PdfPTable.BASECANVAS));
            cell.addElement(table);
            card.addCell(cell);
            r.add(card);
        }
    }

    static Image image(byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        try {
            return Image.getInstance(bytes);
        } catch (Exception e) {
            return null;
        }
    }

    private static String spaced(String text) {
        StringBuilder sb = new StringBuilder();
        for (char ch : text.toCharArray()) {
            sb.append(ch).append(' ');
        }
        return sb.toString().trim();
    }

    /** Banda oscura redondeada con el lirio difuminado a la derecha. */
    private record Band(Color fill, byte[] lily) implements PdfPCellEvent {

        @Override
        public void cellLayout(PdfPCell cell, Rectangle pos, PdfContentByte[] canvases) {
            // Capa de abajo: lo que va dentro de la banda (tarjetas, píldora) se pinta encima.
            PdfContentByte cb = canvases[PdfPTable.BASECANVAS];
            float x = pos.getLeft();
            float y = pos.getBottom();
            float w = pos.getWidth();
            float h = pos.getHeight();
            cb.saveState();
            cb.setColorFill(fill);
            cb.roundRectangle(x, y, w, h, 16);
            cb.fill();
            Image img = image(lily);
            if (img != null) {
                cb.roundRectangle(x, y, w, h, 16);
                cb.clip();
                cb.newPath();
                PdfGState state = new PdfGState();
                state.setFillOpacity(0.2f);
                cb.setGState(state);
                float ih = h * 1.05f;
                float iw = img.getWidth() * ih / img.getHeight();
                try {
                    cb.addImage(img, iw, 0, 0, ih, x + w - iw * 0.92f, y - h * 0.02f);
                } catch (Exception ignored) {
                    // Sin el lirio la banda queda lisa.
                }
            }
            cb.restoreState();
        }
    }

    /**
     * Fondo redondeado con borde fino. layer indica la capa: las tarjetas que contienen otras tablas van en
     * la capa de abajo (BASECANVAS) para que los fondos de adentro se vean encima.
     */
    private record Rounded(Color fill, Color stroke, float radius, float gap, int layer) implements PdfPCellEvent {

        @Override
        public void cellLayout(PdfPCell cell, Rectangle pos, PdfContentByte[] canvases) {
            PdfContentByte cb = canvases[layer];
            cb.saveState();
            cb.setColorFill(fill);
            cb.setColorStroke(stroke);
            cb.setLineWidth(0.6f);
            cb.roundRectangle(pos.getLeft() + gap, pos.getBottom() + 0.5f, pos.getWidth() - 2 * gap,
                    pos.getHeight() - 1, radius);
            cb.fillStroke();
            cb.restoreState();
        }
    }

    /** Píldora detrás de un texto alineado a la derecha (nombre del reporte en la banda). */
    private record Pill(Color fill, Color stroke, boolean right) implements PdfPCellEvent {

        @Override
        public void cellLayout(PdfPCell cell, Rectangle pos, PdfContentByte[] canvases) {
            Phrase phrase = cell.getPhrase();
            if (phrase == null || phrase.getFont() == null || phrase.getFont().getBaseFont() == null) {
                return;
            }
            float width = phrase.getFont().getBaseFont().getWidthPoint(phrase.getContent(), phrase.getFont().getSize()) + 20;
            float height = 18;
            float x = right ? pos.getRight() - width : pos.getLeft();
            float y = pos.getBottom() + (pos.getHeight() - height) / 2 + 1;
            PdfContentByte cb = canvases[PdfPTable.BACKGROUNDCANVAS];
            cb.saveState();
            cb.setColorFill(fill);
            cb.setColorStroke(stroke);
            cb.setLineWidth(0.6f);
            cb.roundRectangle(x, y, width, height, 9);
            cb.fillStroke();
            cb.restoreState();
        }
    }

    /** Barra de proporción bajo el texto de una celda. */
    private record Bar(double fraction, Color track, Color fill) implements PdfPCellEvent {

        @Override
        public void cellLayout(PdfPCell cell, Rectangle pos, PdfContentByte[] canvases) {
            PdfContentByte cb = canvases[PdfPTable.BACKGROUNDCANVAS];
            float x = pos.getLeft() + 5;
            float w = pos.getWidth() - 10;
            float y = pos.getBottom() + 4;
            cb.saveState();
            cb.setColorFill(track);
            cb.roundRectangle(x, y, w, 3, 1.5f);
            cb.fill();
            if (fraction > 0) {
                cb.setColorFill(fill);
                cb.roundRectangle(x, y, (float) Math.max(3, w * fraction), 3, 1.5f);
                cb.fill();
            }
            cb.restoreState();
        }
    }

    // =====================================================================================
    // Excel
    // =====================================================================================

    /** Libro de Excel con los estilos de la marca. */
    public static XlsxReport xlsx(BrandView brand, String fullName, XSSFWorkbook book, String reportName) {
        BrandView b = brand == null ? BrandingService.defaults() : brand;
        return new XlsxReport(b, fullName == null ? b.fullName() : fullName, book, reportName);
    }

    /** Estilos y piezas de Excel: banda, indicadores y tablas con encabezado de la marca. */
    public static final class XlsxReport {

        private final XSSFWorkbook book;
        private final String fullName;
        private final Palette c;
        private final String reportName;
        private final Map<String, XSSFCellStyle> styles = new HashMap<>();
        private final short moneyFormat;
        private final short dateFormat;
        private final short integerFormat;
        private final short percentFormat;
        private int pictureIndex = -1;
        private int logoWidth = 1;
        private int logoHeight = 1;

        private XlsxReport(BrandView brand, String fullName, XSSFWorkbook book, String reportName) {
            this.book = book;
            this.fullName = fullName;
            this.c = Palette.of(brand);
            this.reportName = reportName;
            this.moneyFormat = book.createDataFormat().getFormat("\"$\" #,##0;[Red]-\"$\" #,##0");
            this.dateFormat = book.createDataFormat().getFormat("dd/mm/yyyy hh:mm");
            this.integerFormat = book.createDataFormat().getFormat("#,##0");
            this.percentFormat = book.createDataFormat().getFormat("0 %");
            byte[] logo = logoBytes(brand);
            if (logo != null) {
                int type = logo.length > 3 && (logo[0] & 0xFF) == 0xFF ? XSSFWorkbook.PICTURE_TYPE_JPEG
                        : XSSFWorkbook.PICTURE_TYPE_PNG;
                pictureIndex = book.addPicture(logo, type);
                // Se mide el logo antes de dibujarlo para que el ancla quede completa desde el inicio.
                try {
                    java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(logo));
                    if (img != null) {
                        logoWidth = Math.max(1, img.getWidth());
                        logoHeight = Math.max(1, img.getHeight());
                    }
                } catch (java.io.IOException e) {
                    // Si no se puede leer, se usa un logo cuadrado.
                }
            }
        }

        private XSSFColor x(Color color) {
            return new XSSFColor(new byte[]{(byte) color.getRed(), (byte) color.getGreen(), (byte) color.getBlue()}, null);
        }

        private XSSFFont font(double size, boolean bold, Color color) {
            XSSFFont font = book.createFont();
            font.setFontName(EXCEL_FONT);
            font.setFontHeight(size);
            font.setBold(bold);
            font.setColor(x(color));
            return font;
        }

        private XSSFCellStyle style(String key, java.util.function.Consumer<XSSFCellStyle> setup) {
            return styles.computeIfAbsent(key, k -> {
                XSSFCellStyle s = book.createCellStyle();
                s.setVerticalAlignment(VerticalAlignment.CENTER);
                setup.accept(s);
                return s;
            });
        }

        private XSSFCellStyle fill(String key, Color fill, XSSFFont font, HorizontalAlignment align) {
            return style(key, s -> {
                s.setFillForegroundColor(x(fill));
                s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
                s.setFont(font);
                s.setAlignment(align);
            });
        }

        /**
         * Hoja con la banda de la marca en las primeras filas (logo, nombre, título y período).
         * Usa columns columnas desde la primera. Devuelve la fila donde sigue el contenido.
         */
        public int banner(XSSFSheet sheet, String title, String subtitle, int columns) {
            sheet.setDisplayGridlines(false);
            Color bandText = mix(c.primary(), Color.WHITE, 0.96);
            Color bandMuted = mix(c.primary(), Color.WHITE, 0.72);
            XSSFCellStyle band = fill("band", c.primary(), font(10, false, bandText), HorizontalAlignment.LEFT);
            boolean logo = pictureIndex >= 0;
            XSSFCellStyle caption = fill("band-caption", c.primary(), font(8, true, c.detail()), HorizontalAlignment.LEFT);
            XSSFCellStyle name = fill("band-name", c.primary(), font(14, true, bandText), HorizontalAlignment.LEFT);
            if (logo) {
                // Sangría para que el nombre quede a la derecha del logo.
                caption.setIndention((short) 6);
                name.setIndention((short) 6);
            }
            XSSFCellStyle report = fill("band-report", c.primary(), font(9, true, bandMuted), HorizontalAlignment.RIGHT);
            XSSFCellStyle heading = fill("band-title", c.primary(), font(20, true, bandText), HorizontalAlignment.LEFT);
            XSSFCellStyle sub = fill("band-sub", c.primary(), font(9, false, bandMuted), HorizontalAlignment.LEFT);
            heading.setIndention((short) 1);
            sub.setIndention((short) 1);
            // Al imprimir, la hoja cabe a lo ancho de la página; las hojas anchas van horizontales.
            sheet.setFitToPage(true);
            sheet.getPrintSetup().setFitWidth((short) 1);
            sheet.getPrintSetup().setFitHeight((short) 0);
            sheet.getPrintSetup().setLandscape(columns > 4);
            sheet.getPrintSetup().setPaperSize(org.apache.poi.ss.usermodel.PrintSetup.A4_PAPERSIZE);
            sheet.setMargin(org.apache.poi.ss.usermodel.Sheet.LeftMargin, 0.4);
            sheet.setMargin(org.apache.poi.ss.usermodel.Sheet.RightMargin, 0.4);
            float[] heights = {10, 16, 20, 8, 30, 18, 12};
            XSSFCellStyle[] rowStyle = {band, caption, name, band, heading, sub, band};
            for (int r = 0; r < heights.length; r++) {
                XSSFRow row = row(sheet, r);
                row.setHeightInPoints(heights[r]);
                for (int col = 0; col < columns; col++) {
                    row.createCell(col).setCellStyle(rowStyle[r]);
                }
            }
            row(sheet, 1).getCell(0).setCellValue("F U N E R A R I A");
            row(sheet, 2).getCell(0).setCellValue(shortName(fullName));
            row(sheet, 2).getCell(columns - 1).setCellStyle(report);
            row(sheet, 2).getCell(columns - 1).setCellValue(reportName);
            row(sheet, 4).getCell(0).setCellValue(title);
            row(sheet, 5).getCell(0).setCellValue(subtitle);
            if (logo) {
                XSSFDrawing drawing = sheet.createDrawingPatriarch();
                // El logo mide unos 34 px de alto, sin deformarlo. El ancla se arma completa
                // antes de crear la imagen; si no, Excel ve un tamaño negativo y "repara" el libro.
                int emu = org.apache.poi.util.Units.EMU_PER_PIXEL;
                int dx1 = 6 * emu;
                int dx2 = dx1 + (int) Math.round(logoWidth * (34.0 / logoHeight) * emu);
                // La fila 1 es más baja que el logo: el borde inferior cae dentro de la fila 2.
                int rowOnePx = (int) Math.round(heights[1] * 96 / 72.0);
                int dy2 = Math.max(0, 34 - rowOnePx) * emu;
                XSSFClientAnchor anchor = new XSSFClientAnchor(dx1, 0, dx2, dy2, 0, 1, 0, 2);
                anchor.setAnchorType(ClientAnchor.AnchorType.MOVE_DONT_RESIZE);
                drawing.createPicture(anchor, pictureIndex);
            }
            return heights.length + 1;
        }

        /** Tarjetas de indicadores: cada una ocupa span columnas y tres filas (título, valor, detalle). */
        public int kpis(XSSFSheet sheet, int startRow, int firstColumn, int span, List<Kpi> kpis, boolean highlightFirst) {
            XSSFRow captionRow = row(sheet, startRow);
            XSSFRow valueRow = row(sheet, startRow + 1);
            XSSFRow detailRow = row(sheet, startRow + 2);
            captionRow.setHeightInPoints(20);
            valueRow.setHeightInPoints(28);
            detailRow.setHeightInPoints(18);
            for (int i = 0; i < kpis.size(); i++) {
                Kpi k = kpis.get(i);
                boolean hi = highlightFirst && i == 0;
                String p = hi ? "kpi-hi-" : "kpi-";
                Color bg = hi ? mix(c.card(), c.detail(), 0.22) : c.soft();
                Color detailColor = switch (k.tone()) {
                    case UP -> c.accent();
                    case DOWN -> c.alert();
                    default -> c.muted();
                };
                XSSFCellStyle cap = kpiStyle(p + "cap", bg, font(8, true, hi ? c.goldText() : c.muted()), true, false);
                XSSFCellStyle val = kpiStyle(p + "val", bg, font(16, true, c.ink()), false, false);
                XSSFCellStyle det = kpiStyle(p + "det-" + k.tone(), bg, font(8.5, false, detailColor), false, true);
                int col = firstColumn + i * span;
                fillMerged(sheet, captionRow, col, span, cap, "  " + k.caption());
                fillMerged(sheet, valueRow, col, span, val, " " + k.value());
                fillMerged(sheet, detailRow, col, span, det, "  " + (k.detail() == null ? "" : k.detail()));
            }
            return startRow + 4;
        }

        private XSSFCellStyle kpiStyle(String key, Color bg, XSSFFont font, boolean top, boolean bottom) {
            return style(key, s -> {
                s.setFillForegroundColor(x(bg));
                s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
                s.setFont(font);
                s.setBorderLeft(BorderStyle.THIN);
                s.setBorderRight(BorderStyle.THIN);
                s.setLeftBorderColor(x(c.line()));
                s.setRightBorderColor(x(c.line()));
                if (top) {
                    s.setBorderTop(BorderStyle.THIN);
                    s.setTopBorderColor(x(c.line()));
                }
                if (bottom) {
                    s.setBorderBottom(BorderStyle.THIN);
                    s.setBottomBorderColor(x(c.line()));
                }
            });
        }

        private void fillMerged(XSSFSheet sheet, XSSFRow row, int col, int span, XSSFCellStyle style, String value) {
            for (int i = 0; i < span; i++) {
                XSSFCell cell = row.getCell(col + i) == null ? row.createCell(col + i) : row.getCell(col + i);
                cell.setCellStyle(style);
            }
            row.getCell(col).setCellValue(value);
            if (span > 1) {
                row.getSheet().addMergedRegion(new CellRangeAddress(row.getRowNum(), row.getRowNum(), col, col + span - 1));
            }
        }

        /** Título de sección (como el de las tarjetas de la app) con un subtítulo gris opcional. */
        public int section(XSSFSheet sheet, int rowIndex, int column, String title, String subtitle) {
            XSSFRow row = ExportDesign.row(sheet, rowIndex);
            row.setHeightInPoints(22);
            XSSFCellStyle t = style("section", s -> s.setFont(font(12, true, c.ink())));
            XSSFCell cell = row.createCell(column);
            cell.setCellValue(title);
            cell.setCellStyle(t);
            if (subtitle == null || subtitle.isBlank()) {
                return rowIndex + 1;
            }
            XSSFRow subRow = row(sheet, rowIndex + 1);
            XSSFCellStyle s = style("section-sub", st -> st.setFont(font(8.5, false, c.muted())));
            XSSFCell subCell = subRow.createCell(column);
            subCell.setCellValue(subtitle);
            subCell.setCellStyle(s);
            return rowIndex + 2;
        }

        /** Empieza una tabla con encabezado de la marca en la fila dada. */
        public XlsxTable table(XSSFSheet sheet, int rowIndex, int column, String... headers) {
            return new XlsxTable(this, sheet, rowIndex, column, headers);
        }

        XSSFCellStyle header(boolean right) {
            return style("head-" + right, s -> {
                s.setFillForegroundColor(x(c.primary()));
                s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
                s.setFont(font(8.5, true, mix(c.primary(), Color.WHITE, 0.96)));
                s.setAlignment(right ? HorizontalAlignment.RIGHT : HorizontalAlignment.LEFT);
                s.setIndention((short) 1);
            });
        }

        /** Estilo de celda de datos: kind = text, money, date, int; zebra alterna el fondo; total resalta. */
        XSSFCellStyle cell(String kind, boolean zebra, boolean total, Color textColor) {
            String key = "cell-" + kind + "-" + zebra + "-" + total + "-" + textColor.getRGB();
            return style(key, s -> {
                Color bg = total ? c.goldWash() : (zebra ? mix(c.card(), c.soft(), 0.7) : c.card());
                s.setFillForegroundColor(x(bg));
                s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
                s.setFont(font(9.5, total, textColor));
                s.setBorderBottom(BorderStyle.THIN);
                s.setBottomBorderColor(x(c.line()));
                s.setIndention((short) 1);
                switch (kind) {
                    case "money" -> {
                        s.setDataFormat(moneyFormat);
                        s.setAlignment(HorizontalAlignment.RIGHT);
                    }
                    case "date" -> {
                        s.setDataFormat(dateFormat);
                        s.setAlignment(HorizontalAlignment.LEFT);
                    }
                    case "percent" -> {
                        s.setDataFormat(percentFormat);
                        s.setAlignment(HorizontalAlignment.RIGHT);
                    }
                    case "int" -> {
                        s.setDataFormat(integerFormat);
                        s.setAlignment(HorizontalAlignment.RIGHT);
                    }
                    default -> s.setAlignment(HorizontalAlignment.LEFT);
                }
            });
        }

        Palette palette() {
            return c;
        }

        /** Ancho de cada columna en caracteres, desde la primera. */
        public void widths(XSSFSheet sheet, int... chars) {
            for (int i = 0; i < chars.length; i++) {
                sheet.setColumnWidth(i, chars[i] * 256);
            }
        }
    }

    /** Tabla de Excel con filas alternas, totales y filtro en el encabezado. */
    public static final class XlsxTable {

        private final XlsxReport x;
        private final XSSFSheet sheet;
        private final int headerRow;
        private final int column;
        private final int columns;
        private int next;
        private int col;
        private XSSFRow current;
        private boolean total;
        private boolean faded;

        private XlsxTable(XlsxReport x, XSSFSheet sheet, int rowIndex, int column, String... headers) {
            this.x = x;
            this.sheet = sheet;
            this.headerRow = rowIndex;
            this.column = column;
            this.columns = headers.length;
            XSSFRow row = ExportDesign.row(sheet, rowIndex);
            row.setHeightInPoints(22);
            for (int i = 0; i < headers.length; i++) {
                XSSFCell cell = row.createCell(column + i);
                cell.setCellValue(headers[i].toUpperCase());
                cell.setCellStyle(x.header(i > 0 && PdfTable.numericHeader(headers[i])));
            }
            next = rowIndex + 1;
        }

        /** Empieza una fila nueva. */
        public XlsxTable row() {
            return start(false, false);
        }

        /** Empieza una fila de un registro anulado (texto tenue). */
        public XlsxTable voidedRow() {
            return start(false, true);
        }

        /** Empieza la fila de totales. */
        public XlsxTable totalRow() {
            return start(true, false);
        }

        private XlsxTable start(boolean isTotal, boolean isFaded) {
            current = ExportDesign.row(sheet, next++);
            current.setHeightInPoints(19);
            col = column;
            total = isTotal;
            faded = isFaded;
            return this;
        }

        private boolean zebra() {
            return (current.getRowNum() - headerRow) % 2 == 0;
        }

        private Color ink() {
            return faded ? x.palette().faint() : x.palette().ink();
        }

        public XlsxTable text(String value) {
            Color color = ink();
            if ("Anulado".equals(value) || "Anulada".equals(value)) {
                color = x.palette().alert();
            } else if ("Vigente".equals(value)) {
                color = x.palette().accent();
            }
            XSSFCell cell = current.createCell(col++);
            cell.setCellValue(value == null ? "" : value);
            cell.setCellStyle(x.cell("text", zebra(), total, color));
            return this;
        }

        public XlsxTable money(BigDecimal value) {
            XSSFCell cell = current.createCell(col++);
            cell.setCellValue(value == null ? 0 : value.doubleValue());
            cell.setCellStyle(x.cell("money", zebra(), total, ink()));
            return this;
        }

        public XlsxTable number(long value) {
            XSSFCell cell = current.createCell(col++);
            cell.setCellValue(value);
            cell.setCellStyle(x.cell("int", zebra(), total, ink()));
            return this;
        }

        /** Porcentaje como número (0,54 se ve "54 %"), para que Excel pueda ordenarlo y sumarlo. */
        public XlsxTable percent(double fraction) {
            XSSFCell cell = current.createCell(col++);
            cell.setCellValue(fraction);
            cell.setCellStyle(x.cell("percent", zebra(), total, ink()));
            return this;
        }

        public XlsxTable date(LocalDateTime value) {
            XSSFCell cell = current.createCell(col++);
            if (value != null) {
                cell.setCellValue(value);
            }
            cell.setCellStyle(x.cell("date", zebra(), total, ink()));
            return this;
        }

        /** Fila con un mensaje cuando la tabla no tiene datos. */
        public XlsxTable empty(String text) {
            start(false, true);
            for (int i = 0; i < columns; i++) {
                XSSFCell cell = current.createCell(column + i);
                cell.setCellStyle(x.cell("text", false, false, x.palette().faint()));
            }
            current.getCell(column).setCellValue(text);
            return this;
        }

        /** Pone el filtro en el encabezado (si hay filas) y devuelve la fila libre siguiente. */
        public int end(boolean filter) {
            if (filter && next - 1 > headerRow) {
                sheet.setAutoFilter(new CellRangeAddress(headerRow, next - 1, column, column + columns - 1));
            }
            return next + 1;
        }
    }

    static XSSFRow row(XSSFSheet sheet, int index) {
        XSSFRow row = sheet.getRow(index);
        return row == null ? sheet.createRow(index) : row;
    }
}
