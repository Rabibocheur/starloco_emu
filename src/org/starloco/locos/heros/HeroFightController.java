package org.starloco.locos.heros;

import org.starloco.locos.client.Player;
import org.starloco.locos.common.SocketManager;
import org.starloco.locos.fight.Fight;
import org.starloco.locos.fight.Fighter;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Coordonne le transfert de contrôle des héros pendant un combat.<br>
 * <p>
 * Exemple : après {@link #handleTurnStart(Fight, Fighter)}, un clic sur un sort par le maître utilisera automatiquement
 * les statistiques du héros contrôlé.<br>
 * Cas d'erreur fréquent : appeler {@link #resolveActiveActor(Player)} après la fin du combat ; la méthode retournera
 * alors le maître pour éviter toute fuite de référence.<br>
 * Invariant : un seul héros peut être contrôlé par un maître donné à un instant donné.<br>
 * Effet de bord : déclenche l'envoi des sorts et caractéristiques du héros vers le client du maître à chaque début de tour.
 * </p>
 */
public final class HeroFightController {

    private static final HeroFightController INSTANCE = new HeroFightController(); // Singleton explicite.

    /**
     * Contexte de contrôle indexé par identifiant de maître.<br>
     * La map est concurrente pour gérer les accès multi-threads lors des transitions de tour et des déconnexions.
     */
    private final Map<Integer, HeroControlContext> activeControls = new ConcurrentHashMap<>();

    private HeroFightController() {
        // Constructeur privé : garantit l'usage du singleton pour centraliser les états de contrôle.
    }

    /**
     * Retourne l'instance unique du contrôleur de combat pour les héros.
     *
     * @return singleton interne.
     */
    public static HeroFightController getInstance() {
        return INSTANCE;
    }

    /**
     * Prépare le contrôle manuel lorsqu'un tour commence.<br>
     * <p>
     * Exemple : {@code handleTurnStart(fight, fighter)} renvoie {@code true} et positionne les paquets nécessaires si le
     * combattant est un héros appartenant à un maître connecté.<br>
     * Invariant : la map {@link #activeControls} ne contient que des combats actifs ; tout tour non héros provoque une
     * purge automatique pour le maître concerné.<br>
     * Effet de bord : les paquets {@code Ow} (pods) et {@code SL} (liste des sorts) du héros sont relayés vers le maître.
     * </p>
     *
     * @param fight   combat en cours.
     * @param fighter combattant dont le tour débute.
     * @return {@code true} si le contrôle peut se poursuivre normalement ; {@code false} si le tour doit être passé.
     */
    public boolean handleTurnStart(Fight fight, Fighter fighter) {
        if (fight == null || fighter == null) { // Bloc logique : aucun traitement sans contexte de combat valide.
            return true;
        }
        Player candidate = fighter.getPersonnage(); // Personnage associé au combattant (maître ou héros).
        if (candidate == null) { // Bloc logique : entités non joueur (invocations, monstres) n'ont pas besoin de contrôle manuel.
            return true;
        }
        HeroManager heroManager = HeroManager.getInstance(); // Gestionnaire centralisé des héros actifs.
        if (!heroManager.isHero(candidate)) { // Bloc logique : le combattant est un maître ou un joueur classique.
            releaseControl(candidate); // Purge tout contrôle résiduel pour ce maître.
            return true;
        }
        Player master = heroManager.findMaster(candidate); // Recherche du maître rattaché au héros actif.
        if (!isControlEligible(master, fight)) { // Bloc logique : maître absent, déconnecté ou hors combat : le tour doit être passé.
            return false;
        }
        HeroControlContext context = new HeroControlContext(master, candidate, fight, fighter.getId()); // Contexte figé pour ce tour.
        activeControls.put(master.getId(), context); // Enregistre le transfert de contrôle.
        relayHeroPanels(candidate, master); // Met à jour l'interface côté maître.
        return true;
    }

    /**
     * Nettoie l'état de contrôle à la fin d'un tour.<br>
     * <p>
     * Exemple : {@code handleTurnEnd(fight, fighter)} libère l'association maître ↔ héros pour permettre au prochain
     * combattant du joueur de reprendre la main.<br>
     * Invariant : l'appel est idempotent, il est donc sûr de l'invoquer même si aucun héros n'était contrôlé.<br>
     * Effet de bord : supprime l'entrée correspondante dans {@link #activeControls}.
     * </p>
     *
     * @param fight   combat en cours.
     * @param fighter combattant ayant terminé son tour.
     */
    public void handleTurnEnd(Fight fight, Fighter fighter) {
        if (fight == null || fighter == null) { // Bloc logique : rien à faire sans données valides.
            return;
        }
        Player candidate = fighter.getPersonnage(); // Identifie le joueur éventuellement contrôlé.
        if (candidate == null) { // Bloc logique : aucun lien maître ↔ héros à nettoyer.
            return;
        }
        HeroManager heroManager = HeroManager.getInstance();
        if (!heroManager.isHero(candidate)) { // Bloc logique : le tour concernait le maître, aucune entrée à supprimer.
            return;
        }
        Player master = heroManager.findMaster(candidate); // Retrouve le maître pour supprimer l'association.
        if (master == null) { // Bloc logique : la relation a déjà été purgée ailleurs.
            return;
        }
        activeControls.remove(master.getId()); // Libère le contrôle pour permettre le prochain tour librement.
    }

    /**
     * Supprime toutes les associations liées à un combat terminé.<br>
     * <p>
     * Exemple : {@code releaseForFight(fight)} est invoqué à la fin du combat pour éviter des références persistantes
     * vers des objets {@link Fight} obsolètes.<br>
     * Invariant : la map de contrôle ne contiendra plus aucune entrée pointant vers {@code fight} après l'appel.<br>
     * Effet de bord : purge potentielle de plusieurs héros si le même maître contrôlait plusieurs combats successifs.
     * </p>
     *
     * @param fight combat concerné.
     */
    public void releaseForFight(Fight fight) {
        if (fight == null) { // Bloc logique : évite les NPE sur un combat manquant.
            return;
        }
        activeControls.values().removeIf(context -> context.isForFight(fight)); // Supprime toutes les entrées liées au combat.
    }

    /**
     * Détermine le personnage réellement actif pour le maître fourni.<br>
     * <p>
     * Exemple : {@code resolveActiveActor(master)} retourne le héros actuellement contrôlé ou le maître lui-même si
     * aucun héros n'est en cours d'action.<br>
     * Invariant : retourne toujours un joueur appartenant au même compte que {@code master}.<br>
     * Effet de bord : aucun, la méthode est purement consultative.
     * </p>
     *
     * @param master joueur maître connecté.
     * @return héros contrôlé ou maître s'il agit pour lui-même.
     */
    public Player resolveActiveActor(Player master) {
        if (master == null) { // Bloc logique : renvoie null pour signaler l'absence de contrôleur.
            return null;
        }
        HeroControlContext context = activeControls.get(master.getId()); // Récupère le contexte courant si présent.
        if (context == null || !context.isStillValid()) { // Bloc logique : aucune association active ou contexte obsolète.
            return master;
        }
        return context.hero; // Le maître contrôle effectivement ce héros pour les actions en cours.
    }

    /**
     * Recherche le maître associé à un héros donné.<br>
     * <p>
     * Exemple : {@code findMasterForHero(hero)} s'utilise depuis {@link SocketManager#send(Player, String)} pour rerouter
     * les paquets réseau vers la bonne connexion.<br>
     * Invariant : si le héros n'est pas actif, la méthode retourne {@code Optional.empty()}.
     * </p>
     *
     * @param hero personnage héros concerné.
     * @return maître encapsulé dans un {@link Optional} pour signaler l'absence éventuelle de résultat.
     */
    public Optional<Player> findMasterForHero(Player hero) {
        if (hero == null) { // Bloc logique : aucune recherche sans héros fourni.
            return Optional.empty();
        }
        Player master = HeroManager.getInstance().findMaster(hero); // Délègue la résolution au gestionnaire principal.
        if (master == null) { // Bloc logique : héros inactif ou mal référencé.
            return Optional.empty();
        }
        return Optional.of(master);
    }

    /**
     * Libère explicitement le contrôle pour un maître donné.<br>
     * <p>
     * Exemple : {@code releaseControl(master)} est invoqué lorsqu'un maître reprend son propre tour pour annuler les
     * actions résiduelles du héros précédent.<br>
     * </p>
     *
     * @param master joueur maître.
     */
    public void releaseControl(Player master) {
        if (master == null) { // Bloc logique : aucun maître à purger.
            return;
        }
        activeControls.remove(master.getId()); // Supprime l'association clé → contexte pour ce maître.
    }

    /**
     * Vérifie que le maître peut contrôler un héros pendant le combat.<br>
     *
     * @param master maître pressenti.
     * @param fight  combat actuel.
     * @return {@code true} si les prérequis sont respectés.
     */
    private boolean isControlEligible(Player master, Fight fight) {
        if (master == null) { // Bloc logique : aucun maître n'est disponible pour prendre le contrôle.
            return false;
        }
        if (!master.isOnline()) { // Bloc logique : maître déconnecté, impossible de piloter le héros.
            return false;
        }
        Fight masterFight = master.getFight(); // Combat où se trouve le maître.
        return masterFight != null && masterFight.equals(fight); // Bloc logique : contrôle autorisé uniquement si les combats correspondent.
    }

    /**
     * Propage les informations de panneau (pods, sorts) du héros vers le client du maître.<br>
     *
     * @param hero   héros contrôlé.
     * @param master maître destinataire.
     */
    private void relayHeroPanels(Player hero, Player master) {
        if (hero == null || master == null) { // Bloc logique : évite d'envoyer des paquets incomplets.
            return;
        }
        SocketManager.GAME_SEND_Ow_PACKET(hero); // Met à jour les pods du héros dans l'interface maître.
        SocketManager.GAME_SEND_STATS_PACKET(hero); // Rafraîchit les stats pour afficher PA/PM restants du héros.
        SocketManager.GAME_SEND_SPELL_LIST(hero); // Envoie la barre de sorts correspondant au héros actif.
    }

    /**
     * Contexte interne liant un maître, un héros et un combat en cours.
     */
    private static final class HeroControlContext {

        private final Player master; // Maître titulaire du contrôle.
        private final Player hero; // Héros actuellement contrôlé.
        private final Fight fight; // Combat auquel se rapporte le contrôle.
        private final int fighterId; // Identifiant unique du combattant pour validation.

        private HeroControlContext(Player master, Player hero, Fight fight, int fighterId) {
            this.master = master;
            this.hero = hero;
            this.fight = fight;
            this.fighterId = fighterId;
        }

        private boolean isStillValid() {
            if (master == null || hero == null || fight == null) { // Bloc logique : contexte incomplet.
                return false;
            }
            if (!master.isOnline()) { // Bloc logique : maître hors ligne => contexte invalide.
                return false;
            }
            Fighter heroFighter = fight.getFighterByPerso(hero); // Récupère le combattant courant du héros.
            if (heroFighter == null) { // Bloc logique : le héros n'est plus présent dans ce combat.
                return false;
            }
            if (heroFighter.getId() != fighterId) { // Bloc logique : le tour stocké ne correspond plus à l'identifiant attendu.
                return false;
            }
            return Objects.equals(master.getFight(), fight); // Bloc logique : sécurité supplémentaire sur le combat partagé.
        }

        private boolean isForFight(Fight otherFight) {
            return fight != null && fight.equals(otherFight); // Bloc logique : comparaison directe pour la purge finale.
        }
    }
}

/** Notes pédagogiques */
// 1. Le contrôleur encapsule l'ensemble des règles de transfert pour éviter la duplication de logique dans le moteur de combat.
// 2. Les vérifications de validité sont redondantes : elles préviennent les cas de déconnexions intempestives ou de morts instantanées du héros.
// 3. {@link HeroFightController#relayHeroPanels(Player, Player)} illustre l'usage d'un héros comme source de paquets tout en conservant le maître comme destinataire.
// 4. La map concurrente autorise des modifications depuis plusieurs threads sans blocage explicite, ce qui simplifie la gestion des timers de tours.
// 5. {@link HeroFightController#resolveActiveActor(Player)} sert de point d'entrée unique pour les actions réseau à router.
