package com.example.funerariamc.service;

import com.example.funerariamc.exception.FileUploadException;
import com.example.funerariamc.exception.InsufficientStockException;
import com.example.funerariamc.exception.InvalidDataException;
import com.example.funerariamc.exception.ResourceNotFoundException;
import com.example.funerariamc.exception.UnauthorizedActionException;
import com.example.funerariamc.model.InventoryItem;
import com.example.funerariamc.model.InventoryMovement;
import com.example.funerariamc.model.enums.InventoryCategory;
import com.example.funerariamc.model.enums.MovementType;
import com.example.funerariamc.persistence.InventoryItemRepository;
import com.example.funerariamc.persistence.InventoryMovementRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Módulo Inventario: tarjetas con foto, stock, mínimo y precio, registro y
 * edición de artículos, entradas y salidas con historial y alerta
 * de nivel mínimo. Lo usan el Administrador de cementerio y el Administrador
 * Maestro. La pantalla recibe records ya armados y filtra en memoria.
 *
 * <p>La alerta no se guarda en una tabla: un artículo está "en nivel mínimo" mientras su cantidad
 * sea menor o igual a su mínimo, así nunca hay una alerta duplicada y desaparece
 * sola cuando se repone. Las ventas de Contratos y ventas ya descuentan el stock; aquí solo se leen.
 */
@Service
public class InventoryService {

    /** Tope de artículos que se leen para las tarjetas (una sola consulta). */
    public static final int MAX_ITEMS = 2000;
    /** Filas del historial de un artículo (manuales + ventas). */
    public static final int HISTORY_SIZE = 40;
    /** La foto pesa máximo 2 MB. */
    public static final long PHOTO_MAX_BYTES = 2L * 1024 * 1024;
    public static final Set<String> PHOTO_EXTENSIONS = Set.of("jpg", "jpeg", "png");
    /** Carpeta de FileStorageService donde quedan las fotos (en la base solo la URL). */
    public static final String PHOTO_FOLDER = "inventario";
    /** Tope de inventory_item.unit_price, numeric(10,2). */
    public static final BigDecimal MAX_PRICE = new BigDecimal("99999999.99");
    /** Tope de unidades de un solo movimiento o del stock inicial, para no desbordar integer. */
    public static final int MAX_UNITS = 1_000_000;
    public static final int NAME_LENGTH = 80;
    public static final int REASON_LENGTH = 100;

    private static final Locale ES = Locale.forLanguageTag("es-CO");
    static final String INITIAL_STOCK_REASON = "Existencia inicial";

    /**
     * Filtro de la barra: el inventario para la venta (ataúdes y artículos de venta), el de uso
     * de la funeraria (insumos de tanatopraxia) o "Bajo mínimo".
     */
    public enum Filter {
        ALL("Todos"),
        SALE("Para la venta"),
        FUNERAL_USE("Uso de la funeraria"),
        LOW("Bajo mínimo");

        private final String label;

        Filter(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }
    }

    /**
     * Una tarjeta. code es IN-0012 (del id), category es null si la base tiene un texto que no es
     * una de las categorías; low dice si está en nivel mínimo.
     */
    public record ItemRow(Long id, String code, String name, InventoryCategory category, String categoryLabel,
                          int stock, int minimum, BigDecimal unitPrice, String imageUrl, boolean low,
                          String searchText) {
    }

    /** Indicadores de la banda verde. */
    public record Kpis(int items, int forSale, int funeralUse, int low, int outOfStock, BigDecimal value, long entries,
                       long exits, long sold, String monthName) {

        /** Movimientos del mes: entradas, salidas manuales y unidades vendidas por línea de venta. */
        public long movements() {
            return entries + exits + sold;
        }
    }

    public record Overview(Kpis kpis, List<ItemRow> rows) {
    }

    /**
     * Una fila del historial. quantity lleva signo (+ entra, − sale); stockAfter es null en las
     * ventas, porque sale_detail no lo guarda.
     */
    public record HistoryEntry(LocalDateTime date, String kind, int quantity, Integer stockAfter, String detail,
                               String by, boolean voided) {
    }

