package dev.juanse.tiktokmobs.plugin.source;

import dev.juanse.tiktokmobs.core.event.EventType;
import dev.juanse.tiktokmobs.core.event.GiftEvent;
import dev.juanse.tiktokmobs.core.event.GiftInfo;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TikFinityParserTest {

    @Test
    void regaloConElSobreHabitualDeTikFinity() {
        String json = """
                {"event":"gift","data":{
                  "msgId":"7312",
                  "userId":"6789",
                  "uniqueId":"juanse.dev",
                  "nickname":"Juan Sebastián",
                  "giftId":5655,
                  "giftName":"Rose",
                  "diamondCount":1,
                  "repeatCount":30,
                  "repeatEnd":true,
                  "giftType":1
                }}
                """;

        GiftEvent event = TikFinityParser.parse(json).orElseThrow();

        assertEquals(EventType.GIFT, event.type());
        assertEquals("7312", event.id());
        assertEquals("Juan Sebastián", event.user().nickname());

        GiftInfo gift = event.gift();
        assertEquals(5655, gift.id());
        assertEquals("Rose", gift.name());
        assertEquals(30, gift.count());
        assertEquals(30, gift.totalCoins());
        assertTrue(gift.streakable());
        assertTrue(gift.repeatEnd());
    }

    @Test
    void rachaAbiertaSeMarcaComoTalYNoDebeEjecutarse() {
        String json = """
                {"event":"gift","data":{"giftId":5655,"giftName":"Rose","diamondCount":1,
                  "repeatCount":7,"repeatEnd":false,"giftType":1}}
                """;

        GiftInfo gift = TikFinityParser.parse(json).orElseThrow().gift();

        assertTrue(gift.isOpenStreak(), "un evento intermedio de racha no debe disparar nada");
    }

    @Test
    void aceptaRepeatEndComoNumero() {
        String json = """
                {"event":"gift","data":{"giftName":"Rose","diamondCount":1,
                  "repeatCount":3,"repeatEnd":1,"giftType":1}}
                """;

        assertTrue(TikFinityParser.parse(json).orElseThrow().gift().repeatEnd());
    }

    @Test
    void aceptaCamposEnSnakeCase() {
        String json = """
                {"type":"gift","data":{"gift_id":99,"gift_name":"Galaxy","diamond_count":1000,
                  "repeat_count":1,"repeat_end":true,"gift_type":0,"unique_id":"ana"}}
                """;

        GiftEvent event = TikFinityParser.parse(json).orElseThrow();

        assertEquals(99, event.gift().id());
        assertEquals(1000, event.gift().totalCoins());
        assertEquals("ana", event.user().uniqueId());
        assertFalse(event.gift().streakable());
    }

    @Test
    void sinSobreNiCampoEventPeroConDatosDeRegalo() {
        String json = """
                {"giftName":"Rose","diamondCount":1,"repeatCount":1,"nickname":"pepito"}
                """;

        GiftEvent event = TikFinityParser.parse(json).orElseThrow();

        assertEquals(EventType.GIFT, event.type());
        assertEquals("pepito", event.user().nickname());
    }

    @Test
    void siFaltaRepeatEndAsumimosCierreParaNoPerderLaDonacion() {
        String json = """
                {"event":"gift","data":{"giftName":"TikTok","diamondCount":1,"repeatCount":1}}
                """;

        assertTrue(TikFinityParser.parse(json).orElseThrow().gift().repeatEnd());
    }

    @Test
    void likesConCualquierAlias() {
        assertEquals(42, TikFinityParser.parse(
                """
                {"event":"like","data":{"likeCount":42,"uniqueId":"ana"}}
                """).orElseThrow().likeCount());

        assertEquals(7, TikFinityParser.parse(
                """
                {"event":"like","data":{"likes":7}}
                """).orElseThrow().likeCount());
    }

    @Test
    void follows() {
        assertEquals(EventType.FOLLOW, TikFinityParser.parse(
                """
                {"event":"follow","data":{"uniqueId":"ana"}}
                """).orElseThrow().type());
    }

    @Test
    void basuraYEventosDesconocidosSeDescartanSinLanzar() {
        assertTrue(TikFinityParser.parse("no soy json").isEmpty());
        assertTrue(TikFinityParser.parse("[1,2,3]").isEmpty());
        assertTrue(TikFinityParser.parse("{\"event\":\"config\",\"data\":{}}").isEmpty());
        assertTrue(TikFinityParser.parse("{}").isEmpty());
    }

    @Test
    void elRegaloTraeLaUrlDeSuImagen() {
        // Es la URL del CDN de TikTok que manda el propio evento: con ella el panel y el
        // catalogo enseñan el icono de verdad sin que nosotros guardemos nada ajeno.
        GiftEvent evento = TikFinityParser.parse("""
                {"event":"gift","data":{"uniqueId":"ana","giftId":5655,"giftName":"Rosa",
                 "diamondCount":1,"giftPictureUrl":"https://p16.tiktokcdn.com/rosa.webp"}}
                """).orElseThrow();

        assertEquals("https://p16.tiktokcdn.com/rosa.webp", evento.gift().imageUrl());
    }

    @Test
    void laImagenAceptaLosAliasConocidos() {
        for (String campo : new String[]{"giftPictureUrl", "giftImage", "image", "icon"}) {
            GiftEvent evento = TikFinityParser.parse(
                    "{\"event\":\"gift\",\"data\":{\"giftId\":1,\"giftName\":\"x\",\"" + campo
                            + "\":\"http://img\"}}").orElseThrow();
            assertEquals("http://img", evento.gift().imageUrl(), "fallo con el campo " + campo);
        }
    }

    @Test
    void sinImagenNoPasaNada() {
        GiftEvent evento = TikFinityParser.parse(
                "{\"event\":\"gift\",\"data\":{\"giftId\":1,\"giftName\":\"x\"}}").orElseThrow();

        assertEquals("", evento.gift().imageUrl(), "nunca null: el resto del codigo no lo espera");
    }

    @Test
    void elComentarioLlegaConSuTexto() {
        GiftEvent evento = TikFinityParser.parse("""
                {"event":"chat","data":{"uniqueId":"ana","nickname":"Ana","comment":"hola juanse"}}
                """).orElseThrow();

        assertEquals(dev.juanse.tiktokmobs.core.event.EventType.COMMENT, evento.type());
        assertEquals("hola juanse", evento.comment());
        assertEquals("Ana", evento.user().rawLabel());
    }

    @Test
    void elComentarioAceptaLosAliasDeTexto() {
        // TikFinity no documenta el nombre del campo y cambia entre versiones.
        for (String campo : new String[]{"comment", "message", "text", "content", "chatMessage"}) {
            GiftEvent evento = TikFinityParser.parse(
                    "{\"event\":\"comment\",\"data\":{\"uniqueId\":\"ana\",\"" + campo + "\":\"ey\"}}")
                    .orElseThrow(() -> new AssertionError("no parseo con el campo " + campo));
            assertEquals("ey", evento.comment(), "fallo con el campo " + campo);
        }
    }

    @Test
    void unComentarioVacioNiSiquieraSeEncola() {
        // Si no hay texto no hay nada que enseñar: mejor descartarlo en el parser que
        // gastar un hueco de la cola y un tick del servidor en una linea en blanco.
        assertTrue(TikFinityParser.parse(
                "{\"event\":\"chat\",\"data\":{\"uniqueId\":\"ana\",\"comment\":\"\"}}").isEmpty());
        assertTrue(TikFinityParser.parse(
                "{\"event\":\"chat\",\"data\":{\"uniqueId\":\"ana\"}}").isEmpty());
    }

    @Test
    void elHandshakeDeConfigNoSeConfundeConUnRegalo() {
        Optional<GiftEvent> parsed = TikFinityParser.parse(
                "{\"event\":\"config\",\"data\":{\"uniqueId\":\"juanse\"}}");

        assertTrue(parsed.isEmpty());
    }
}
