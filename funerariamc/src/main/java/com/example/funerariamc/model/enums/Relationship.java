package com.example.funerariamc.model.enums;

public enum Relationship {

    SPOUSE("Cónyuge o compañero(a)", true),
    CHILD("Hijo(a)", true),
    PARENT("Padre o madre", true),
    SIBLING("Hermano(a)", false),
    GRANDCHILD("Nieto(a)", false),
    GRANDPARENT("Abuelo(a)", false),
    IN_LAW("Suegro(a), yerno o nuera", false),
    EMPLOYEE("Empleado (plan empresarial)", true),
    OTHER("Otro", false);

    private final String displayName;
    private final boolean direct;

    Relationship(String displayName, boolean direct) {

        this.displayName = displayName;
        this.direct = direct;

    }

    public String getDisplayName() {
        return  displayName;
    }

    public boolean isDirect() {
        return direct;
    }

    public static Relationship fromCode(String code) {
        if (code == null) {
            return  null;
        }
        try {
            return  valueOf(code.trim());
        } catch (IllegalArgumentException e) {
            return  null;
        }
    }
    
}
