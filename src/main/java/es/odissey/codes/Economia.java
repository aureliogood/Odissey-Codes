package es.odissey.codes;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import java.lang.reflect.Method;

/**
 * Acceso opcional a Vault para leer el dinero de un jugador.
 *
 * Se hace por reflexion para no depender de Vault al compilar. Si el servidor
 * no tiene Vault (o ninguna economia registrada en el), disponible() es false.
 */
public final class Economia {

    private static boolean vaultPresente; // la clase de Vault esta en el classpath
    private static Object eco;
    private static Method mSaldo;
    private static Method mDeposito;

    private Economia() {
    }

    /** true si hay una economia de Vault utilizable (Vault + proveedor registrado). */
    public static boolean disponible() {
        buscar();
        return eco != null;
    }

    /** true si la clase de Vault esta presente (aunque no haya proveedor). */
    public static boolean vaultPresente() {
        buscar();
        return vaultPresente;
    }

    /** Saldo del jugador, o 0 si no hay economia. */
    public static double saldo(OfflinePlayer jugador) {
        buscar();
        if (eco == null || mSaldo == null) return 0;
        try {
            Object r = mSaldo.invoke(eco, jugador);
            return r instanceof Number ? ((Number) r).doubleValue() : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    public static boolean depositar(OfflinePlayer jugador, double cantidad) {
        buscar();
        if (eco == null || mDeposito == null || cantidad <= 0) return false;
        try {
            Object respuesta = mDeposito.invoke(eco, jugador, cantidad);
            if (respuesta == null) return true;
            try { return (Boolean) respuesta.getClass().getMethod("transactionSuccess").invoke(respuesta); }
            catch (ReflectiveOperationException ignored) { return true; }
        } catch (Throwable t) { return false; }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void buscar() {
        if (eco != null) return;
        Class<?> economia;
        try {
            economia = Class.forName("net.milkbowl.vault.economy.Economy");
            vaultPresente = true;
        } catch (Throwable sinVault) {
            return; // no hay Vault instalado
        }
        Object servicios = Bukkit.getServicesManager();

        // 1) Via directa: ServicesManager#getRegistration(Class).
        try {
            Method getReg = servicios.getClass().getMethod("getRegistration", Class.class);
            Object reg = getReg.invoke(servicios, economia);
            if (reg != null && usarProveedor(reg)) return;
        } catch (Throwable ignored) {
            // se intenta el siguiente metodo
        }

        // 2) Alternativa: ServicesManager#getRegistrations(Class).
        try {
            Method getRegs = servicios.getClass().getMethod("getRegistrations", Class.class);
            Object res = getRegs.invoke(servicios, economia);
            if (res instanceof Iterable<?> registros) {
                for (Object rsp : registros) {
                    if (usarProveedor(rsp)) return;
                }
            }
        } catch (Throwable ignored) {
            // sin economia: las condiciones de dinero no se pueden comprobar
        }
    }

    /** Guarda el proveedor de un RegisteredServiceProvider. true si lo consiguio. */
    private static boolean usarProveedor(Object registro) {
        try {
            Object proveedor = registro.getClass().getMethod("getProvider").invoke(registro);
            if (proveedor == null) return false;
            eco = proveedor;
            mSaldo = proveedor.getClass().getMethod("getBalance", OfflinePlayer.class);
            try { mDeposito = proveedor.getClass().getMethod("depositPlayer", OfflinePlayer.class, double.class); }
            catch (NoSuchMethodException ignored) { mDeposito = null; }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
