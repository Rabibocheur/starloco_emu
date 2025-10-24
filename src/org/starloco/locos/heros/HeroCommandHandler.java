package org.starloco.locos.heros;

import org.starloco.locos.client.Player;

/**
 * Analyse et exécute les commandes liées au mode héros.
 */
public final class HeroCommandHandler {

    private HeroCommandHandler() {
    }

    /**
     * Traite la commande .heros.
     *
     * @param master joueur maître.
     * @param rawMessage message complet saisi par le joueur.
     * @return {@code true} si la commande est gérée.
     */
    public static boolean handleCommand(Player master, String rawMessage) {
        if (master == null) {
            return true;
        }
        String sanitized = sanitize(rawMessage);
        String[] parts = sanitized.split("\\s+");
        if (parts.length < 2) {
            sendUsage(master);
            return true;
        }
        String action = parts[1].toLowerCase();
        HeroManager manager = HeroManager.getInstance();
        switch (action) {
            case "add":
                // Alias historique : "connect" déclenche exactement la même logique que "add".
            case "connect":
                if (parts.length < 3) {
                    master.sendErrorMessage("Format attendu : .heros " + action + " [idPerso]");
                    return true;
                }
                Integer heroId = parseId(parts[2]);
                if (heroId == null) {
                    master.sendErrorMessage("L'identifiant doit être numérique.");
                    return true;
                }
                HeroManager.HeroOperationResult addResult = manager.addHero(master, heroId);
                if (addResult.isSuccess()) {
                    master.sendInformationMessage(addResult.getMessage());
                } else {
                    master.sendErrorMessage(addResult.getMessage());
                }
                return true;
            case "disconnect":
                if (parts.length < 3) {
                    master.sendErrorMessage("Format attendu : .heros disconnect [idPerso]");
                    return true;
                }
                Integer toRemove = parseId(parts[2]);
                if (toRemove == null) {
                    master.sendErrorMessage("L'identifiant doit être numérique.");
                    return true;
                }
                HeroManager.HeroOperationResult removeResult = manager.removeHero(master, toRemove);
                if (removeResult.isSuccess()) {
                    master.sendInformationMessage(removeResult.getMessage());
                } else {
                    master.sendErrorMessage(removeResult.getMessage());
                }
                return true;
            case "list":
                master.sendMessage(manager.describe(master));
                return true;
            default:
                sendUsage(master);
                return true;
        }
    }

    private static void sendUsage(Player master) {
        master.sendInformationMessage("Utilisation : .heros [add|connect|disconnect|list] [idPerso]");
    }

    private static String sanitize(String rawMessage) {
        if (rawMessage == null || rawMessage.isEmpty()) {
            return "";
        }
        String trimmed = rawMessage;
        if (trimmed.charAt(trimmed.length() - 1) == '\u0000') {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed.trim();
    }

    private static Integer parseId(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
