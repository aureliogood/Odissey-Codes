package es.odissey.codes;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * /codes y /code (code es alias).
 *
 * Sin argumentos abre el menu de dialogo al jugador. Con argumentos hace de
 * panel de admin: reload, list [pagina], info, reset, generate, enable,
 * disable, delete y stats.
 */
public final class ComandoCodes implements CommandExecutor, TabCompleter {

    // Cuantos codigos se muestran por pagina en /codes list.
    private static final int TAMANO_PAGINA = 8;

    private final OdisseyCodes plugin;

    public ComandoCodes(OdisseyCodes plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender quien, Command comando, String etiqueta, String[] args) {
        if (!quien.hasPermission("odisseycodes.usar")) {
            quien.sendMessage(plugin.msg("no-permission"));
            return true;
        }

        if (args.length == 0) {
            if (!(quien instanceof Player jugador)) {
                quien.sendMessage(plugin.msg("players-only"));
                return true;
            }
            plugin.menu().abrir(jugador);
            return true;
        }

        if (args.length == 1 && args[0].equalsIgnoreCase("cancel") && quien instanceof Player jugador) {
            if (plugin.menu().cancelar(jugador)) jugador.sendMessage("§7Canje cancelado.");
            else jugador.sendMessage("§7No tienes ningún canje pendiente.");
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload":
            case "recargar":
                if (!admin(quien)) return true;
                plugin.recargar();
                quien.sendMessage(plugin.msg("reloaded"));
                return true;
            case "list":
            case "lista":
                if (!admin(quien)) return true;
                listar(quien, pagina(args));
                return true;
            case "info":
            case "ver":
                if (!admin(quien)) return true;
                info(quien, args);
                return true;
            case "stats":
            case "estadisticas":
                if (!admin(quien)) return true;
                stats(quien);
                return true;
            case "enable":
            case "activar":
                if (!admin(quien)) return true;
                habilitar(quien, args, true);
                return true;
            case "disable":
            case "desactivar":
                if (!admin(quien)) return true;
                habilitar(quien, args, false);
                return true;
            case "delete":
            case "borrar":
                if (!admin(quien)) return true;
                eliminar(quien, args);
                return true;
            case "reset":
                if (!admin(quien)) return true;
                reset(quien, args);
                return true;
            case "generate":
            case "generar":
                if (!admin(quien)) return true;
                generar(quien, args);
                return true;
            case "redeem":
            case "canjear":
                if (!(quien instanceof Player jugador)) { quien.sendMessage(plugin.msg("players-only")); return true; }
                if (args.length < 2) { quien.sendMessage("§eUso: /codes redeem <codigo>"); return true; }
                plugin.menu().canjearDirecto(jugador, args[1]);
                return true;
            case "preview":
            case "previsualizar":
                if (!admin(quien)) return true;
                previsualizar(quien, args);
                return true;
            case "campaign":
            case "campana":
                if (!admin(quien)) return true;
                campana(quien, args);
                return true;
            case "history":
            case "historial":
                if (!admin(quien)) return true;
                historial(quien, args.length > 1 ? args[1] : "");
                return true;
            case "export":
            case "exportar":
                if (!admin(quien)) return true;
                exportar(quien, args.length > 1 ? args[1] : "codes-export.yml");
                return true;
            case "import":
            case "importar":
                if (!admin(quien)) return true;
                importar(quien, args);
                return true;
            case "backup":
            case "respaldo":
                if (!admin(quien)) return true;
                backup(quien);
                return true;

            default:
                quien.sendMessage(plugin.msg("usage"));
                return true;
        }
    }

    private boolean admin(CommandSender quien) {
        if (quien.hasPermission("odisseycodes.admin")) return true;
        quien.sendMessage(plugin.msg("no-permission"));
        return false;
    }

