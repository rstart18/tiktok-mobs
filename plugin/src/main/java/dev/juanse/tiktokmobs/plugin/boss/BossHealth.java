package dev.juanse.tiktokmobs.plugin.boss;

import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.LivingEntity;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * Poner una vida máxima exacta sin casarse con el enum de atributos.
 *
 * <p>El enum {@code Attribute} se renombró entre 1.21.x ({@code GENERIC_MAX_HEALTH} →
 * {@code MAX_HEALTH}), así que referenciar la constante compila hoy y rompe el jar al cambiar
 * de servidor. La <b>clave</b> del atributo ({@code minecraft:max_health}) no ha cambiado
 * nunca, así que se busca por registro.
 *
 * <p>Si en algún servidor eso falla, queda el plan B de siempre: {@code HEALTH_BOOST}, que
 * sube la vida de 4 en 4 y por tanto no es exacto, pero no deja al jefe con 20 de vida.
 */
public final class BossHealth {

    private static final int LONG_DURATION_TICKS = 20 * 60 * 60;

    private BossHealth() {
    }

    /**
     * Fija la vida máxima.
     *
     * @param vanillaMax vida máxima original de la entidad, leída justo al spawnear. Solo la
     *                   usa el plan B, que trabaja sumando sobre la base vanilla.
     * @return la vida máxima que quedó de verdad (puede no ser exacta con el plan B)
     */
    public static double setMax(LivingEntity living, double target, double vanillaMax) {
        double wanted = Math.max(1.0, target);

        try {
            Attribute attribute = Registry.ATTRIBUTE.get(NamespacedKey.minecraft("max_health"));
            if (attribute != null) {
                AttributeInstance instance = living.getAttribute(attribute);
                if (instance != null) {
                    instance.setBaseValue(wanted);
                    return wanted;
                }
            }
        } catch (Throwable ignored) {
            // NoSuchFieldError / NoClassDefFoundError incluidos: esta versión no lo soporta.
        }

        return viaHealthBoost(living, wanted, vanillaMax);
    }

    private static double viaHealthBoost(LivingEntity living, double target, double vanillaMax) {
        double base = vanillaMax <= 0 ? living.getHealth() : vanillaMax;
        if (target <= base) return base;

        // HEALTH_BOOST da +4 de vida por nivel.
        int amplifier = (int) Math.round((target - base) / 4.0) - 1;
        amplifier = Math.max(0, Math.min(amplifier, 120));

        living.removePotionEffect(PotionEffectType.HEALTH_BOOST);
        living.addPotionEffect(new PotionEffect(
                PotionEffectType.HEALTH_BOOST, LONG_DURATION_TICKS, amplifier, false, false));

        return base + (amplifier + 1) * 4.0;
    }

    /** Cura sin pasarse del máximo: setHealth lanza si te pasas, y eso cortaría la pelea. */
    public static void heal(LivingEntity living, double amount, double max) {
        if (amount <= 0) return;
        try {
            living.setHealth(Math.min(max, living.getHealth() + amount));
        } catch (IllegalArgumentException ignored) {
            // Ya estaba a tope, o el modificador aún no es visible en este tick.
        }
    }

    /**
     * Resuelve un efecto por nombre probando alternativas.
     *
     * <p>{@code INCREASE_DAMAGE} pasó a llamarse {@code STRENGTH}, y el enum ya no es enum en
     * las versiones nuevas. Buscar por nombre es lo único que sobrevive a las dos ramas.
     */
    @SuppressWarnings("deprecation")
    public static PotionEffectType effectByName(String... names) {
        for (String name : names) {
            try {
                PotionEffectType type = PotionEffectType.getByName(name);
                if (type != null) return type;
            } catch (RuntimeException | LinkageError ignored) {
                // Siguiente candidato.
            }
        }
        return null;
    }
}
