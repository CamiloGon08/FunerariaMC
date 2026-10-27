package com.example.funerariamc.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

/**Guarda la información de una funeraria aliada.*/
@Entity
@Table(name = "allied_funeral_home")
@Getter
@Setter
@NoArgsConstructor
public class AlliedFuneralHome {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "nit")
    private String nit;

    @Column(name = "contact_phone")
    private String contactPhone;

    @Column(name = "agreement_start_date")
    private LocalDate agreementStartDate;

    @Column(name = "city", length = 50)
    private String city;

    @Column(name = "email", length = 50)
    private String email;

    // Una funeraria aliada se desactiva, no se borra
    @Column(name = "active", nullable = false)
    private Boolean active = true;
}