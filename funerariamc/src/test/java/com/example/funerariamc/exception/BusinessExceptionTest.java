package com.example.funerariamc.exception;

import java.lang.reflect.Modifier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BusinessExceptionTest {

    @Test
    void businessExceptionIsAbstractAndUnchecked() throws Exception {
        assertAll(
                () -> assertTrue(Modifier.isAbstract(BusinessException.class.getModifiers())),
                () -> assertEquals(RuntimeException.class, BusinessException.class.getSuperclass()),
                () -> assertTrue(Modifier.isProtected(
                        BusinessException.class.getDeclaredConstructor(String.class).getModifiers()))
        );
    }
}