    /** Lee la pagina pedida ("/codes list 2"); 1 si falta o no es un numero. */
    private int pagina(String[] args) {
        if (args.length < 2) return 1;
        try {
            return Math.max(1, Integer.parseInt(args[1]));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private void listar(CommandSender quien, int pagina) {
        List<OdisseyCodes.Codigo> todos = new ArrayList<>(plugin.codigos().values());
        if (todos.isEmpty()) {
            quien.sendMessage(plugin.msg("list-empty"));
            return;
        }
        int paginas = (todos.size() + TAMANO_PAGINA - 1) / TAMANO_PAGINA;
        pagina = Math.min(pagina, paginas);
        quien.sendMessage(plugin.texto(plugin.crudo("list-header")
                .replace("%total%", String.valueOf(todos.size()))));

        int desde = (pagina - 1) * TAMANO_PAGINA;
        int hasta = Math.min(desde + TAMANO_PAGINA, todos.size());
        for (int i = desde; i < hasta; i++) {
            OdisseyCodes.Codigo c = todos.get(i);
            // "inf" y no el simbolo de infinito: la consola no lo imprime bien.
            String inicial = c.usos <= 0 ? "inf" : String.valueOf(c.usos);
            String linea = plugin.crudo("list-item")
                    .replace("%code%", c.nombre)
                    .replace("%used%", String.valueOf(plugin.store().total(c.nombre)))
                    .replace("%initial%", inicial)
                    .replace("%expires%", Tiempo.fecha(c.caduca))
                    .replace("%state%", plugin.estadoTexto(c));
            quien.sendMessage(plugin.texto(linea));
        }
        if (paginas > 1) {
            quien.sendMessage(plugin.texto(plugin.crudo("list-page")
                    .replace("%page%", String.valueOf(pagina))
                    .replace("%pages%", String.valueOf(paginas))));
        }
    }

    private void info(CommandSender quien, String[] args) {
        if (args.length < 2) {
            quien.sendMessage(plugin.msg("info-usage"));
            return;
        }
        OdisseyCodes.Codigo c = plugin.buscar(args[1]);
        if (c == null) {
            quien.sendMessage(plugin.msg("code-not-found"));
            return;
        }
        quien.sendMessage(plugin.texto(plugin.crudo("info-header")
                .replace("%code%", c.nombre)));
        linea(quien, "Estado", plugin.estadoTexto(c));
        linea(quien, "Usos maximos", c.usos <= 0 ? "inf" : String.valueOf(c.usos));
        linea(quien, "Canjes", String.valueOf(plugin.store().total(c.nombre)));
        linea(quien, "Usos por jugador", String.valueOf(c.usosPorJugador));
        linea(quien, "Caduca", c.caduca > 0 ? Tiempo.fecha(c.caduca) : "-");
        linea(quien, "Permiso", c.permiso.isEmpty() ? "-" : c.permiso);
        linea(quien, "Mundo", c.mundo.isEmpty() ? "-" : c.mundo);
        linea(quien, "Tiempo minimo", c.minTiempo > 0 ? Tiempo.humano(c.minTiempo * 1000) : "-");
        linea(quien, "Dinero minimo", c.minDinero > 0 ? OdisseyCodes.miles(c.minDinero) : "-");
        linea(quien, "Recompensas", String.valueOf(c.comandos.size()));
        for (String cmd : c.comandos) {
            quien.sendMessage(plugin.texto("&8  - &7" + cmd));
        }
    }

    private void linea(CommandSender quien, String clave, String valor) {
        quien.sendMessage(plugin.texto(plugin.crudo("info-line")
                .replace("%clave%", clave)
                .replace("%valor%", valor)));
    }

    private void stats(CommandSender quien) {
        List<OdisseyCodes.Codigo> todos = new ArrayList<>(plugin.codigos().values());
        int totalCanjes = 0;
        for (OdisseyCodes.Codigo c : todos) totalCanjes += plugin.store().total(c.nombre);

        quien.sendMessage(plugin.texto(plugin.crudo("stats-header")));
        quien.sendMessage(plugin.texto(plugin.crudo("stats-total")
                .replace("%codes%", String.valueOf(todos.size()))
                .replace("%redemptions%", String.valueOf(totalCanjes))));
        if (totalCanjes == 0) {
            quien.sendMessage(plugin.msg("stats-empty"));
            return;
        }
        List<OdisseyCodes.Codigo> orden = new ArrayList<>(todos);
        orden.sort(Comparator.comparingInt(
                (OdisseyCodes.Codigo c) -> plugin.store().total(c.nombre)).reversed());
        int limite = Math.min(10, orden.size());
        for (int i = 0; i < limite; i++) {
            OdisseyCodes.Codigo c = orden.get(i);
            int usos = plugin.store().total(c.nombre);
            if (usos == 0) continue;
            quien.sendMessage(plugin.texto(plugin.crudo("stats-item")
                    .replace("%code%", c.nombre)
                    .replace("%used%", String.valueOf(usos))));
        }
    }

    private void habilitar(CommandSender quien, String[] args, boolean activo) {
        if (args.length < 2) {
            quien.sendMessage(plugin.msg(activo ? "enable-usage" : "disable-usage"));
            return;
        }
        OdisseyCodes.Codigo c = plugin.buscar(args[1]);
        if (c == null) {
            quien.sendMessage(plugin.msg("code-not-found"));
            return;
        }
        if (!plugin.habilitar(c.nombre, activo)) {
            quien.sendMessage(plugin.msg("code-not-found"));
            return;
        }
        quien.sendMessage(plugin.msg(activo ? "enabled-ok" : "disabled-ok", "%code%", c.nombre));
        if (quien instanceof Player jugador) plugin.sonido(jugador, "select");
    }

    private void eliminar(CommandSender quien, String[] args) {
        if (args.length < 2) {
            quien.sendMessage(plugin.msg("delete-usage"));
            return;
        }
        OdisseyCodes.Codigo c = plugin.buscar(args[1]);
        if (c == null) {
            quien.sendMessage(plugin.msg("code-not-found"));
            return;
        }
        int canjes = plugin.store().total(c.nombre);
        plugin.eliminarCodigo(c.nombre);
        quien.sendMessage(plugin.msg("deleted",
                "%code%", c.nombre, "%canjes%", String.valueOf(canjes)));
        if (quien instanceof Player jugador) plugin.sonido(jugador, "delete");
    }

    private void reset(CommandSender quien, String[] args) {
        if (args.length < 3) {
            quien.sendMessage(plugin.msg("usage"));
            return;
        }
        OdisseyCodes.Codigo c = plugin.buscar(args[2]);
        if (c == null) {
            quien.sendMessage(plugin.msg("code-not-found"));
            return;
        }
        OfflinePlayer objetivo = Bukkit.getPlayerExact(args[1]) != null
                ? Bukkit.getPlayerExact(args[1])
                : Bukkit.getOfflinePlayer(args[1]);
        if (objetivo == null || (objetivo.getName() == null && !objetivo.hasPlayedBefore())) {
            quien.sendMessage(plugin.msg("player-not-found"));
            return;
        }
        if (plugin.store().reset(c.nombre, objetivo.getUniqueId())) {
            quien.sendMessage(plugin.msg("reset-ok",
                    "%code%", c.nombre, "%player%", String.valueOf(objetivo.getName())));
        } else {
            quien.sendMessage(plugin.msg("reset-fail",
                    "%code%", c.nombre, "%player%", String.valueOf(objetivo.getName())));
        }
    }

    /** /codes generate <usos> <tiempo> [prefijo] */
    private void generar(CommandSender quien, String[] args) {
        if (args.length < 3) {
            quien.sendMessage(plugin.msg("generate-usage"));
            return;
        }
        int usos;
        try {
            usos = Math.max(0, Integer.parseInt(args[1]));
        } catch (NumberFormatException e) {
            quien.sendMessage(plugin.msg("generate-usage"));
            return;
        }
        String tiempo = args[2];
        String duracion = "";
        if (!Tiempo.sinCaducidad(tiempo)) {
            if (Tiempo.duracion(tiempo) <= 0) {
                quien.sendMessage(plugin.msg("generate-bad-time"));
                return;
            }
            duracion = tiempo;
        }
        String prefijo = args.length >= 4 ? args[3] : "";

        String plantilla = plugin.getConfig().getString("generate-template", "");
        if (plantilla == null || plantilla.trim().isEmpty() || plugin.buscar(plantilla) == null) {
            quien.sendMessage(plugin.msg("generate-no-template"));
            return;
        }

        String codigo = plugin.generar(usos, duracion, prefijo, plantilla);
        OdisseyCodes.Codigo c = plugin.buscar(codigo);
        String expira = (c != null && c.caduca > 0) ? Tiempo.fecha(c.caduca) : "-";
        quien.sendMessage(plugin.msg("generated",
                "%code%", codigo,
                "%uses%", String.valueOf(usos),
                "%expires%", expira));
    }

    private void previsualizar(CommandSender quien, String[] args) {
        if (args.length < 2) { quien.sendMessage(plugin.msg("info-usage")); return; }
        OdisseyCodes.Codigo c = plugin.buscar(args[1]);
        if (c == null) { quien.sendMessage(plugin.msg("code-not-found")); return; }
        quien.sendMessage(plugin.texto("&8&m-----&r &ePrevisualización &f" + c.nombre + " &8&m-----"));
        linea(quien, "Campaña", plugin.campanaDe(c.nombre).isBlank() ? "-" : plugin.campanaDe(c.nombre));
        linea(quien, "Modo de recompensa", plugin.getConfig().getString("codes." + c.nombre + ".reward-mode", "all"));
        for (String cmd : c.comandos) quien.sendMessage(plugin.texto("&8  - &7" + cmd));
    }

    private void campana(CommandSender quien, String[] args) {
        if (args.length == 4 && args[1].equalsIgnoreCase("set")) {
            OdisseyCodes.Codigo c = plugin.buscar(args[2]);
            if (c == null) { quien.sendMessage(plugin.msg("code-not-found")); return; }
            plugin.asignarCampana(c.nombre, args[3]);
            quien.sendMessage(plugin.texto("&aCódigo &e" + c.nombre + " &aasignado a la campaña &e" + args[3]));
            return;
        }
        if (args.length == 3 && args[1].equalsIgnoreCase("clear")) {
            OdisseyCodes.Codigo c = plugin.buscar(args[2]);
            if (c == null) { quien.sendMessage(plugin.msg("code-not-found")); return; }
            plugin.asignarCampana(c.nombre, ""); quien.sendMessage("§aCampaña eliminada."); return;
        }
        if (args.length >= 2) {
            int encontrados = 0;
            for (OdisseyCodes.Codigo c : plugin.codigos().values()) {
                if (plugin.campanaDe(c.nombre).equalsIgnoreCase(args[1])) {
                    quien.sendMessage(plugin.texto("&8- &e" + c.nombre + " &7(" + plugin.store().total(c.nombre) + " canjes)")); encontrados++;
                }
            }
            if (encontrados == 0) quien.sendMessage("§7No hay códigos en esa campaña.");
            return;
        }
        quien.sendMessage("§eUso: /codes campaign set <codigo> <nombre> | clear <codigo> | <nombre>");
    }

    private void historial(CommandSender quien, String filtro) {
        List<String> filas = plugin.historial(filtro);
        quien.sendMessage(plugin.texto("&8&m-----&r &eHistorial reciente &8&m-----"));
        if (filas.isEmpty()) { quien.sendMessage(plugin.msg("stats-empty")); return; }
        for (String fila : filas) quien.sendMessage(plugin.texto("&7" + fila));
    }

    private void exportar(CommandSender quien, String nombre) {
        if (!nombre.matches("[A-Za-z0-9._-]+\\.yml")) { quien.sendMessage("§cNombre inválido. Usa un archivo .yml simple."); return; }
        try { File f = plugin.exportar(nombre); quien.sendMessage("§aExportados " + plugin.codigos().size() + " códigos a exports/" + f.getName()); }
        catch (Exception e) { quien.sendMessage("§cError exportando: " + e.getMessage()); }
    }

    private void importar(CommandSender quien, String[] args) {
        if (args.length < 2 || !args[1].matches("[A-Za-z0-9._-]+\\.yml")) { quien.sendMessage("§eUso: /codes import <archivo.yml> (desde la carpeta exports)"); return; }
        try { int n = plugin.importar(args[1]); quien.sendMessage("§aImportados " + n + " códigos."); }
        catch (Exception e) { quien.sendMessage("§cError importando: " + e.getMessage()); }
    }

    private void backup(CommandSender quien) {
        try {
            File dir = new File(plugin.getDataFolder(), "backups");
            if (!dir.exists()) dir.mkdirs();
            String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
            File dest = new File(dir, stamp); dest.mkdirs();
            for (String name : new String[]{"config.yml", "redenciones.yml", "historial.yml", "codes.db"}) {
                File src = new File(plugin.getDataFolder(), name);
                if (src.isFile()) Files.copy(src.toPath(), new File(dest, name).toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            plugin.exportar("backup-" + stamp + ".yml");
            boolean mysql = "mysql".equalsIgnoreCase(plugin.getConfig().getString("storage.type", "yaml"));
            quien.sendMessage("§aRespaldo creado en backups/" + stamp + " (incluye códigos exportados).");
            if (mysql) quien.sendMessage("§eLa base MySQL/MariaDB debe respaldarse también desde tu proveedor; el plugin guarda aquí sus códigos exportados e historial local.");
        } catch (Exception e) { quien.sendMessage("§cError creando respaldo: " + e.getMessage()); }
    }

    @Override
    public List<String> onTabComplete(CommandSender quien, Command comando, String etiqueta, String[] args) {
        List<String> salida = new ArrayList<>();
        boolean admin = quien.hasPermission("odisseycodes.admin");
        if (args.length == 1) {
            for (String op : new String[]{"redeem", "canjear"}) {
                if (op.startsWith(args[0].toLowerCase(Locale.ROOT))) salida.add(op);
            }
            if (!admin) return salida;
            for (String op : new String[]{"reload", "list", "info", "stats", "preview", "campaign", "history", "export", "import", "backup",
                    "enable", "disable", "delete", "reset", "generate"}) {
                if (op.startsWith(args[0].toLowerCase(Locale.ROOT))) salida.add(op);
            }
            return salida;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if ((sub.equals("redeem") || sub.equals("canjear")) && args.length == 2) {
            for (String codigo : plugin.codigos().keySet()) {
                if (codigo.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))) salida.add(codigo);
            }
            return salida;
        }
        if (!admin) return salida;
        if (args.length == 2) {
            if (sub.equals("reset")) {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (p.getName().toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))) {
                        salida.add(p.getName());
                    }
                }
            } else if (sub.equals("info") || sub.equals("enable") || sub.equals("disable")
                    || sub.equals("delete") || sub.equals("borrar")
                    || sub.equals("activar") || sub.equals("desactivar") || sub.equals("ver")) {
                for (String codigo : plugin.codigos().keySet()) {
                    if (codigo.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))) {
                        salida.add(codigo);
                    }
                }
            }
        } else if (args.length == 3 && sub.equals("reset")) {
            for (String codigo : plugin.codigos().keySet()) {
                if (codigo.toLowerCase(Locale.ROOT).startsWith(args[2].toLowerCase(Locale.ROOT))) {
                    salida.add(codigo);
                }
            }
        }
        return salida;
    }
}
