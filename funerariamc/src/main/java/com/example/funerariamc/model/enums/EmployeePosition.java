package com.example.funerariamc.model.enums;

import com.example.funerariamc.model.CafeteriaAttendant;
import com.example.funerariamc.model.Driver;
import com.example.funerariamc.model.Employee;
import com.example.funerariamc.model.FuneralSalesperson;
import com.example.funerariamc.model.Manager;
import com.example.funerariamc.model.Receptionist;
import com.example.funerariamc.model.Thanatopractor;
import com.example.funerariamc.model.ThanatopraxyAssistant;

import java.util.function.Supplier;

/** Defines employee positions and their corresponding entity types. */
public enum EmployeePosition {

    MANAGER("Administrador", Manager.class, Manager::new),
    RECEPTIONIST("Secretaría", Receptionist.class, Receptionist::new),
    FUNERAL_SALESPERSON("Vendedor funerario", FuneralSalesperson.class, FuneralSalesperson::new),
    THANATOPRACTOR("Tanatólogo", Thanatopractor.class, Thanatopractor::new),
    THANATOPRAXY_ASSISTANT("Auxiliar de tanatopraxia", ThanatopraxyAssistant.class, ThanatopraxyAssistant::new),
    DRIVER("Conductor", Driver.class, Driver::new),
    CAFETERIA_ATTENDANT("Auxiliar de cafetería", CafeteriaAttendant.class, CafeteriaAttendant::new);

    private final String displayName;
    private final Class<? extends Employee> type;
    private final Supplier<? extends Employee> factory;

    EmployeePosition(
            String displayName,
            Class<? extends Employee> type,
            Supplier<? extends Employee> factory) {
        this.displayName = displayName;
        this.type = type;
        this.factory = factory;
    }

    /** Returns the position's display name. */
    public String getDisplayName() {
        return displayName;
    }

    /** Returns the employee entity class. */
    public Class<? extends Employee> getType() {
        return type;
    }

    /** Creates an employee of this position. */
    public Employee newEmployee() {
        return factory.get();
    }

    /** Finds the position of an employee. */
    public static EmployeePosition of(Employee employee) {
        for (EmployeePosition position : values()) {
            if (position.type.isInstance(employee)) {
                return position;
            }
        }

        throw new IllegalArgumentException(
                "Unknown position: " + employee.getClass().getSimpleName());
    }

    /** Checks whether the position can have a user account. */
    public boolean canHaveUserAccount() {
        return this == MANAGER
                || this == RECEPTIONIST
                || this == FUNERAL_SALESPERSON;
    }
}
