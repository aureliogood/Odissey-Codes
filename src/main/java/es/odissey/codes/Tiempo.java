package es.odissey.codes;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Utilidades para leer tiempos del config:
 *   - duraciones: "30m", "12h", "7d", "2w" o combinadas como "1d12h"
 *   - fechas: "31/12/2026", "31/12/2026 23:59", "2026-12-31", "2026-12-31 23:59"
 */
public final class Tiempo {

    private Tiempo() {
    }

    /** Milisegundos de una duracion, o -1 si no es valida. */
    public static long duracion(String texto) {
        if (texto == null) return -1;
        String s = texto.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return -1;
        long total = 0;
        int i = 0;
        boolean hubo = false;
        while (i < s.length()) {
            int ini = i;
            while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
            if (i == ini) return -1; // falta el numero
            long n;
            try {
                n = Long.parseLong(s.substring(ini, i));
            } catch (NumberFormatException e) {
                return -1;
            }
            if (i >= s.length()) return -1; // falta la unidad
            char u = s.charAt(i++);
            long ms;
            switch (u) {
                case 's': ms = 1_000L; break;
                case 'm': ms = 60_000L; break;
                case 'h': ms = 3_600_000L; break;
                case 'd': ms = 86_400_000L; break;
                case 'w': ms = 604_800_000L; break;
                default: return -1;
            }
            if (n > (Long.MAX_VALUE - total) / ms) return -1;
            total += n * ms;
            hubo = true;
        }
        return hubo ? total : -1;
    }

    /** Instante (epoch ms) de una fecha, o -1 si no es valida. */
    public static long absoluta(String texto) {
        if (texto == null) return -1;
        String s = texto.trim();
        if (s.isEmpty()) return -1;
        DateTimeFormatter[] conHora = {
                DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"),
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        };
        for (DateTimeFormatter f : conHora) {
            try {
                return LocalDateTime.parse(s, f).atZone(ZoneId.systemDefault())
                        .toInstant().toEpochMilli();
            } catch (Exception ignored) {
                // se prueba con el siguiente formato
            }
        }
        DateTimeFormatter[] soloFecha = {
                DateTimeFormatter.ofPattern("dd/MM/yyyy"),
                DateTimeFormatter.ofPattern("yyyy-MM-dd")
        };
        for (DateTimeFormatter f : soloFecha) {
            try {
                return LocalDate.parse(s, f).atStartOfDay(ZoneId.systemDefault())
                        .toInstant().toEpochMilli();
            } catch (Exception ignored) {
                // se prueba con el siguiente formato
            }
        }
        return -1;
    }

    private static final DateTimeFormatter SALIDA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    /** Formatea un instante para mostrarlo. "-" si no hay fecha. */
    public static String fecha(long epochMs) {
        if (epochMs <= 0) return "-";
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneId.systemDefault())
                .format(SALIDA);
    }

    /** true si el texto significa "sin caducidad". */
    public static boolean sinCaducidad(String texto) {
        if (texto == null) return false;
        String s = texto.trim().toLowerCase(Locale.ROOT);
        return s.equals("0") || s.equals("none") || s.equals("perm")
                || s.equals("permanent") || s.equals("nunca");
    }

    /** Texto legible de una duracion en milisegundos, p.ej. "1d 2h 30m". */
    public static String humano(long ms) {
        if (ms <= 0) return "0s";
        long s = ms / 1000L;
        long d = s / 86400L; s %= 86400L;
        long h = s / 3600L; s %= 3600L;
        long m = s / 60L; s %= 60L;
        StringBuilder sb = new StringBuilder();
        if (d > 0) sb.append(d).append("d ");
        if (h > 0) sb.append(h).append("h ");
        if (m > 0) sb.append(m).append("m ");
        if (s > 0 || sb.length() == 0) sb.append(s).append("s");
        return sb.toString().trim();
    }
}
