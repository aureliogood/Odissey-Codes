package es.odissey.codes;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.dialog.DialogLike;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * El menu de dialogo de los codigos (Paper 1.21.6+).
 *
 * Es un dialogo de confirmacion: un campo de texto para el codigo y dos botones
 * (Canjear / Cerrar). Al pulsar Canjear se lee lo escrito y se canjea. Si algo
 * falla (codigo mal, ya usado...), se le avisa y se le vuelve a abrir el menu
 * para que lo intente otra vez.
 */
public final class MenuCodigo implements Listener {

    // Clave del campo de texto. Se usa luego para leer lo que escribio.
    private static final String CAMPO = "codigo";

    private final OdisseyCodes plugin;
    private final java.util.Set<java.util.UUID> esperandoCodigo = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public MenuCodigo(OdisseyCodes plugin) {
        this.plugin = plugin;
    }

    /** Abre el menu de dialogo al jugador. */
    public void abrir(Player jugador) {
        if (!tieneShowDialog(jugador)) {
            esperandoCodigo.add(jugador.getUniqueId());
            jugador.sendMessage("§eEscribe tu código en el chat. Usa §f/codes cancel§e para cancelar.");
            return;
        }
        plugin.sonido(jugador, "open");
        Component titulo = plugin.texto(plugin.crudo("title"));

        ActionButton canjear = ActionButton.builder(plugin.texto(plugin.crudo("button-redeem")))
                .width(100)
                .action(DialogAction.customClick(this::canjear,
                        ClickCallback.Options.builder().uses(1).build()))
                .build();

        ActionButton cerrar = ActionButton.builder(plugin.texto(plugin.crudo("button-close")))
                .width(100)
                .build(); // sin accion: solo cierra el dialogo

        // El boton de invitacion solo aparece si la funcion esta activada.
        DialogType tipo;
        if (plugin.refActivo()) {
            ActionButton referido = ActionButton.builder(plugin.texto(plugin.crudo("button-referral")))
                    .tooltip(hoverReferido(jugador))
                    .width(200)
                    .action(DialogAction.customClick(this::crearInvitacion,
                            ClickCallback.Options.builder().uses(1).build()))
                    .build();
            tipo = DialogType.multiAction(List.of(canjear, referido), cerrar, 1);
        } else {
            tipo = DialogType.confirmation(canjear, cerrar);
        }

        Dialog dialogo = Dialog.create(b -> b.empty()
                .base(DialogBase.builder(titulo)
                        .externalTitle(titulo)
                        .canCloseWithEscape(true)
                        .body(List.of(DialogBody.plainMessage(
                                plugin.texto(plugin.crudo("body")))))
                        .inputs(List.of(
                                DialogInput.text(CAMPO, plugin.texto(plugin.crudo("label")))
                                        .labelVisible(true)
                                        .width(300)
                                        .maxLength(64)
                                        .build()))
                        .build())
                .type(tipo));

        try {
            // Dialog implementa DialogLike; la firma de Paper acepta DialogLike,
            // no Dialog. La reflexión mantiene el fallback para servidores sin API.
            jugador.getClass().getMethod("showDialog", DialogLike.class).invoke(jugador, dialogo);
        } catch (ReflectiveOperationException e) {
            plugin.getLogger().fine("No se pudo abrir el diálogo de códigos; se usará entrada por chat: " + e.getMessage());
            esperandoCodigo.add(jugador.getUniqueId());
            jugador.sendMessage("§eEscribe tu código en el chat. Usa §f/codes cancel§e para cancelar.");
        }
    }

    private boolean tieneShowDialog(Player jugador) {
        try {
            jugador.getClass().getMethod("showDialog", DialogLike.class);
            return true;
        } catch (ReflectiveOperationException | LinkageError e) {
            return false;
        }
    }

    public boolean cancelar(Player jugador) {
        return esperandoCodigo.remove(jugador.getUniqueId());
    }

    public void canjearDirecto(Player jugador, String codigo) {
        procesarCanjeChat(jugador, codigo);
    }

    @EventHandler
    public void alEscribirCodigo(AsyncPlayerChatEvent event) {
        Player jugador = event.getPlayer();
        if (!esperandoCodigo.remove(jugador.getUniqueId())) return;
        event.setCancelled(true);
        String codigo = event.getMessage();
        Bukkit.getScheduler().runTask(plugin, () -> procesarCanjeChat(jugador, codigo));
    }

    private void procesarCanjeChat(Player jugador, String codigo) {
        if (!jugador.isOnline()) return;
        OdisseyCodes.Res res = plugin.canjear(jugador, codigo);
        switch (res.tipo) {
            case OK:
                jugador.sendMessage(plugin.msg("redeemed", "%code%", res.codigo));
                plugin.sonido(jugador, "success");
                plugin.sonido(jugador, "reward");
                plugin.particula(jugador, "success");
                plugin.titulo(jugador, "success", "%code%", res.codigo);
                if (res.recompensaFallida) jugador.sendMessage(plugin.msg("reward-failed"));
                break;
            case VACIO:
                error(jugador, "empty-code");
                break;
            case INVALIDO:
                error(jugador, "invalid");
                break;
            case YA_USADO:
                error(jugador, "already-used");
                break;
            case IP_USADA:
                error(jugador, "ip-used");
                break;
            case AGOTADO:
                error(jugador, "exhausted");
                break;
            case CADUCADO:
                error(jugador, "expired");
                break;
            case CONDICION:
                error(jugador, res.clave, res.args);
                break;
            case REF_OK:
                Referido ref = plugin.store().ref(res.codigo);
                jugador.sendMessage(plugin.msg("referral-redeemed", "%dueno%", ref == null ? "" : ref.duenoNombre));
                plugin.sonido(jugador, "success");
                plugin.particula(jugador, "success");
                plugin.titulo(jugador, "success", "%code%", res.codigo);
                break;
            case ERROR:
                jugador.sendMessage("§cNo se pudo registrar el canje. Inténtalo más tarde y avisa a un administrador.");
                reabrir(jugador);
                break;
        }
    }

