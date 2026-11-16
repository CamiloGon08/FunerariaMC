package com.example.funerariamc.ui;

import com.example.funerariamc.service.BrandingService;
import com.example.funerariamc.service.BrandingService.BrandView;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.ListChangeListener;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Window;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Pone la marca de la funeraria en toda la interfaz. 
 */
public final class BrandTheme {

    /**
     * Clase que llevan las raíces cuando la funeraria ya no se ve como El Edén (otro logo u otros colores):
     * app.css oculta las fotos del lirio, que son de El Edén y vienen en verde.
     */
    public static final String CUSTOM_CLASS = "brand-custom";
    /** La funeraria no quiere imagen en las bandas verdes. */
    public static final String NO_BAND_CLASS = "brand-no-band";
    /** Clave en las propiedades del nodo donde se guarda su estilo original, sin la paleta. */
    private static final String BAND_SHEET_PREFIX = "funerariamc-band-";
    private static final String OWN_STYLE = "brand-theme.own-style";
    private static final String APPLIED_STYLE = "brand-theme.applied-style";
    private static final ReadOnlyObjectWrapper<BrandView> BRAND = new ReadOnlyObjectWrapper<>(BrandingService.defaults());
    private static final ReadOnlyObjectWrapper<Image> LOGO = new ReadOnlyObjectWrapper<>();
    private static final ReadOnlyObjectWrapper<Image> MENU_IMAGE = new ReadOnlyObjectWrapper<>();
    /** Hoja con la imagen propia de las bandas (archivo temporal); null si usa el lirio o ninguna. */
    private static String bandStylesheet;
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
        BRAND.set(brand);
        LOGO.set(brand.hasCustomLogo() ? new Image(new ByteArrayInputStream(brand.logo())) : null);
        MENU_IMAGE.set(brand.hasCustomMenuImage() ? new Image(new ByteArrayInputStream(brand.menuImage())) : null);
        bandStylesheet = brand.hasCustomBandImage() ? bandStylesheet(brand.bandImage()) : null;
        paletteStyle = brand.palette().inlineStyle();
        Window.getWindows().forEach(window -> {
            if (window.getScene() != null) {
                apply(window.getScene().getRoot());
            }
        });
    }

    /** Cada ventana nueva y cada cambio de pantalla (login -> menú) reciben la paleta. */
    private static void watch(Window window) {
        window.sceneProperty().addListener((obs, old, scene) -> watch(scene));
        watch(window.getScene());
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
        if (!brand().edenLook()) {
            root.getStyleClass().add(CUSTOM_CLASS);
        }
        if (!brand().showBandImage()) {
            root.getStyleClass().add(NO_BAND_CLASS);
        }
        // La hoja de la imagen propia va de última para ganarle a la regla de app.css
        root.getStylesheets().removeIf(sheet -> sheet.contains(BAND_SHEET_PREFIX));
        if (bandStylesheet != null) {
            root.getStylesheets().add(bandStylesheet);
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
