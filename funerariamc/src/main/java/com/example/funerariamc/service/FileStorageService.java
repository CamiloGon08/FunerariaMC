package com.example.funerariamc.service;

import com.example.funerariamc.exception.FileUploadException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * RN-25: guarda los documentos de soporte (certificados, informes, órdenes) fuera de la base
 * de datos y devuelve solo su URL. Por ahora los copia a una carpeta de la aplicación
 * (app.files.base-dir, por defecto ~/FunerariaMC/archivos); cambiar a un almacenamiento en la
 * nube solo cambia esta clase, porque el resto del sistema guarda y abre la URL.
 */
@Service
public class FileStorageService {

    /** Tamaño máximo de un documento: 10 MB. */
    public static final long MAX_BYTES = 10L * 1024 * 1024;
    public static final Set<String> EXTENSIONS = Set.of("pdf", "png", "jpg", "jpeg");

    private final Path baseDir;

    public FileStorageService(@Value("${app.files.base-dir:${user.home}/FunerariaMC/archivos}") String baseDir) {
        this.baseDir = Paths.get(baseDir).toAbsolutePath().normalize();
    }

    /**
     * Copia el archivo a {@code baseDir/folder} con un nombre único y devuelve su URL (file:/...).
     * Lanza FileUploadException si el archivo no existe, no es PDF o imagen, pesa más de 10 MB
     * o no se pudo copiar.
     */
    public String store(Path source, String folder) {
        if (source == null || !Files.isRegularFile(source)) {
            throw new FileUploadException("No se encontró el archivo seleccionado. Vuelva a adjuntarlo.");
        }
        String extension = extension(source.getFileName().toString());
        if (!EXTENSIONS.contains(extension)) {
            throw new FileUploadException("El archivo debe ser PDF, PNG o JPG.");
        }
        try {
            if (Files.size(source) > MAX_BYTES) {
                throw new FileUploadException("El archivo pesa más de 10 MB. Adjunte uno más liviano.");
            }
            Path dir = baseDir.resolve(safe(folder)).normalize();
            if (!dir.startsWith(baseDir)) {
                throw new FileUploadException("Carpeta de destino no válida.");
            }
            Files.createDirectories(dir);
            Path target = dir.resolve(UUID.randomUUID() + "-" + safe(source.getFileName().toString()));
            Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES);
            return target.toUri().toString();
        } catch (IOException e) {
            throw new FileUploadException("No se pudo guardar el archivo. Intente de nuevo.");
        }
    }

    /** Borra un archivo guardado por esta clase (cuando la operación que lo usaba no se guardó). */
    public void delete(String url) {
        if (url == null) {
            return;
        }
        try {
            Path path = Paths.get(URI.create(url)).toAbsolutePath().normalize();
            if (path.startsWith(baseDir)) {
                Files.deleteIfExists(path);
            }
        } catch (IllegalArgumentException | IOException ignored) {
            // Si no se puede borrar, queda un archivo huérfano en la carpeta; no afecta los datos.
        }
    }

    public Path getBaseDir() {
        return baseDir;
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** Solo letras, números, punto, guion y guion bajo: evita rutas raras en el nombre. */
    private static String safe(String text) {
        String clean = text == null ? "" : text.replaceAll("[^A-Za-z0-9._-]", "_");
        return clean.isBlank() ? "archivo" : clean;
    }
}