    /**
     * Texto del hover del boton de invitacion: las lineas del config y, si el
     * jugador ya tiene su codigo, una linea extra con el codigo y su progreso.
     */
    private Component hoverReferido(Player jugador) {
        List<String> lineas = new ArrayList<>(
                plugin.getConfig().getStringList("messages.button-referral-hover"));
        if (lineas.isEmpty()) lineas.add(plugin.crudo("button-referral"));

        int meta = plugin.refMeta();
        String dinero = OdisseyCodes.miles(plugin.refDineroPorUso());
        String bono = OdisseyCodes.miles(plugin.refBono());
        List<Component> comps = new ArrayList<>();
        for (String l : lineas) {
            comps.add(plugin.texto(l
                    .replace("%dinero%", dinero)
                    .replace("%bono%", bono)
                    .replace("%meta%", String.valueOf(meta))));
        }

        String code = plugin.store().codigoRefDe(jugador.getUniqueId());
        if (code != null) {
            comps.add(plugin.texto(plugin.crudo("button-referral-code")
                    .replace("%code%", code)));
            String tpl = plugin.crudo("button-referral-count");
            if (tpl != null && !tpl.equals("button-referral-count")) {
                comps.add(plugin.texto(tpl
                        .replace("%total%", String.valueOf(plugin.store().totalRef(code)))
                        .replace("%meta%", String.valueOf(meta))));
            }
        }
        return Component.join(Component.newline(), comps);
    }

    /** Se dispara al pulsar "Crea tu propio codigo". */
    private void crearInvitacion(DialogResponseView vista, Audience audiencia) {
        if (!(audiencia instanceof Player jugador)) return;
        String code = plugin.codigoInvitacion(jugador); // crea el codigo si aun no lo tiene
        if (code == null) {
            jugador.sendMessage(plugin.msg("referral-disabled"));
            return;
        }
        plugin.sonido(jugador, "create");
        reabrir(jugador);
    }

    /** Se dispara al pulsar "Canjear". */
    private void canjear(DialogResponseView vista, Audience audiencia) {
        if (!(audiencia instanceof Player jugador)) return;
        String texto = vista.getText(CAMPO);

        OdisseyCodes.Res res = plugin.canjear(jugador, texto);
        switch (res.tipo) {
            case OK:
                jugador.sendMessage(plugin.msg("redeemed", "%code%", res.codigo));
                plugin.sonido(jugador, "success");
                plugin.sonido(jugador, "reward");
                plugin.particula(jugador, "success");
                plugin.titulo(jugador, "success", "%code%", res.codigo);
                if (res.recompensaFallida) {
                    jugador.sendMessage(plugin.msg("reward-failed"));
                }
                break;
            case VACIO:
                error(jugador, "empty-code");
                break;
            case INVALIDO:
                error(jugador, "invalid");
                break;
            case YA_USADO:
                error(jugador, "already-used");
                break;
            case IP_USADA:
                error(jugador, "ip-used");
                break;
            case AGOTADO:
                error(jugador, "exhausted");
                break;
            case CADUCADO:
                error(jugador, "expired");
                break;
            case CONDICION:
                error(jugador, res.clave, res.args);
                break;
            case REF_OK: {
                Referido ref = plugin.store().ref(res.codigo);
                jugador.sendMessage(plugin.msg("referral-redeemed",
                        "%dueno%", ref == null ? "" : ref.duenoNombre));
                plugin.sonido(jugador, "success");
                plugin.particula(jugador, "success");
                plugin.titulo(jugador, "success", "%code%", res.codigo);
                break;
            }
            case ERROR:
                jugador.sendMessage("§cNo se pudo registrar el canje. Inténtalo más tarde y avisa a un administrador.");
                reabrir(jugador);
                break;
            default:
                break;
        }
    }

    // Aviso de error: mensaje al chat, sonido, particula, titulo y reabrir el menu.
    private void error(Player jugador, String claveMsg, String... reemplazos) {
        jugador.sendMessage(plugin.msg(claveMsg, reemplazos));
        plugin.sonido(jugador, "error");
        plugin.particula(jugador, "error");
        String motivo = plugin.crudo(claveMsg);
        for (int i = 0; i + 1 < reemplazos.length; i += 2) {
            motivo = motivo.replace(reemplazos[i], reemplazos[i + 1]);
        }
        plugin.titulo(jugador, "error", "%motivo%", motivo);
        reabrir(jugador);
    }

    // Reabre el menu un tick despues, cuando el dialogo actual ya se cerro.
    private void reabrir(Player jugador) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (jugador.isOnline()) abrir(jugador);
        });
    }
}
