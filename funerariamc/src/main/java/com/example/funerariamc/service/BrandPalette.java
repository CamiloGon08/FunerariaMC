package com.example.funerariamc.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Paleta de la interfaz calculada a partir de los 3 colores de la marca (principal, acento y detalle).
 * Cada variable de app.css pertenece a una familia: los tonos de esa familia siguen al color elegido
 * manteniendo la misma relación de claridad que tienen en la paleta de El Edén. Los colores de estado
 * (error, aviso, éxito) no cambian para que sigan significando lo mismo en cualquier funeraria.
 */
public final class BrandPalette {

    /** Colores de El Edén: con estos tres la paleta queda exactamente igual a la de app.css. */
    public static final String DEFAULT_PRIMARY = "#182F25";
    public static final String DEFAULT_ACCENT = "#465E47";
    public static final String DEFAULT_DETAIL = "#C4AF84";

    /** Contraste mínimo para texto normal y para filetes o títulos grandes (pautas WCAG). */
    static final double TEXT_CONTRAST = 4.5;
    static final double LARGE_CONTRAST = 3.0;
    /** Por debajo de este contraste el detalle casi no se ve sobre el principal, sea cual sea el tono. */
    static final double MIN_DETAIL_CONTRAST = 1.5;
    /** Grados de matiz por debajo de los cuales dos colores se ven del mismo tono. */
    static final double SIMILAR_HUE = 30;

    private static final Pattern HEX = Pattern.compile("#[0-9A-Fa-f]{6}");
    /** Los fondos claros conservan su claridad; solo cambian de matiz y de saturación. */
    private static final double LIGHT_FROM = 0.6;
    /** Los fondos claros nunca se saturan más que esto, para que el texto oscuro siga leyéndose. */
    private static final double MAX_TINT_SATURATION = 0.35;

    /** A qué color de la marca sigue cada variable. */
    enum Family { PRIMARY, ACCENT, DETAIL, PRIMARY_TINT, DETAIL_TINT, FIXED }

    /** Variable de app.css con su valor en la paleta de El Edén. */
    record Token(String name, String defaultHex, Family family) {}

    /** Variable con transparencia: toma el color de otra variable y le pone su opacidad. */
    record Shade(String name, String base, double alpha) {}

