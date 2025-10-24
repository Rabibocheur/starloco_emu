package org.starloco.locos.heros;

import org.starloco.locos.client.Player;
import org.starloco.locos.common.SocketManager;
import org.starloco.locos.fight.Fight;
import org.starloco.locos.fight.Fighter;
import org.starloco.locos.game.GameClient;
import org.starloco.locos.kernel.Logging;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Coordonne les interactions liées aux combats pour les groupes de héros.
 * <p>
 * Exemple : {@code new HeroFightController(manager)} permet ensuite d'appeler
 * {@link #prepareTurnControl(Fight, Fighter)} juste avant le tour d'un héros.<br>
 * Invariant : chaque maître possède au plus un héros contrôlé manuellement via {@link #activeHeroControl}.<br>
 * Effets de bord : les méthodes déclenchent régulièrement des envois de paquets réseau vers le client du maître.
 * </p>
 */
final class HeroFightController {

    private static final String HERO_LOG_CONTEXT = "HeroControl"; // Contexte de logs dédié pour filtrer rapidement les traces liées aux incarnations.
    private final HeroManager heroManager; // Référence principale pour accéder aux métadonnées des héros.
    private final Map<Integer, Player> activeHeroControl = new ConcurrentHashMap<>(); // Mémorise quel héros est piloté par chaque maître.
    private final Map<Integer, Player> sessionIncarnationSnapshots = new ConcurrentHashMap<>(); // Capture le personnage incarné avant de basculer temporairement sur un héros.

    /**
     * Initialise un contrôleur de combat adossé au gestionnaire fourni.
     *
     * @param heroManager gestionnaire racine, ne doit pas être {@code null}.
     */
    HeroFightController(HeroManager heroManager) {
        this.heroManager = heroManager; // Bloc logique : stocke la référence pour les requêtes ultérieures (findMaster, etc.).
    }

    /**
     * Supprime toute association de contrôle pour le maître donné.
     * <p>
     * Exemple : {@code clearManualControl(master)} juste après {@link HeroManager#addHero(Player, int)}<br>
     * permet d'éviter qu'un ancien héros reste sélectionné côté client.<br>
     * Invariant : si le maître est {@code null}, la méthode n'a aucun effet.
     * </p>
     *
     * @param master maître dont on souhaite purger le contrôle manuel.
     */
    void clearManualControl(Player master) {
        if (master == null) { // Bloc logique : rien à effacer sans maître explicite.
            return;
        }
        activeHeroControl.remove(master.getId()); // Effet de bord : le client repassera sur le personnage par défaut au prochain tick.
        restorePrimaryIncarnation(master); // Bloc logique : replace aussitôt l'incarnation réseau sur le maître historique.
    }

    /**
     * Variante dédiée aux scénarios hors-ligne où seul l'identifiant est connu.
     *
     * @param masterId identifiant du maître dont le contrôle doit être purgé.
     */
    void clearManualControl(int masterId) {
        activeHeroControl.remove(masterId); // Bloc logique : supprime le lien résiduel sans nécessité d'instancier le joueur.
        sessionIncarnationSnapshots.remove(masterId); // Bloc logique : oublie toute incarnation temporaire précédemment mémorisée.
    }

    /**
     * Transfère le contrôle manuel d'un maître vers un autre.
     * <p>
     * Exemple : appelée depuis {@link HeroManager#switchMaster(Player, int)} pour que le nouveau maître récupère
     * immédiatement le héros précédemment piloté.<br>
     * Erreur fréquente : oublier de vérifier que les deux joueurs sont non {@code null}, ce qui conduirait à une {@link NullPointerException}.
     * </p>
     *
     * @param previousMaster maître quittant son rôle actif.
     * @param newMaster      maître prenant le relais.
     */
    void transferManualControl(Player previousMaster, Player newMaster) {
        if (previousMaster == null || newMaster == null) { // Bloc logique : transfert impossible sans binôme valide.
            return;
        }
        Player transferred = activeHeroControl.remove(previousMaster.getId()); // Capture l'éventuel héros encore contrôlé.
        if (transferred == null) { // Bloc logique : aucun héros n'était piloté, rien à recopier.
            return;
        }
        activeHeroControl.put(newMaster.getId(), transferred); // Effet de bord : le nouveau maître retrouve le même combattant actif.
        Player snapshot = sessionIncarnationSnapshots.remove(previousMaster.getId()); // Bloc logique : récupère l'incarnation initiale stockée.
        if (snapshot != null) { // Bloc logique : propage la référence de maître original uniquement si elle existait.
            sessionIncarnationSnapshots.put(newMaster.getId(), snapshot); // Effet de bord : garantit que la restauration utilisera la même ancre.
        }
    }

    /**
     * Prépare la prise de contrôle d'un combattant juste avant son tour.
     * <p>
     * Exemple : {@code prepareTurnControl(fight, fighter)} en début de tour d'un héros pour orienter l'UI sur lui.<br>
     * Cas d'erreur fréquent : invoquer la méthode avec un combattant invoqué, ce qui renverra immédiatement {@code true} sans envoyer de paquet.<br>
     * Effet de bord : peut retourner {@code false} pour inviter le moteur de combat à passer le tour.
     * </p>
     *
     * @param fight   combat en cours d'exécution.
     * @param fighter combattant qui s'apprête à jouer.
     * @return {@code true} si le tour peut continuer normalement, {@code false} si le tour doit être passé côté serveur.
     */
    boolean prepareTurnControl(Fight fight, Fighter fighter) {
        if (fighter == null) { // Bloc logique : aucune synchronisation requise sans combattant.
            return true;
        }
        Player actor = fighter.getPersonnage();
        if (actor == null) { // Bloc logique : les invocations et entités neutres sont ignorées.
            return true;
        }
        if (!heroManager.isHero(actor)) { // Bloc logique : un joueur standard conserve la main sans traitement héros.
            releaseControlFor(actor, null); // Effet de bord : s'assure que le maître voit de nouveau son personnage.
            return true;
        }
        Player master = heroManager.findMaster(actor); // Bloc logique : récupère le propriétaire du héros pour orienter la session.
        if (master == null) { // Bloc logique : en l'absence de maître connecté, on demande un passage de tour.
            if (Logging.USE_LOG) { // Bloc logique : journalise la situation pour identifier les combats orphelins.
                Logging.getInstance().write(HERO_LOG_CONTEXT, "[TURN] Refus car maître introuvable pour héros=" + actor.getId());
            }
            return false;
        }
        if (fighter.isDead()) { // Bloc logique : évite de basculer sur un héros déjà éliminé.
            if (Logging.USE_LOG) { // Bloc logique : trace l'état afin de vérifier l'ordre d'appel côté combat.
                Logging.getInstance().write(HERO_LOG_CONTEXT, "[TURN] Ignoré car héros mort fight=" + fight.getId()
                        + " hero=" + actor.getId());
            }
            releaseControlFor(master, actor); // Effet de bord : assure un retour immédiat sur le maître.
            return true;
        }
        GameClient client = master.getGameClient();
        if (client == null || master.getFight() != fight) { // Bloc logique : abandonne si le client est déconnecté ou sur un autre combat.
            if (Logging.USE_LOG) { // Bloc logique : détaille la raison (déconnexion ou combat divergent).
                Logging.getInstance().write(HERO_LOG_CONTEXT, "[TURN] Refus car client hors-ligne ou combat différent master="
                        + master.getId() + " fight=" + fight.getId());
            }
            if (client != null) { // Bloc logique : réinitialise la session si le réseau est toujours accessible.
                client.resetControlledFighter(); // Effet de bord : évite de conserver un identifiant obsolète côté client.
            }
            clearManualControl(master); // Bloc logique : purge la carte de contrôle pour éviter les incohérences.
            return false;
        }
        if (master.isEsclave()) { // Bloc logique : protège les cas où le maître est lui-même incarné comme esclave.
            if (Logging.USE_LOG) { // Bloc logique : note l'état pour repérer les verrous côté gameplay.
                Logging.getInstance().write(HERO_LOG_CONTEXT, "[TURN] Refus car maître esclave master=" + master.getId());
            }
            return false;
        }
        if (actor.getAccount() != null && master.getAccount() != null
                && actor.getAccount().getId() != master.getAccount().getId()) { // Bloc logique : empêche les croisements de comptes.
            if (Logging.USE_LOG) { // Bloc logique : log l'anomalie pour l'équipe QA.
                Logging.getInstance().write(HERO_LOG_CONTEXT, "[TURN] Refus car comptes divergents master=" + master.getId()
                        + " hero=" + actor.getId());
            }
            return false;
        }
        Fighter resolved = fight.getFighterById(fighter.getId()); // Bloc logique : valide que le combattant ciblé est bien enregistré dans ce combat.
        if (resolved == null) { // Bloc logique : évite de cibler un combattant qui aurait disparu (ex : retrait de timeline).
            if (Logging.USE_LOG) { // Bloc logique : trace l'incohérence pour examen ultérieur.
                Logging.getInstance().write(HERO_LOG_CONTEXT, "[TURN] Refus car fighter introuvable fight=" + fight.getId()
                        + " hero=" + actor.getId() + " fighterId=" + fighter.getId());
            }
            return false;
        }
        if (Logging.USE_LOG) { // Bloc logique : log de contexte avant bascule pour vérifier les IDs échangés.
            Logging.getInstance().write(HERO_LOG_CONTEXT, "[TURN] Préparation fight=" + fight.getId() + " master=" + master.getId()
                    + " hero=" + actor.getId() + " fighter=" + fighter.getId());
        }
        if (!engageTemporaryIncarnation(master, actor)) { // Bloc logique : abandonne si la session refuse de changer d'incarnation.
            client.resetControlledFighter(); // Effet de bord : nettoie toute tentative partielle.
            clearManualControl(master); // Bloc logique : purge les caches de contrôle pour éviter les suivis fantômes.
            return false;
        }
        Player incarnated = client.getPlayer(); // Bloc logique : contrôle immédiat que la session pointe bien sur le héros.
        if (incarnated == null || incarnated.getId() != actor.getId()) { // Bloc logique : abandonne si le switch n'est pas visible côté session.
            if (Logging.USE_LOG) { // Bloc logique : détaille la divergence pour l'équipe de test.
                Logging.getInstance().write(HERO_LOG_CONTEXT, "[TURN] Refus car incarnation reste sur="
                        + (incarnated != null ? incarnated.getId() : "aucun") + " attendu=" + actor.getId());
            }
            client.resetControlledFighter(); // Effet de bord : remet la session dans un état cohérent.
            clearManualControl(master); // Bloc logique : supprime le suivi de contrôle erroné.
            return false;
        }
        activeHeroControl.put(master.getId(), actor); // Bloc logique : mémorise quel héros est désormais piloté.
        client.setControlledFighterId(fighter.getId()); // Effet de bord : informe le client du combattant actif.
        SocketManager.GAME_SEND_GIC_PACKET(master, fighter); // Bloc logique : centre l'interface combat sur le héros ciblé.
        SocketManager.GAME_SEND_GAS_PACKET(master, fighter.getId()); // Bloc logique : actualise l'ordre de jeu côté client.
        SocketManager.GAME_SEND_GTM_PACKET(master, fight); // Bloc logique : synchronise PV et positions du combattant.
        SocketManager.GAME_SEND_GTL_PACKET(master, fight); // Bloc logique : rafraîchit la timeline pour refléter PA/PM à jour.
        SocketManager.GAME_SEND_STATS_PACKET(client, actor); // Effet de bord : met à jour la fiche de caractéristiques.
        SocketManager.GAME_SEND_SPELL_LIST(client, actor); // Bloc logique : aligne la barre de sorts du héros actif.
        SocketManager.GAME_SEND_Ow_PACKET(client, actor); // Bloc logique : synchronise l'inventaire (pods).
        if (Logging.USE_LOG) { // Bloc logique : confirme l'ordre d'envoi des paquets pour relecture rapide.
            Logging.getInstance().write(HERO_LOG_CONTEXT, "[TURN] Switch confirmé hero=" + actor.getId() + " fighter="
                    + fighter.getId());
        }
        return true;
    }

    /**
     * Termine la session de contrôle d'un héros à la fin de son tour.
     * <p>
     * Exemple : invoqué depuis {@code Fight#endTurn} pour rendre la main au maître.<br>
     * Invariant : si le combattant n'est pas un héros, la méthode ne déclenche aucune action réseau.<br>
     * Effet de bord : remet en avant les statistiques du maître et vide la carte de contrôle.
     * </p>
     *
     * @param fighter combattant dont le tour vient de s'achever.
     */
    void finalizeTurnControl(Fighter fighter) {
        if (fighter == null) { // Bloc logique : rien à restaurer sans combattant défini.
            return;
        }
        Player actor = fighter.getPersonnage();
        if (actor == null || !heroManager.isHero(actor)) { // Bloc logique : uniquement pour les héros virtuels.
            return;
        }
        Player master = heroManager.findMaster(actor);
        if (master == null) { // Bloc logique : sans maître, aucune restauration d'interface n'est possible.
            return;
        }
        releaseControlFor(master, actor); // Effet de bord : libère la redirection si elle ciblait bien ce héros.
    }

    /**
     * Résout l'acteur effectivement contrôlé par la session réseau d'un maître.
     * <p>
     * Exemple : {@code resolveControlledActor(master)} redirige un déplacement combat vers le héros actif.<br>
     * Invariant : si la session incarne déjà un héros, c'est cette référence qui est retournée.<br>
     * Effet de bord : aucun, la méthode n'altère jamais la carte de contrôle.
     * </p>
     *
     * @param sessionPlayer joueur relié à la session réseau.
     * @return héros actuellement piloté ou le joueur original si aucun héros n'est ciblé.
     */
    Player resolveControlledActor(Player sessionPlayer) {
        if (sessionPlayer == null) { // Bloc logique : aucune résolution possible sans session.
            return null;
        }
        if (heroManager.isHero(sessionPlayer)) { // Bloc logique : si la session incarne déjà un héros, on le renvoie directement.
            return sessionPlayer;
        }
        Player hero = activeHeroControl.get(sessionPlayer.getId()); // Bloc logique : consulte la carte pour identifier le héros piloté.
        return hero != null ? hero : sessionPlayer; // Effet de bord : aucun, simple valeur de retour.
    }

    /**
     * Libère le contrôle manuel d'un maître et restaure son interface d'origine.
     * <p>
     * Exemple : {@code releaseControlFor(master, hero)} après {@link #finalizeTurnControl(Fighter)} pour revenir au maître.<br>
     * Cas d'erreur fréquent : appeler la méthode avec un héros inattendu, le test {@code expectedHero} protège ce scénario.<br>
     * Effets de bord : envoie les paquets GIC/GAS/GTL/GTM ainsi que les caractéristiques du maître.
     * </p>
     *
     * @param master       maître dont on veut restaurer l'affichage.
     * @param expectedHero héros attendu ; si non {@code null}, la libération n'a lieu que si ce héros était bien contrôlé.
     */
    void releaseControlFor(Player master, Player expectedHero) {
        if (master == null) { // Bloc logique : sans maître, aucun contrôle n'est à libérer.
            return;
        }
        Player active = activeHeroControl.get(master.getId());
        if (active == null) { // Bloc logique : rien à faire si aucun héros n'est enregistré.
            return;
        }
        if (expectedHero != null && active.getId() != expectedHero.getId()) { // Bloc logique : ignore si ce n'est pas le héros ciblé.
            return;
        }
        activeHeroControl.remove(master.getId()); // Effet de bord : coupe la redirection des commandes côté serveur.
        restorePrimaryIncarnation(master); // Effet de bord : replace l'incarnation réseau sur le maître (ou le joueur initial mémorisé).
        GameClient client = master.getGameClient();
        if (client == null) { // Bloc logique : aucun envoi si le maître est déjà déconnecté.
            return;
        }
        client.resetControlledFighter(); // Effet de bord : restaure l'identifiant de contrôle par défaut côté session.
        Fight fight = master.getFight();
        if (fight != null) { // Bloc logique : renvoie les paquets combat uniquement si le maître est toujours engagé.
            Fighter masterFighter = fight.getFighterByPerso(master);
            if (masterFighter != null) { // Bloc logique : évite les paquets incohérents si le maître a quitté le combat.
                SocketManager.GAME_SEND_GIC_PACKET(master, masterFighter); // Bloc logique : remet le focus combat sur le maître.
                SocketManager.GAME_SEND_GAS_PACKET(master, masterFighter.getId()); // Bloc logique : confirme l'identifiant actif.
                SocketManager.GAME_SEND_GTL_PACKET(master, fight); // Bloc logique : rafraîchit la timeline PA/PM.
                SocketManager.GAME_SEND_GTM_PACKET(master, fight); // Bloc logique : synchronise PV et positions.
            }
        }
        SocketManager.GAME_SEND_STATS_PACKET(client, master); // Effet de bord : réaffiche les caractéristiques du maître.
        SocketManager.GAME_SEND_SPELL_LIST(client, master); // Bloc logique : restaure la barre de sorts d'origine.
        SocketManager.GAME_SEND_Ow_PACKET(client, master); // Bloc logique : recalcul des pods visibles pour le maître.
        if (Logging.USE_LOG) { // Bloc logique : confirme le retour d'incarnation avec l'identifiant actif.
            Player incarnated = client.getPlayer();
            Logging.getInstance().write(HERO_LOG_CONTEXT, "[RESTORE] Master=" + master.getId() + " incarné="
                    + (incarnated != null ? incarnated.getId() : "aucun"));
        }
    }

    /**
     * Engage une incarnation temporaire du maître sur le héros actif.
     * <p>
     * Exemple : lors du début d'un tour, {@code engageTemporaryIncarnation(master, hero)} force le client à incarner le héros.<br>
     * Erreur fréquente : rappeler cette méthode alors que le héros est déjà incarné, ce qui écraserait la sauvegarde du maître d'origine.<br>
     * Effet de bord : met à jour {@link GameClient#switchActivePlayer(Player)} et stocke l'ancre de restauration dans {@link #sessionIncarnationSnapshots}.
     * </p>
     *
     * @param master maître dont la session réseau doit être déplacée.
     * @param hero   héros qui devient temporairement incarné.
     * @return {@code true} si la session incarne désormais le héros, {@code false} sinon.
     */
    private boolean engageTemporaryIncarnation(Player master, Player hero) {
        if (master == null || hero == null) { // Bloc logique : aucune incarnation possible sans paire valide.
            if (Logging.USE_LOG) { // Bloc logique : trace l'erreur pour identifier l'appel fautif.
                Logging.getInstance().write(HERO_LOG_CONTEXT, "[SWITCH] Annulé car pair invalide master="
                        + (master != null ? master.getId() : "aucun") + " hero="
                        + (hero != null ? hero.getId() : "aucun"));
            }
            return false;
        }
        GameClient client = master.getGameClient();
        if (client == null) { // Bloc logique : abandonne si la session réseau n'est plus active.
            if (Logging.USE_LOG) { // Bloc logique : note la déconnexion pour corréler avec les logs réseau.
                Logging.getInstance().write(HERO_LOG_CONTEXT, "[SWITCH] Annulé car client déconnecté master=" + master.getId());
            }
            return false;
        }
        Player currentlyIncarnated = client.getPlayer();
        if (currentlyIncarnated != null && currentlyIncarnated.getId() == hero.getId()) { // Bloc logique : évite de dupliquer l'incarnation si le héros est déjà actif.
            if (Logging.USE_LOG) { // Bloc logique : consigne que l'incarnation était déjà conforme.
                Logging.getInstance().write(HERO_LOG_CONTEXT, "[SWITCH] Déjà incarné hero=" + hero.getId());
            }
            return true;
        }
        if (!sessionIncarnationSnapshots.containsKey(master.getId())) { // Bloc logique : mémorise le maître initial seulement une fois par tour.
            sessionIncarnationSnapshots.put(master.getId(), currentlyIncarnated != null ? currentlyIncarnated : master); // Effet de bord : stocke le personnage de référence pour la restauration.
        }
        boolean switched = client.switchActivePlayer(hero); // Effet de bord : bascule l'incarnation côté client sans rompre la session réseau.
        if (!switched && Logging.USE_LOG) { // Bloc logique : journalise tout refus de bascule pour investigation.
            Logging.getInstance().write(HERO_LOG_CONTEXT, "[SWITCH] Echec incarnation hero=" + hero.getId() + " master="
                    + master.getId());
        }
        return switched; // Bloc logique : relaie l'état réel du switch aux appelants.
    }

    /**
     * Restaure l'incarnation principale d'un maître après un tour de héros.
     * <p>
     * Exemple : à la fin du tour, {@code restorePrimaryIncarnation(master)} redonne le contrôle au personnage original.<br>
     * Cas limite : si aucune incarnation n'est mémorisée (déconnexion par exemple), la méthode retombe sur {@code master}.<br>
     * Effet de bord : appelle {@link GameClient#switchActivePlayer(Player)} pour réaligner la session et nettoie la sauvegarde.
     * </p>
     *
     * @param master maître à restaurer.
     */
    private void restorePrimaryIncarnation(Player master) {
        if (master == null) { // Bloc logique : rien à restaurer sans maître explicite.
            return;
        }
        GameClient client = master.getGameClient();
        if (client == null) { // Bloc logique : purge simplement la sauvegarde si le réseau est coupé.
            sessionIncarnationSnapshots.remove(master.getId());
            return;
        }
        Player snapshot = sessionIncarnationSnapshots.remove(master.getId()); // Bloc logique : récupère le personnage incarné avant le tour du héros.
        Player target = snapshot != null ? snapshot : master; // Bloc logique : retombe sur le maître si aucune sauvegarde n'est disponible.
        Player currentlyIncarnated = client.getPlayer();
        if (currentlyIncarnated != null && currentlyIncarnated.getId() == target.getId()) { // Bloc logique : évite un switch inutile lorsque l'incarnation est déjà correcte.
            if (Logging.USE_LOG) { // Bloc logique : signale que la restauration était déjà effective.
                Logging.getInstance().write(HERO_LOG_CONTEXT, "[RESTORE] Déjà sur=" + target.getId());
            }
            return;
        }
        boolean switched = client.switchActivePlayer(target); // Effet de bord : réaligne immédiatement la session sur le personnage maître ou sauvegardé.
        if (!switched && Logging.USE_LOG) { // Bloc logique : avertit si la restauration n'a pas pu aboutir.
            Logging.getInstance().write(HERO_LOG_CONTEXT, "[RESTORE] Echec pour cible=" + target.getId());
        }
    }

    /** Notes pédagogiques
     * - {@link #prepareTurnControl(Fight, Fighter)} renvoie {@code false} pour inviter le moteur à passer le tour si le maître est indisponible.
     * - {@link #finalizeTurnControl(Fighter)} délègue à {@link #releaseControlFor(Player, Player)} la restauration complète de l'UI.
     * - {@link #resolveControlledActor(Player)} permet aux couches réseau de router les commandes sur le bon combattant.
     * - {@link #transferManualControl(Player, Player)} maintient le même héros actif après un {@code switchMaster}.
     * - {@link #engageTemporaryIncarnation(Player, Player)} force le client à incarner un héros pendant son tour.
     * - {@link #restorePrimaryIncarnation(Player)} garantit le retour automatique sur le maître en fin de tour ou lors d'un nettoyage.
     * - {@link #clearManualControl(Player)} reste utile pour purger tout résidu lors de l'ajout ou du retrait d'un héros.
     */
}
