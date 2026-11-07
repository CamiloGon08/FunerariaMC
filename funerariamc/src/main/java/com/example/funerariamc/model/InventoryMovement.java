package com.example.funerariamc.model;

import com.example.funerariamc.model.enums.MovementType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.time.LocalDateTime;

/**Entrada o salida manual de un artículo del inventario.*/
@Entity
@Table(name = "inventory_movement")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class InventoryMovement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "inventory_item_id", nullable = false)
    private InventoryItem inventoryItem;

    @Enumerated(EnumType.STRING)
    @Column(name = "movement_type", nullable = false, length = 10)
    private MovementType movementType;

    @Column(name = "quantity", nullable = false)
    private Integer quantity;

    /** Unidades del artículo justo después del movimiento. */
    @Column(name = "stock_after", nullable = false)
    private Integer stockAfter;

    @Column(name = "reason", length = 100)
    private String reason;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "employee_id")
    private Employee employee;

    /** La llena la base con now(). */
    @Column(name = "movement_date", insertable = false, updatable = false)
    private LocalDateTime movementDate;
}
