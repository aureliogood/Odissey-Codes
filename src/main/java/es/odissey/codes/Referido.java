package es.odissey.codes;

/**
 * Un codigo de invitacion ("crea tu propio codigo").
 *
 * Cada jugador puede crear UNO solo. Cuando otra persona lo canjea, el dueño
 * recibe dinero (aunque este desconectado), y al llegar a la meta de invitados
 * recibe un bono extra una sola vez.
 */
public final class Referido {

    public final String codigo;       // el texto del codigo (REF-XXXXXX)
    public final String dueno;        // UUID del dueño, en texto
    public final String duenoNombre;  // nombre del dueño (para el mensaje/pago)
    public final String duenoIp;      // IP del dueño al crearlo (bloquea multis)
    public final boolean bono;        // true si ya cobro el bono de la meta

    public Referido(String codigo, String dueno, String duenoNombre, String duenoIp, boolean bono) {
        this.codigo = codigo;
        this.dueno = dueno == null ? "" : dueno;
        this.duenoNombre = duenoNombre == null ? "" : duenoNombre;
        this.duenoIp = duenoIp == null ? "" : duenoIp;
        this.bono = bono;
    }
}
