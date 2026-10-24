
package com.example.funerariamc.model.enums;

/** 
 * Types of clients registered in the system. 
*/
public enum ClientType {
    NATURAL,
    CORPORATE;

    /**
     * Returns the display name in Spanish 
     */
    public String getDisplayName(){
        return this == NATURAL ? "Natural" : "Empresa";
    }

    /**
     * Returns the description of the cliente type.
     */
    public String getDescription() {
        return this == NATURAL ? "Persona natural" : "Empresa o entidad";
    }

    /**
     * Indicates whether the client is a company or entity.
     */
    public boolean isCorporate() {
        return this == CORPORATE;
    }
}
