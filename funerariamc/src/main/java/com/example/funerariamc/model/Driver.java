package com.example.funerariamc.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import jakarta.persistence.*;

/** Subtipo de Employee: conductor de los vehículos de la funeraria. Tabla: driver */
@Entity
@Table(name = "driver")
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class Driver extends Employee {

    @Column(name = "license_category", length = 10)
    private String licenseCategory;

    @Column(name = "license_file_url", length = 250)
    private String licenseFileUrl;
}
