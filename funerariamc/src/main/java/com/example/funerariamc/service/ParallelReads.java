package com.example.funerariamc.service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * Lecturas que no dependen entre sí, para las pantallas de resumen (Inicio, Panel de análisis y
 * Resumen financiero). Cada una corre en su propio hilo y con su propia conexión, así la pantalla
 * espera lo que tarda la consulta más lenta y no la suma de todas (cada consulta a Supabase es un
 * viaje de ida y vuelta). Son 4 hilos para no ocupar todas las conexiones del pool (máximo 5).
 */
final class ParallelReads {

    private static final ExecutorService POOL = Executors.newFixedThreadPool(4, work -> {
        Thread thread = new Thread(work, "parallel-read");
        thread.setDaemon(true);
        return thread;
    });

    private ParallelReads() {
    }

    /** Empieza la lectura en otro hilo. */
    static <T> CompletableFuture<T> start(Supplier<T> work) {
        return CompletableFuture.supplyAsync(work, POOL);
    }

    /** Espera el resultado; si la lectura falló, lanza el mismo error para que la pantalla muestre su mensaje. */
    static <T> T get(CompletableFuture<T> future) {
        try {
            return future.join();
        } catch (CompletionException failure) {
            if (failure.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (failure.getCause() instanceof Error error) {
                throw error;
            }
            throw failure;
        }
    }
}
