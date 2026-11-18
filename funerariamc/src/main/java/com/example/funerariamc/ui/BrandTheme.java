package com.example.funerariamc.ui;

import com.example.funerariamc.service.BrandPalette;
import com.example.funerariamc.service.BrandingService;
import com.example.funerariamc.service.BrandingService.BrandView;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.ListChangeListener;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.effect.Blend;
import javafx.scene.effect.BlendMode;
import javafx.scene.effect.ColorAdjust;
import javafx.scene.effect.ColorInput;
import javafx.scene.effect.Effect;
import javafx.scene.image.Image;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.Window;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Pone la marca de la funeraria en toda la interfaz. 
 */
public final class BrandTheme {

    /**
     * Clase que llevan las raíces cuando la funeraria quitó la imagen del menú: app.css cambia la foto del
     * login por un panel con el color principal. Con el lirio o con una foto propia el login conserva el
     * mismo diseño de El Edén (borde curvo dorado, hojas y velo del color principal, ver loginTint).
     */
    public static final String CUSTOM_CLASS = "brand-custom";
    /** La funeraria no quiere imagen en las bandas verdes. */
    public static final String NO_BAND_CLASS = "brand-no-band";
    /** Clave en las propiedades del nodo donde se guarda su estilo original, sin la paleta. */
    private static final String BAND_SHEET_PREFIX = "funerariamc-band-";
    private static final String LOGIN_SHEET_PREFIX = "funerariamc-login-";
    private static final String OWN_STYLE = "brand-theme.own-style";
    private static final String APPLIED_STYLE = "brand-theme.applied-style";
    private static final ReadOnlyObjectWrapper<BrandView> BRAND = new ReadOnlyObjectWrapper<>(BrandingService.defaults());
    private static final ReadOnlyObjectWrapper<Image> LOGO = new ReadOnlyObjectWrapper<>();
    private static final ReadOnlyObjectWrapper<Image> MENU_IMAGE = new ReadOnlyObjectWrapper<>();
    /** Hoja con la imagen propia de las bandas (archivo temporal); null si usa el lirio o ninguna. */
    private static String bandStylesheet;
    /** Hoja con la foto propia del menú recortada con la forma del lirio (archivo temporal); null si no hay. */
    private static String loginStylesheet;
    /** Foto del lirio del login: de ella salen la forma curva y el filete dorado para la foto propia. */
    private static final String LOGIN_PHOTO = "/images/login-lirio.png";
    /** Emblema del lirio: icono de la ventana cuando la funeraria no subió logo. */
    private static final String EMBLEM = "/images/emblema.png";
    /** Ancho en píxeles del filete dorado del borde curvo en login-lirio.png. */
    private static final int LOGIN_BORDER = 4;
    private static Image emblem;
    private static String paletteStyle = BrandingService.defaults().palette().inlineStyle();
    private static boolean installed;

    private BrandTheme() {
    }

    /** Se llama una vez al abrir la aplicación, antes de mostrar el login. */
    public static synchronized void install(BrandingService brandingService) {
        if (installed) {
            return;
        }
        installed = true;
        set(brandingService.current());
        brandingService.addListener(brand -> {
            if (Platform.isFxApplicationThread()) {
                set(brand);
            } else {
                Platform.runLater(() -> set(brand));
            }
        });
        Window.getWindows().addListener((ListChangeListener<Window>) change -> {
            while (change.next()) {
                change.getAddedSubList().forEach(BrandTheme::watch);
            }
        });
        Window.getWindows().forEach(BrandTheme::watch);
    }

    /** Marca actual (nombre, frase, logo y colores). */
    public static ReadOnlyObjectProperty<BrandView> brandProperty() {
        return BRAND.getReadOnlyProperty();
    }

    public static BrandView brand() {
        return BRAND.get();
    }

    /** Logo subido por la funeraria; null si usa el emblema del lirio. */
    public static ReadOnlyObjectProperty<Image> logoProperty() {
        return LOGO.getReadOnlyProperty();
    }

    /** Imagen propia detrás de los módulos del menú; null si va el lirio de El Edén (o ninguna, ver brand()). */
    public static ReadOnlyObjectProperty<Image> menuImageProperty() {
        return MENU_IMAGE.getReadOnlyProperty();
    }

    /** Título de la ventana con el nombre de la funeraria: "Funeraria MC — Iniciar sesión" -> "Funeraria El Edén — Iniciar sesión". */
    public static String title(String title) {
        return title == null ? brand().fullName() : title.replace("Funeraria MC", brand().fullName());
    }

    /** Llena la plantilla de BrandText: {name}, {fullName} y {tagline}. */
    static String fill(String template, BrandView brand) {
        if (template == null) {
            return "";
        }
        return template.replace("{fullName}", brand.fullName())
                .replace("{name}", brand.commercialName())
                .replace("{tagline}", brand.tagline() == null ? "" : brand.tagline());
    }

