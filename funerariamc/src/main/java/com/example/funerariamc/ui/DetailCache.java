package com.example.funerariamc.ui;

import javafx.application.Platform;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Caché de los paneles de detalle de una pantalla (expediente de un difunto, panel de un plan).
 * Guarda lo ya consultado para que el panel abra al instante y adelanta consultas en otro hilo:
 * al pasar el mouse por una tarjeta o apenas cargan las tarjetas destacadas.
 * Se usa solo desde el hilo de JavaFX; la consulta a la base corre en un hilo aparte.
 * La pantalla la vacía con {@link #clear()} cada vez que se recarga (por ejemplo después de guardar),
 * así nunca muestra datos de antes del cambio.
 */
public final class DetailCache<V> {

    private record Waiter<V>(Consumer<V> onReady, Consumer<Throwable> onError) {
    }

    private final Function<Long, V> loader;
    private final String threadName;
    private final Map<Long, V> values = new HashMap<>();
    /** Consultas en camino con quienes esperan su resultado. */
    private final Map<Long, List<Waiter<V>>> pending = new HashMap<>();
    /** Sube con cada clear(): un resultado de una consulta anterior se descarta. */
    private int generation;

    public DetailCache(Function<Long, V> loader, String threadName) {
        this.loader = loader;
        this.threadName = threadName;
    }

    /** El detalle ya consultado, o null si todavía no está. */
    public V get(Long id) {
        return values.get(id);
    }

    /**
     * Entrega el detalle a onReady en el hilo de JavaFX: al instante si ya está guardado, o cuando
     * termine la consulta. Si la misma consulta ya va en camino (por un adelanto), se espera esa.
     */
    public void load(Long id, Consumer<V> onReady, Consumer<Throwable> onError) {
        V cached = values.get(id);
        if (cached != null) {
            onReady.accept(cached);
            return;
        }
        List<Waiter<V>> waiters = pending.get(id);
        if (waiters != null) {
            waiters.add(new Waiter<>(onReady, onError));
            return;
        }
        waiters = new ArrayList<>();
        waiters.add(new Waiter<>(onReady, onError));
        pending.put(id, waiters);
        start(List.of(id));
    }

    /** Adelanta la consulta de un detalle sin mostrar nada; los errores se ignoran. */
    public void prefetch(Long id) {
        prefetchAll(List.of(id));
    }

    /** Adelanta varios detalles uno tras otro en un solo hilo, para no ocupar todas las conexiones. */
    public void prefetchAll(List<Long> ids) {
        List<Long> missing = new ArrayList<>();
        for (Long id : ids) {
            if (id != null && !values.containsKey(id) && !pending.containsKey(id)) {
                pending.put(id, new ArrayList<>());
                missing.add(id);
            }
        }
        if (!missing.isEmpty()) {
            start(missing);
        }
    }

    /** Olvida todo lo guardado; las consultas en camino se descartan al llegar. */
    public void clear() {
        values.clear();
        pending.clear();
        generation++;
    }

    private void start(List<Long> ids) {
        int startedIn = generation;
        Thread thread = new Thread(() -> {
            for (Long id : ids) {
                try {
                    V value = loader.apply(id);
                    Platform.runLater(() -> finish(startedIn, id, value, null));
                } catch (RuntimeException e) {
                    Platform.runLater(() -> finish(startedIn, id, null, e));
                }
            }
        }, threadName);
        thread.setDaemon(true);
        thread.start();
    }

    private void finish(int startedIn, Long id, V value, Throwable error) {
        if (startedIn != generation) {
            return;
        }
        List<Waiter<V>> waiters = pending.remove(id);
        if (value != null) {
            values.put(id, value);
        }
        if (waiters == null) {
            return;
        }
        for (Waiter<V> waiter : waiters) {
            if (error == null) {
                waiter.onReady().accept(value);
            } else if (waiter.onError() != null) {
                waiter.onError().accept(error);
            }
        }
    }
}