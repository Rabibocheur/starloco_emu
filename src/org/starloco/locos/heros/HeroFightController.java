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
        GameClient client = master.getGameClient(); // Bloc logique : récupère la session pour un éventuel handshake.
        if (client != null) { // Bloc logique : uniquement si le joueur est toujours connecté.
            sendIncarnationHandshake(client, master, null); // Effet de bord : force le client à réafficher le maître après purge.
        }
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
     * Effet de bord : peut retourner {@code false} pour inviter le moteur de combat à passer le tour et déclenche un handshake complet côté client pour les héros.
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
        client.setControlledFighterId(fighter.getId()); // Effet de bord : informe immédiatement la session du combattant actif.
        sendIncarnationHandshake(client, actor, fighter); // Effet de bord : notifie le client de l'incarnation et renvoie les stats nécessaires.
        activeHeroControl.put(master.getId(), actor); // Bloc logique : mémorise quel héros est désormais piloté.
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
     * Effets de bord : envoie les paquets GIC/GAS/GTL/GTM et déclenche un handshake ASK/Stats/Spells/Pods pour replacer le maître.
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
        sendIncarnationHandshake(client, master, null); // Effet de bord : rafraîchit immédiatement l'incarnation côté client.
        client.resetControlledFighter(); // Effet de bord : restaure l'identifiant de contrôle par défaut côté session.
        Fight fight = master.getFight();
        if (fight != null) { // Bloc logique : renvoie les paquets combat uniquement si le maître est toujours engagé.
            Fighter masterFighter = fight.getFighterByPerso(master);
            if (masterFighter != null) { // Bloc logique : évite les paquets incohérents si le maître a quitté le combat.
                SocketManager.GAME_SEND_GIC_PACKET(master, masterFighter, true); // Bloc logique : remet explicitement le focus combat sur le maître.
                SocketManager.GAME_SEND_GAS_PACKET(master, masterFighter.getId()); // Bloc logique : confirme l'identifiant actif.
                SocketManager.GAME_SEND_GTL_PACKET(master, fight); // Bloc logique : rafraîchit la timeline PA/PM.
                GameClient masterClient = master.getGameClient(); // Bloc logique : sécurise l'accès à la session pour le GTM ciblé.
                if (masterClient != null) { // Bloc logique : envoie le GTM uniquement si le socket maître est disponible.
                    notifyLocalControlSwitch(masterClient, masterFighter); // Effet de bord : replace idPersoActif sur le maître restauré.
                    SocketManager.GAME_SEND_GTM_PACKET(masterClient, fight, masterFighter); // Bloc logique : synchronise PV et positions en marquant le maître local.
                    SocketManager.GAME_SEND_GTR_PACKET(masterClient, masterFighter.getId()); // Bloc logique : assure le recentrage caméra sur le maître.
                    SocketManager.GAME_SEND_Im_PACKET(masterClient, "1201"); // Bloc logique : répète le feedback « C'est votre tour » lors du retour sur le maître.
                }
            }
        }
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

    /**
     * Expédie les paquets d'incarnation nécessaires pour aligner le client sur un nouveau personnage contrôlé.
     * <p>
     * Exemple : {@code sendIncarnationHandshake(client, hero, fighter)} juste après {@link #engageTemporaryIncarnation(Player, Player)}
     * garantit que l'UI combat affiche les PA/PM du héros actif sans recharger la map.<br>
     * Erreur fréquente : appeler cette méthode avec un client déconnecté, rien n'est envoyé et le switch paraît figé côté joueur.<br>
     * Effets de bord : en combat, un {@code ASK} officiel minimal suivi de Stats, Pods, Spells puis GTS est envoyé ; hors combat, le paquet ASK complet est conservé.<br>
     * Invariant : ne fait rien si la session ou la cible est absente, ce qui protège des NullPointerException pour les débutants.<br>
     * </p>
     *
     * @param client         session réseau toujours connectée.
     * @param target         personnage qui devient ou redevient l'incarnation active.
     * @param combatFighter  combattant lié au personnage pour cadencer GTS ; peut être {@code null} si inconnu.
     */
    private void sendIncarnationHandshake(GameClient client, Player target, Fighter combatFighter) {
        if (client == null || target == null) { // Bloc logique : refuse un envoi si la session ou la cible manque.
            return;
        }
        Fight targetFight = target.getFight(); // Bloc logique : identifie un éventuel combat actif pour adapter les paquets.
        boolean combatContext = targetFight != null && targetFight.isBegin(); // Bloc logique : ne déclenche le mode combat que si la timeline est réellement engagée.
        Fighter resolvedFighter = combatFighter; // Bloc logique : réutilise l'identité reçue pour éviter une recherche supplémentaire.
        if (combatContext) { // Bloc logique : contourne le rechargement de carte en simulant une incarnation locale.
            if (resolvedFighter == null && targetFight != null) { // Bloc logique : retombe sur le combattant lié au personnage si absent.
                resolvedFighter = targetFight.getFighterByPerso(target); // Effet de bord : récupère l'identifiant pour préparer GTS.
            }
            sendCombatPackets(client, target, targetFight, resolvedFighter); // Effet de bord : diffuse la séquence combat en marquant le focus local si présent.
            return; // Bloc logique : interrompt la méthode après les paquets combat dédiés.
        }
        sendMapPackets(client, target); // Bloc logique : applique la séquence standard hors combat.
        if (Logging.USE_LOG) { // Bloc logique : trace l'envoi pour faciliter les diagnostics QA.
            int accountId = client.getAccount() != null ? client.getAccount().getId() : -1; // Bloc logique : protège l'accès si l'association compte n'est plus définie.
            Logging.getInstance().write(HERO_LOG_CONTEXT, "[HANDSHAKE] Session=" + accountId + " cible=" + target.getId());
        }
    }

    /**
     * Diffuse la séquence complète de paquets combat pour aligner l'interface sur un combattant donné.
     * <p>
     * Exemple : {@code sendCombatPackets(client, hero, fight, fighter)} lors du début de tour d'un héros.<br>
     * Cas d'erreur : passer un {@code fight} nul coupe immédiatement la séquence pour éviter un envoi incohérent.<br>
     * Effets de bord : envoie GIC/GTL/GTM/GAS/GTS, GTR et Im1201 lorsque le combattant est défini.
     * </p>
     *
     * @param client          session réseau connectée au maître.
     * @param target          personnage incarné par le client.
     * @param fight           combat en cours.
     * @param resolvedFighter combattant correspondant à l'incarnation ; peut être {@code null} si le focus est inconnu.
     */
    private void sendCombatPackets(GameClient client, Player target, Fight fight, Fighter resolvedFighter) {
        if (client == null || fight == null) { // Bloc logique : refuse l'envoi sans session ni combat.
            return;
        }
        boolean hasFocus = resolvedFighter != null; // Bloc logique : mémorise la présence d'un combattant à marquer localement.
        if (hasFocus) { // Bloc logique : garantit que la session connaît l'identifiant du combattant contrôlé.
            synchronizeLocalControl(client, resolvedFighter); // Effet de bord : force le GameClient à pointer sur le bon fighter pour router GA/GAS.
            notifyLocalControlSwitch(client, resolvedFighter); // Effet de bord : avertit le client que les entrées doivent cibler ce combattant.
            SocketManager.GAME_SEND_GIC_PACKET(client, resolvedFighter, true); // Effet de bord : marque explicitement le combattant comme local.
            SocketManager.GAME_SEND_GTM_PACKET(client, fight, resolvedFighter); // Bloc logique : synchronise immédiatement les jauges PA/PM sur le héros actif.
        } else { // Bloc logique : conserve la compatibilité même sans focus explicite (spectateur, désynchronisation).
            SocketManager.GAME_SEND_GTM_PACKET(client, fight); // Effet de bord : diffuse les jauges sans drapeau local.
        }
        SocketManager.GAME_SEND_GTL_PACKET(client, fight); // Bloc logique : rafraîchit la timeline complète.
        SocketManager.GAME_SEND_MINI_ASK_PACKET(client, target, resolvedFighter); // Bloc logique : valide l'incarnation côté client sans recharger la carte.
        SocketManager.GAME_SEND_STATS_PACKET(client, target); // Bloc logique : actualise PV/PA/PM visibles.
        SocketManager.GAME_SEND_SPELL_LIST(client, target); // Bloc logique : met à jour la barre de sorts.
        if (hasFocus) { // Bloc logique : n'envoie la suite que si un combattant précis est identifié.
            SocketManager.GAME_SEND_GAS_PACKET(client, resolvedFighter.getId()); // Effet de bord : active les actions pour le bon combattant.
            int elapsed = fight.getElapsedFightTime(); // Bloc logique : calcule le temps écoulé pour synchroniser le timer.
            int turnCount = fight.getTurnsCount(); // Bloc logique : transmet le numéro de tour courant.
            SocketManager.GAME_SEND_GAMETURNSTART_PACKET(client, resolvedFighter.getId(), elapsed, turnCount); // Effet de bord : déclenche le chronomètre côté client.
            SocketManager.GAME_SEND_GTR_PACKET(client, resolvedFighter.getId()); // Effet de bord : recadre la caméra sur la bonne entité juste après la synchronisation des PA/PM.
            SocketManager.GAME_SEND_Im_PACKET(client, "1201"); // Effet de bord : affiche le message « C'est votre tour ».
        }
        if (Logging.USE_LOG) { // Bloc logique : consigne la séquence envoyée pour diagnostic.
            int accountId = client.getAccount() != null ? client.getAccount().getId() : -1; // Bloc logique : protège l'accès si l'association compte n'est plus définie.
            int fighterId = hasFocus ? resolvedFighter.getId() : -1; // Bloc logique : fournit un identifiant clair même en absence de combattant.
            Logging.getInstance().write(HERO_LOG_CONTEXT, "[HANDSHAKE] Combat session=" + accountId + " cible=" + target.getId() + " fighter=" + fighterId); // Effet de bord : laisse une trace complète pour les QA.
        }
    }

    /**
     * Harmonise l'identifiant du combattant contrôlé côté session avec le focus en cours.
     * <p>
     * Exemple : {@code synchronizeLocalControl(client, fighter)} juste avant l'envoi de GTM garantit que
     * {@link GameClient#getControlledFighterId()} renvoie le même identifiant que le combattant marqué localement.<br>
     * Cas d'erreur fréquent : déclencher la séquence de handshake après une reconnexion ; sans cet appel, le client conserverait l'identifiant précédent.<br>
     * Invariant : n'a aucun effet si la session référence déjà le bon combattant ou si l'un des paramètres est {@code null}.<br>
     * Effet de bord : met à jour {@link GameClient#setControlledFighterId(int)} pour que toutes les actions GA/GAS/GAF ciblent le héros actif.
     * </p>
     *
     * @param client         session réseau concernée par la bascule.
     * @param resolvedFighter combattant actuellement ciblé comme focus local.
     */
    private void synchronizeLocalControl(GameClient client, Fighter resolvedFighter) {
        if (client == null || resolvedFighter == null) { // Bloc logique : abandonne si la session ou le combattant manquent.
            return;
        }
        int currentFocus = client.getControlledFighterId(); // Bloc logique : récupère l'identifiant actuellement mémorisé.
        if (currentFocus == resolvedFighter.getId()) { // Bloc logique : évite une écriture inutile lorsque l'identifiant est déjà correct.
            return;
        }
        client.setControlledFighterId(resolvedFighter.getId()); // Effet de bord : aligne l'identifiant contrôlé sur le combattant héros.
    }

    /**
     * Prévient explicitement le client que l'acteur contrôlé vient de changer afin de transférer le focus des entrées.
     * <p>
     * Exemple : {@code notifyLocalControlSwitch(client, fighter)} juste après {@link #synchronizeLocalControl(GameClient, Fighter)}.<br>
     * Cas d'erreur fréquent : négliger cet appel maintient le focus sur le maître, rendant le héros inerte côté joueur.<br>
     * Effet de bord : expédie la paire de paquets GS/BP attendue par le client pour mettre à jour « idPersoActif » et le curseur combat.<br>
     * Invariant : sans session ou combattant valide, aucun paquet n'est envoyé pour éviter une désynchronisation supplémentaire.
     * </p>
     *
     * @param client  session réseau qui doit rediriger ses entrées.
     * @param fighter combattant désormais contrôlé par le joueur.
     */
    private void notifyLocalControlSwitch(GameClient client, Fighter fighter) {
        if (client == null || fighter == null) { // Bloc logique : impossible de signaler un focus sans session ni combattant.
            return;
        }
        SocketManager.GAME_SEND_GS_PACKET(client); // Effet de bord : réactive le mécanisme interne qui relit l'incarnation locale.
        SocketManager.GAME_SEND_BP_PACKET(client, fighter.getId()); // Effet de bord : définit l'identifiant du combattant contrôlé côté client.
    }

    /**
     * Expédie la séquence d'incarnation standard utilisée hors combat.
     * <p>
     * Exemple : {@code sendMapPackets(client, master)} après la fin d'un combat pour restaurer l'affichage habituel.<br>
     * Cas d'erreur : fournir un {@code client} nul interrompra silencieusement l'envoi.<br>
     * Effets de bord : envoie ASK, Stats, Spells et Ow comme lors d'un changement de personnage classique.
     * </p>
     *
     * @param client session réseau ciblée.
     * @param target personnage actif dont il faut afficher les informations.
     */
    private void sendMapPackets(GameClient client, Player target) {
        if (client == null || target == null) { // Bloc logique : évite toute tentative d'envoi sans session ou cible valide.
            return;
        }
        SocketManager.GAME_SEND_ASK(client, target); // Effet de bord : annonce l'identité et l'équipement du personnage actif hors combat.
        SocketManager.GAME_SEND_STATS_PACKET(client, target); // Bloc logique : actualise la fiche de caractéristiques visible.
        SocketManager.GAME_SEND_SPELL_LIST(client, target); // Effet de bord : synchronise la barre de sorts sur l'incarnation courante.
        SocketManager.GAME_SEND_Ow_PACKET(client, target); // Bloc logique : recalcule l'inventaire (pods) pour l'affichage.
    }

    /** Notes pédagogiques
     * - {@link #prepareTurnControl(Fight, Fighter)} renvoie {@code false} pour inviter le moteur à passer le tour si le maître est indisponible.
     * - {@link #finalizeTurnControl(Fighter)} délègue à {@link #releaseControlFor(Player, Player)} la restauration complète de l'UI.
     * - {@link #resolveControlledActor(Player)} permet aux couches réseau de router les commandes sur le bon combattant.
     * - {@link #transferManualControl(Player, Player)} maintient le même héros actif après un {@code switchMaster}.
     * - {@link #engageTemporaryIncarnation(Player, Player)} force le client à incarner un héros pendant son tour.
     * - {@link #restorePrimaryIncarnation(Player)} garantit le retour automatique sur le maître en fin de tour ou lors d'un nettoyage.
     * - {@link #clearManualControl(Player)} reste utile pour purger tout résidu lors de l'ajout ou du retrait d'un héros.
     * - {@link #sendCombatPackets(GameClient, Player, Fight, Fighter)} orchestre GIC/GTL/GTM/GAS/GTS + GTR/Im1201 pour le combattant local.
     * - {@link #notifyLocalControlSwitch(GameClient, Fighter)} diffuse GS/BP afin de transférer les clics sur le héros ciblé.
     * - {@link #sendMapPackets(GameClient, Player)} renvoie la séquence ASK/Stats/Spells/Ow classique hors combat.
     */
}
