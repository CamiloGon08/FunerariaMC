package com.example.funerariamc;

import com.example.funerariamc.config.JavaFxApplication;
import javafx.application.Application;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.Locale;

/**
 * Punto de entrada. Lanza JavaFX, que a su vez levanta el contexto de Spring
 * en {@link JavaFxApplication#init()}.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class FunerariamcApplication {

	public static void main(String[] args) {
		// Calendarios y fechas en español aunque Windows esté en otro idioma.
		Locale.setDefault(Locale.forLanguageTag("es-CO"));
		Application.launch(JavaFxApplication.class, args);
	}

	/** Confirma la conexión a Supabase al arrancar  */
	@Bean
	CommandLineRunner printDatabaseVersion(DataSource dataSource) {
		return args -> {
			try (Connection connection = dataSource.getConnection()) {
				System.out.println("Database version: "
						+ connection.getMetaData().getDatabaseProductVersion());
			}
		};
	}

}