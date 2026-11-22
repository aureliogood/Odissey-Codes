package es.odissey.codes;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.Statistic;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.io.File;
import java.io.IOException;
import org.bukkit.configuration.file.YamlConfiguration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * OdisseyCodes: el jugador escribe un codigo en un menu de dialogo y recibe la
 * recompensa que el admin tenga configurada para ese codigo.
 *
 * Inspirado en el funcionamiento de AdvancedRedeem (codigos con usos limitados
 * o ilimitados, ejecucion de comandos y proteccion por IP), pero cambiado del
 * todo: alla se canjea con /redeem, aqui se canjea con un dialogo de Paper
 * (1.21.6+), sin escribir el codigo en el chat ni como argumento del comando.
 */
public final class OdisseyCodes extends JavaPlugin {

    // Version del archivo de config. Si sube, asegurarDefaults() añade las
    // claves nuevas que falten sin sobrescribir las que ya tenga el usuario.
    private static final int CONFIG_VERSION = 2;

    // Los codigos cargados, en el orden en que salen en el config.
    private final Map<String, Codigo> codigos = new LinkedHashMap<>();

    // Evita que el mismo canje se procese dos veces a la vez (doble clic o dos
    // dialogos abiertos). La seccion critica de canjear() esta sincronizada.
    private final Object canjeoLock = new Object();
    private final Set<String> enProceso = new HashSet<>();
    private final Map<UUID, Intentos> intentos = new HashMap<>();

    private Almacen store;
    private boolean usaBd;
    private MenuCodigo menu;
    private int syncTask = -1;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        asegurarDefaults();
        elegirAlmacen();
        menu = new MenuCodigo(this);
        Bukkit.getPluginManager().registerEvents(menu, this);
        cargarCodigos();
        sembrarSiVacio();

