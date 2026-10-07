package com.example.funerariamc.model;

import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import jakarta.persistence.*;
import java.math.BigDecimal;

/** Artículo de inventario vendible (ataúdes, flores, urnas, etc.). Tabla: inventory_item */
@Entity
@Table(name = "inventory_item")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class InventoryItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", length = 80, nullable = false)
    private String name;

    @Column(name = "category", length = 30)
    private String category;

    @Column(name = "stock_quantity")
    private Integer stockQuantity = 0;

    @Column(name = "minimum_stock")
    private Integer minimumStock = 0;

    @Column(name = "unit_price", precision = 10, scale = 2)
    private BigDecimal unitPrice;
}
