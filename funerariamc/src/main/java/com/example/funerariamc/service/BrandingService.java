package com.example.funerariamc.service;

import com.example.funerariamc.exception.InvalidDataException;
import com.example.funerariamc.exception.UnauthorizedActionException;
import com.example.funerariamc.model.AppUser;
import com.example.funerariamc.model.Branding;
import com.example.funerariamc.persistence.BrandingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Identidad de la funeraria (nombre, frase, logo y colores) para venderle el sistema a varias funerarias.
 * Cualquiera la lee (el login la muestra antes de entrar); solo el Administrador Maestro la cambia,
 * y cada cambio queda en la bitácora. Si la tabla aún no tiene fila se usa la marca El Edén.
 */
@Service
public class BrandingService {

    public static final String DEFAULT_NAME = "El Edén";
    public static final String DEFAULT_TAGLINE = "Donde el recuerdo florece";
    /** Límites técnicos: los mismos largos de las columnas y un logo liviano para que cargue rápido. */
    public static final int MAX_NAME = 60;
    public static final int MAX_TAGLINE = 80;
    public static final int MAX_LOGO_BYTES = 1024 * 1024;
    private static final String PNG = "image/png";
    private static final String JPEG = "image/jpeg";
    private static final Logger log = LoggerFactory.getLogger(BrandingService.class);
    private static final int AUDIT_MAX = 100;

    /**
     * Marca que ven las pantallas y los mensajes. logo null = emblema del lirio. Las imágenes de las bandas
     * y del menú: si showX es false no se pone ninguna; si es true y la imagen es null va el lirio de El Edén.
     */
    public record BrandView(String commercialName, String tagline, byte[] logo, String logoType,
                            String primaryColor, String accentColor, String detailColor,
                            byte[] bandImage, boolean showBandImage, byte[] menuImage, boolean showMenuImage,
                            LocalDateTime updatedAt, String updatedBy) {

        public BrandView {
            logo = copy(logo);
            bandImage = copy(bandImage);
            menuImage = copy(menuImage);
        }

        @Override public byte[] logo() { return copy(logo); }
        @Override public byte[] bandImage() { return copy(bandImage); }
        @Override public byte[] menuImage() { return copy(menuImage); }

        private static byte[] copy(byte[] bytes) { return bytes == null ? null : bytes.clone(); }

        /** Con los lirios de El Edén en las bandas y en el menú. */
        public BrandView(String commercialName, String tagline, byte[] logo, String logoType,
                         String primaryColor, String accentColor, String detailColor,
                         LocalDateTime updatedAt, String updatedBy) {
            this(commercialName, tagline, logo, logoType, primaryColor, accentColor, detailColor,
                    null, true, null, true, updatedAt, updatedBy);
        }

        /** "Funeraria El Edén"; si el nombre ya empieza por "Funeraria" no se repite. */
        public String fullName() {
            return commercialName.toLowerCase(Locale.ROOT).startsWith("funeraria")
                    ? commercialName : "Funeraria " + commercialName;
        }

        public boolean hasCustomLogo() {
            return logo != null && logo.length > 0;
        }

        /** true si se ve como El Edén (emblema y colores originales): solo así va la foto del lirio en el login. */
        public boolean edenLook() {
            return !hasCustomLogo() && BrandPalette.DEFAULT_PRIMARY.equalsIgnoreCase(primaryColor)
                    && BrandPalette.DEFAULT_ACCENT.equalsIgnoreCase(accentColor)
                    && BrandPalette.DEFAULT_DETAIL.equalsIgnoreCase(detailColor);
        }

        public boolean hasCustomBandImage() {
            return showBandImage && bandImage != null && bandImage.length > 0;
        }

        public boolean hasCustomMenuImage() {
            return showMenuImage && menuImage != null && menuImage.length > 0;
        }

        public BrandPalette palette() {
            return BrandPalette.of(primaryColor, accentColor, detailColor);
        }
    }

