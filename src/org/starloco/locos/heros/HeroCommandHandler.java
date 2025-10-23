package org.starloco.locos.heros;

import org.starloco.locos.client.Player;

public class HeroCommandHandler {

    public static boolean handleCommand(Player player, String msg) {
        String[] args = msg.split(" ");

        if (args.length < 2) {
            player.sendMessage("Utilisation: .heros [create|switch|list]");
            return true;
        }

        String sub = args[1].toLowerCase();

        switch (sub) {
            case "create":
                player.sendMessage("✅ Groupe de héros créé !");
                break;

            case "switch":
                if (args.length < 3) {
                    player.sendMessage("Utilisation: .heros switch [nomHero]");
                    break;
                }
                player.sendMessage("🔁 Changement de héros vers : " + args[2]);
                break;

            case "list":
                player.sendMessage("📜 Liste des héros disponibles : [en développement]");
                break;

            default:
                player.sendMessage("Commande inconnue : " + sub);
                break;
        }

        return true;
    }
}