package com.example.funerariamc.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Datos del formulario "Nuevo contrato". Según el tipo se usan unos u otros:
 * PRODUCT solo necesita el cliente y los productos; PENSION necesita la cantidad de salarios mínimos;
 * PLAN y PRIVATE necesitan el difunto y la cuota base del servicio.
 *
 * @param kind           tipo de venta elegido en el formulario
 * @param baseAmount     cuota base del servicio (PLAN y PRIVATE)
 * @param pensionWages   salarios mínimos que cubre la entidad pensional (PENSION)
 * @param coffinItemId ataúd elegido del inventario; con plan se cobra solo el excedente
 * @param extraBuses buses adicionales al incluido
 * @param products otros artículos del inventario con su cantidad
 * @param initialPayment abono que se recibe al registrar; igual al total si es de contado
 */
public record ContractData(ContractService.SaleKind kind, Long clientId, Long deceasedId, Long planId,
                           Long salespersonId, BigDecimal baseAmount, BigDecimal pensionWages,
                           Long roomId, LocalDateTime wakeStart, LocalDateTime wakeEnd,
                           Long coffinItemId, int extraBuses, List<ItemLine> products,
                           BigDecimal initialPayment) {

    /** Un artículo del inventario con la cantidad que se vende. */
    public record ItemLine(Long itemId, int quantity) {
    }
}