    private final BrandingRepository brandingRepository;
    private final AccessLogService accessLogService;
    private final SessionContext sessionContext;
    private final List<Consumer<BrandView>> listeners = new CopyOnWriteArrayList<>();
    private volatile BrandView current;

    public BrandingService(BrandingRepository brandingRepository, AccessLogService accessLogService,
                           SessionContext sessionContext) {
        this.brandingRepository = brandingRepository;
        this.accessLogService = accessLogService;
        this.sessionContext = sessionContext;
    }

    /** Marca de El Edén, la que trae el sistema. */
    public static BrandView defaults() {
        return new BrandView(DEFAULT_NAME, DEFAULT_TAGLINE, null, null, BrandPalette.DEFAULT_PRIMARY,
                BrandPalette.DEFAULT_ACCENT, BrandPalette.DEFAULT_DETAIL, null, null);
    }

    /** Marca actual; se lee de la BD una vez y queda en memoria hasta que alguien la cambie. */
    @Transactional(readOnly = true)
    public synchronized BrandView current() {
        BrandView brand = current;
        if (brand == null) {
            brand = brandingRepository.findCurrent().map(BrandingService::view).orElseGet(BrandingService::defaults);
            current = brand;
        }
        return brand;
    }

    /** Nombre completo con la marca de El Edén si el servicio no está (pruebas sin Spring). */
    public static String fullNameOf(BrandingService service) {
        return service == null ? defaults().fullName() : service.fullName();
    }

    /** "Funeraria El Edén" para correos, reportes y el bot. */
    public String fullName() {
        return current().fullName();
    }

    /** Avisa cada vez que se guarda una marca nueva (la interfaz cambia sus colores al momento). */
    public void addListener(Consumer<BrandView> listener) {
        listeners.add(Objects.requireNonNull(listener));
    }

    /** Guarda la marca escrita por el administrador. Si algún color deja texto ilegible no se guarda. */
    @Transactional
    public BrandView save(BrandingData data) {
        AppUser actor = requireMaster();
        if (data == null) {
            throw new InvalidDataException("Faltan los datos de la marca.");
        }
        String name = required(data.commercialName(), "el nombre comercial", MAX_NAME);
        String tagline = optional(data.tagline(), "la frase", MAX_TAGLINE);
        String primary = color(data.primaryColor(), "principal");
        String accent = color(data.accentColor(), "de acento");
        String detail = color(data.detailColor(), "de detalle");
        List<String> problems = BrandPalette.of(primary, accent, detail).readabilityProblems();
        if (!problems.isEmpty()) {
            throw new InvalidDataException(String.join(" ", problems));
        }
        String logoType = data.logo() == null ? null : imageType(data.logo(), "El logo");
        String bandType = data.bandImage() == null ? null : imageType(data.bandImage(), "La imagen de las bandas");
        String menuType = data.menuImage() == null ? null : imageType(data.menuImage(), "La imagen del menú");

        Branding branding = brandingRepository.findById(Branding.SINGLE_ROW).orElseGet(Branding::new);
        BrandView before = branding.getCommercialName() == null ? defaults() : view(branding);
        branding.setId(Branding.SINGLE_ROW);
        branding.setCommercialName(name);
        branding.setTagline(tagline);
        if (data.removeLogo()) {
            branding.setLogo(null);
            branding.setLogoType(null);
        } else if (data.logo() != null) {
            branding.setLogo(data.logo());
            branding.setLogoType(logoType);
        }
        applyImage(data.bandChoice(), data.bandImage(), bandType, branding.getBandImage(), "las bandas",
                (bytes, type) -> {
                    branding.setBandImage(bytes);
                    branding.setBandImageType(type);
                }, branding::setShowBandImage);
        applyImage(data.menuChoice(), data.menuImage(), menuType, branding.getMenuImage(), "el menú",
                (bytes, type) -> {
                    branding.setMenuImage(bytes);
                    branding.setMenuImageType(type);
                }, branding::setShowMenuImage);
        branding.setPrimaryColor(primary);
        branding.setAccentColor(accent);
        branding.setDetailColor(detail);
        branding.setUpdatedAt(LocalDateTime.now(AccessLogService.APP_ZONE));
        branding.setUpdatedBy(actor);
        Branding saved = brandingRepository.save(branding);
        BrandView after = view(saved);
        String action = AccessLogService.BRANDING_UPDATED + changes(before, after);
        accessLogService.record(actor.getId(), action.substring(0, Math.min(AUDIT_MAX, action.length())));
        publishAfterCommit(after);
        return after;
    }