    static final List<Token> TOKENS = List.of(
            new Token("-color-forest-deep", "#0F1F17", Family.PRIMARY),
            new Token("-color-primary", "#182F25", Family.PRIMARY),
            new Token("-color-forest", "#1E3A2E", Family.PRIMARY),
            new Token("-color-primary-mid", "#2C4535", Family.PRIMARY),
            new Token("-color-ink", "#1E2A22", Family.PRIMARY),
            new Token("-color-moss", "#3E5A3F", Family.ACCENT),
            new Token("-color-accent", "#465E47", Family.ACCENT),
            new Token("-color-sage-dark", "#6B7F67", Family.ACCENT),
            new Token("-color-olive", "#676B48", Family.ACCENT),
            new Token("-color-sage-mid", "#9DA897", Family.ACCENT),
            new Token("-color-sage-light", "#B8CBBA", Family.ACCENT),
            new Token("-color-sage-pale", "#C9D5CA", Family.ACCENT),
            new Token("-color-text-strong", "#4A524B", Family.PRIMARY_TINT),
            new Token("-color-secondary", "#5E6660", Family.PRIMARY_TINT),
            new Token("-color-text-muted", "#6A7366", Family.PRIMARY_TINT),
            new Token("-color-text-faint", "#8A917F", Family.PRIMARY_TINT),
            new Token("-color-border", "#A9AFA8", Family.PRIMARY_TINT),
            new Token("-color-mist", "#B9C0B0", Family.PRIMARY_TINT),
            new Token("-color-linen", "#C7CBB8", Family.PRIMARY_TINT),
            new Token("-color-line", "#D5DACB", Family.PRIMARY_TINT),
            new Token("-color-surface", "#E2E6DD", Family.PRIMARY_TINT),
            new Token("-color-content", "#E3E6DA", Family.PRIMARY_TINT),
            new Token("-color-panel", "#E6E8DE", Family.PRIMARY_TINT),
            new Token("-color-soft", "#EEF0E6", Family.PRIMARY_TINT),
            new Token("-color-paper", "#F4F5EE", Family.PRIMARY_TINT),
            new Token("-color-paper-white", "#FAFBF7", Family.PRIMARY_TINT),
            new Token("-color-detail", "#C4AF84", Family.DETAIL),
            new Token("-color-gold-deep", "#8A6D3B", Family.DETAIL),
            new Token("-color-gold-text", "#8C7648", Family.DETAIL),
            new Token("-color-gold-mid", "#A07E45", Family.DETAIL),
            new Token("-color-gold-muted", "#A99A6B", Family.DETAIL),
            new Token("-color-gold-pale", "#CFC9A8", Family.DETAIL),
            new Token("-color-gold-light", "#E9DDB0", Family.DETAIL),
            new Token("-color-gold-wash", "#F1EAD9", Family.DETAIL),
            new Token("-color-background", "#F4F1EC", Family.DETAIL_TINT),
            new Token("-color-card", "#FAF9F6", Family.DETAIL_TINT),
            new Token("-color-ivory-input", "#FBFAF4", Family.DETAIL_TINT),
            new Token("-color-ivory-login", "#F6F3E8", Family.DETAIL_TINT),
            new Token("-color-ivory-mute", "#ECEAE6", Family.DETAIL_TINT),
            new Token("-color-ivory-line", "#DCD8C8", Family.DETAIL_TINT),
            new Token("-color-alert", "#8A4B3C", Family.FIXED),
            new Token("-color-alert-bg", "#F0E3DC", Family.FIXED),
            new Token("-color-alert-soft", "#F0C9BD", Family.FIXED),
            new Token("-color-error", "#9E3B32", Family.FIXED),
            new Token("-color-error-bg", "#F1E4E1", Family.FIXED),
            new Token("-color-warning", "#6E6A45", Family.FIXED),
            new Token("-color-warning-deep", "#6E6232", Family.FIXED),
            new Token("-color-warning-bg", "#EDEBDD", Family.FIXED),
            new Token("-color-success-bg", "#CFE3C4", Family.FIXED),
            new Token("-color-success-soft", "#DCE5D6", Family.FIXED)
    );

    static final List<Shade> SHADES = List.of(
            new Shade("-color-shadow-05", "-color-forest-deep", 0.05),
            new Shade("-color-shadow-06", "-color-forest-deep", 0.06),
            new Shade("-color-shadow-10", "-color-forest-deep", 0.1),
            new Shade("-color-shadow-16", "-color-forest-deep", 0.16),
            new Shade("-color-shadow-22", "-color-forest-deep", 0.22),
            new Shade("-color-shadow-25", "-color-forest-deep", 0.25),
            new Shade("-color-shadow-32", "-color-forest-deep", 0.32),
            new Shade("-color-shadow-45", "-color-forest-deep", 0.45),
            new Shade("-color-primary-a06", "-color-primary", 0.06),
            new Shade("-color-primary-a15", "-color-primary", 0.15),
            new Shade("-color-primary-a18", "-color-primary", 0.18),
            new Shade("-color-primary-a25", "-color-primary", 0.25),
            new Shade("-color-linen-a06", "-color-linen", 0.06),
            new Shade("-color-linen-a12", "-color-linen", 0.12),
            new Shade("-color-linen-a22", "-color-linen", 0.22),
            new Shade("-color-linen-a45", "-color-linen", 0.45),
            new Shade("-color-surface-a45", "-color-surface", 0.45),
            new Shade("-color-surface-a70", "-color-surface", 0.7),
            new Shade("-color-paper-a50", "-color-paper", 0.5),
            new Shade("-color-paper-a72", "-color-paper", 0.72),
            new Shade("-color-paper-a75", "-color-paper", 0.75),
            new Shade("-color-paper-a85", "-color-paper", 0.85),
            new Shade("-color-paper-a88", "-color-paper", 0.88),
            new Shade("-color-detail-a50", "-color-detail", 0.5),
            new Shade("-color-card-a60", "-color-card", 0.6),
            new Shade("-color-paper-white-a94", "-color-paper-white", 0.94)
    );

    private final Map<String, String> values;

    private BrandPalette(Map<String, String> values) {
        this.values = values;
    }

