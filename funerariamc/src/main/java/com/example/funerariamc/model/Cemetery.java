
package com.example.funerariamc.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * Represents a cemetery where the funeral home owns vaults.
 */
@Entity
@Table(name = "cemetery")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Cemetery {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "address")
    private String address;
}