    /** Vuelve a la marca El Edén (nombre, frase, emblema y colores). */
    @Transactional
    public BrandView restoreDefaults() {
        BrandView brand = defaults();
        return save(new BrandingData(brand.commercialName(), brand.tagline(), null, true,
                brand.primaryColor(), brand.accentColor(), brand.detailColor()));
    }

    /**
     * EDEN: sin imagen propia y con el lirio. NONE: sin ninguna imagen. CUSTOM: la imagen nueva, o la propia
     * que ya estaba si no se eligió otra.
     */
    private static void applyImage(BrandingData.ImageChoice choice, byte[] bytes, String type, byte[] stored, String where,
                                   BiConsumer<byte[], String> setImage, Consumer<Boolean> setShow) {
        BrandingData.ImageChoice option = choice == null ? BrandingData.ImageChoice.EDEN : choice;
        switch (option) {
            case EDEN -> {
                setImage.accept(null, null);
                setShow.accept(true);
            }
            case NONE -> {
                setImage.accept(null, null);
                setShow.accept(false);
            }
            case CUSTOM -> {
                if (bytes != null) {
                    setImage.accept(bytes, type);
                } else if (stored == null || stored.length == 0) {
                    throw new InvalidDataException("Elija la imagen para " + where + ".");
                }
                setShow.accept(true);
            }
        }
    }

    /** Qué cambió, en una línea corta para la bitácora (los colores y el nombre; el logo solo si cambió). */
    static String changes(BrandView before, BrandView after) {
        List<String> parts = new ArrayList<>();
        if (!before.commercialName().equals(after.commercialName())) {
            parts.add("name '" + before.commercialName() + "' -> '" + after.commercialName() + "'");
        }
        if (!Objects.equals(before.tagline(), after.tagline())) {
            parts.add("tagline");
        }
        if (before.hasCustomLogo() != after.hasCustomLogo()
                || (after.hasCustomLogo() && !Arrays.equals(before.logo(), after.logo()))) {
            parts.add(after.hasCustomLogo() ? "logo uploaded" : "logo removed");
        }
        if (!imageState(before.bandImage(), before.showBandImage()).equals(imageState(after.bandImage(), after.showBandImage()))
                || (after.bandImage() != null && !Arrays.equals(before.bandImage(), after.bandImage()))) {
            parts.add("band image " + imageState(after.bandImage(), after.showBandImage()));
        }
        if (!imageState(before.menuImage(), before.showMenuImage()).equals(imageState(after.menuImage(), after.showMenuImage()))
                || (after.menuImage() != null && !Arrays.equals(before.menuImage(), after.menuImage()))) {
            parts.add("menu image " + imageState(after.menuImage(), after.showMenuImage()));
        }
        String colorsBefore = before.primaryColor() + " " + before.accentColor() + " " + before.detailColor();
        String colorsAfter = after.primaryColor() + " " + after.accentColor() + " " + after.detailColor();
        if (!colorsBefore.equals(colorsAfter)) {
            parts.add("colors " + colorsBefore + " -> " + colorsAfter);
        }
        return parts.isEmpty() ? "no changes" : String.join("; ", parts);
    }