    /** Panel lateral de un artículo. */
    public record ItemView(ItemRow row, List<HistoryEntry> history) {
    }

    /** Resultado de un movimiento: el stock nuevo y si quedó en nivel mínimo, para el aviso. */
    public record MovementResult(Long itemId, String name, int stock, int minimum, boolean low) {
    }

    private final InventoryItemRepository itemRepository;
    private final InventoryMovementRepository movementRepository;
    private final FileStorageService fileStorageService;
    private final AccessLogService accessLogService;
    private final SessionContext sessionContext;
    private final Clock clock;

    @Autowired
    public InventoryService(InventoryItemRepository itemRepository, InventoryMovementRepository movementRepository,
                            FileStorageService fileStorageService, AccessLogService accessLogService,
                            SessionContext sessionContext) {
        this(itemRepository, movementRepository, fileStorageService, accessLogService, sessionContext,
                Clock.system(AccessLogService.APP_ZONE));
    }

    /** Las pruebas fijan la fecha con un Clock. */
    InventoryService(InventoryItemRepository itemRepository, InventoryMovementRepository movementRepository,
                     FileStorageService fileStorageService, AccessLogService accessLogService,
                     SessionContext sessionContext, Clock clock) {
        this.itemRepository = itemRepository;
        this.movementRepository = movementRepository;
        this.fileStorageService = fileStorageService;
        this.accessLogService = accessLogService;
        this.sessionContext = sessionContext;
        this.clock = clock;
    }

    // ---------- pantalla ----------

    /** Tarjetas e indicadores en una transacción de lectura: dos consultas. */
    @Transactional(readOnly = true)
    public Overview overview() {
        requireAccess();
        List<ItemRow> rows = itemRepository.findAllByOrderByNameAscIdAsc(PageRequest.of(0, MAX_ITEMS)).stream()
                .map(InventoryService::row)
                .toList();
        YearMonth month = YearMonth.now(clock);
        InventoryMovementRepository.MonthCounts counts = movementRepository.countMonth(
                toDb(month.atDay(1).atStartOfDay()), toDb(month.plusMonths(1).atDay(1).atStartOfDay()));
        return new Overview(kpis(rows, counts, month), rows);
    }

    /** Indicadores calculados de las mismas filas: no hay consultas por tarjeta. */
    static Kpis kpis(List<ItemRow> rows, InventoryMovementRepository.MonthCounts counts, YearMonth month) {
        int forSale = 0;
        int funeralUse = 0;
        int low = 0;
        int out = 0;
        BigDecimal value = BigDecimal.ZERO;
        for (ItemRow r : rows) {
            if (r.category() != null && r.category().isForSale()) {
                forSale++;
            } else if (r.category() != null) {
                funeralUse++;
            }
            if (r.low()) {
                low++;
            }
            if (r.stock() <= 0) {
                out++;
            }
            value = value.add(r.unitPrice().multiply(BigDecimal.valueOf(Math.max(r.stock(), 0))));
        }
        return new Kpis(rows.size(), forSale, funeralUse, low, out, value,
                counts == null ? 0 : counts.getEntries(), counts == null ? 0 : counts.getExits(),
                counts == null ? 0 : counts.getSold(), monthName(month));
    }

    /** Filtro de la barra y texto del buscador (nombre, código o categoría), en memoria. */
    public static List<ItemRow> filter(List<ItemRow> rows, Filter filter, String text) {
        String needle = normalize(text);
        Filter f = filter == null ? Filter.ALL : filter;
        return rows.stream()
                .filter(r -> switch (f) {
                    case ALL -> true;
                    case LOW -> r.low();
                    case SALE -> r.category() != null && r.category().isForSale();
                    case FUNERAL_USE -> r.category() != null && !r.category().isForSale();
                })
                .filter(r -> needle.isEmpty() || r.searchText().contains(needle))
                .toList();
    }

