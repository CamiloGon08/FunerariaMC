package com.example.funerariamc.service;

/**
 * Datos que escribe el administrador en "Identidad de la funeraria".
 * logo null deja el logo que ya está; removeLogo true vuelve al emblema del lirio.
 * Las imágenes de las bandas y del menú llevan su opción (El Edén, propia o ninguna); con PROPIA y
 * bytes null se deja la imagen propia que ya estaba guardada.
 */
public record BrandingData(String commercialName, String tagline, byte[] logo, boolean removeLogo,
                           String primaryColor, String accentColor, String detailColor,
                           ImageChoice bandChoice, byte[] bandImage, ImageChoice menuChoice, byte[] menuImage) {

    public BrandingData {
        logo = copy(logo);
        bandImage = copy(bandImage);
        menuImage = copy(menuImage);
    }

    @Override public byte[] logo() { return copy(logo); }
    @Override public byte[] bandImage() { return copy(bandImage); }
    @Override public byte[] menuImage() { return copy(menuImage); }

    private static byte[] copy(byte[] bytes) { return bytes == null ? null : bytes.clone(); }

    /** Qué imagen va en las bandas verdes o detrás del menú. */
    public enum ImageChoice { EDEN, CUSTOM, NONE }

    /** Sin cambiar las imágenes de las bandas ni del menú (las deja como El Edén). */
    public BrandingData(String commercialName, String tagline, byte[] logo, boolean removeLogo,
                        String primaryColor, String accentColor, String detailColor) {
        this(commercialName, tagline, logo, removeLogo, primaryColor, accentColor, detailColor,
                ImageChoice.EDEN, null, ImageChoice.EDEN, null);
    }
}