    private static String imageState(byte[] image, boolean show) {
        return !show ? "none" : image == null || image.length == 0 ? "lily" : "custom";
    }

    /** La interfaz se entera solo si la transacción quedó guardada. */
    private void publishAfterCommit(BrandView brand) {
        Runnable publish = () -> {
            synchronized (this) {
                current = brand;
            }
            for (Consumer<BrandView> listener : listeners) {
                try {
                    listener.accept(brand);
                } catch (RuntimeException failure) {
                    log.warn("No se pudo actualizar una vista de la marca ({}).", failure.getClass().getSimpleName());
                }
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publish.run();
                }
            });
        } else {
            publish.run();
        }
    }

    private static BrandView view(Branding b) {
        String editor = null;
        AppUser user = b.getUpdatedBy();
        if (user != null) {
            editor = user.getEmployee() != null
                    ? user.getEmployee().getName() + " " + user.getEmployee().getLastname()
                    : user.getUsername();
        }
        return new BrandView(b.getCommercialName(), b.getTagline(), b.getLogo(), b.getLogoType(),
                b.getPrimaryColor(), b.getAccentColor(), b.getDetailColor(),
                b.getBandImage(), !Boolean.FALSE.equals(b.getShowBandImage()),
                b.getMenuImage(), !Boolean.FALSE.equals(b.getShowMenuImage()), b.getUpdatedAt(), editor);
    }

    private AppUser requireMaster() {
        boolean allowed = sessionContext.getRoleName().map(AppModule.PARAMETROS::isAllowedFor).orElse(false);
        AppUser actor = sessionContext.getCurrentUser().orElseThrow(UnauthorizedActionException::new);
        if (!allowed || actor.getId() == null) {
            throw new UnauthorizedActionException();
        }
        return actor;
    }

    private static String required(String text, String field, int max) {
        String value = text == null ? "" : text.strip();
        if (value.isEmpty()) {
            throw new InvalidDataException("Escriba " + field + ".");
        }
        if (value.length() > max) {
            throw new InvalidDataException("El nombre comercial admite máximo " + max + " caracteres.");
        }
        return value;
    }

    private static String optional(String text, String field, int max) {
        String value = text == null ? "" : text.strip();
        if (value.length() > max) {
            throw new InvalidDataException("La frase admite máximo " + max + " caracteres.");
        }
        return value.isEmpty() ? null : value;
    }

    private static String color(String text, String which) {
        String value = text == null ? "" : text.strip();
        if (!BrandPalette.isHex(value)) {
            throw new InvalidDataException("El color " + which + " debe tener el formato #RRGGBB.");
        }
        return value.toUpperCase(Locale.ROOT);
    }

    /** Solo imágenes PNG o JPG de hasta 1 MB, verificadas por su contenido. */
    static String logoType(byte[] logo) {
        return imageType(logo, "El logo");
    }

    static String imageType(byte[] logo, String what) {
        if (logo.length == 0) {
            throw new InvalidDataException(what + " está vacío.");
        }
        if (logo.length > MAX_LOGO_BYTES) {
            throw new InvalidDataException(what + " pesa más de 1 MB. Use una imagen más liviana.");
        }
        String type = null;
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};
        if (logo.length > png.length && Arrays.equals(png, Arrays.copyOf(logo, png.length))) {
            type = PNG;
        } else if (logo.length > 3 && (logo[0] & 0xFF) == 0xFF && (logo[1] & 0xFF) == 0xD8 && (logo[2] & 0xFF) == 0xFF) {
            type = JPEG;
        }
        if (type != null) {
            try (ByteArrayInputStream input = new ByteArrayInputStream(logo)) {
                if (ImageIO.read(input) != null) {
                    return type;
                }
            } catch (IOException | RuntimeException invalidImage) {
                throw new InvalidDataException(what + " debe ser una imagen PNG o JPG válida.");
            }
        }
        throw new InvalidDataException(what + " debe ser una imagen PNG o JPG.");
    }
}
