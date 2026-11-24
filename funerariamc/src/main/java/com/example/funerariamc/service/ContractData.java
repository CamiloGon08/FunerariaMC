package com.example.funerariamc.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Datos del formulario "Nuevo contrato". Según el tipo se usan unos u otros:
 * PRODUCT solo necesita el cliente y los productos (o un lote u osario del cementerio);
 * PENSION necesita la cantidad de salarios mínimos; PLAN y PRIVATE necesitan el difunto y la cuota base.
 *
 * @param kind             tipo de venta elegido en el formulario
 * @param baseAmount       cuota base del servicio (PLAN y PRIVATE)
 * @param pensionWages     salarios mínimos que cubre la entidad pensional (PENSION)
 * @param coffinItemId     ataúd elegido del inventario; con plan se cobra solo el excedente
 * @param extraBuses       buses adicionales al incluido
 * @param products         otros artículos del inventario con su cantidad
 * @param initialPayment   abono que se recibe al registrar; igual al total si es de contado
 * @param homeWake         la familia vela en la casa: no se asigna sala ni hay descuento
 * @param intercityKm      kilómetros del traslado desde otra ciudad (0 si no hay); el plan no lo cubre
 * @param cemetery         qué se cobra del cementerio: arriendo de bóveda, lote doble u osario
 * @param lotConstructions puestos del lote que se construyen
 * @param lotPreparation   se usa un puesto del lote: se cobra la adecuación (abrir, excavar, descensor)
 * @param alliedHomeId     funeraria aliada que presta el servicio en otra ciudad; null si lo presta la funeraria
 */
public record ContractData(ContractService.SaleKind kind, Long clientId, Long deceasedId, Long planId,
                           Long salespersonId, BigDecimal baseAmount, BigDecimal pensionWages,
                           Long roomId, LocalDateTime wakeStart, LocalDateTime wakeEnd,
                           Long coffinItemId, int extraBuses, List<ItemLine> products,
                           BigDecimal initialPayment, boolean homeWake, int intercityKm,
                           CemeteryCharge cemetery, int lotConstructions, boolean lotPreparation,
                           Long alliedHomeId) {

    /** Lo que se cobra del cementerio en la venta. */
    public enum CemeteryCharge {
        NONE("Sin cementerio"),
        VAULT_LEASE("Arriendo de bóveda (4 años)"),
        DOUBLE_LOT("Lote doble de contado"),
        OSSUARY("Osario");

        private final String label;

        CemeteryCharge(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }
    }

    /** Servicio que presta la propia funeraria (sin funeraria aliada). */
    public ContractData(ContractService.SaleKind kind, Long clientId, Long deceasedId, Long planId,
                        Long salespersonId, BigDecimal baseAmount, BigDecimal pensionWages,
                        Long roomId, LocalDateTime wakeStart, LocalDateTime wakeEnd,
                        Long coffinItemId, int extraBuses, List<ItemLine> products,
                        BigDecimal initialPayment, boolean homeWake, int intercityKm,
                        CemeteryCharge cemetery, int lotConstructions, boolean lotPreparation) {
        this(kind, clientId, deceasedId, planId, salespersonId, baseAmount, pensionWages, roomId, wakeStart, wakeEnd,
                coffinItemId, extraBuses, products, initialPayment, homeWake, intercityKm, cemetery, lotConstructions,
                lotPreparation, null);
    }

    /** Venta sin velación en casa, traslado desde otra ciudad ni cargos del cementerio. */
    public ContractData(ContractService.SaleKind kind, Long clientId, Long deceasedId, Long planId,
                        Long salespersonId, BigDecimal baseAmount, BigDecimal pensionWages,
                        Long roomId, LocalDateTime wakeStart, LocalDateTime wakeEnd,
                        Long coffinItemId, int extraBuses, List<ItemLine> products, BigDecimal initialPayment) {
        this(kind, clientId, deceasedId, planId, salespersonId, baseAmount, pensionWages, roomId, wakeStart, wakeEnd,
                coffinItemId, extraBuses, products, initialPayment, false, 0, CemeteryCharge.NONE, 0, false);
    }

    /** Los mismos datos con el difunto ya registrado (al registrar el difunto del plan en la misma venta). */
    public ContractData withDeceased(Long id) {
        return new ContractData(kind, clientId, id, planId, salespersonId, baseAmount, pensionWages, roomId, wakeStart,
                wakeEnd, coffinItemId, extraBuses, products, initialPayment, homeWake, intercityKm, cemetery,
                lotConstructions, lotPreparation, alliedHomeId);
    }

    /** Un artículo del inventario con la cantidad que se vende. */
    public record ItemLine(Long itemId, int quantity) {
    }

    /** Hay algo del cementerio que cobrar. */
    public boolean hasCemeteryCharges() {
        return (cemetery != null && cemetery != CemeteryCharge.NONE) || lotConstructions > 0 || lotPreparation;
    }
}