    /** Paleta de El Edén, igual a la de app.css. */
    public static BrandPalette defaults() {
        return of(DEFAULT_PRIMARY, DEFAULT_ACCENT, DEFAULT_DETAIL);
    }

    /** Calcula todas las variables a partir de los 3 colores de la marca (#RRGGBB). */
    public static BrandPalette of(String primary, String accent, String detail) {
        double[] p = hsl(checked(primary));
        double[] a = hsl(checked(accent));
        double[] d = hsl(checked(detail));
        boolean same = primary.equalsIgnoreCase(DEFAULT_PRIMARY) && accent.equalsIgnoreCase(DEFAULT_ACCENT)
                && detail.equalsIgnoreCase(DEFAULT_DETAIL);
        double[] p0 = hsl(DEFAULT_PRIMARY);
        double[] a0 = hsl(DEFAULT_ACCENT);
        double[] d0 = hsl(DEFAULT_DETAIL);
        Map<String, String> values = new LinkedHashMap<>();
        for (Token token : TOKENS) {
            String value = token.defaultHex();
            if (!same) {
                value = switch (token.family()) {
                    case PRIMARY -> follow(token.defaultHex(), p0, p, false);
                    case ACCENT -> follow(token.defaultHex(), a0, a, false);
                    case DETAIL -> follow(token.defaultHex(), d0, d, false);
                    case PRIMARY_TINT -> follow(token.defaultHex(), p0, p, true);
                    case DETAIL_TINT -> follow(token.defaultHex(), d0, d, true);
                    case FIXED -> token.defaultHex();
                };
            }
            values.put(token.name(), value);
        }
        // Los 3 colores elegidos se usan tal cual, sin redondeos
        values.put("-color-primary", checked(primary));
        values.put("-color-accent", checked(accent));
        values.put("-color-detail", checked(detail));
        for (Shade shade : SHADES) {
            int[] rgb = rgb(values.get(shade.base()));
            values.put(shade.name(), String.format(Locale.ROOT, "rgba(%d,%d,%d,%s)",
                    rgb[0], rgb[1], rgb[2], trim(shade.alpha())));
        }
        return new BrandPalette(values);
    }

    /** Valor de una variable (#RRGGBB o rgba). */
    public String get(String name) {
        return values.get(name);
    }

    /** Todas las variables en el orden de app.css. */
    public Map<String, String> values() {
        return Map.copyOf(values);
    }

    /** Estilo en línea para la raíz de cada ventana: "-color-primary: #182F25; ...". */
    public String inlineStyle() {
        StringBuilder style = new StringBuilder();
        values.forEach((name, value) -> style.append(name).append(": ").append(value).append("; "));
        return style.toString().trim();
    }

    /**
     * Problemas de lectura que dejarían estos colores: vacío si se pueden usar. Se revisa el texto
     * claro del menú y el texto de los botones; si alguno no se lee, la marca no se guarda.
     */
    public List<String> readabilityProblems() {
        List<String> problems = new ArrayList<>();
        if (contrast(get("-color-background"), get("-color-forest")) < TEXT_CONTRAST
                || contrast(get("-color-background"), get("-color-primary")) < TEXT_CONTRAST) {
            problems.add("El color principal es muy claro: el texto del menú lateral no se leería.");
        }
        if (contrast(get("-color-background"), get("-color-accent")) < TEXT_CONTRAST) {
            problems.add("El color de acento es muy claro: el texto de los botones no se leería.");
        }
        return problems;
    }

    /**
     * Avisos que no impiden guardar. El color de detalle solo adorna (títulos pequeños, filetes) sobre
     * el principal: se avisa cuando los dos se parecen de verdad, es decir, casi la misma claridad y
     * un matiz parecido. Un dorado sobre verde azulado, por ejemplo, se distingue aunque la claridad
     * sea cercana, porque el tono es muy distinto.
     */
    public List<String> readabilityWarnings() {
        List<String> warnings = new ArrayList<>();
        String detail = get("-color-detail");
        String forest = get("-color-forest");
        double ratio = contrast(detail, forest);
        double hueGap = Math.abs(hsl(detail)[0] - hsl(forest)[0]);
        hueGap = Math.min(hueGap, 360 - hueGap);
        if (ratio < MIN_DETAIL_CONTRAST || (ratio < LARGE_CONTRAST && hueGap < SIMILAR_HUE)) {
            warnings.add("El color de detalle se parece al principal: los títulos dorados del menú podrían verse poco.");
        }
        return warnings;
    }

