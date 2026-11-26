package com.example.funerariamc.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WordSearchTest {

    @Test
    void matchesOnlyTheStartOfAWordIgnoringAccentsAndCase() {
        assertTrue(WordSearch.matches("Jesús María Daza", "j"));
        assertTrue(WordSearch.matches("Hernan Jose Devilleros", "J"));
        assertTrue(WordSearch.matches("Jesús María Daza", "maria"));
        assertFalse(WordSearch.matches("Edilma Rosa Cujia", "j"));
        assertFalse(WordSearch.matches("Daniela Alejandra Fragozo", "j"));
        assertTrue(WordSearch.matches("Hernan Jose Devilleros", "jose dev"));
        assertTrue(WordSearch.matches("SC-2026-0005", "0005"));
        assertTrue(WordSearch.matches("cualquiera", "  "));
    }

    @Test
    void namesThatStartWithTheTextGoFirstKeepingTheirOrder() {
        List<String> names = List.of("Hernan Jose Devilleros", "Jesús María Daza", "Marlon Jose", "Jesus David");
        assertEquals(List.of("Jesús María Daza", "Jesus David", "Hernan Jose Devilleros", "Marlon Jose"),
                WordSearch.startsFirst(names, "j", Function.identity()));
        assertEquals(names, WordSearch.startsFirst(names, "", Function.identity()));
    }

    @Test
    void sqlPatternLooksForTheStartOfAWord() {
        assertEquals("% duran%", WordSearch.sqlPattern(" Durán "));
        assertEquals("%", WordSearch.sqlPattern(null));
    }
}