        ComandoCodes comando = new ComandoCodes(this);
        if (getCommand("codes") != null) {
            getCommand("codes").setExecutor(comando);
            getCommand("codes").setTabCompleter(comando);
        } else {
            getLogger().severe("No se pudo registrar el comando /codes.");
        }
        if (usaBd) iniciarSync();
        avisarEconomia();
        getLogger().info("OdisseyCodes activado con " + codigos.size() + " codigo(s) ["
                + getConfig().getString("storage.type", "yaml") + "].");
    }

    /**
     * Añade al config las claves nuevas que falten (sonidos, interruptores de
     * efectos y mensajes nuevos) SIN sobrescribir nunca lo que ya tenga el
     * usuario. Tambien migra el prefijo por defecto antiguo al formato Odissey.
     * Asi las instalaciones existentes siguen funcionando igual y solo ganan
     * las opciones nuevas.
     */
    private void asegurarDefaults() {
        boolean cambios = false;

        if (!getConfig().isSet("config-version")) {
            getConfig().set("config-version", CONFIG_VERSION);
            cambios = true;
        }

        // Migra SOLO el prefijo por defecto antiguo (si el usuario lo cambio a
        // mano, se respeta).
        String prefix = getConfig().getString("messages.prefix", "");
        if ("&8[&6Odissey &eCodes&8] &r".equals(prefix)
                || "&8[&6Odissey &eCodes&8]".equals(prefix)) {
            getConfig().set("messages.prefix", "&8[&eODISSEY&8] &r");
            cambios = true;
        }

        // Interruptores generales de efectos.
        cambios |= ponerSiFalta("sounds.enabled", true);
        cambios |= ponerSiFalta("particles.enabled", true);
        cambios |= ponerSiFalta("titles.enabled", true);
        cambios |= ponerSiFalta("security.max-failed-attempts", 5);
        cambios |= ponerSiFalta("security.lockout-seconds", 60);

        // Sonidos nuevos.
        cambios |= ponerSiFalta("sounds.reward", "ui.toast.challenge_complete");
        cambios |= ponerSiFalta("sounds.create", "entity.experience_orb.pickup");
        cambios |= ponerSiFalta("sounds.delete", "entity.item.break");
        cambios |= ponerSiFalta("sounds.select", "ui.button.click");

        // Mensajes nuevos.
        cambios |= ponerSiFalta("messages.reward-failed",
                "&eTu canje se registro, pero una recompensa fallo. Avisa a un administrador.");
        cambios |= ponerSiFalta("messages.rate-limited", "&cDemasiados intentos fallidos. Espera %segundos%s antes de volver a probar.");
        cambios |= ponerSiFalta("messages.state-active", "&aACTIVO");
        cambios |= ponerSiFalta("messages.state-exhausted", "&cAGOTADO");
        cambios |= ponerSiFalta("messages.state-expired", "&6CADUCADO");
        cambios |= ponerSiFalta("messages.state-inactive", "&8INACTIVO");
        cambios |= ponerSiFalta("messages.list-page",
                "&7Pagina &f%page%&7/&f%pages%&7. Usa &f/codes list <pagina>&7.");
        cambios |= ponerSiFalta("messages.info-header", "&8&m-----&r &eCodigo %code% &8&m-----");
        cambios |= ponerSiFalta("messages.info-line", "&7%clave%: &f%valor%");
        cambios |= ponerSiFalta("messages.info-usage", "&eUso: /codes info <codigo>");
        cambios |= ponerSiFalta("messages.enabled-ok", "&aCodigo &e%code%&a activado.");
        cambios |= ponerSiFalta("messages.disabled-ok", "&cCodigo &e%code%&c desactivado.");
        cambios |= ponerSiFalta("messages.enable-usage", "&eUso: /codes enable <codigo>");
        cambios |= ponerSiFalta("messages.disable-usage", "&eUso: /codes disable <codigo>");
        cambios |= ponerSiFalta("messages.delete-usage", "&eUso: /codes delete <codigo>");
        cambios |= ponerSiFalta("messages.deleted",
                "&aCodigo &e%code%&a eliminado (&7%canjes% canjes borrados&a).");
        cambios |= ponerSiFalta("messages.stats-header", "&8&m-----&r &eEstadisticas &8&m-----");
        cambios |= ponerSiFalta("messages.stats-total",
                "&7Codigos: &f%codes% &7| Canjes totales: &f%redemptions%");
        cambios |= ponerSiFalta("messages.stats-item", "&8- &e%code% &7- &f%used% &7canjes");
        cambios |= ponerSiFalta("messages.stats-empty", "&7Aun no hay canjes registrados.");

        if (cambios) saveConfig();
    }

    /** Pone una clave solo si no existe. Devuelve true si la escribio. */
    private boolean ponerSiFalta(String ruta, Object valor) {
        if (getConfig().isSet(ruta)) return false;
        getConfig().set(ruta, valor);
        return true;
    }

    /**
     * Avisa por consola si algun codigo pide dinero pero el servidor no tiene
     * una economia que se pueda leer (Vault + proveedor). Sin eso, la condicion
     * de dinero no se puede comprobar y los codigos afectados no se podran canjear.
     */
    private void avisarEconomia() {
        if (Economia.disponible()) return;
        List<String> conDinero = new ArrayList<>();
        for (Codigo c : codigos.values()) {
            if (c.minDinero > 0) conDinero.add(c.nombre);
        }
        if (conDinero.isEmpty()) return;
        String aviso = Economia.vaultPresente()
                ? "Vault esta instalado pero no tiene ninguna economia registrada."
                : "No hay Vault instalado.";
        getLogger().warning(aviso + " Estos codigos piden dinero y no se podran canjear: "
                + conDinero + ". Instala Vault + un plugin de economia, o quita la condicion 'money'.");
    }

    @Override
    public void onDisable() {
        if (syncTask != -1) {
            Bukkit.getScheduler().cancelTask(syncTask);
            syncTask = -1;
        }
        if (store != null) {
            store.guardar();
            store.cerrar();
        }
    }

    /** Elige el almacen segun el config: yaml (por defecto), sqlite o mysql. */
    private void elegirAlmacen() {
        String tipo = getConfig().getString("storage.type", "yaml");
        tipo = tipo == null ? "yaml" : tipo.trim().toLowerCase(Locale.ROOT);
        if (tipo.equals("mariadb")) tipo = "mysql";
        if (tipo.equals("mysql") || tipo.equals("sqlite")) {
            String prefijo = getConfig().getString("storage.table-prefix", "odisseycodes_");
            Db db = new Db(this, tipo, prefijo);
            if (db.abrir()) {
                store = new SqlStore(this, db);
                usaBd = true;
                return;
            }
            getLogger().warning("No se pudo usar la base de datos; se pasa a almacenamiento YAML.");
        }
        store = new CodesStore(this);
        usaBd = false;
    }

    private void iniciarSync() {
        int seg = Math.max(5, getConfig().getInt("storage.sync-seconds", 30));
        syncTask = Bukkit.getScheduler()
                .runTaskTimerAsynchronously(this, this::refrescarDesdeBd, 20L * seg, 20L * seg)
                .getTaskId();
    }

    /**
     * Vuelve a leer los codigos de la base de datos (los que crea la web/bot).
     *
     * OJO: antes solo copiaba las filas de la BD y perdia las condiciones
     * (rango/mundo/tiempo/dinero) que el config superpone a esos codigos, asi
     * que a los sync-seconds un codigo podia dejar de exigir sus condiciones.
     * Ahora se reusa cargarCodigos(), que si aplica esas condiciones.
     */
    private void refrescarDesdeBd() {
        if (!usaBd) return;
        cargarCodigos();
    }

    /** Recarga el config y vuelve a leer los codigos. */
    public void recargar() {
        reloadConfig();
        cargarCodigos();
    }

    private void cargarCodigos() {
        codigos.clear();
        if (usaBd) {
            // En modo BD los codigos viven en la base de datos, pero si en el
            // config hay un codigo con el MISMO nombre y condiciones, se le
            // aplican (util para exigir rango/mundo/tiempo/dinero sin tocar la BD).
            Map<String, Cond> extra = condicionesDeConfig();
            for (Codigo db : ((SqlStore) store).cargarCodigos()) {
                codigos.put(db.nombre, aplicarCondiciones(db, extra.get(db.nombre.toLowerCase(Locale.ROOT))));
            }
            return;
        }
        for (Codigo c : leerCodigosDeConfig()) {
            codigos.put(c.nombre, c);
        }
    }

    /** Condiciones declaradas en el config (por nombre en minusculas). Solo las que existen. */
    private Map<String, Cond> condicionesDeConfig() {
        Map<String, Cond> out = new HashMap<>();
        ConfigurationSection sec = getConfig().getConfigurationSection("codes");
        if (sec == null) return out;
        for (String clave : sec.getKeys(false)) {
            ConfigurationSection c = sec.getConfigurationSection(clave);
            if (c == null) continue;
            String permiso = c.getString("conditions.permission", c.getString("permission", ""));
            String mundo = c.getString("conditions.world", "");
            long minTiempo = segundosDe(c, "conditions.time-played", "conditions.time_played");
            double minDinero = Math.max(0, c.getDouble("conditions.money", 0));
            if (minTiempo <= 0 && minDinero <= 0
                    && (mundo == null || mundo.isEmpty())
                    && (permiso == null || permiso.isEmpty())) {
                continue;
            }
            out.put(clave.toLowerCase(Locale.ROOT), new Cond(permiso, mundo, minTiempo, minDinero));
        }
        return out;
    }

    /** Devuelve el codigo de la BD con las condiciones del config copiadas encima. */
    private Codigo aplicarCondiciones(Codigo db, Cond cond) {
        if (cond == null) return db;
        return new Codigo(db.nombre, db.usos, db.usosPorJugador, db.comandos, db.caduca,
                cond.permiso, db.activo, cond.minTiempo, cond.minDinero, cond.mundo);
    }

    /** Condiciones sueltas leidas del config, para superponerlas a un codigo de BD. */
    private static final class Cond {
        final String permiso;
        final String mundo;
        final long minTiempo;
        final double minDinero;

        Cond(String permiso, String mundo, long minTiempo, double minDinero) {
            this.permiso = permiso == null ? "" : permiso;
            this.mundo = mundo == null ? "" : mundo;
            this.minTiempo = minTiempo;
            this.minDinero = minDinero;
        }
    }

    /** Codigos declarados en el config: se usan en modo yaml y para sembrar la BD. */
    private List<Codigo> leerCodigosDeConfig() {
        List<Codigo> salida = new ArrayList<>();
        ConfigurationSection sec = getConfig().getConfigurationSection("codes");
        if (sec == null) return salida;
        for (String clave : sec.getKeys(false)) {
            ConfigurationSection c = sec.getConfigurationSection(clave);
            if (c == null) continue;
            int usos = Math.max(0, c.getInt("uses", 1));
            List<String> cmds = new ArrayList<>();
            for (String s : c.getStringList("recompensas")) {
                if (s != null && !s.trim().isEmpty()) cmds.add(s);
            }
            if (cmds.isEmpty()) {
                getLogger().warning("El codigo '" + clave + "' no tiene recompensas; se ignora.");
                continue;
            }
            String permiso = c.getString("conditions.permission", c.getString("permission", ""));
            String mundo = c.getString("conditions.world", "");
            long minTiempo = segundosDe(c, "conditions.time-played", "conditions.time_played");
            double minDinero = Math.max(0, c.getDouble("conditions.money", 0));
            boolean activo = c.getBoolean("enabled", true);
            salida.add(new Codigo(clave, usos,
                    Math.max(1, c.getInt("uses-per-player", 1)),
                    cmds, caducidadDe(clave, c),
                    permiso, activo, minTiempo, minDinero, mundo));
        }
        return salida;
    }

    /** Si la base de datos esta vacia, copia los codigos del config (migracion). */
    private void sembrarSiVacio() {
        if (!usaBd) return;
        if (!getConfig().getBoolean("storage.seed-from-config", true)) return;
        if (!codigos.isEmpty()) return;
        List<Codigo> base = leerCodigosDeConfig();
        if (base.isEmpty()) return;
        SqlStore s = (SqlStore) store;
        for (Codigo c : base) s.insertarCodigo(c);
        cargarCodigos();
        getLogger().info("Base de datos vacia: se copiaron " + base.size() + " codigo(s) del config.");
    }

    /** Instante de caducidad de un codigo (0 = no caduca). */
    private long caducidadDe(String clave, ConfigurationSection c) {
        String fecha = c.getString("expires-at");
        if (fecha != null && !fecha.trim().isEmpty()) {
            long t = Tiempo.absoluta(fecha);
            if (t > 0) return t;
            getLogger().warning("El codigo '" + clave + "' tiene 'expires-at' invalido: " + fecha);
        }
        String dur = c.getString("duration");
        if (dur != null && !dur.trim().isEmpty()) {
            long ms = Tiempo.duracion(dur);
            if (ms > 0) {
                long creado = store.creado(clave, System.currentTimeMillis());
                return creado > Long.MAX_VALUE - ms ? Long.MAX_VALUE : creado + ms;
            }
            getLogger().warning("El codigo '" + clave + "' tiene 'duration' invalido: " + dur);
        }
        return 0L;
    }

    /**
     * Lee un minimo de tiempo jugado del config. Un numero suelto se interpreta
     * en segundos; tambien acepta un tiempo tipo "30m"/"1d" por comodidad.
     */
    private long segundosDe(ConfigurationSection c, String... claves) {
        for (String k : claves) {
            String v = c.getString(k);
            if (v == null || v.trim().isEmpty()) continue;
            v = v.trim();
            if (v.matches("\\d+")) return Long.parseLong(v);
            long ms = Tiempo.duracion(v);
            if (ms > 0) return ms / 1000;
            getLogger().warning("Valor de tiempo invalido en '" + k + "': " + v);
        }
        return 0;
    }

    /** Comprueba las condiciones de un codigo. Devuelve null si se cumplen todas. */
    private Res condiciones(Codigo c, Player jugador) {
        // Requisito global: tiempo minimo jugado en el servidor (config).
        long global = segundosDe(getConfig(), "min-play-time", "min_play_time");
        long jugado = tiempoJugado(jugador);
        if (global > 0 && jugado < global) {
            return new Res(Res.Tipo.CONDICION, c.nombre, "condition-playtime",
                    new String[]{"%tiempo%", Tiempo.humano(global * 1000),
                            "%actual%", Tiempo.humano(jugado * 1000)});
        }
        if (!c.permiso.isEmpty() && !jugador.hasPermission(c.permiso)) {
            return new Res(Res.Tipo.CONDICION, c.nombre, "condition-permission",
                    new String[]{"%permiso%", c.permiso});
        }
        if (!c.mundo.isEmpty() && !jugador.getWorld().getName().equalsIgnoreCase(c.mundo)) {
            return new Res(Res.Tipo.CONDICION, c.nombre, "condition-world",
                    new String[]{"%mundo%", c.mundo});
        }
        if (c.minTiempo > 0 && jugado < c.minTiempo) {
            return new Res(Res.Tipo.CONDICION, c.nombre, "condition-time",
                    new String[]{"%tiempo%", Tiempo.humano(c.minTiempo * 1000),
                            "%actual%", Tiempo.humano(jugado * 1000)});
        }
        // El dinero necesita Vault. Si no hay economia NO se salta la condicion:
        // se avisa y se impide el canje (antes se ignoraba en silencio).
        if (c.minDinero > 0) {
            if (!Economia.disponible()) {
                return new Res(Res.Tipo.CONDICION, c.nombre, "condition-money-novault", null);
            }
            double saldo = Economia.saldo(jugador);
            if (saldo < c.minDinero) {
                return new Res(Res.Tipo.CONDICION, c.nombre, "condition-money",
                        new String[]{"%dinero%", dinero(c.minDinero),
                                "%actual%", dinero(saldo)});
            }
        }
        return null;
    }

    /** Formatea una cantidad de dinero sin decimales de mas. */
    private static String dinero(double v) {
        if (!Double.isInfinite(v) && v == Math.floor(v)) return String.valueOf((long) v);
        return String.format(Locale.ROOT, "%.2f", v);
    }

    /** Segundos de tiempo jugado del jugador. */
    private long tiempoJugado(Player jugador) {
        try {
            return jugador.getStatistic(Statistic.PLAY_ONE_MINUTE) / 20L;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** Busca un codigo. Segun config, con o sin distinguir mayusculas. */
    public Codigo buscar(String texto) {
        if (texto == null) return null;
        String t = texto.trim();
        if (t.isEmpty()) return null;
        boolean ignorar = getConfig().getBoolean("ignore-case", true);
        for (Codigo c : codigos.values()) {
            if (ignorar ? c.nombre.equalsIgnoreCase(t) : c.nombre.equals(t)) return c;
        }
        return null;
    }

    /**
     * Canjea un codigo para un jugador.
     *
     * Toda la comprobacion de limites y el registro del canje se hacen de forma
     * ATOMICA (bajo un cerrojo) y ANTES de ejecutar los comandos: asi un doble
     * clic, dos dialogos abiertos o dos jugadores a la vez no reparten la
     * recompensa dos veces ni se pasan del limite global de usos. Si algun
     * comando de recompensa falla, el canje queda igualmente registrado (no se
     * puede volver a canjear) y se avisa al jugador y a la consola.
     */
    public Res canjear(Player jugador, String textoCodigo) {
        if (textoCodigo == null || textoCodigo.trim().isEmpty()) {
            return new Res(Res.Tipo.VACIO, textoCodigo);
        }
        long restante = bloqueoRestante(jugador.getUniqueId());
        if (restante > 0) return new Res(Res.Tipo.CONDICION, "", "rate-limited",
                new String[]{"%segundos%", String.valueOf(restante)});
        Codigo c = buscar(textoCodigo);
        if (c == null) {
            // Puede ser un codigo de invitacion ("Crea tu propio codigo").
            Referido ref = store.ref(textoCodigo);
            if (ref != null) return canjearRef(jugador, ref);
            registrarIntentoFallido(jugador.getUniqueId());
            return new Res(Res.Tipo.INVALIDO, textoCodigo);
        }
        if (!c.activo) {
            registrarIntentoFallido(jugador.getUniqueId());
            return new Res(Res.Tipo.INVALIDO, c.nombre);
        }

        // Caducidad: si ya paso su fecha, no vale.
        if (c.caduca > 0 && System.currentTimeMillis() > c.caduca) {
            return new Res(Res.Tipo.CADUCADO, c.nombre);
        }
        // Condiciones extra (rango/permiso, mundo, tiempo jugado, dinero).
        Res falta = condiciones(c, jugador);
        if (falta != null) return falta;

        // Seccion critica: comprobar limites + registrar, de forma atomica.
        String clave = jugador.getUniqueId() + "|" + c.nombre.toLowerCase(Locale.ROOT);
        synchronized (canjeoLock) {
            if (!enProceso.add(clave)) {
                // Ese mismo canje ya se esta procesando (doble clic / dos dialogos).
                return new Res(Res.Tipo.YA_USADO, c.nombre);
            }
            try {
                // Limite por jugador (por defecto 1).
                if (store.usadasPor(c.nombre, jugador.getUniqueId()) >= c.usosPorJugador) {
                    return new Res(Res.Tipo.YA_USADO, c.nombre);
                }
                // Una vez por IP: las cuentas alternativas comparten conexion.
                String ip = ipDe(jugador);
                if (getConfig().getBoolean("once-per-ip", true)
                        && ip != null && store.ipUsada(c.nombre, ip)) {
                    return new Res(Res.Tipo.IP_USADA, c.nombre);
                }
                // Limite global de usos (0 = ilimitado).
                int total = store.total(c.nombre);
                if (c.usos > 0 && total >= c.usos) {
                    return new Res(Res.Tipo.AGOTADO, c.nombre);
                }

                // Registramos primero y luego repartimos.
                if (!store.registrar(c.nombre, jugador.getUniqueId(), jugador.getName(), ip)) {
                    return new Res(Res.Tipo.ERROR, c.nombre);
                }
                boolean entregaOk = ejecutar(c, jugador);
                registrarHistorial(c.nombre, jugador, "canje");

                if (getConfig().getBoolean("delete-when-exhausted", false)
                        && c.usos > 0 && store.total(c.nombre) >= c.usos) {
                    eliminarCodigo(c.nombre);
                }
                return new Res(Res.Tipo.OK, c.nombre, null, null, !entregaOk);
            } finally {
                enProceso.remove(clave);
            }
        }
    }

    private long bloqueoRestante(UUID uuid) {
        Intentos estado = intentos.get(uuid);
        if (estado == null || estado.bloqueadoHasta <= System.currentTimeMillis()) return 0;
        return Math.max(1, (estado.bloqueadoHasta - System.currentTimeMillis() + 999) / 1000);
    }

    private void registrarIntentoFallido(UUID uuid) {
        long ahora = System.currentTimeMillis();
        Intentos estado = intentos.computeIfAbsent(uuid, k -> new Intentos());
        if (ahora - estado.inicio > 60_000) { estado.inicio = ahora; estado.cantidad = 0; }
        estado.cantidad++;
        int max = Math.max(1, getConfig().getInt("security.max-failed-attempts", 5));
        if (estado.cantidad >= max) {
            long ms = Math.max(1, getConfig().getLong("security.lockout-seconds", 60)) * 1000L;
            estado.bloqueadoHasta = ahora + ms;
            estado.cantidad = 0;
        }
    }

    private void registrarHistorial(String codigo, Player jugador, String tipo) {
        File archivo = new File(getDataFolder(), "historial.yml");
        YamlConfiguration h = YamlConfiguration.loadConfiguration(archivo);
        String id = Long.toString(System.currentTimeMillis()) + "-" + UUID.randomUUID();
        h.set("entries." + id + ".code", codigo);
        h.set("entries." + id + ".uuid", jugador.getUniqueId().toString());
        h.set("entries." + id + ".player", jugador.getName());
        h.set("entries." + id + ".type", tipo);
        h.set("entries." + id + ".at", System.currentTimeMillis());
        try { h.save(archivo); }
        catch (IOException e) { getLogger().warning("No se pudo guardar el historial: " + e.getMessage()); }
    }

    public List<String> historial(String filtro) {
        YamlConfiguration h = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "historial.yml"));
        ConfigurationSection sec = h.getConfigurationSection("entries");
        List<String> salida = new ArrayList<>();
        if (sec == null) return salida;
        List<String> ids = new ArrayList<>(sec.getKeys(false));
        ids.sort(Comparator.reverseOrder());
        for (String id : ids) {
            String code = h.getString("entries." + id + ".code", "");
            String player = h.getString("entries." + id + ".player", "");
            if (filtro != null && !filtro.isBlank() && !code.equalsIgnoreCase(filtro)
                    && !player.equalsIgnoreCase(filtro)) continue;
            salida.add(Tiempo.fecha(h.getLong("entries." + id + ".at")) + " " + player + " -> " + code);
            if (salida.size() >= 20) break;
        }
        return salida;
    }

    public void asignarCampana(String codigo, String campana) {
        getConfig().set("codes." + codigo + ".campaign", campana == null || campana.isBlank() ? null : campana.trim());
        saveConfig();
    }

    public String campanaDe(String codigo) {
        return getConfig().getString("codes." + codigo + ".campaign", "");
    }

    public File carpetaExportaciones() {
        File dir = new File(getDataFolder(), "exports");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    public File exportar(String archivo) throws IOException {
        File salida = new File(carpetaExportaciones(), archivo);
        YamlConfiguration yml = new YamlConfiguration();
        for (Codigo c : codigos.values()) {
            String ruta = "codes." + c.nombre + ".";
            yml.set(ruta + "uses", c.usos);
            yml.set(ruta + "uses-per-player", c.usosPorJugador);
            yml.set(ruta + "enabled", c.activo);
            yml.set(ruta + "recompensas", c.comandos);
            yml.set(ruta + "campaign", campanaDe(c.nombre));
            yml.set(ruta + "reward-mode", getConfig().getString("codes." + c.nombre + ".reward-mode", "all"));
            yml.set(ruta + "reward-money", getConfig().getDouble("codes." + c.nombre + ".reward-money", 0));
            yml.set(ruta + "reward-items", getConfig().getStringList("codes." + c.nombre + ".reward-items"));
            yml.set(ruta + "permission", c.permiso);
            yml.set(ruta + "conditions.time-played", c.minTiempo);
            yml.set(ruta + "conditions.money", c.minDinero);
            yml.set(ruta + "conditions.world", c.mundo);
            if (c.caduca > 0) yml.set(ruta + "expires-at", new java.text.SimpleDateFormat("dd/MM/yyyy HH:mm").format(new java.util.Date(c.caduca)));
        }
        yml.save(salida);
        return salida;
    }

    public int importar(String archivo) throws IOException {
        File entrada = new File(carpetaExportaciones(), archivo);
        if (!entrada.isFile()) throw new IOException("No existe el archivo de importación.");
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(entrada);
        ConfigurationSection sec = yml.getConfigurationSection("codes");
        if (sec == null) throw new IOException("El archivo no contiene la sección codes.");
        int cuenta = 0;
        for (String nombre : sec.getKeys(false)) {
            ConfigurationSection c = sec.getConfigurationSection(nombre);
            if (c == null) continue;
            getConfig().set("codes." + nombre, c.getValues(false));
            List<String> cmds = c.getStringList("recompensas");
            if (usaBd && !cmds.isEmpty()) {
                int usos = Math.max(0, c.getInt("uses", 1));
                long caduca = caducidadDe(nombre, c);
                Cond cond = condicionesDeConfig().get(nombre.toLowerCase(Locale.ROOT));
                ((SqlStore) store).insertarCodigo(new Codigo(nombre, usos,
                        Math.max(1, c.getInt("uses-per-player", 1)), cmds, caduca,
                        cond == null ? "" : cond.permiso, c.getBoolean("enabled", true),
                        cond == null ? 0 : cond.minTiempo, cond == null ? 0 : cond.minDinero,
                        cond == null ? "" : cond.mundo));
            }
            cuenta++;
        }
        saveConfig();
        cargarCodigos();
        return cuenta;
    }

    private static final class Intentos {
        long inicio = System.currentTimeMillis();
        long bloqueadoHasta;
        int cantidad;
    }

    // ------------------------------------------------------------------
    //  CODIGOS DE INVITACION ("Crea tu propio codigo")
    // ------------------------------------------------------------------

    /** true si la seccion de invitaciones esta activada. */
    public boolean refActivo() {
        return getConfig().getBoolean("referidos.enabled", true);
    }

    public int refMeta() {
        return Math.max(1, getConfig().getInt("referidos.meta", 10));
    }

    public double refDineroPorUso() {
        return Math.max(0, getConfig().getDouble("referidos.dinero-por-uso", 5000));
    }

    public double refBono() {
        return Math.max(0, getConfig().getDouble("referidos.bono", 30000));
    }

    /**
     * Devuelve el codigo de invitacion del jugador. Si aun no tiene, se lo crea
     * (solo una vez por jugador). Devuelve null si la funcion esta desactivada.
     */
    public String codigoInvitacion(Player jugador) {
        String code = store.codigoRefDe(jugador.getUniqueId());
        if (code != null) return code;
        if (!refActivo()) return null;
        code = nuevoCodigoRef();
        for (int i = 0; i < 50 && store.ref(code) != null; i++) code = nuevoCodigoRef();
        store.crearRef(code, jugador.getUniqueId(), jugador.getName(), ipDe(jugador),
                System.currentTimeMillis());
        return code;
    }

    private String nuevoCodigoRef() {
        String pref = getConfig().getString("referidos.prefijo-codigo", "REF-");
        if (pref == null) pref = "REF-";
        final String abc = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        ThreadLocalRandom r = ThreadLocalRandom.current();
        StringBuilder sb = new StringBuilder(pref.trim().toUpperCase(Locale.ROOT));
        for (int i = 0; i < 6; i++) sb.append(abc.charAt(r.nextInt(abc.length())));
        return sb.toString();
    }

    /** Canjea un codigo de invitacion: paga al dueño, aunque este desconectado. */
    private Res canjearRef(Player jugador, Referido ref) {
        synchronized (canjeoLock) {
        String clave = "ref|" + jugador.getUniqueId() + "|" + ref.codigo.toLowerCase(Locale.ROOT);
        if (!enProceso.add(clave)) return new Res(Res.Tipo.YA_USADO, ref.codigo);
        try {
        if (!refActivo()) return new Res(Res.Tipo.INVALIDO, ref.codigo);
        // No puedes usar tu propio codigo.
        if (ref.dueno.equals(jugador.getUniqueId().toString())) {
            return new Res(Res.Tipo.CONDICION, ref.codigo, "referral-own", null);
        }
        // El mismo jugador solo cuenta una vez (mensaje claro antes que el de IP).
        if (store.usadasPorRef(ref.codigo, jugador.getUniqueId()) >= 1) {
            return new Res(Res.Tipo.YA_USADO, ref.codigo);
        }
        String ip = ipDe(jugador);
        // Anti-multis: ni el propio dueño (su IP) ni una conexion que ya lo uso.
        if (getConfig().getBoolean("once-per-ip", true) && ip != null
                && (ip.equalsIgnoreCase(ref.duenoIp) || store.ipUsadaRef(ref.codigo, ip))) {
            return new Res(Res.Tipo.IP_USADA, ref.codigo);
        }

        // Registramos el uso y pagamos al dueño (aunque no este conectado).
        if (!store.registrarRef(ref.codigo, jugador.getUniqueId(), jugador.getName(), ip)) {
            return new Res(Res.Tipo.ERROR, ref.codigo);
        }
        int total = store.totalRef(ref.codigo);
        if (total < 0) return new Res(Res.Tipo.ERROR, ref.codigo);
        int meta = refMeta();

        double porUso = refDineroPorUso();
        if (porUso > 0) pagar(ref.duenoNombre, porUso);
        avisarDueno(ref, "referral-owner-used",
                "%jugador%", jugador.getName(),
                "%cantidad%", miles(porUso),
                "%total%", String.valueOf(total),
                "%meta%", String.valueOf(meta));

        if (total >= meta && !ref.bono) {
            // Marcar el bono de forma atomica antes de pagar: evita duplicarlo
            // si hay dos instancias del plugin o falla la escritura despues.
            if (!store.marcarBonoRef(ref.codigo)) {
                return new Res(Res.Tipo.ERROR, ref.codigo);
            }
            double bono = refBono();
            if (bono > 0) pagar(ref.duenoNombre, bono);
            avisarDueno(ref, "referral-owner-bonus",
                    "%meta%", String.valueOf(meta),
                    "%bono%", miles(bono));
        }
        registrarHistorial(ref.codigo, jugador, "referido");
        return new Res(Res.Tipo.REF_OK, ref.codigo);
        } finally {
            enProceso.remove(clave);
        }
        }
    }

    /** Paga dinero al dueño ejecutando los comandos configurados (vale offline). */
    private void pagar(String jugador, double cantidad) {
        if (cantidad <= 0 || jugador == null || jugador.isEmpty()) return;
        String monto = dinero(cantidad);
        List<String> lista = getConfig().getStringList("referidos.comandos-pago");
        if (lista.isEmpty()) {
            String uno = getConfig().getString("referidos.comando-pago", "eco give %jugador% %cantidad%");
            lista = new ArrayList<>();
            if (uno != null && !uno.trim().isEmpty()) lista.add(uno);
        }
        for (String plantilla : lista) {
            if (plantilla == null || plantilla.trim().isEmpty()) continue;
            String cmd = plantilla.replace("%jugador%", jugador).replace("%cantidad%", monto);
            if (cmd.startsWith("/")) cmd = cmd.substring(1);
            despachar(cmd);
        }
    }

    /** Avisa al dueño del codigo si esta conectado. */
    private void avisarDueno(Referido ref, String clave, String... reemplazos) {
        Player dueno;
        try {
            dueno = Bukkit.getPlayer(UUID.fromString(ref.dueno));
        } catch (IllegalArgumentException e) {
            return;
        }
        if (dueno != null && dueno.isOnline()) dueno.sendMessage(msg(clave, reemplazos));
    }

    /** Formatea dinero con separador de miles (5000 -> "5,000"). */
    public static String miles(double v) {
        if (!Double.isInfinite(v) && v == Math.floor(v)) {
            return String.format(Locale.US, "%,d", (long) v);
        }
        return String.format(Locale.US, "%,.2f", v);
    }

    /** Ejecuta las recompensas. Devuelve false si alguna no se pudo ejecutar. */
    private boolean ejecutar(Codigo c, Player jugador) {
        String inicial = c.usos <= 0 ? "inf" : String.valueOf(c.usos);
        boolean todoOk = true;
        List<String> recompensas = c.comandos;
        String modo = getConfig().getString("codes." + c.nombre + ".reward-mode", "all");
        if ("random".equalsIgnoreCase(modo) && !recompensas.isEmpty()) {
            recompensas = List.of(recompensas.get(ThreadLocalRandom.current().nextInt(recompensas.size())));
        }
        for (String plantilla : recompensas) {
            String cmd = plantilla
                    .replace("%player%", jugador.getName())
                    .replace("%code%", c.nombre)
                    .replace("%initial%", inicial)
                    .replace("%inicial%", inicial);
            if (cmd.startsWith("/")) cmd = cmd.substring(1);
            if (!despachar(cmd)) {
                todoOk = false;
                getLogger().warning("No se pudo ejecutar la recompensa '" + plantilla
                        + "' del codigo '" + c.nombre + "' para " + jugador.getName()
                        + ". El canje quedo registrado; revisa ese comando.");
            }
        }
        double dineroRecompensa = getConfig().getDouble("codes." + c.nombre + ".reward-money", 0);
        if (dineroRecompensa > 0 && !Economia.depositar(jugador, dineroRecompensa)) {
            todoOk = false;
            getLogger().warning("No se pudo entregar el dinero configurado para " + c.nombre + ". Verifica Vault y su economía.");
        }
        for (String entrada : getConfig().getStringList("codes." + c.nombre + ".reward-items")) {
            if (entrada == null || entrada.isBlank()) continue;
            String[] partes = entrada.split(":", 2);
            Material material = Material.matchMaterial(partes[0].trim());
            int cantidad;
            try { cantidad = partes.length == 2 ? Integer.parseInt(partes[1].trim()) : 1; }
            catch (NumberFormatException e) { cantidad = 0; }
            if (material == null || material.isAir() || cantidad < 1) {
                todoOk = false;
                getLogger().warning("Recompensa de objeto inválida en " + c.nombre + ": " + entrada);
                continue;
            }
            for (ItemStack resto : jugador.getInventory().addItem(new ItemStack(material, cantidad)).values()) {
                jugador.getWorld().dropItemNaturally(jugador.getLocation(), resto);
            }
        }
        return todoOk;
    }

    // Los comandos los ejecuta la consola y siempre en el hilo principal.
    // Devuelve false si el comando no existe o lanza un error.
    private boolean despachar(String cmd) {
        try {
            if (Bukkit.isPrimaryThread()) {
                return Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
            }
            Bukkit.getScheduler().runTask(this, () -> {
                try {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
                } catch (Throwable t) {
                    getLogger().warning("Error ejecutando la recompensa '" + cmd + "': " + t.getMessage());
                }
            });
            return true;
        } catch (Throwable t) {
            getLogger().warning("Error ejecutando la recompensa '" + cmd + "': " + t.getMessage());
            return false;
        }
    }

    /**
     * Borra un codigo y todos sus canjes, tanto en el config (modo yaml) como
     * en la base de datos (sqlite/mysql).
     */
    public void eliminarCodigo(String nombre) {
        Codigo c = buscar(nombre);
        if (c == null) return;
        if (usaBd) {
            ((SqlStore) store).borrarCodigo(c.nombre);
            cargarCodigos();
            return;
        }
        // OJO: los nombres de codigo no deben llevar puntos, porque el config de
        // Bukkit parte la ruta por '.'. Con nombres normales (letras y numeros)
        // esto borra el codigo y lo saca de la lista.
        getConfig().set("codes." + c.nombre, null);
        saveConfig();
        cargarCodigos();
    }

    /**
     * Activa o desactiva un codigo. En modo yaml escribe 'enabled' en el config;
     * en modo BD cambia la columna 'enabled'. Devuelve false si no existe.
     */
    public boolean habilitar(String nombre, boolean activo) {
        Codigo c = buscar(nombre);
        if (c == null) return false;
        if (usaBd) {
            ((SqlStore) store).setEnabled(c.nombre, activo);
        } else {
            getConfig().set("codes." + c.nombre + ".enabled", activo);
            saveConfig();
        }
        cargarCodigos();
        return true;
    }

    /** Etiqueta de estado de un codigo (activo/agotado/caducado/inactivo). */
    public String estadoTexto(Codigo c) {
        if (!c.activo) return crudo("state-inactive");
        if (c.caduca > 0 && System.currentTimeMillis() > c.caduca) return crudo("state-expired");
        if (c.usos > 0 && store.total(c.nombre) >= c.usos) return crudo("state-exhausted");
        return crudo("state-active");
    }

    private String ipDe(Player jugador) {
        try {
            if (jugador.getAddress() != null && jugador.getAddress().getAddress() != null) {
                return jugador.getAddress().getAddress().getHostAddress();
            }
        } catch (Throwable t) {
            // Si la IP no se puede leer, simplemente no se aplica el filtro por IP.
        }
        return null;
    }

    /**
     * Resuelve el nombre de un sonido del config. Acepta el nombre moderno
     * ("entity.player.levelup", "ui.button.click") o el viejo enum
     * ("ENTITY_PLAYER_LEVELUP"). Devuelve null si no se reconoce.
     */
    @SuppressWarnings("removal")
    private Sound sonidoDe(String nombre) {
        String n = nombre.trim();
        if (n.isEmpty() || n.equalsIgnoreCase("none")) return null;
        if (n.indexOf('.') >= 0) {
            NamespacedKey key = NamespacedKey.fromString(n.toLowerCase(Locale.ROOT));
            return key == null ? null : Registry.SOUND_EVENT.get(key);
        }
        try {
            return Sound.valueOf(n.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Reproduce el sonido configurado para una clave ("open", "success", "error"...). */
    public void sonido(Player jugador, String clave) {
        if (!getConfig().getBoolean("sounds.enabled", true)) return;
        String nombre = getConfig().getString("sounds." + clave, "");
        if (nombre == null || nombre.trim().isEmpty()) return;
        Sound s = sonidoDe(nombre);
        if (s == null) {
            if (!nombre.equalsIgnoreCase("none")) {
                getLogger().warning("Sonido desconocido en sounds." + clave + ": " + nombre);
            }
            return;
        }
        jugador.playSound(jugador.getLocation(), s, 1f, 1f);
    }

    /**
     * Resuelve el nombre de una particula del config. Acepta el nombre del enum
     * ("TOTEM_OF_UNDYING") o uno con espacio de nombres ("minecraft:heart").
     */
    @SuppressWarnings("removal")
    private Particle particulaDe(String nombre) {
        String n = nombre.trim();
        if (n.isEmpty() || n.equalsIgnoreCase("none")) return null;
        try {
            return Particle.valueOf(n.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            NamespacedKey key = NamespacedKey.fromString(n.toLowerCase(Locale.ROOT));
            return key == null ? null : Registry.PARTICLE_TYPE.get(key);
        }
    }

    /** Lanza la particula configurada para una clave ("success" o "error"). */
    public void particula(Player jugador, String clave) {
        if (!getConfig().getBoolean("particles.enabled", true)) return;
        String nombre = getConfig().getString("particles." + clave, "");
        if (nombre == null || nombre.trim().isEmpty()) return;
        Particle p = particulaDe(nombre);
        if (p == null) {
            if (!nombre.equalsIgnoreCase("none")) {
                getLogger().warning("Particula desconocida en particles." + clave + ": " + nombre);
            }
            return;
        }
        int count = Math.max(1, getConfig().getInt("particles.count", 25));
        Location loc = jugador.getLocation().add(0, 1.0, 0);
        jugador.getWorld().spawnParticle(p, loc, count, 0.4, 0.7, 0.4, 0.02);
    }

    /** Muestra el titulo configurado ("success" o "error") con reemplazos opcionales. */
    public void titulo(Player jugador, String tipo, String... reemplazos) {
        if (!getConfig().getBoolean("titles.enabled", true)) return;
        String t = getConfig().getString("titles." + tipo + "-title", "");
        String s = getConfig().getString("titles." + tipo + "-subtitle", "");
        if (t == null) t = "";
        if (s == null) s = "";
        for (int i = 0; i + 1 < reemplazos.length; i += 2) {
            t = t.replace(reemplazos[i], reemplazos[i + 1]);
            s = s.replace(reemplazos[i], reemplazos[i + 1]);
        }
        if (t.isEmpty() && s.isEmpty()) return;
        jugador.showTitle(Title.title(texto(t), texto(s)));
    }

    /**
     * Crea un codigo aleatorio nuevo en el config.
     *
     * @param usos      usos maximos (0 = ilimitado)
     * @param duracion  duracion textual ("7d") o "" si no debe caducar
     * @param prefijo   prefijo visible del codigo ("EVENTO") o ""
     * @param plantilla nombre de un codigo existente del que copiar los comandos
     * @return el nombre del codigo creado
     */
    public String generar(int usos, String duracion, String prefijo, String plantilla) {
        Codigo t = buscar(plantilla);
        List<String> cmds = t == null ? new ArrayList<>() : new ArrayList<>(t.comandos);

        String codigo = nombreAleatorio(prefijo);
        if (usaBd) {
            while (buscar(codigo) != null) codigo = nombreAleatorio(prefijo);
        } else {
            ConfigurationSection sec = getConfig().getConfigurationSection("codes");
            while (sec != null && sec.contains(codigo)) codigo = nombreAleatorio(prefijo);
        }

        long caduca = 0;
        if (duracion != null && !duracion.isEmpty()) {
            long ms = Tiempo.duracion(duracion);
            if (ms > 0) {
                long ahora = System.currentTimeMillis();
                caduca = ahora > Long.MAX_VALUE - ms ? Long.MAX_VALUE : ahora + ms;
            }
        }

        if (usaBd) {
            ((SqlStore) store).insertarCodigo(
                    new Codigo(codigo, usos, 1, cmds, caduca, "", true, 0, 0, ""));
        } else {
            getConfig().set("codes." + codigo + ".uses", usos);
            if (duracion != null && !duracion.isEmpty()) {
                getConfig().set("codes." + codigo + ".duration", duracion);
            }
            if (!cmds.isEmpty()) getConfig().set("codes." + codigo + ".recompensas", cmds);
            store.fijarCreado(codigo, System.currentTimeMillis());
            store.guardar();
            saveConfig();
        }
        cargarCodigos();
        return codigo;
    }

    private String nombreAleatorio(String prefijo) {
        final String abc = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        ThreadLocalRandom r = ThreadLocalRandom.current();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8; i++) sb.append(abc.charAt(r.nextInt(abc.length())));
        String base = sb.toString();
        if (prefijo != null && !prefijo.trim().isEmpty()) {
            // Se limpia el prefijo: solo letras, numeros, '-' y '_'. Asi un
            // prefijo con puntos o comillas no puede corromper la ruta del
            // config ("codes.<codigo>") ni colarse en los comandos.
            String limpio = prefijo.trim().toUpperCase(Locale.ROOT)
                    .replaceAll("[^A-Z0-9_-]", "");
            if (!limpio.isEmpty()) base = limpio + "-" + base;
        }
        return base;
    }

    /** Texto tal cual del config (sin prefijo). */
    public String crudo(String clave) {
        return getConfig().getString("messages." + clave, clave);
    }

    /** Mensaje de chat ya con el prefijo delante (en crudo, con '&'). */
    public String mensaje(String clave) {
        return crudo("prefix") + crudo(clave);
    }

    /** Convierte un texto con '&' a un Component de Adventure. */
    public Component texto(String conColor) {
        return LegacyComponentSerializer.legacyAmpersand()
                .deserialize(conColor == null ? "" : conColor);
    }

    /** Mensaje de chat (con prefijo) como Component, con reemplazos opcionales. */
    public Component msg(String clave, String... reemplazos) {
        String s = crudo(clave);
        for (int i = 0; i + 1 < reemplazos.length; i += 2) {
            s = s.replace(reemplazos[i], reemplazos[i + 1]);
        }
        return texto(crudo("prefix") + s);
    }

    public Map<String, Codigo> codigos() {
        return codigos;
    }

    public Almacen store() {
        return store;
    }

    public MenuCodigo menu() {
        return menu;
    }

    /** Un codigo con toda su informacion. */
    public static final class Codigo {
        public final String nombre;
        public final int usos;             // usos maximos (0 = ilimitado)
        public final int usosPorJugador;   // usos maximos por jugador (>= 1)
        public final List<String> comandos;
        public final long caduca;          // instante de caducidad (0 = no caduca)
        public final String permiso;       // rango/permiso necesario ("" = ninguno)
        public final boolean activo;
        public final long minTiempo;       // tiempo jugado minimo en segundos (0 = no)
        public final double minDinero;     // dinero minimo (0 = no, requiere Vault)
        public final String mundo;         // mundo requerido ("" = cualquiera)

        Codigo(String nombre, int usos, int usosPorJugador, List<String> comandos,
               long caduca, String permiso, boolean activo,
               long minTiempo, double minDinero, String mundo) {
            this.nombre = nombre;
            this.usos = usos;
            this.usosPorJugador = usosPorJugador;
            this.comandos = comandos;
            this.caduca = caduca;
            this.permiso = permiso == null ? "" : permiso;
            this.activo = activo;
            this.minTiempo = minTiempo;
            this.minDinero = minDinero;
            this.mundo = mundo == null ? "" : mundo;
        }
    }

    /** Resultado de intentar canjear un codigo. */
    public static final class Res {
        public enum Tipo {
            OK, VACIO, INVALIDO, YA_USADO, IP_USADA, AGOTADO, CADUCADO, CONDICION, REF_OK, ERROR
        }

        public final Tipo tipo;
        public final String codigo;
        public final String clave;      // clave del mensaje a mostrar (solo CONDICION)
        public final String[] args;     // reemplazos para ese mensaje (solo CONDICION)
        public final boolean recompensaFallida; // true si un comando de recompensa fallo

        public Res(Tipo tipo, String codigo) {
            this(tipo, codigo, null, null, false);
        }

        public Res(Tipo tipo, String codigo, String clave, String[] args) {
            this(tipo, codigo, clave, args, false);
        }

        public Res(Tipo tipo, String codigo, String clave, String[] args, boolean recompensaFallida) {
            this.tipo = tipo;
            this.codigo = codigo;
            this.clave = clave;
            this.args = args == null ? new String[0] : args;
            this.recompensaFallida = recompensaFallida;
        }
    }
}
