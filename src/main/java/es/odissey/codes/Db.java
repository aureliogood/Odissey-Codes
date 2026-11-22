package es.odissey.codes;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Conexion a la base de datos (MySQL/MariaDB o SQLite) por JDBC y creacion de
 * las tablas si no existen.
 *
 * Sirve para que los administradores creen codigos desde fuera del juego (un
 * panel web o un bot de Discord) escribiendo en la misma base de datos.
 *
 * Los drivers se declaran en el plugin.yml; Paper los carga para el plugin.
 */
public final class Db {

    private final OdisseyCodes plugin;
    private final String tipo;    // "mysql" | "sqlite"
    private final String prefijo; // prefijo de las tablas
    private String url;
    private String user = "";
    private String pass = "";

    public Db(OdisseyCodes plugin, String tipo, String prefijo) {
        this.plugin = plugin;
        this.tipo = tipo;
        this.prefijo = prefijo == null || prefijo.isEmpty() ? "odisseycodes_" : prefijo;
    }

    public boolean esMysql() {
        return tipo.equals("mysql");
    }

    /** Tabla de codigos. */
    public String codigos() {
        return prefijo + "codes";
    }

    /** Tabla de canjes. */
    public String redenciones() {
        return prefijo + "redemptions";
    }

    /** Tabla de codigos de invitacion (referidos). */
    public String referidos() {
        return prefijo + "referrals";
    }

    /** Tabla de usos de los codigos de invitacion. */
    public String usosRef() {
        return prefijo + "referral_uses";
    }

    /** Abre la conexion y crea las tablas. Devuelve false si fallo. */
    public boolean abrir() {
        try {
            if (tipo.equals("sqlite")) {
                Class.forName("org.sqlite.JDBC");
                File f = new File(plugin.getDataFolder(), "codes.db");
                url = "jdbc:sqlite:" + f.getAbsolutePath();
            } else {
                Class.forName("com.mysql.cj.jdbc.Driver");
                String host = plugin.getConfig().getString("storage.mysql.host", "localhost");
                int puerto = plugin.getConfig().getInt("storage.mysql.port", 3306);
                String base = plugin.getConfig().getString("storage.mysql.database", "odisseycodes");
                user = plugin.getConfig().getString("storage.mysql.user", "root");
                pass = plugin.getConfig().getString("storage.mysql.password", "");
                url = "jdbc:mysql://" + host + ":" + puerto + "/" + base
                        + "?useSSL=false&allowPublicKeyRetrieval=true"
                        + "&characterEncoding=utf8&serverTimezone=UTC";
            }
            try (Connection c = con()) {
                crearTablas(c);
                asegurarColumnas(c);
            }
            return true;
        } catch (Exception e) {
            plugin.getLogger().severe("No se pudo conectar a la base de datos (" + tipo
                    + "): " + e.getMessage());
            return false;
        }
    }

    public Connection con() throws SQLException {
        if (tipo.equals("sqlite")) {
            return DriverManager.getConnection(url);
        }
        return DriverManager.getConnection(url, user, pass);
    }