    /** Panel del artículo: sus datos y el historial (movimientos manuales y ventas), dos consultas. */
    @Transactional(readOnly = true)
    public ItemView detail(Long itemId) {
        requireAccess();
        InventoryItem item = itemRepository.findById(itemId)
                .orElseThrow(() -> new ResourceNotFoundException("Artículo", itemId));
        PageRequest page = PageRequest.of(0, HISTORY_SIZE);
        List<HistoryEntry> history = new ArrayList<>();
        for (InventoryMovementRepository.MovementLine m : movementRepository.findHistory(itemId, page)) {
            boolean entry = MovementType.ENTRY.name().equals(m.getMovementType());
            int quantity = m.getQuantity() == null ? 0 : m.getQuantity();
            history.add(new HistoryEntry(toApp(m.getMovementDate()),
                    entry ? MovementType.ENTRY.getDisplayName() : MovementType.EXIT.getDisplayName(),
                    entry ? quantity : -quantity, m.getStockAfter(), blankToNull(m.getReason()),
                    blankToNull(m.getEmployeeName()), false));
        }
        for (InventoryMovementRepository.SaleLine s : movementRepository.findSales(itemId, page)) {
            boolean voided = "VOIDED".equals(s.getStatus());
            String client = blankToNull(s.getClientName());
            history.add(new HistoryEntry(toApp(s.getSaleDate()), voided ? "Venta anulada" : "Venta",
                    -(s.getQuantity() == null ? 0 : s.getQuantity()), null,
                    "Venta N.° %04d".formatted(s.getSaleId()) + (client == null ? "" : " · " + client),
                    null, voided));
        }
        history.sort(Comparator.comparing((HistoryEntry entry) -> entry.date(),
                Comparator.nullsLast(Comparator.reverseOrder())));
        return new ItemView(row(item), history.size() > HISTORY_SIZE ? history.subList(0, HISTORY_SIZE) : history);
    }

    // ---------- registro y edición ----------

    /**
     * Registra el artículo con su stock inicial y su mínimo. Si el stock inicial es mayor que cero
     * queda una entrada "Existencia inicial" en el historial. Devuelve el id.
     */
    @Transactional
    public Long register(InventoryItemData data) {
        requireAccess();
        validate(data, true);
        Long actorId = currentUserId();
        InventoryItem item = new InventoryItem();
        apply(item, data);
        int stock = data.initialStock();
        item.setStockQuantity(stock);
        String photoUrl = storePhoto(data.photo());
        item.setImageUrl(photoUrl);
        InventoryItem saved = itemRepository.save(item);
        if (stock > 0) {
            saveMovement(saved, MovementType.ENTRY, stock, stock, INITIAL_STOCK_REASON);
        }
        accessLogService.record(actorId, AccessLogService.INVENTORY_ITEM_REGISTERED + code(saved.getId()));
        return saved.getId();
    }

    /** Cambia nombre, categoría, mínimo, precio y foto. El stock solo cambia con movimientos. */
    @Transactional
    public Long update(Long itemId, InventoryItemData data) {
        requireAccess();
        validate(data, false);
        Long actorId = currentUserId();
        InventoryItem item = itemRepository.findForUpdate(itemId)
                .orElseThrow(() -> new ResourceNotFoundException("Artículo", itemId));
        apply(item, data);
        String newPhoto = storePhoto(data.photo());
        if (newPhoto != null) {
            String oldPhoto = item.getImageUrl();
            item.setImageUrl(newPhoto);
            afterCommit(() -> fileStorageService.delete(oldPhoto));
        }
        itemRepository.save(item);
        accessLogService.record(actorId, AccessLogService.INVENTORY_ITEM_UPDATED + code(itemId));
        return itemId;
    }

    // ---------- movimientos ----------

