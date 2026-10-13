package com.example.funerariamc.exception;

/** RN-19: un empleado inactivo no puede realizar la operación solicitada. */
public class InactiveEmployeeException extends BusinessException {

    private static final long serialVersionUID = 1L;

    public InactiveEmployeeException() {
        super("El empleado está inactivo y no puede realizar esta operación.");
    }
}