    /** true si la plantilla usa la frase y la funeraria no tiene frase (la etiqueta no se muestra). */
    static boolean hidesTagline(String template, BrandView brand) {
        return template != null && template.contains("{tagline}") && (brand.tagline() == null || brand.tagline().isBlank());
    }

    /** Cambia la marca en todas las ventanas abiertas. */
    static void set(BrandView brand) {
        String oldName = BRAND.get().fullName();
        BRAND.set(brand);
        LOGO.set(brand.hasCustomLogo() ? new Image(new ByteArrayInputStream(brand.logo())) : null);
        MENU_IMAGE.set(brand.hasCustomMenuImage() ? new Image(new ByteArrayInputStream(brand.menuImage())) : null);
        bandStylesheet = brand.hasCustomBandImage() ? bandStylesheet(brand.bandImage()) : null;
        loginStylesheet = brand.hasCustomMenuImage() ? loginStylesheet(brand.menuImage()) : null;
        paletteStyle = brand.palette().inlineStyle();
        Window.getWindows().forEach(window -> {
            if (window.getScene() != null) {
                apply(window.getScene().getRoot());
            }
            if (window instanceof Stage stage) {
                brandStage(stage);
                // El título de las ventanas abiertas cambia con el nombre sin tener que volver a entrar
                if (stage.getTitle() != null && !oldName.equals(brand.fullName())) {
                    stage.setTitle(stage.getTitle().replace(oldName, brand.fullName()));
                }
            }
        });
    }

    /** Cada ventana nueva y cada cambio de pantalla (login -> menú) reciben la paleta. */
    private static void watch(Window window) {
        window.sceneProperty().addListener((obs, old, scene) -> watch(scene));
        watch(window.getScene());
        if (window instanceof Stage stage) {
            brandStage(stage);
        }
    }

    /** Icono de la ventana (barra de título y barra de tareas): el logo de la funeraria o el emblema del lirio. */
    private static void brandStage(Stage stage) {
        Image icon = LOGO.get() != null ? LOGO.get() : emblem();
        if (icon != null && (stage.getIcons().size() != 1 || stage.getIcons().get(0) != icon)) {
            stage.getIcons().setAll(icon);
        }
    }

    private static Image emblem() {
        if (emblem == null) {
            var url = BrandTheme.class.getResource(EMBLEM);
            emblem = url == null ? null : new Image(url.toExternalForm());
        }
        return emblem;
    }

    private static void watch(Scene scene) {
        if (scene == null || scene.getProperties().containsKey(APPLIED_STYLE)) {
            return;
        }
        scene.getProperties().put(APPLIED_STYLE, Boolean.TRUE);
        scene.rootProperty().addListener((obs, old, root) -> apply(root));
        apply(scene.getRoot());
    }

    /** Estilo propio del nodo + la paleta. Si el nodo cambia su estilo después, se respeta y se vuelve a sumar. */
    static void apply(Parent root) {
        if (root == null) {
            return;
        }
        Object applied = root.getProperties().get(APPLIED_STYLE);
        String current = root.getStyle() == null ? "" : root.getStyle();
        String own = applied != null && applied.equals(current)
                ? (String) root.getProperties().getOrDefault(OWN_STYLE, "") : current;
        String style = own.isBlank() ? paletteStyle : own.strip() + (own.strip().endsWith(";") ? " " : "; ") + paletteStyle;
        root.getProperties().put(OWN_STYLE, own);
        root.getProperties().put(APPLIED_STYLE, style);
        root.setStyle(style);
        root.getStyleClass().removeAll(CUSTOM_CLASS, NO_BAND_CLASS);
        if (!brand().showMenuImage()) {
            root.getStyleClass().add(CUSTOM_CLASS);
        }
        // Login: la foto (lirio o la propia) y las hojas de las esquinas toman el tono del color principal.
        // La foto propia siempre lleva el velo, aun con el verde de El Edén, para que se vea igual que el lirio.
        String primary = brand().showMenuImage() ? brand().primaryColor() : null;
        boolean ownPhoto = loginStylesheet != null;
        for (Node node : root.lookupAll(".login-photo")) {
            node.setEffect(ownPhoto ? tint(primary, 0.55) : loginTint(primary, 0.55));
        }
        for (Node node : root.lookupAll(".eden-leaves")) {
            node.setEffect(loginTint(primary, 0.75));
        }
        if (!brand().showBandImage()) {
            root.getStyleClass().add(NO_BAND_CLASS);
        }
        // La hoja de la imagen propia va de última para ganarle a la regla de app.css
        root.getStylesheets().removeIf(sheet -> sheet.contains(BAND_SHEET_PREFIX) || sheet.contains(LOGIN_SHEET_PREFIX));
        if (bandStylesheet != null) {
            root.getStylesheets().add(bandStylesheet);
        }
        if (loginStylesheet != null) {
            root.getStylesheets().add(loginStylesheet);
        }
    }