    /**
     * Entrada o salida de unidades. El artículo se bloquea mientras cambia, así una venta al mismo
     * tiempo no deja el stock mal. Una salida no puede dejar el stock negativo.
     */
    @Transactional
    public MovementResult registerMovement(InventoryMovementData data) {
        requireAccess();
        if (data == null || data.itemId() == null) {
            throw new InvalidDataException("Elija el artículo.");
        }
        if (data.type() == null) {
            throw new InvalidDataException("Elija si es una entrada o una salida.");
        }
        int quantity = units(data.quantity(), "la cantidad", true);
        String reason = trimToNull(data.reason());
        if (reason != null && reason.length() > REASON_LENGTH) {
            throw new InvalidDataException("El motivo admite máximo " + REASON_LENGTH + " caracteres.");
        }
        Long actorId = currentUserId();
        InventoryItem item = itemRepository.findForUpdate(data.itemId())
                .orElseThrow(() -> new ResourceNotFoundException("Artículo", data.itemId()));
        int stock = orZero(item.getStockQuantity());
        int after;
        if (data.type() == MovementType.EXIT) {
            if (quantity > stock) {
                throw new InsufficientStockException(stock);
            }
            after = stock - quantity;
        } else {
            if ((long) stock + quantity > Integer.MAX_VALUE) {
                throw new InvalidDataException("La cantidad es demasiado grande.");
            }
            after = stock + quantity;
        }
        item.setStockQuantity(after);
        itemRepository.save(item);
        saveMovement(item, data.type(), quantity, after, reason);
        accessLogService.record(actorId, AccessLogService.INVENTORY_MOVEMENT_REGISTERED + code(item.getId()) + " "
                + data.type().name() + " " + quantity);
        int minimum = orZero(item.getMinimumStock());
        return new MovementResult(item.getId(), item.getName(), after, minimum, isLow(after, minimum));
    }

    /** El artículo pide reposición cuando su cantidad es menor o igual al mínimo. */
    public static boolean isLow(int stock, int minimum) {
        return stock <= minimum;
    }