    /** true si el texto es un color #RRGGBB. */
    public static boolean isHex(String text) {
        return text != null && HEX.matcher(text).matches();
    }

    /** Contraste entre dos colores #RRGGBB, de 1 (iguales) a 21 (negro sobre blanco). */
    public static double contrast(String first, String second) {
        double a = luminance(rgb(first));
        double b = luminance(rgb(second));
        return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
    }

    /**
     * Mueve un tono de El Edén al color nuevo: toma el matiz del color elegido, ajusta la saturación en
     * la misma proporción y, en los tonos oscuros, corre la claridad lo mismo que se corrió el color base.
     */
    private static String follow(String defaultHex, double[] base0, double[] base, boolean tint) {
        double[] tone = hsl(defaultHex);
        double hue = tint ? base[0] : (tone[0] + base[0] - base0[0] + 360) % 360;
        double ratio = base0[1] == 0 ? 1 : base[1] / base0[1];
        double saturation = clamp(tone[1] * ratio, 0, tint ? MAX_TINT_SATURATION : 1);
        double lightness = tone[2] >= LIGHT_FROM ? tone[2] : clamp(tone[2] + base[2] - base0[2], 0.03, 0.97);
        return hex(hue, saturation, lightness);
    }

    private static String checked(String hex) {
        if (!isHex(hex)) {
            throw new IllegalArgumentException("Color no válido: " + hex);
        }
        return hex.toUpperCase(Locale.ROOT);
    }

    private static int[] rgb(String hex) {
        return new int[]{Integer.parseInt(hex.substring(1, 3), 16), Integer.parseInt(hex.substring(3, 5), 16),
                Integer.parseInt(hex.substring(5, 7), 16)};
    }

    /** Matiz (0-360), saturación y claridad (0-1). */
    static double[] hsl(String hex) {
        int[] c = rgb(hex);
        double r = c[0] / 255.0;
        double g = c[1] / 255.0;
        double b = c[2] / 255.0;
        double max = Math.max(r, Math.max(g, b));
        double min = Math.min(r, Math.min(g, b));
        double l = (max + min) / 2;
        double h = 0;
        double s = 0;
        if (max != min) {
            double delta = max - min;
            s = l > 0.5 ? delta / (2 - max - min) : delta / (max + min);
            if (max == r) {
                h = (g - b) / delta + (g < b ? 6 : 0);
            } else if (max == g) {
                h = (b - r) / delta + 2;
            } else {
                h = (r - g) / delta + 4;
            }
            h *= 60;
        }
        return new double[]{h, s, l};
    }

    static String hex(double h, double s, double l) {
        double c = (1 - Math.abs(2 * l - 1)) * s;
        double x = c * (1 - Math.abs((h / 60) % 2 - 1));
        double m = l - c / 2;
        double r;
        double g;
        double b;
        if (h < 60) { r = c; g = x; b = 0; }
        else if (h < 120) { r = x; g = c; b = 0; }
        else if (h < 180) { r = 0; g = c; b = x; }
        else if (h < 240) { r = 0; g = x; b = c; }
        else if (h < 300) { r = x; g = 0; b = c; }
        else { r = c; g = 0; b = x; }
        return String.format(Locale.ROOT, "#%02X%02X%02X", channel(r + m), channel(g + m), channel(b + m));
    }

    private static int channel(double value) {
        return (int) Math.round(clamp(value, 0, 1) * 255);
    }

    private static double luminance(int[] c) {
        double[] linear = new double[3];
        for (int i = 0; i < 3; i++) {
            double v = c[i] / 255.0;
            linear[i] = v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
        }
        return 0.2126 * linear[0] + 0.7152 * linear[1] + 0.0722 * linear[2];
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String trim(double alpha) {
        String text = String.format(Locale.ROOT, "%.2f", alpha);
        return text.replaceAll("0+$", "").replaceAll("\\.$", "");
    }
}
