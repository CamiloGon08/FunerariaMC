package com.example.funerariamc.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class AppModuleTest {

    @ParameterizedTest
    @MethodSource("permissions")
    void rolesSeeExactlyTheModulesFromTheEp01Contract(String role, Set<AppModule> expected) {
        Set<AppModule> actual = Arrays.stream(AppModule.values())
                .filter(module -> module.isAllowedFor(role))
                .collect(Collectors.toSet());
        assertEquals(expected, actual);
    }

    static Stream<Arguments> permissions() {
        return Stream.of(
                Arguments.of(RoleNames.MASTER_ADMIN, EnumSet.allOf(AppModule.class)),
                Arguments.of(RoleNames.CEMETERY_ADMIN, EnumSet.of(AppModule.DIFUNTOS,
                        AppModule.CEMENTERIO, AppModule.CREMACIONES, AppModule.INVENTARIO)),
                Arguments.of(RoleNames.SALESPERSON, EnumSet.of(AppModule.CLIENTES,
                        AppModule.PLANES, AppModule.CONTRATOS)),
                Arguments.of(RoleNames.SECRETARY, EnumSet.of(AppModule.CLIENTES,
                        AppModule.DIFUNTOS, AppModule.PLANES, AppModule.SALAS,
                        AppModule.CONTRATOS, AppModule.TRANSPORTE, AppModule.FINANZAS))
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "UNKNOWN_ROLE", "master_admin", " MASTER_ADMIN", "Administrador Maestro"})
    void unknownOrAlteredRoleNamesHaveNoAccess(String role) {
        assertTrue(Arrays.stream(AppModule.values()).noneMatch(module -> module.isAllowedFor(role)));
    }

    @Test
    void missingRoleHasNoAccess() {
        assertTrue(Arrays.stream(AppModule.values()).noneMatch(module -> module.isAllowedFor(null)));
    }
}
