package es.odissey.codes;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Almacenamiento en base de datos (MySQL/MariaDB o SQLite).
 *
 * Aqui viven tanto los codigos como los canjes, asi que un panel web o un bot
 * de Discord pueden crear codigos insertando una fila en la tabla de codigos,
 * y el plugin los recoge solo (sync-seconds).
 */
public final class SqlStore implements Almacen {

    private final OdisseyCodes plugin;
    private final Db db;

    public SqlStore(OdisseyCodes plugin, Db db) {
        this.plugin = plugin;
        this.db = db;
    }

    // ------------------------------------------------------------------
    //  CODIGOS
    // ------------------------------------------------------------------

    /**
     * Lee TODOS los codigos de la base de datos (activos e inactivos). Los
     * inactivos se cargan igualmente para poder verlos y activarlos con
     * /codes enable; canjear() los rechaza por su flag 'activo'.
     */
    public List<OdisseyCodes.Codigo> cargarCodigos() {
        List<OdisseyCodes.Codigo> salida = new ArrayList<>();
        String sql = "SELECT code, uses, uses_per_player, expires_at, commands, permission, "
                + "min_time_played, min_money, required_world, enabled "
                + "FROM " + db.codigos();
        try (Connection c = db.con();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String code = rs.getString("code");
                int uses = Math.max(0, rs.getInt("uses"));
                int upp = Math.max(1, rs.getInt("uses_per_player"));
                long exp = rs.getLong("expires_at");
                if (rs.wasNull()) exp = 0;
                String perm = rs.getString("permission");
                long minTiempo = rs.getLong("min_time_played");
                double minDinero = rs.getDouble("min_money");
                String mundo = rs.getString("required_world");
                boolean activo = rs.getInt("enabled") != 0;
                List<String> cmds = new ArrayList<>();
                String cmdsTxt = rs.getString("commands");
                if (cmdsTxt != null) {
                    for (String s : cmdsTxt.split("\n")) {
                        if (!s.trim().isEmpty()) cmds.add(s.trim());
                    }
                }
                if (cmds.isEmpty()) continue;
                salida.add(new OdisseyCodes.Codigo(code, uses, upp, cmds, exp,
                        perm == null ? "" : perm, activo,
                        minTiempo, minDinero, mundo == null ? "" : mundo));
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Error leyendo codigos de la base de datos: " + e.getMessage());
        }
        return salida;
    }

