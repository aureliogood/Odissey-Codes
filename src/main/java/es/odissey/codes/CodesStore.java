package es.odissey.codes;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.UUID;

/**
 * Almacenamiento en archivo (redenciones.yml). Es el modo por defecto y el mas
 * comodo para un solo servidor: guarda quien ha canjeado cada codigo, cuando y
 * desde que IP, y cuantas veces lo ha hecho cada jugador.
 */
public final class CodesStore implements Almacen {

    private final OdisseyCodes plugin;
    private final File archivo;
    private YamlConfiguration datos;
    private boolean cargaFallida;

    public CodesStore(OdisseyCodes plugin) {
        this.plugin = plugin;
        this.archivo = new File(plugin.getDataFolder(), "redenciones.yml");
        cargar();
    }

    private void cargar() {
        if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
            plugin.getLogger().warning("No se pudo crear la carpeta del plugin.");
        }
        cargaFallida = false;
        datos = new YamlConfiguration();
        if (archivo.exists()) {
            try {
                datos.load(archivo);
            } catch (Exception e) {
                cargaFallida = true;
                plugin.getLogger().severe("No se pudo leer redenciones.yml; se bloquearan escrituras para conservar el archivo: " + e.getMessage());
            }
        }
    }

    // El nombre del codigo se usa como ruta YAML: un punto partiria la ruta en
    // subsecciones, asi que se sustituye por '_' solo para el almacenamiento.
    private String base(String codigo) {
        return "codigos." + codigo.replace('.', '_');
    }

    @Override
    public int usadasPor(String codigo, UUID uuid) {
        return datos.getInt(base(codigo) + ".jugadores." + uuid + ".veces", 0);
    }

    @Override
    public boolean yaCanjeado(String codigo, UUID uuid) {
        return usadasPor(codigo, uuid) >= 1;
    }

    @Override
    public boolean ipUsada(String codigo, String ip) {
        if (ip == null || ip.isEmpty()) return false;
        ConfigurationSection sec = datos.getConfigurationSection(base(codigo) + ".jugadores");
        if (sec == null) return false;
        for (String clave : sec.getKeys(false)) {
            if (ip.equalsIgnoreCase(sec.getString(clave + ".ip", ""))) return true;
        }
        return false;
    }

    @Override
    public int total(String codigo) {
        return datos.getInt(base(codigo) + ".total", 0);
    }

    @Override
    public long creado(String codigo, long siAusente) {
        String ruta = base(codigo) + ".creado";
        if (datos.contains(ruta)) return datos.getLong(ruta);
        datos.set(ruta, siAusente);
        return siAusente;
    }

    @Override
    public void fijarCreado(String codigo, long cuando) {
        datos.set(base(codigo) + ".creado", cuando);
    }

    @Override
    public boolean registrar(String codigo, UUID uuid, String nombre, String ip) {
        String b = base(codigo);
        String j = b + ".jugadores." + uuid;
        datos.set(b + ".total", total(codigo) + 1);
        datos.set(j + ".veces", datos.getInt(j + ".veces", 0) + 1);
        datos.set(j + ".nombre", nombre);
        datos.set(j + ".ip", ip == null ? "" : ip);
        datos.set(j + ".cuando", System.currentTimeMillis());
        return guardarSeguro();
    }

    @Override
    public boolean reset(String codigo, UUID uuid) {
        String ruta = base(codigo) + ".jugadores." + uuid;
        if (!datos.contains(ruta)) return false;
        int veces = datos.getInt(ruta + ".veces", 1);
        datos.set(ruta, null);
        datos.set(base(codigo) + ".total", Math.max(0, total(codigo) - veces));
        guardar();
        return true;
    }

    @Override
    public void borrarTodo(String codigo) {
        datos.set(base(codigo), null);
        guardar();
    }

    @Override
    public void guardar() {
        guardarSeguro();
    }

    private boolean guardarSeguro() {
        if (cargaFallida) return false;
        try {
            datos.save(archivo);
            return true;
        } catch (IOException e) {
            plugin.getLogger().warning("No se pudo guardar redenciones.yml: " + e.getMessage());
            return false;
        }
    }

    @Override
    public void cerrar() {
        // el archivo ya se guarda en cada cambio
    }

    // ------------------------------------------------------------------
    //  CODIGOS DE INVITACION (referidos)
    // ------------------------------------------------------------------

    private String baseRef(String codigo) {
        return "referidos.porCodigo." + codigo.replace('.', '_');
    }

    @Override
    public void crearRef(String codigo, UUID dueno, String nombreDueno, String ipDueno, long cuando) {
        String b = baseRef(codigo);
        datos.set(b + ".dueno", dueno.toString());
        datos.set(b + ".duenoNombre", nombreDueno);
        datos.set(b + ".duenoIp", ipDueno == null ? "" : ipDueno);
        datos.set(b + ".bono", false);
        datos.set(b + ".creado", cuando);
        datos.set("referidos.porJugador." + dueno, codigo);
        guardar();
    }

    @Override
    public String codigoRefDe(UUID jugador) {
        return datos.getString("referidos.porJugador." + jugador);
    }

    @Override
    public Referido ref(String codigo) {
        if (codigo == null || codigo.isEmpty()) return null;
        ConfigurationSection sec = datos.getConfigurationSection("referidos.porCodigo");
        if (sec == null) return null;
        for (String clave : sec.getKeys(false)) {
            if (clave.equalsIgnoreCase(codigo)) {
                String b = "referidos.porCodigo." + clave;
                return new Referido(clave,
                        datos.getString(b + ".dueno", ""),
                        datos.getString(b + ".duenoNombre", ""),
                        datos.getString(b + ".duenoIp", ""),
                        datos.getBoolean(b + ".bono", false));
            }
        }
        return null;
    }

    @Override
    public boolean ipUsadaRef(String codigo, String ip) {
        if (ip == null || ip.isEmpty()) return false;
        ConfigurationSection sec = datos.getConfigurationSection(baseRef(codigo) + ".jugadores");
        if (sec == null) return false;
        for (String clave : sec.getKeys(false)) {
            if (ip.equalsIgnoreCase(sec.getString(clave + ".ip", ""))) return true;
        }
        return false;
    }

    @Override
    public int usadasPorRef(String codigo, UUID jugador) {
        return datos.getInt(baseRef(codigo) + ".jugadores." + jugador + ".veces", 0);
    }

    @Override
    public int totalRef(String codigo) {
        return datos.getInt(baseRef(codigo) + ".total", 0);
    }

    @Override
    public boolean registrarRef(String codigo, UUID jugador, String nombre, String ip) {
        String b = baseRef(codigo);
        String j = b + ".jugadores." + jugador;
        datos.set(j + ".veces", datos.getInt(j + ".veces", 0) + 1);
        datos.set(j + ".nombre", nombre);
        datos.set(j + ".ip", ip == null ? "" : ip);
        datos.set(j + ".cuando", System.currentTimeMillis());
        datos.set(b + ".total", datos.getInt(b + ".total", 0) + 1);
        return guardarSeguro();
    }

    @Override
    public boolean marcarBonoRef(String codigo) {
        datos.set(baseRef(codigo) + ".bono", true);
        return guardarSeguro();
    }
}
