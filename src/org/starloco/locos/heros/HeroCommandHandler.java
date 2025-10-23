package org.starloco.locos.heros;

import org.starloco.locos.client.Player;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Analyse et exécute la commande .heros côté joueur.
 */
public final class HeroCommandHandler {

    private HeroCommandHandler() {
    }

    /**
     * Traite la commande transmise par le client.
     *
     * @param master joueur ayant saisi la commande.
     * @param rawMessage message brut saisi.
     * @return {@code true} si la commande a été reconnue.
     */
    public static boolean handleCommand(Player master, String rawMessage) {
        if (master == null || rawMessage == null) {
            return false;
        }
        String sanitized = sanitize(rawMessage);
        String[] tokens = sanitized.split("\\s+");
        if (tokens.length < 2) {
            sendUsage(master);
            return true;
        }
        String action = tokens[1].toLowerCase();
        HeroManager manager = HeroManager.getInstance();
        switch (action) {
            case "add":
            case "connect":
                if (tokens.length < 3) {
                    master.sendErrorMessage("Utilisation : .heros add [id].");
                    return true;
                }
                handleAdd(master, tokens[2], manager);
                return true;
            case "disconnect":
                if (tokens.length < 3) {
                    master.sendErrorMessage("Utilisation : .heros disconnect [id].");
                    return true;
                }
                handleRemove(master, tokens[2], manager);
                return true;
            case "list":
                handleList(master, manager);
                return true;
            default:
                sendUsage(master);
                return true;
        }
    }

    private static void handleAdd(Player master, String idToken, HeroManager manager) {
        Integer heroId = parseId(idToken);
        if (heroId == null) {
            master.sendErrorMessage("Identifiant invalide.");
            return;
        }
        if (master.getAccount() == null) {
            master.sendErrorMessage("Compte non chargé.");
            return;
        }
        Player original = master.getAccount().getPlayers().get(heroId);
        HeroManager.HeroOperationResult result = manager.addHero(master, original);
        sendResult(master, result);
    }

    private static void handleRemove(Player master, String idToken, HeroManager manager) {
        Integer heroId = parseId(idToken);
        if (heroId == null) {
            master.sendErrorMessage("Identifiant invalide.");
            return;
        }
        HeroManager.HeroOperationResult result = manager.removeHero(master, heroId);
        sendResult(master, result);
    }

    private static void handleList(Player master, HeroManager manager) {
        if (master.getAccount() == null) {
            master.sendErrorMessage("Compte non chargé.");
            return;
        }
        List<HeroManager.HeroInstance> heroes = manager.getHeroes(master);
        String activeDisplay = heroes.isEmpty() ? "Aucun" : heroes.stream()
                .map(instance -> formatCharacter(instance.getOriginal()))
                .collect(Collectors.joining(", "));
        Map<Integer, Player> accountCharacters = master.getAccount().getPlayers();
        List<Player> remaining = accountCharacters.values().stream()
                .filter(player -> player.getId() != master.getId())
                .filter(player -> !manager.isHeroActive(player.getId()))
                .sorted(Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER))
                .collect(Collectors.toList());
        String availableDisplay = remaining.isEmpty() ? "Aucun" : remaining.stream()
                .map(HeroCommandHandler::formatCharacter)
                .collect(Collectors.joining(", "));
        StringBuilder message = new StringBuilder();
        message.append("Maître : <b>")
                .append(master.getName()).append(" (").append(master.getId()).append(")</b><br>");
        message.append("Héros actifs : ").append(activeDisplay).append("<br>");
        message.append("Personnages disponibles : ").append(availableDisplay);
        master.sendInformationMessage(message.toString());
    }

    private static String sanitize(String rawMessage) {
        String trimmed = rawMessage;
        if (trimmed.endsWith("\u0000")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed.trim();
    }

    private static void sendUsage(Player master) {
        master.sendInformationMessage("Utilisation : .heros [add|connect|disconnect|list] [id].");
    }

    private static Integer parseId(String token) {
        try {
            return Integer.parseInt(token);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String formatCharacter(Player character) {
        return character.getName() + " (" + character.getId() + ")";
    }

    private static void sendResult(Player master, HeroManager.HeroOperationResult result) {
        if (result.isSuccess()) {
            master.sendInformationMessage(result.getMessage());
        } else {
            master.sendErrorMessage(result.getMessage());
        }
    }
}
