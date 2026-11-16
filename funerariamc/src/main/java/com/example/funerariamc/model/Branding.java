package com.example.funerariamc.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Identidad de la funeraria que usa el sistema: nombre, frase, logo y los 3 colores de la interfaz.
 */
@Entity
@Table(name = "branding")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Branding {

    /** Única fila de la tabla. */
    public static final short SINGLE_ROW = 1;

    @Id
    @Column(name = "id")
    private Short id = SINGLE_ROW;
    // Nombre comercial sin la palabra "Funeraria" (por ejemplo "El Edén")
    @Column(name = "commercial_name", nullable = false, length = 60)
    private String commercialName;
    @Column(name = "tagline", length = 80)
    private String tagline;
    // Imagen PNG o JPG. vacío = emblema del lirio de El Edén
    @Column(name = "logo")
    private byte[] logo;
    @Column(name = "logo_type", length = 20)
    private String logoType;
    // Imagen de las bandas verdes de los módulos
    @Column(name = "band_image")
    private byte[] bandImage;
    @Column(name = "band_image_type", length = 20)
    private String bandImageType;
    @Column(name = "show_band_image", nullable = false)
    private Boolean showBandImage = true;
    // Imagen detrás de los módulos del menú lateral, con la misma regla
    @Column(name = "menu_image")
    private byte[] menuImage;
    @Column(name = "menu_image_type", length = 20)
    private String menuImageType;
    @Column(name = "show_menu_image", nullable = false)
    private Boolean showMenuImage = true;
    @Column(name = "primary_color", nullable = false, length = 7)
    private String primaryColor;
    @Column(name = "accent_color", nullable = false, length = 7)
    private String accentColor;
    @Column(name = "detail_color", nullable = false, length = 7)
    private String detailColor;
    // La pone la app en hora de Bogotá en cada cambio
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by")
    private AppUser updatedBy;
}
