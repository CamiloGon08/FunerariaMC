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
    /** Prepara la pantalla destino sin mostrarla (para abrir su formulario sobre la pantalla actual). */
    private static Consumer<AppModule> preparer;
    /** Módulo que se está viendo y el módulo desde el que se hizo el último salto. */
    private static AppModule current;
    private static AppModule origin;

    private ModuleLink() {
    }

    /** Lo llama la pantalla principal al iniciar sesión. */
    public static void register(Consumer<AppModule> open, Predicate<AppModule> canOpen) {
        register(open, canOpen, null);
    }

    /** Igual que el anterior, con la forma de preparar una pantalla sin mostrarla. */
    public static void register(Consumer<AppModule> open, Predicate<AppModule> canOpen, Consumer<AppModule> prepare) {
        opener = open;
        allowed = canOpen;
        preparer = prepare;
        PENDING.clear();
        current = null;
        origin = null;
    }

    /** Al cerrar sesión no queda ningún salto pendiente. */
    public static void clear() {
        opener = null;
        preparer = null;
        allowed = module -> false;
        PENDING.clear();
        current = null;
        origin = null;
    }

    /** La pantalla principal avisa qué módulo quedó abierto (por el menú o por un salto). */
    public static void opened(AppModule module) {
        current = module;
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
        origin = current == module ? null : current;
        opener.accept(module);
    }

    /**
     * Deja el dato para el módulo sin cambiar la pantalla que se ve: el módulo destino abre su
     * formulario encima (por ejemplo "Nuevo contrato" desde la tarjeta de un cliente). Si no se
     * puede preparar así, salta al módulo como {@link #open}.
     */
    public static void openInPlace(AppModule module, Object payload) {
        if (preparer == null) {
            open(module, payload);
            return;
        }
        if (!canOpen(module)) {
            return;
        }
        PENDING.put(module, payload);
        preparer.accept(module);
    }

    /**
     * Vuelve al módulo desde el que se saltó a {@code from}, por ejemplo al cerrar "Nuevo contrato"
     * abierto desde Clientes, para que el usuario siga donde estaba. Si no hubo salto no hace nada.
     */
    public static void back(AppModule from) {
        AppModule target = origin;
        origin = null;
        if (target != null && target != from && current == from && canOpen(target)) {
            opener.accept(target);
        }
    }

    /** El módulo destino toma el dato una sola vez. */
    public static Optional<Object> take(AppModule module) {
        return Optional.ofNullable(PENDING.remove(module));
    }
}
