package com.example.funerariamc.service;

import com.example.funerariamc.exception.FileUploadException;
import com.example.funerariamc.exception.InsufficientStockException;
import com.example.funerariamc.exception.InvalidDataException;
import com.example.funerariamc.exception.UnauthorizedActionException;
import com.example.funerariamc.model.AppUser;
import com.example.funerariamc.model.InventoryItem;
import com.example.funerariamc.model.InventoryMovement;
import com.example.funerariamc.model.Manager;
import com.example.funerariamc.model.Role;
import com.example.funerariamc.model.enums.InventoryCategory;
import com.example.funerariamc.model.enums.MovementType;
import com.example.funerariamc.persistence.InventoryItemRepository;
import com.example.funerariamc.persistence.InventoryMovementRepository;
import com.example.funerariamc.service.InventoryService.Filter;
import com.example.funerariamc.service.InventoryService.ItemRow;
import com.example.funerariamc.service.InventoryService.Kpis;
import com.example.funerariamc.service.InventoryService.MovementResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Criterios de aceptación del inventario: registro de
 * artículos sin cantidades negativas, entradas y salidas con historial, alerta en nivel mínimo sin
 * duplicados y foto solo JPG o PNG de máximo 2 MB.
 */
class InventoryServiceTest {

    private static final ZoneId BOGOTA = ZoneId.of("America/Bogota");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 6);

    private final InventoryItemRepository items = mock(InventoryItemRepository.class);
    private final InventoryMovementRepository movements = mock(InventoryMovementRepository.class);
    private final FileStorageService files = mock(FileStorageService.class);
    private final AccessLogService accessLog = mock(AccessLogService.class);
    private final SessionContext session = new SessionContext();
    private final InventoryService service = new InventoryService(items, movements, files, accessLog, session,
            Clock.fixed(TODAY.atTime(10, 0).atZone(BOGOTA).toInstant(), BOGOTA));

    @TempDir
    Path temp;

    @BeforeEach
    void loginAsCemeteryAdmin() {
        loginAs(RoleNames.CEMETERY_ADMIN);
        when(items.save(any())).thenAnswer(invocation -> {
            InventoryItem item = invocation.getArgument(0);
            if (item.getId() == null) {
                item.setId(12L);
            }
            return item;
        });
    }

    // ---------- registro ----------

    @Test
    void negativeQuantityIsRejected() {
        // Prueba de aceptación: registrar un artículo con cantidad -3; PASA si se rechaza.
        InvalidDataException error = assertThrows(InvalidDataException.class,
                () -> service.register(data("Formol al 37 %", -3, 5, "85000")));
        assertTrue(error.getMessage().contains("negativos"));
        verify(items, never()).save(any());
    }

    @Test
    void negativeMinimumIsRejected() {
        assertThrows(InvalidDataException.class, () -> service.register(data("Formol", 3, -1, "85000")));
    }

    @Test
    void registerSavesTheItemAndAnInitialEntry() {
        Long id = service.register(data("  Ataúd Roble Clásico ", 4, 2, "2200000"));

        assertEquals(12L, id);
        ArgumentCaptor<InventoryItem> saved = ArgumentCaptor.forClass(InventoryItem.class);
        verify(items).save(saved.capture());
        assertEquals("Ataúd Roble Clásico", saved.getValue().getName());
        assertEquals("COFFIN", saved.getValue().getCategory());
        assertEquals(4, saved.getValue().getStockQuantity());
        ArgumentCaptor<InventoryMovement> movement = ArgumentCaptor.forClass(InventoryMovement.class);
        verify(movements).save(movement.capture());
        assertEquals(MovementType.ENTRY, movement.getValue().getMovementType());
        assertEquals(4, movement.getValue().getStockAfter());
        assertEquals(InventoryService.INITIAL_STOCK_REASON, movement.getValue().getReason());
        verify(accessLog).record(5L, "INVENTORY_ITEM_REGISTERED: IN-0012");
    }

    @Test
    void registerWithZeroStockLeavesNoMovement() {
        service.register(data("Velas litúrgicas", 0, 6, "24000"));
        verify(movements, never()).save(any());
    }

    @Test
    void categoryAndPriceAreRequired() {
        assertThrows(InvalidDataException.class, () -> service.register(
                new InventoryItemData("Urna", null, 1, 1, BigDecimal.ONE, null)));
        assertThrows(InvalidDataException.class, () -> service.register(
                new InventoryItemData("Urna", InventoryCategory.PRODUCT, 1, 1, null, null)));
    }

    @Test
    void editingKeepsTheStock() {
        InventoryItem item = item(12L, "Formol", 6, 5, "85000");
        when(items.findForUpdate(12L)).thenReturn(Optional.of(item));

        service.update(12L, new InventoryItemData("Formol al 37 %", InventoryCategory.SUPPLY, 999, 4,
                new BigDecimal("90000"), null));

        assertEquals(6, item.getStockQuantity());
        assertEquals(4, item.getMinimumStock());
        assertEquals("Formol al 37 %", item.getName());
        verify(accessLog).record(5L, "INVENTORY_ITEM_UPDATED: IN-0012");
    }

    // ---------- movimientos ----------

    @Test
    void entryAddsUnitsAndRecordsTheMovement() {
        when(items.findForUpdate(12L)).thenReturn(Optional.of(item(12L, "Formol", 2, 5, "85000")));

        MovementResult result = service.registerMovement(
                new InventoryMovementData(12L, MovementType.ENTRY, 10, " compra "));

        assertEquals(12, result.stock());
        assertFalse(result.low());
        ArgumentCaptor<InventoryMovement> movement = ArgumentCaptor.forClass(InventoryMovement.class);
        verify(movements).save(movement.capture());
        assertEquals(10, movement.getValue().getQuantity());
        assertEquals(12, movement.getValue().getStockAfter());
        assertEquals("compra", movement.getValue().getReason());
        assertEquals(99L, movement.getValue().getEmployee().getId());
        verify(accessLog).record(5L, "INVENTORY_MOVEMENT_REGISTERED: IN-0012 ENTRY 10");
    }

    @Test
    void exitBeyondStockIsRejected() {
        when(items.findForUpdate(12L)).thenReturn(Optional.of(item(12L, "Ataúd", 1, 1, "1200000")));

        InsufficientStockException error = assertThrows(InsufficientStockException.class,
                () -> service.registerMovement(new InventoryMovementData(12L, MovementType.EXIT, 2, null)));
        assertEquals(1, error.getAvailableQuantity());
        verify(movements, never()).save(any());
    }

    @Test
    void zeroQuantityMovementIsRejected() {
        assertThrows(InvalidDataException.class,
                () -> service.registerMovement(new InventoryMovementData(12L, MovementType.EXIT, 0, null)));
    }

    // ---------- alerta ----------

    @Test
    void twoUnitsWithMinimumFiveRaiseTheAlert() {
        // Prueba de aceptación: artículo con 2 unidades y mínimo 5; PASA si se genera la alerta.
        assertTrue(InventoryService.isLow(2, 5));
        assertTrue(InventoryService.isLow(5, 5));
        assertFalse(InventoryService.isLow(6, 5));
    }

    @Test
    void exitThatReachesTheMinimumReturnsTheAlert() {
        when(items.findForUpdate(12L)).thenReturn(Optional.of(item(12L, "Formol", 7, 5, "85000")));

        MovementResult result = service.registerMovement(new InventoryMovementData(12L, MovementType.EXIT, 5, null));

        assertEquals(2, result.stock());
        assertTrue(result.low());
    }

    @Test
    void alertIsCountedOncePerItem() {
        // La alerta es el estado del artículo, así no se duplica.
        List<ItemRow> rows = List.of(row(1L, "Formol", "SUPPLY", 2, 5, "85000"),
                row(2L, "Ataúd", "COFFIN", 4, 2, "3200000"), row(3L, "Urna", "PRODUCT", 0, 0, "350000"));
        Kpis k = InventoryService.kpis(rows, counts(36, 20, 72), YearMonth.of(2026, 11));

        assertEquals(3, k.items());
        assertEquals(2, k.forSale());
        assertEquals(1, k.funeralUse());
        assertEquals(2, k.low());
        assertEquals(1, k.outOfStock());
        assertEquals(0, new BigDecimal("12970000").compareTo(k.value()));
        assertEquals(128, k.movements());
        assertEquals("noviembre", k.monthName());
    }

    // ---------- filtro ----------

    @Test
    void filterBySaleUseLowAndTextWithoutAccents() {
        List<ItemRow> rows = List.of(row(1L, "Formol", "SUPPLY", 2, 5, "85000"),
                row(2L, "Ataúd Cedro", "COFFIN", 4, 2, "3200000"), row(3L, "Urna", "PRODUCT", 9, 2, "350000"));

        // Dos inventarios: para la venta (ataúdes y artículos de venta) y de uso de la funeraria (insumos).
        assertEquals(List.of(2L, 3L), InventoryService.filter(rows, Filter.SALE, null).stream().map(row -> row.id()).toList());
        assertEquals(List.of(1L), InventoryService.filter(rows, Filter.FUNERAL_USE, null).stream().map(row -> row.id()).toList());
        assertEquals(List.of(1L), InventoryService.filter(rows, Filter.LOW, "").stream().map(row -> row.id()).toList());
        assertEquals(List.of(2L), InventoryService.filter(rows, Filter.ALL, "ataud").stream().map(row -> row.id()).toList());
        assertEquals(List.of(3L), InventoryService.filter(rows, Filter.ALL, "in-0003").stream().map(row -> row.id()).toList());
    }

    @Test
    void unknownCategoryFromTheDatabaseIsShownAsStored() {
        ItemRow r = row(4L, "DEMO Arreglo", "FLORES", 3, 1, "250000");
        assertEquals("FLORES", r.categoryLabel());
        assertEquals(null, r.category());
    }

    // ---------- foto ----------

    @Test
    void pdfPhotoIsRejected() throws IOException {
        // Prueba de aceptación: adjuntar un PDF como foto; PASA si se rechaza.
        Path pdf = Files.writeString(temp.resolve("ficha.pdf"), "x");
        assertThrows(FileUploadException.class, () -> InventoryService.validatePhoto(pdf));
    }

    @Test
    void photoOverTwoMegabytesIsRejected() throws IOException {
        Path big = Files.write(temp.resolve("grande.jpg"), new byte[(int) InventoryService.PHOTO_MAX_BYTES + 1]);
        assertThrows(FileUploadException.class, () -> InventoryService.validatePhoto(big));
    }

    @Test
    void validPhotoIsStoredInTheInventoryFolder() throws IOException {
        Path png = Files.write(temp.resolve("roble.png"), new byte[100]);
        when(files.store(eq(png), eq(InventoryService.PHOTO_FOLDER))).thenReturn("file:///fotos/roble.png");

        service.register(new InventoryItemData("Ataúd Roble", InventoryCategory.COFFIN, 1, 1,
                new BigDecimal("2200000"), png));

        ArgumentCaptor<InventoryItem> saved = ArgumentCaptor.forClass(InventoryItem.class);
        verify(items).save(saved.capture());
        assertEquals("file:///fotos/roble.png", saved.getValue().getImageUrl());
    }

    // ---------- permisos ----------

    @Test
    void salespersonCannotOpenInventory() {
        loginAs(RoleNames.SALESPERSON);
        assertThrows(UnauthorizedActionException.class, service::overview);
    }

    // ---------- apoyo ----------

    private static InventoryItemData data(String name, Integer stock, Integer minimum, String price) {
        return new InventoryItemData(name, InventoryCategory.COFFIN, stock, minimum, new BigDecimal(price), null);
    }

    private static InventoryItem item(Long id, String name, int stock, int minimum, String price) {
        InventoryItem item = new InventoryItem();
        item.setId(id);
        item.setName(name);
        item.setCategory("SUPPLY");
        item.setStockQuantity(stock);
        item.setMinimumStock(minimum);
        item.setUnitPrice(new BigDecimal(price));
        return item;
    }

    private static ItemRow row(Long id, String name, String category, int stock, int minimum, String price) {
        InventoryItem item = item(id, name, stock, minimum, price);
        item.setCategory(category);
        return InventoryService.row(item);
    }

    private static InventoryMovementRepository.MonthCounts counts(long entries, long exits, long sold) {
        return new InventoryMovementRepository.MonthCounts() {
            @Override
            public long getEntries() {
                return entries;
            }

            @Override
            public long getExits() {
                return exits;
            }

            @Override
            public long getSold() {
                return sold;
            }
        };
    }

    private void loginAs(String roleName) {
        Manager employee = new Manager();
        employee.setId(99L);
        employee.setRole(new Role(1L, roleName, null));
        AppUser user = new AppUser();
        user.setId(5L);
        user.setEmployee(employee);
        session.start(user);
    }
}
