
package com.example.funerariamc.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.math.BigDecimal;

/**
 * Represents a wake room.
 */
@Entity
@Table(name = "wake_room")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class WakeRoom {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "capacity")
    private Integer capacity;

    @Column(name = "base_price")
    private BigDecimal basePrice;

    @Column(name = "surcharge_applies")
    private Boolean surchargeApplies = false;
}
