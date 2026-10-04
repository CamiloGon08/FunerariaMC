package com.example.funerariamc.model;

import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import jakarta.persistence.*;
import java.time.LocalDate;

/** Funeraria externa aliada con convenio. Tabla: allied_funeral_home */
@Entity
@Table(name = "allied_funeral_home")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class AlliedFuneralHome {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", length = 100, nullable = false)
    private String name;

    @Column(name = "nit", length = 15)
    private String nit;

    @Column(name = "contact_phone", length = 10)
    private String contactPhone;

    @Column(name = "agreement_start_date")
    private LocalDate agreementStartDate;
}
