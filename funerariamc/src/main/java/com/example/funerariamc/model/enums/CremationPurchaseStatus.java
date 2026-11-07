package com.example.funerariamc.model.enums;

/**
 * Estados de una compra de cremaciones prepagadas.
 * REQUESTED: solicitud que espera confirmación.
 * ORDERED: compra confirmada y pagada, con el gasto en Finanzas, esperando que lleguen los cupos.
 * RECEIVED: los cupos llegaron y quedan disponibles; CANCELLED: anulada.
 */
public enum CremationPurchaseStatus {
    REQUESTED,
    ORDERED,
    RECEIVED,
    CANCELLED
}