    /** Crea o actualiza un codigo (lo usa el generador del propio plugin). */
    public void insertarCodigo(OdisseyCodes.Codigo c) {
        String cols = "(code, uses, uses_per_player, expires_at, commands, permission, "
                + "min_time_played, min_money, required_world, enabled, created_at)";
        String sql;
        if (db.esMysql()) {
            sql = "INSERT INTO " + db.codigos() + " " + cols + " VALUES (?,?,?,?,?,?,?,?,?,?,?)"
                    + " ON DUPLICATE KEY UPDATE uses=VALUES(uses), uses_per_player=VALUES(uses_per_player),"
                    + " expires_at=VALUES(expires_at), commands=VALUES(commands),"
                    + " permission=VALUES(permission), min_time_played=VALUES(min_time_played),"
                    + " min_money=VALUES(min_money), required_world=VALUES(required_world),"
                    + " enabled=VALUES(enabled)";
        } else {
            sql = "INSERT OR REPLACE INTO " + db.codigos() + " " + cols + " VALUES (?,?,?,?,?,?,?,?,?,?,?)";
        }
        try (Connection conn = db.con(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, c.nombre);
            ps.setInt(2, c.usos);
            ps.setInt(3, c.usosPorJugador);
            if (c.caduca > 0) ps.setLong(4, c.caduca);
            else ps.setNull(4, Types.BIGINT);
            ps.setString(5, String.join("\n", c.comandos));
            if (c.permiso == null || c.permiso.isEmpty()) ps.setNull(6, Types.VARCHAR);
            else ps.setString(6, c.permiso);
            ps.setLong(7, c.minTiempo);
            ps.setDouble(8, c.minDinero);
            if (c.mundo == null || c.mundo.isEmpty()) ps.setNull(9, Types.VARCHAR);
            else ps.setString(9, c.mundo);
            ps.setInt(10, c.activo ? 1 : 0);
            ps.setLong(11, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("Error guardando el codigo en la base de datos: " + e.getMessage());
        }
    }

    /** Activa o desactiva un codigo de la base de datos. */
    public void setEnabled(String codigo, boolean activo) {
        String sql = "UPDATE " + db.codigos() + " SET enabled = ? WHERE code = ?";
        try (Connection c = db.con(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, activo ? 1 : 0);
            ps.setString(2, codigo);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("Error cambiando el estado del codigo: " + e.getMessage());
        }
    }

    /** Borra un codigo y sus canjes. */
    public void borrarCodigo(String codigo) {
        try (Connection conn = db.con()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM " + db.codigos() + " WHERE code = ?")) {
                ps.setString(1, codigo);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM " + db.redenciones() + " WHERE code = ?")) {
                ps.setString(1, codigo);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Error borrando el codigo de la base de datos: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    //  CANJES (Almacen)
    // ------------------------------------------------------------------

    @Override
    public boolean yaCanjeado(String codigo, UUID uuid) {
        return usadasPor(codigo, uuid) >= 1;
    }

    @Override
    public int usadasPor(String codigo, UUID uuid) {
        String sql = "SELECT COUNT(*) FROM " + db.redenciones() + " WHERE code = ? AND uuid = ?";
        try (Connection c = db.con(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, codigo);
            ps.setString(2, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Error contando canjes: " + e.getMessage());
            return Integer.MAX_VALUE;
        }
    }

    @Override
    public boolean ipUsada(String codigo, String ip) {
        if (ip == null || ip.isEmpty()) return false;
        String sql = "SELECT 1 FROM " + db.redenciones() + " WHERE code = ? AND ip = ? LIMIT 1";
        try (Connection c = db.con(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, codigo);
            ps.setString(2, ip);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Error comprobando IP: " + e.getMessage());
            return true;
        }
    }

    @Override
    public int total(String codigo) {
        String sql = "SELECT COUNT(*) FROM " + db.redenciones() + " WHERE code = ?";
        try (Connection c = db.con(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, codigo);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Error contando usos: " + e.getMessage());
            return Integer.MAX_VALUE;
        }
    }

    @Override
    public boolean registrar(String codigo, UUID uuid, String nombre, String ip) {
        String sql = "INSERT INTO " + db.redenciones()
                + " (code, uuid, player_name, ip, redeemed_at) VALUES (?,?,?,?,?)";
        try (Connection c = db.con(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, codigo);
            ps.setString(2, uuid.toString());
            ps.setString(3, nombre);
            ps.setString(4, ip == null ? "" : ip);
            ps.setLong(5, System.currentTimeMillis());
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            plugin.getLogger().warning("Error registrando el canje: " + e.getMessage());
            return false;
        }
    }

    @Override
    public boolean reset(String codigo, UUID uuid) {
        String sql = "DELETE FROM " + db.redenciones() + " WHERE code = ? AND uuid = ?";
        try (Connection c = db.con(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, codigo);
            ps.setString(2, uuid.toString());
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            plugin.getLogger().warning("Error reseteando el canje: " + e.getMessage());
            return false;
        }
    }

    @Override
    public void borrarTodo(String codigo) {
        try (Connection c = db.con(); PreparedStatement ps = c.prepareStatement(
                "DELETE FROM " + db.redenciones() + " WHERE code = ?")) {
            ps.setString(1, codigo);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("Error borrando los canjes: " + e.getMessage());
        }
    }

    // En modo BD el instante de creacion no se usa: la caducidad se guarda ya
    // como fecha absoluta (expires_at) en la propia tabla de codigos.
    @Override
    public long creado(String codigo, long siAusente) {
        return siAusente;
    }

    @Override
    public void fijarCreado(String codigo, long cuando) {
        // nada que hacer
    }

    @Override
    public void guardar() {
        // la base de datos ya guarda al momento
    }

    @Override
    public void cerrar() {
        db.cerrar();
    }

    // ------------------------------------------------------------------
    //  CODIGOS DE INVITACION (referidos)
    // ------------------------------------------------------------------

    @Override
    public void crearRef(String codigo, UUID dueno, String nombreDueno, String ipDueno, long cuando) {
        String cols = "(code, owner_uuid, owner_name, owner_ip, bonus_paid, created_at)";
        String sql;
        if (db.esMysql()) {
            sql = "INSERT INTO " + db.referidos() + " " + cols + " VALUES (?,?,?,?,?,?)"
                    + " ON DUPLICATE KEY UPDATE owner_uuid=VALUES(owner_uuid), owner_name=VALUES(owner_name),"
                    + " owner_ip=VALUES(owner_ip)";
        } else {
            sql = "INSERT OR REPLACE INTO " + db.referidos() + " " + cols + " VALUES (?,?,?,?,?,?)";
        }
        try (Connection c = db.con(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, codigo);
            ps.setString(2, dueno.toString());
            ps.setString(3, nombreDueno);
            ps.setString(4, ipDueno == null ? "" : ipDueno);
            ps.setInt(5, 0);
            ps.setLong(6, cuando);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("Error creando el codigo de invitacion: " + e.getMessage());
        }
    }

    @Override
    public String codigoRefDe(UUID jugador) {
        String sql = "SELECT code FROM " + db.referidos() + " WHERE owner_uuid = ?";
        try (Connection c = db.con(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, jugador.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Error leyendo el codigo de invitacion: " + e.getMessage());
            return null;
        }
    }

    @Override
    public Referido ref(String codigo) {
        if (codigo == null || codigo.isEmpty()) return null;
        String sql = "SELECT code, owner_uuid, owner_name, owner_ip, bonus_paid FROM "
                + db.referidos() + " WHERE UPPER(code) = UPPER(?)";
        try (Connection c = db.con(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, codigo);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new Referido(rs.getString("code"), rs.getString("owner_uuid"),
                        rs.getString("owner_name"), rs.getString("owner_ip"),
                        rs.getInt("bonus_paid") != 0);
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Error buscando el codigo de invitacion: " + e.getMessage());
            return null;
        }
    }

    @Override
    public boolean ipUsadaRef(String codigo, String ip) {
        if (ip == null || ip.isEmpty()) return false;
        String sql = "SELECT 1 FROM " + db.usosRef() + " WHERE code = ? AND ip = ? LIMIT 1";
        try (Connection c = db.con(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, codigo);
            ps.setString(2, ip);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Error comprobando IP de invitacion: " + e.getMessage());
            return true;
        }
    }

    @Override
    public int usadasPorRef(String codigo, UUID jugador) {
        String sql = "SELECT COUNT(*) FROM " + db.usosRef() + " WHERE code = ? AND uuid = ?";
        try (Connection c = db.con(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, codigo);
            ps.setString(2, jugador.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Error contando usos de invitacion: " + e.getMessage());
            return Integer.MAX_VALUE;
        }
    }

    @Override
    public int totalRef(String codigo) {
        String sql = "SELECT COUNT(*) FROM " + db.usosRef() + " WHERE code = ?";
        try (Connection c = db.con(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, codigo);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Error contando usos de invitacion: " + e.getMessage());
            return -1;
        }
    }

    @Override
    public boolean registrarRef(String codigo, UUID jugador, String nombre, String ip) {
        String sql = "INSERT INTO " + db.usosRef()
                + " (code, uuid, player_name, ip, used_at) VALUES (?,?,?,?,?)";
        try (Connection c = db.con(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, codigo);
            ps.setString(2, jugador.toString());
            ps.setString(3, nombre);
            ps.setString(4, ip == null ? "" : ip);
            ps.setLong(5, System.currentTimeMillis());
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            plugin.getLogger().warning("Error registrando el uso de invitacion: " + e.getMessage());
            return false;
        }
    }

    @Override
    public boolean marcarBonoRef(String codigo) {
        String sql = "UPDATE " + db.referidos()
                + " SET bonus_paid = 1 WHERE UPPER(code) = UPPER(?) AND bonus_paid = 0";
        try (Connection c = db.con(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, codigo);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            plugin.getLogger().warning("Error marcando el bono de invitacion: " + e.getMessage());
            return false;
        }
    }
}
