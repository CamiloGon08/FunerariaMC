package com.example.funerariamc.controller;

import com.example.funerariamc.service.InventoryService.MovementResult;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Barra de stock, textos de dinero y aviso después de un movimiento en la pantalla de Inventario. */
class InventoryCardMathTest {

    @Test
    void stockBarFillsAgainstStockPlusMinimum() {
        assertEquals(4.0 / 6, InventoryController.fill(4, 2), 1e-9);
        assertEquals(1.0, InventoryController.fill(5, 0), 1e-9);
        assertTrue(InventoryController.fill(0, 3) < 0.05);
    }

    @Test
    void moneyIsShownInColombianPesos() {
        assertEquals("$ 1.200.000", InventoryController.pesos(new BigDecimal("1200000.00")));
        assertEquals("$ 86,2 M", InventoryController.short_(new BigDecimal("86200000")));
        assertEquals(0, new BigDecimal("1200000").compareTo(InventoryController.parseAmount("$ 1.200.000", "x")));
    }

    @Test
    void lowStockMessageAsksToRestock() {
        assertEquals("Formol quedó en nivel mínimo: 2 de 5. Conviene reponerlo.",
                InventoryController.movementMessage(new MovementResult(1L, "Formol", 2, 5, true)));
        assertEquals("Movimiento registrado. Formol queda con 1 unidad.",
                InventoryController.movementMessage(new MovementResult(1L, "Formol", 1, 0, false)));
    }
}