    /**
     * Tono para las fotos del login (vienen en el verde de El Edén): la foto pasa a blanco y negro y encima
     * se pone un velo del color principal, solo donde hay foto. El lirio queda claro y el resto toma el
     * color de la funeraria. null si el color es el de El Edén.
     */
    static Effect loginTint(String primary, double veil) {
        if (primary == null || BrandPalette.DEFAULT_PRIMARY.equalsIgnoreCase(primary)) {
            return null;
        }
        return tint(primary, veil);
    }

    /** Blanco y negro + velo del color, solo donde hay imagen. */
    private static Effect tint(String primary, double veil) {
        if (primary == null) {
            return null;
        }
        ColorAdjust gray = new ColorAdjust(0, -1, 0, 0.25);
        Color color = Color.web(primary);
        ColorInput tone = new ColorInput(-4000, -4000, 8000, 8000,
                Color.color(color.getRed(), color.getGreen(), color.getBlue(), veil));
        return new Blend(BlendMode.SRC_ATOP, gray, tone);
    }


    /**
     * Foto propia del menú con la misma forma de la foto del lirio: se recorta con el borde curvo de
     * login-lirio.png (llenándolo, se corta lo que sobre) y se le deja encima el filete dorado. Así en el
     * login solo cambia la imagen y el resto del diseño queda igual.
     */
    private static String loginStylesheet(byte[] image) {
        try (InputStream lilyStream = BrandTheme.class.getResourceAsStream(LOGIN_PHOTO)) {
            BufferedImage photo = ImageIO.read(new ByteArrayInputStream(image));
            if (lilyStream == null || photo == null) {
                return null;
            }
            BufferedImage lily = ImageIO.read(lilyStream);
            int width = lily.getWidth();
            int height = lily.getHeight();
            // Borde derecho de la foto del lirio en cada fila (último píxel visible)
            int[] edge = new int[height];
            int widest = 0;
            for (int y = 0; y < height; y++) {
                edge[y] = -1;
                for (int x = width - 1; x >= 0; x--) {
                    if ((lily.getRGB(x, y) >>> 24) >= 32) {
                        edge[y] = x;
                        break;
                    }
                }
                widest = Math.max(widest, edge[y] + 1);
            }
            // La foto propia llena el área del lirio (como "cover") y queda centrada
            BufferedImage filled = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            double scale = Math.max((double) widest / photo.getWidth(), (double) height / photo.getHeight());
            int drawWidth = (int) Math.ceil(photo.getWidth() * scale);
            int drawHeight = (int) Math.ceil(photo.getHeight() * scale);
            Graphics2D graphics = filled.createGraphics();
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.drawImage(photo, (widest - drawWidth) / 2, (height - drawHeight) / 2, drawWidth, drawHeight, null);
            graphics.dispose();
            BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x <= edge[y]; x++) {
                    int lilyPixel = lily.getRGB(x, y);
                    if (x > edge[y] - LOGIN_BORDER) {
                        result.setRGB(x, y, lilyPixel); // filete dorado del borde curvo
                    } else {
                        int alpha = lilyPixel >>> 24;
                        result.setRGB(x, y, (alpha << 24) | (filled.getRGB(x, y) & 0xFFFFFF));
                    }
                }
            }
            Path folder = Files.createDirectories(Path.of(System.getProperty("java.io.tmpdir"), "funerariamc-brand"));
            String id = Integer.toHexString(Arrays.hashCode(image)) + "-" + image.length;
            Path picture = folder.resolve("login-" + id + ".png");
            ImageIO.write(result, "png", picture.toFile());
            Path sheet = folder.resolve(LOGIN_SHEET_PREFIX + id + ".css");
            Files.writeString(sheet, ".root .login-photo { -fx-background-image: url(\"" + picture.toUri() + "\"); }\n");
            picture.toFile().deleteOnExit();
            sheet.toFile().deleteOnExit();
            return sheet.toUri().toString();
        } catch (IOException | RuntimeException failure) {
            return null; // sin la foto propia queda la foto del lirio: el login sigue funcionando
        }
    }

    /**
     * Tono para las fotos del login (vienen en el verde de El Edén): la foto pasa a blanco y negro y encima
     * se pone un velo del color principal, solo donde hay foto. El lirio queda claro y el resto toma el
     * color de la funeraria. null si el color es el de El Edén.
     */
    static Effect loginTint(String primary, double veil) {
        if (primary == null || BrandPalette.DEFAULT_PRIMARY.equalsIgnoreCase(primary)) {
            return null;
        }
        return tint(primary, veil);
    }

    /** Blanco y negro + velo del color, solo donde hay imagen. */
    private static Effect tint(String primary, double veil) {
        if (primary == null) {
            return null;
        }
        ColorAdjust gray = new ColorAdjust(0, -1, 0, 0.25);
        Color color = Color.web(primary);
        ColorInput tone = new ColorInput(-4000, -4000, 8000, 8000,
                Color.color(color.getRed(), color.getGreen(), color.getBlue(), veil));
        return new Blend(BlendMode.SRC_ATOP, gray, tone);
    }


    /**
     * Foto propia del menú con la misma forma de la foto del lirio: se recorta con el borde curvo de
     * login-lirio.png (llenándolo, se corta lo que sobre) y se le deja encima el filete dorado. Así en el
     * login solo cambia la imagen y el resto del diseño queda igual.
     */
    private static String loginStylesheet(byte[] image) {
        try (InputStream lilyStream = BrandTheme.class.getResourceAsStream(LOGIN_PHOTO)) {
            BufferedImage photo = ImageIO.read(new ByteArrayInputStream(image));
            if (lilyStream == null || photo == null) {
                return null;
            }
            BufferedImage lily = ImageIO.read(lilyStream);
            int width = lily.getWidth();
            int height = lily.getHeight();
            // Borde derecho de la foto del lirio en cada fila (último píxel visible)
            int[] edge = new int[height];
            int widest = 0;
            for (int y = 0; y < height; y++) {
                edge[y] = -1;
                for (int x = width - 1; x >= 0; x--) {
                    if ((lily.getRGB(x, y) >>> 24) >= 32) {
                        edge[y] = x;
                        break;
                    }
                }
                widest = Math.max(widest, edge[y] + 1);
            }
            // La foto propia llena el área del lirio (como "cover") y queda centrada
            BufferedImage filled = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            double scale = Math.max((double) widest / photo.getWidth(), (double) height / photo.getHeight());
            int drawWidth = (int) Math.ceil(photo.getWidth() * scale);
            int drawHeight = (int) Math.ceil(photo.getHeight() * scale);
            Graphics2D graphics = filled.createGraphics();
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.drawImage(photo, (widest - drawWidth) / 2, (height - drawHeight) / 2, drawWidth, drawHeight, null);
            graphics.dispose();
            BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x <= edge[y]; x++) {
                    int lilyPixel = lily.getRGB(x, y);
                    if (x > edge[y] - LOGIN_BORDER) {
                        result.setRGB(x, y, lilyPixel); // filete dorado del borde curvo
                    } else {
                        int alpha = lilyPixel >>> 24;
                        result.setRGB(x, y, (alpha << 24) | (filled.getRGB(x, y) & 0xFFFFFF));
                    }
                }
            }
            Path folder = Files.createDirectories(Path.of(System.getProperty("java.io.tmpdir"), "funerariamc-brand"));
            String id = Integer.toHexString(Arrays.hashCode(image)) + "-" + image.length;
            Path picture = folder.resolve("login-" + id + ".png");
            ImageIO.write(result, "png", picture.toFile());
            Path sheet = folder.resolve(LOGIN_SHEET_PREFIX + id + ".css");
            Files.writeString(sheet, ".root .login-photo { -fx-background-image: url(\"" + picture.toUri() + "\"); }\n");
            picture.toFile().deleteOnExit();
            sheet.toFile().deleteOnExit();
            return sheet.toUri().toString();
        } catch (IOException | RuntimeException failure) {
            return null; // sin la foto propia queda la foto del lirio: el login sigue funcionando
        }
    }

    /**
     * Las hojas de JavaFX solo leen imágenes desde una dirección.
     */
    private static String bandStylesheet(byte[] image) {
        try {
            Path folder = Files.createDirectories(Path.of(System.getProperty("java.io.tmpdir"), "funerariamc-brand"));
            String id = Integer.toHexString(Arrays.hashCode(image)) + "-" + image.length;
            Path picture = folder.resolve("band-" + id + ".img");
            Files.write(picture, image);
            Path sheet = folder.resolve(BAND_SHEET_PREFIX + id + ".css");
            Files.writeString(sheet, ".root .hero-band { -fx-background-image: url(\"" + picture.toUri() + "\"); }\n");
            picture.toFile().deleteOnExit();
            sheet.toFile().deleteOnExit();
            return sheet.toUri().toString();
        } catch (IOException | RuntimeException failure) {
            return null; // sin la imagen propia queda el lirio: la pantalla sigue funcionando
        }
    }
}
