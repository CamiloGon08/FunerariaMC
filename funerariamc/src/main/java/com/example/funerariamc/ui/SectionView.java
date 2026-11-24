package com.example.funerariamc.ui;

/**
 * Pantalla con varias partes que el menú muestra como pestañas propias, por ejemplo Finanzas con
 * Resumen, Movimientos y Comisiones. La pantalla esconde sus botones de pestaña y el menú le dice
 * cuál mostrar.
 */
public interface SectionView {

    void showSection(String section);
}
