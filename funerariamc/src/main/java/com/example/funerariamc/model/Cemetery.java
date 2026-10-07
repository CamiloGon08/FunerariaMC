package com.example.funerariamc.model;

import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import jakarta.persistence.*;

/** Cementerio donde la funeraria tiene bóvedas/nichos. Tabla: cemetery */
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

    @Column(name = "name", length = 100)
    private String name;

    @Column(name = "address", length = 40)
    private String address;
}
