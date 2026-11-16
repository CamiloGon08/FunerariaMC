package com.example.funerariamc.ui;

import com.example.funerariamc.service.AppModule;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Salto de un módulo a otro con un dato para la pantalla destino.
 */
public final class ModuleLink {

    private static final Map<AppModule, Object> PENDING = new EnumMap<>(AppModule.class);
    private static Consumer<AppModule> opener;
    private static Predicate<AppModule> allowed = module -> false;

    private ModuleLink() {
    }

    /** Lo llama la pantalla principal al iniciar sesión. */
    public static void register(Consumer<AppModule> open, Predicate<AppModule> canOpen) {
        opener = open;
        allowed = canOpen;
        PENDING.clear();
    }

    /** Al cerrar sesión no queda ningún salto pendiente. */
    public static void clear() {
        opener = null;
        allowed = module -> false;
        PENDING.clear();
    }

    /** Si el usuario puede entrar al módulo; si no, el botón que salta no se muestra. */
    public static boolean canOpen(AppModule module) {
        return opener != null && allowed.test(module);
    }

    /** Deja el dato para el módulo y lo abre. */
    public static void open(AppModule module, Object payload) {
        if (!canOpen(module)) {
            return;
        }
        PENDING.put(module, payload);
        opener.accept(module);
    }

    /** El módulo destino toma el dato una sola vez. */
    public static Optional<Object> take(AppModule module) {
        return Optional.ofNullable(PENDING.remove(module));
    }
}
