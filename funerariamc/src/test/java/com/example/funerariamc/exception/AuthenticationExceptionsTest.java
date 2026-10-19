package com.example.funerariamc.exception;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class AuthenticationExceptionsTest {

    @ParameterizedTest
    @MethodSource("businessFailures")
    void failuresKeepBusinessTypeAndUserMessage(BusinessException failure, String message) {
        assertAll(
                () -> assertInstanceOf(RuntimeException.class, failure),
                () -> assertEquals(message, failure.getMessage())
        );
    }

    static Stream<Arguments> businessFailures() {
        return Stream.of(
                Arguments.of(AuthenticationFailedException.invalidCredentials(),
                        "Usuario o contraseña incorrectos"),
                Arguments.of(AuthenticationFailedException.disabledAccount(),
                        "La cuenta está deshabilitada. Comuníquese con el Administrador Maestro."),
                Arguments.of(AuthenticationFailedException.withoutRole(),
                        "El usuario no tiene un rol asignado y no puede ingresar al sistema."),
                Arguments.of(InvalidResetCodeException.invalidCode(), "El código no es válido."),
                Arguments.of(InvalidResetCodeException.expired(),
                        "El código venció o ya no es válido. Solicite uno nuevo."),
                Arguments.of(InvalidResetCodeException.weakPassword(8),
                        "La nueva contraseña debe tener al menos 8 caracteres."),
                Arguments.of(InvalidResetCodeException.deliveryFailed(),
                        "No se pudo enviar el código. Intente de nuevo o comuníquese con el Administrador Maestro.")
        );
    }

    @Test
    void passwordPolicyMessageUsesTheReceivedMinimum() {
        assertEquals("La nueva contraseña debe tener al menos 12 caracteres.",
                InvalidResetCodeException.weakPassword(12).getMessage());
    }
}