    private void crearTablas(Connection c) throws SQLException {
        try (Statement st = c.createStatement()) {
            if (tipo.equals("sqlite")) {
                st.executeUpdate("CREATE TABLE IF NOT EXISTS " + codigos() + " ("
                        + "code TEXT PRIMARY KEY,"
                        + "uses INTEGER NOT NULL DEFAULT 0,"
                        + "uses_per_player INTEGER NOT NULL DEFAULT 1,"
                        + "expires_at INTEGER,"
                        + "commands TEXT NOT NULL,"
                        + "permission TEXT,"
                        + "min_time_played INTEGER NOT NULL DEFAULT 0,"
                        + "min_money REAL NOT NULL DEFAULT 0,"
                        + "required_world TEXT,"
                        + "enabled INTEGER NOT NULL DEFAULT 1,"
                        + "created_by TEXT,"
                        + "created_at INTEGER NOT NULL)");
                st.executeUpdate("CREATE TABLE IF NOT EXISTS " + redenciones() + " ("
                        + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                        + "code TEXT NOT NULL,"
                        + "uuid TEXT NOT NULL,"
                        + "player_name TEXT,"
                        + "ip TEXT,"
                        + "redeemed_at INTEGER NOT NULL)");
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_" + prefijo
                        + "code ON " + redenciones() + " (code)");
                st.executeUpdate("CREATE TABLE IF NOT EXISTS " + referidos() + " ("
                        + "code TEXT PRIMARY KEY,"
                        + "owner_uuid TEXT NOT NULL,"
                        + "owner_name TEXT,"
                        + "owner_ip TEXT,"
                        + "bonus_paid INTEGER NOT NULL DEFAULT 0,"
                        + "created_at INTEGER NOT NULL)");
                st.executeUpdate("CREATE TABLE IF NOT EXISTS " + usosRef() + " ("
                        + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                        + "code TEXT NOT NULL,"
                        + "uuid TEXT NOT NULL,"
                        + "player_name TEXT,"
                        + "ip TEXT,"
                        + "used_at INTEGER NOT NULL)");
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_" + prefijo
                        + "refcode ON " + usosRef() + " (code)");
            } else {
                st.executeUpdate("CREATE TABLE IF NOT EXISTS " + codigos() + " ("
                        + "code VARCHAR(64) NOT NULL,"
                        + "uses INT NOT NULL DEFAULT 0,"
                        + "uses_per_player INT NOT NULL DEFAULT 1,"
                        + "expires_at BIGINT NULL,"
                        + "commands TEXT NOT NULL,"
                        + "permission VARCHAR(128) NULL,"
                        + "min_time_played BIGINT NOT NULL DEFAULT 0,"
                        + "min_money DOUBLE NOT NULL DEFAULT 0,"
                        + "required_world VARCHAR(64) NULL,"
                        + "enabled TINYINT(1) NOT NULL DEFAULT 1,"
                        + "created_by VARCHAR(64) NULL,"
                        + "created_at BIGINT NOT NULL,"
                        + "PRIMARY KEY (code)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
                st.executeUpdate("CREATE TABLE IF NOT EXISTS " + redenciones() + " ("
                        + "id BIGINT NOT NULL AUTO_INCREMENT,"
                        + "code VARCHAR(64) NOT NULL,"
                        + "uuid VARCHAR(36) NOT NULL,"
                        + "player_name VARCHAR(32) NULL,"
                        + "ip VARCHAR(45) NULL,"
                        + "redeemed_at BIGINT NOT NULL,"
                        + "PRIMARY KEY (id),"
                        + "KEY idx_" + prefijo + "code (code),"
                        + "KEY idx_" + prefijo + "uuid (code, uuid))"
                        + " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
                st.executeUpdate("CREATE TABLE IF NOT EXISTS " + referidos() + " ("
                        + "code VARCHAR(64) NOT NULL,"
                        + "owner_uuid VARCHAR(36) NOT NULL,"
                        + "owner_name VARCHAR(32) NULL,"
                        + "owner_ip VARCHAR(45) NULL,"
                        + "bonus_paid TINYINT(1) NOT NULL DEFAULT 0,"
                        + "created_at BIGINT NOT NULL,"
                        + "PRIMARY KEY (code),"
                        + "KEY idx_" + prefijo + "refowner (owner_uuid))"
                        + " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
                st.executeUpdate("CREATE TABLE IF NOT EXISTS " + usosRef() + " ("
                        + "id BIGINT NOT NULL AUTO_INCREMENT,"
                        + "code VARCHAR(64) NOT NULL,"
                        + "uuid VARCHAR(36) NOT NULL,"
                        + "player_name VARCHAR(32) NULL,"
                        + "ip VARCHAR(45) NULL,"
                        + "used_at BIGINT NOT NULL,"
                        + "PRIMARY KEY (id),"
                        + "KEY idx_" + prefijo + "refcode (code),"
                        + "KEY idx_" + prefijo + "refuuid (code, uuid))"
                        + " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            }
        }
    }

    public void cerrar() {
        // Las conexiones se abren y cierran por operacion; no hay nada que cerrar.
    }

    /**
     * Anade columnas nuevas a bases de datos ya existentes (de versiones
     * anteriores). Si la columna ya esta, la sentencia falla y se ignora.
     */
    private void asegurarColumnas(Connection c) {
        String[][] cols = {
                {"min_time_played", tipo.equals("sqlite") ? "INTEGER NOT NULL DEFAULT 0" : "BIGINT NOT NULL DEFAULT 0"},
                {"min_money", tipo.equals("sqlite") ? "REAL NOT NULL DEFAULT 0" : "DOUBLE NOT NULL DEFAULT 0"},
                {"required_world", tipo.equals("sqlite") ? "TEXT" : "VARCHAR(64) NULL"}
        };
        for (String[] col : cols) {
            try (Statement st = c.createStatement()) {
                st.executeUpdate("ALTER TABLE " + codigos()
                        + " ADD COLUMN " + col[0] + " " + col[1]);
            } catch (SQLException ignored) {
                // la columna ya existe
            }
        }
    }
}