    /** Solo JPG o PNG de máximo 2 MB. Lanza FileUploadException si no cumple. */
    public static void validatePhoto(Path photo) {
        if (photo == null) {
            return;
        }
        String name = photo.getFileName() == null ? "" : photo.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String extension = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!PHOTO_EXTENSIONS.contains(extension)) {
            throw new FileUploadException("La foto debe ser JPG o PNG.");
        }
        if (!Files.isRegularFile(photo)) {
            throw new FileUploadException("No se encontró la foto seleccionada. Vuelva a elegirla.");
        }
        try {
            if (Files.size(photo) > PHOTO_MAX_BYTES) {
                throw new FileUploadException("La foto pesa más de 2 MB. Elija una más liviana.");
            }
        } catch (IOException e) {
            throw new FileUploadException("No se pudo leer la foto. Intente de nuevo.");
        }
    }

    /** "IN-0012": código del artículo en pantalla y en la bitácora, tomado del id. */
    public static String code(Long id) {
        return id == null ? "" : "IN-%04d".formatted(id);
    }

    // ---------- apoyo ----------

    private void validate(InventoryItemData data, boolean creating) {
        if (data == null) {
            throw new InvalidDataException("Complete los datos del artículo.");
        }
        String name = trimToNull(data.name());
        if (name == null) {
            throw new InvalidDataException("Escriba el nombre del artículo.");
        }
        if (name.length() > NAME_LENGTH) {
            throw new InvalidDataException("El nombre admite máximo " + NAME_LENGTH + " caracteres.");
        }
        if (data.category() == null) {
            throw new InvalidDataException("Elija la categoría del artículo.");
        }
        if (creating) {
            units(data.initialStock(), "la cantidad disponible", false);
        }
        units(data.minimumStock(), "el nivel mínimo", false);
        BigDecimal price = data.unitPrice();
        if (price == null) {
            throw new InvalidDataException("Escriba el precio unitario.");
        }
        if (price.signum() < 0) {
            throw new InvalidDataException("El precio unitario no puede ser negativo.");
        }
        if (price.compareTo(MAX_PRICE) > 0) {
            throw new InvalidDataException("El precio unitario es demasiado alto.");
        }
        validatePhoto(data.photo());
    }

    /** Cantidades enteras no negativas (en un movimiento, mayores que cero). */
    private static int units(Integer value, String what, boolean positive) {
        if (value == null) {
            throw new InvalidDataException("Escriba " + what + ".");
        }
        if (value < 0) {
            throw new InvalidDataException("No se aceptan valores negativos en " + what + ".");
        }
        if (positive && value == 0) {
            throw new InvalidDataException("Escriba en " + what + " un número mayor que cero.");
        }
        if (value > MAX_UNITS) {
            throw new InvalidDataException("El valor de " + what + " es demasiado grande.");
        }
        return value;
    }

    private static void apply(InventoryItem item, InventoryItemData data) {
        item.setName(data.name().trim());
        item.setCategory(data.category().name());
        item.setMinimumStock(data.minimumStock());
        item.setUnitPrice(data.unitPrice());
    }

    /** Guarda la foto y, si la transacción no termina bien, la borra para no dejar archivos sueltos. */
    private String storePhoto(Path photo) {
        if (photo == null) {
            return null;
        }
        String url = fileStorageService.store(photo, PHOTO_FOLDER);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status != STATUS_COMMITTED) {
                        fileStorageService.delete(url);
                    }
                }
            });
        }
        return url;
    }

    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    private void saveMovement(InventoryItem item, MovementType type, int quantity, int after, String reason) {
        InventoryMovement movement = new InventoryMovement();
        movement.setInventoryItem(item);
        movement.setMovementType(type);
        movement.setQuantity(quantity);
        movement.setStockAfter(after);
        movement.setReason(reason);
        movement.setEmployee(sessionContext.getCurrentUser().map(user -> user.getEmployee()).orElse(null));
        movementRepository.save(movement);
    }

    static ItemRow row(InventoryItem item) {
        InventoryCategory category = InventoryCategory.fromDb(item.getCategory());
        String categoryLabel = category != null ? category.getDisplayName()
                : item.getCategory() == null || item.getCategory().isBlank() ? "Sin categoría" : item.getCategory();
        int stock = orZero(item.getStockQuantity());
        int minimum = orZero(item.getMinimumStock());
        String code = code(item.getId());
        BigDecimal price = item.getUnitPrice() == null ? BigDecimal.ZERO : item.getUnitPrice();
        String search = normalize(code + " " + item.getName() + " " + categoryLabel);
        return new ItemRow(item.getId(), code, item.getName(), category, categoryLabel, stock, minimum, price,
                blankToNull(item.getImageUrl()), isLow(stock, minimum), search);
    }

    private static LocalDateTime toDb(LocalDateTime appTime) {
        return ZonedDateTime.of(appTime, AccessLogService.APP_ZONE)
                .withZoneSameInstant(AccessLogService.DB_ZONE).toLocalDateTime();
    }

    private static LocalDateTime toApp(LocalDateTime dbTime) {
        return dbTime == null ? null : AccessLogService.toAppTime(dbTime);
    }

    static String monthName(YearMonth month) {
        return month.getMonth().getDisplayName(TextStyle.FULL, ES);
    }

    /** Minúsculas y sin tildes, para que "ataud" encuentre "Ataúd". */
    static String normalize(String text) {
        if (text == null) {
            return "";
        }
        String plain = Normalizer.normalize(text.trim().toLowerCase(ES), Normalizer.Form.NFD);
        return plain.replaceAll("\\p{M}", "");
    }

    private static String trimToNull(String text) {
        return text == null || text.isBlank() ? null : text.trim();
    }

    private static String blankToNull(String text) {
        return trimToNull(text);
    }

    private static int orZero(Integer value) {
        return value == null ? 0 : value;
    }

    private void requireAccess() {
        boolean allowed = sessionContext.getRoleName().map(AppModule.INVENTARIO::isAllowedFor).orElse(false);
        if (!allowed) {
            throw new UnauthorizedActionException();
        }
    }

    private Long currentUserId() {
        return sessionContext.getCurrentUser().map(user -> user.getId()).orElseThrow(UnauthorizedActionException::new);
    }
}
