package es.odissey.codes;

import java.util.UUID;

/**
 * Guarda quien ha canjeado cada codigo (para las reglas de una vez por jugador
 * y una vez por IP) y el contador de usos.
 *
 * Hay dos implementaciones: CodesStore (archivo YAML) y SqlStore (MySQL/SQLite).
 * El resto del plugin solo conoce esta interfaz.
 */
public interface Almacen {

    /** Cuantas veces ese jugador ya canjeo ese codigo. */
    int usadasPor(String codigo, UUID uuid);

    /** true si el jugador ya canjeo el codigo al menos una vez. */
    boolean yaCanjeado(String codigo, UUID uuid);

    /** true si esa IP ya canjeo el codigo. */
    boolean ipUsada(String codigo, String ip);

    /** Cuantas veces se ha canjeado el codigo en total. */
    int total(String codigo);

    /** Registra un canje. */
    boolean registrar(String codigo, UUID uuid, String nombre, String ip);

    /** Borra los canjes de un jugador en un codigo. Devuelve si habia alguno. */
    boolean reset(String codigo, UUID uuid);

    /** Borra todos los canjes de un codigo. */
    void borrarTodo(String codigo);

    /** Instante en que el codigo se vio por primera vez (para la caducidad). */
    long creado(String codigo, long siAusente);

    /** Fija el instante de creacion de un codigo. */
    void fijarCreado(String codigo, long cuando);

    /** Vuelca los cambios a disco si hace falta. */
    void guardar();

    /** Cierra la conexion si hace falta. */
    void cerrar();

    // ------------------------------------------------------------------
    //  CODIGOS DE INVITACION (referidos)
    // ------------------------------------------------------------------

    /** Crea el codigo de invitacion de un jugador (una sola vez). */
    void crearRef(String codigo, UUID dueno, String nombreDueno, String ipDueno, long cuando);

    /** El codigo de invitacion de un jugador, o null si aun no ha creado el suyo. */
    String codigoRefDe(UUID jugador);

    /** Busca un codigo de invitacion por su texto (sin distinguir mayusculas). */
    Referido ref(String codigo);

    /** true si esa IP ya uso ese codigo de invitacion. */
    boolean ipUsadaRef(String codigo, String ip);

    /** Cuantas veces ese jugador uso ese codigo de invitacion. */
    int usadasPorRef(String codigo, UUID jugador);

    /** Cuantos usos lleva ese codigo de invitacion. */
    int totalRef(String codigo);

    /** Registra un uso del codigo de invitacion. */
    boolean registrarRef(String codigo, UUID jugador, String nombre, String ip);

    /** Marca que el bono de la meta ya se pago (para no pagarlo dos veces). */
    boolean marcarBonoRef(String codigo);
}
