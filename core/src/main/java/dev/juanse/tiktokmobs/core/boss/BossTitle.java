package dev.juanse.tiktokmobs.core.boss;

import java.util.ArrayList;
import java.util.List;

/**
 * El nombre del jefe cuando lo paga más de una persona.
 *
 * <p>Parece cosmético y es lo contrario: es el motivo por el que un segundo donante manda
 * mientras el jefe está vivo. Ver tu nombre pegado al de otro es la recompensa.
 *
 * <p>Con un límite, eso sí: sin él, cinco donantes dejan un cartel de media pantalla encima
 * del bicho y ya no se ve la pelea.
 */
public final class BossTitle {

    /** Separador de la lista guardada en la PDC. NameSanitizer nunca deja pasar un '|'. */
    public static final String SEPARATOR = "|";

    /** Cuántos nombres se enseñan antes de resumir en "y N más". */
    public static final int VISIBLE = 3;

    /** Cuántos se guardan como máximo: la PDC no es un sitio para una lista infinita. */
    public static final int STORED = 8;

    private BossTitle() {
    }

    /**
     * @param donors   en orden de llegada; el primero es quien lo invocó
     * @param one      plantilla para un solo donante
     * @param many     plantilla para dos o más
     */
    public static String compose(List<String> donors, String one, String many) {
        List<String> clean = clean(donors);
        if (clean.isEmpty()) return "";

        String names = join(clean);
        String template = clean.size() == 1 ? one : many;
        if (template == null || template.isBlank()) return names;

        return template
                .replace("{donadores}", names)
                .replace("{donador}", names);
    }

    /** "Ana" / "Ana y Pedro" / "Ana, Pedro y Luis" / "Ana, Pedro y 3 más". */
    public static String join(List<String> donors) {
        List<String> clean = clean(donors);
        if (clean.isEmpty()) return "";
        if (clean.size() == 1) return clean.get(0);

        if (clean.size() <= VISIBLE) {
            String head = String.join(", ", clean.subList(0, clean.size() - 1));
            return head + " y " + clean.get(clean.size() - 1);
        }

        String head = String.join(", ", clean.subList(0, VISIBLE - 1));
        return head + " y " + (clean.size() - (VISIBLE - 1)) + " más";
    }

    /** Añade un donante si no estaba ya, respetando el tope de guardados. */
    public static List<String> add(List<String> donors, String donor) {
        List<String> out = new ArrayList<>(clean(donors));
        String name = donor == null ? "" : donor.trim();

        if (name.isEmpty() || out.contains(name)) return out;
        if (out.size() >= STORED) return out;

        out.add(name);
        return out;
    }

    /** Lista -> cadena para guardar en la PersistentDataContainer. */
    public static String store(List<String> donors) {
        return String.join(SEPARATOR, clean(donors));
    }

    /** Cadena guardada -> lista. */
    public static List<String> parse(String stored) {
        if (stored == null || stored.isBlank()) return List.of();

        List<String> out = new ArrayList<>();
        for (String part : stored.split("\\|")) {
            String name = part.trim();
            if (!name.isEmpty() && !out.contains(name)) out.add(name);
        }
        return List.copyOf(out);
    }

    private static List<String> clean(List<String> donors) {
        if (donors == null || donors.isEmpty()) return List.of();

        List<String> out = new ArrayList<>(donors.size());
        for (String donor : donors) {
            String name = donor == null ? "" : donor.trim();
            if (!name.isEmpty() && !out.contains(name)) out.add(name);
        }
        return out;
    }
}
