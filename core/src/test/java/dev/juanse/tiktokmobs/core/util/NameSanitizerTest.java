package dev.juanse.tiktokmobs.core.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NameSanitizerTest {

    @Test
    void quitaCodigosDeColor() {
        assertEquals("hola", NameSanitizer.clean("§chola"));
        assertEquals("hola", NameSanitizer.clean("&lhola"));
    }

    @Test
    void quitaSaltosDeLineaQuePartirianElChat() {
        assertEquals("hola admin", NameSanitizer.clean("hola\nadmin"));
    }

    @Test
    void neutralizaIntentoDeInyeccionDeComandos() {
        String nick = "x; op x";
        String clean = NameSanitizer.clean(nick);
        assertEquals("x op x", clean);
        assertFalse(NameSanitizer.isCommandSafe(nick));
    }

    @Test
    void recortaAlLimiteDelNameplate() {
        assertEquals(NameSanitizer.MAX_LENGTH,
                NameSanitizer.clean("abcdefghijklmnopqrstuvwxyz").length());
    }

    @Test
    void quitaElPrefijoDeFloodgate() {
        assertEquals("jugadorBedrock", NameSanitizer.clean(".jugadorBedrock"));
    }

    @Test
    void nickVacioOSoloSimbolosCaeEnAnon() {
        assertEquals("anon", NameSanitizer.clean(""));
        assertEquals("anon", NameSanitizer.clean("!!!@@@"));
        assertEquals("anon", NameSanitizer.clean(null));
    }

    @Test
    void conservaAcentosYEmojisFuera() {
        assertEquals("Sebastián", NameSanitizer.clean("Sebastián"));
    }

    @Test
    void unNickNormalEsSeguroParaComandos() {
        assertTrue(NameSanitizer.isCommandSafe("juanse"));
    }

    @Test
    void elComentarioConservaLosSignosPeroPierdeLosColores() {
        // Un comentario no es un nick: si le quitas los signos queda ilegible. Lo que si
        // se va son los codigos de color, que es como un viewer falsifica una linea de chat.
        assertEquals("¿vas a morir? jajaja!!",
                NameSanitizer.cleanMessage("¿vas a morir? jajaja!!"));
        assertEquals("hola", NameSanitizer.cleanMessage("&chola"));
        assertEquals("hola", NameSanitizer.cleanMessage("§lhola"));
    }

    @Test
    void elComentarioNoPuedePartirElChatEnDos() {
        assertEquals("hola admin", NameSanitizer.cleanMessage("hola\nadmin"));
    }

    @Test
    void elComentarioLargoSeCorta() {
        String limpio = NameSanitizer.cleanMessage("a".repeat(400));

        assertTrue(limpio.length() <= NameSanitizer.MAX_MESSAGE_LENGTH + 3,
                "se fue a " + limpio.length() + " caracteres");
        assertTrue(limpio.endsWith("..."));
    }

    @Test
    void elComentarioVacioNoDaNada() {
        assertEquals("", NameSanitizer.cleanMessage(null));
        assertEquals("", NameSanitizer.cleanMessage("   "));
    }
}
