package org.starloco.locos.client.other;

import org.starloco.locos.area.map.GameCase;
import org.starloco.locos.area.map.GameMap;
import org.starloco.locos.client.Player;
import org.starloco.locos.common.PathFinding;
import org.starloco.locos.common.SocketManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Représente un groupe de joueurs (party) et encapsule la logique de suivi du chef.
 * <p>
 * Exemple :<br>
 * {@code Party party = new Party(master, hero);} crée un groupe où {@code master}
 * est chef par défaut. Il reste possible de changer le chef via {@link #setChief(Player)}
 * lorsqu'un héros prend le contrôle. En cas de joueur absent, penser à vérifier
 * {@link #isWithTheMaster(Player, boolean)} afin de savoir s'il suit correctement.
 * </p>
 */
public class Party {

    /** Chef de groupe actuel. Invariant : doit toujours appartenir à {@link #players}. */
    private Player chief;

    /** Participants du groupe, préserve l'ordre d'inscription. */
    private final List<Player> players = new ArrayList<>();

    /** Joueur contrôlé côté client (maître actif). Peut différer du chef affiché. */
    private Player master;

    /**
     * Construit une party minimale avec deux joueurs.
     * <p>
     * Pré-condition : {@code p1} et {@code p2} ne sont pas {@code null}.<br>
     * Post-condition : {@code p1} devient chef et tous deux sont listés dans {@link #players}.<br>
     * Erreur fréquente : oublier d'appeler {@link #setMaster(Player)} pour initialiser le maître logique.
     * </p>
     */
    public Party(Player p1, Player p2) {
        this.chief = p1; // Initialise immédiatement le chef avec le premier joueur.
        this.players.add(p1); // Le maître d'origine rejoint la collection.
        this.players.add(p2); // Le second joueur devient automatiquement membre.
    }

    /**
     * Retourne une vue modifiable des joueurs inscrits.
     * <p>
     * Attention : manipuler directement cette liste peut casser l'invariant "chef ∈ joueurs".
     * Favoriser les méthodes dédiées comme {@link #addPlayer(Player)} ou {@link #leave(Player)}.
     * </p>
     */
    public List<Player> getPlayers() {
        return this.players; // Fournit l'accès direct attendu par le reste du serveur.
    }

    /**
     * Donne le chef actuellement affiché côté client.
     * @return joueur servant de chef.
     */
    public Player getChief() {
        return this.chief; // Retourne simplement la référence courante.
    }

    /**
     * Met à jour le chef affiché.
     * <p>
     * Exemple : appeler {@code party.setChief(newLeader)} après un switch de maître.<br>
     * Si {@code newLeader} n'appartient pas déjà au groupe, il est ajouté pour garantir la cohérence.
     * </p>
     * @param newChief nouveau chef souhaité (doit être non nul).
     */
    public void setChief(Player newChief) {
        if (newChief == null) { // Vérifie que l'appelant ne fournit pas une référence invalide.
            throw new IllegalArgumentException("Le chef ne peut pas être nul");
        }
        if (!players.contains(newChief)) { // Assure l'invariant : un chef doit être membre.
            players.add(newChief); // Ajoute automatiquement le joueur manquant.
        }
        this.chief = newChief; // Remplace le chef tout en conservant les liens existants.
    }

    /**
     * Indique si l'identifiant fourni correspond au chef.
     * @param id identifiant joueur testé.
     * @return {@code true} si le joueur ciblé est le chef.
     */
    public boolean isChief(int id) {
        return this.chief.getId() == id; // Comparaison directe, aucun calcul secondaire.
    }

    /**
     * Retourne le maître logique utilisé pour synchroniser les déplacements.
     */
    public Player getMaster() {
        return master; // Peut être nul tant que la party n'est pas activée côté héros.
    }

    /**
     * Définit le maître logique.
     * <p>
     * Cas d'usage : {@code party.setMaster(activePlayer);} juste après un switch de héros.
     * Effet de bord : aucune notification réseau, cette responsabilité reste à l'appelant.
     * </p>
     */
    public void setMaster(Player master) {
        this.master = master; // Mémorise le pilote actuel sans modifier l'ordre des joueurs.
    }

    /**
     * Ajoute un joueur au groupe si besoin.
     * @param player joueur à inscrire (doit être non nul).
     */
    public void addPlayer(Player player) {
        this.players.add(player); // Ajoute tel quel : l'appelant gère les doublons ou nulls.
    }

    /**
     * Retire proprement un joueur du groupe.
     * <p>
     * L'appel nettoie les relations de suivi et envoie les paquets réseau correspondants.
     * Attention : si le joueur retiré était chef, la responsabilité de choisir un nouveau chef
     * revient à l'appelant via {@link #setChief(Player)}.
     * </p>
     */
    public void leave(Player player) {
        if (!this.players.contains(player)) { // Vérifie d'abord la présence pour éviter les effets de bord inutiles.
            return; // Sort silencieusement si le joueur n'était pas membre.
        }

        player.follow = null; // Réinitialise la cible suivie côté joueur.
        player.follower.clear(); // Vide la liste des suiveurs liés.
        player.setParty(null); // Détache complètement le joueur du groupe.
        this.players.remove(player); // Retire de la collection pour maintenir la cohérence.

        for (Player member : this.players) { // Parcourt le reste du groupe pour couper les relations obsolètes.
            if (member.follow == player) { // Si un membre suivait le joueur sortant...
                member.follow = null; // ... on annule le suivi pour éviter des déplacements fantômes.
            }
            if (member.follower.containsKey(player.getId())) { // Vérifie aussi la table des suiveurs.
                member.follower.remove(player.getId()); // Supprime l'entrée correspondante.
            }
        }

        if (this.players.size() == 1) { // Bloc logique : reste-t-il un seul joueur ?
            Player survivor = this.players.get(0); // Récupère l'unique survivant du groupe.
            survivor.setParty(null); // Le joueur reste désormais sans groupe.
            if (survivor.getAccount() == null || survivor.getGameClient() == null) { // Vérifie la disponibilité réseau.
                return; // Aucun paquet à envoyer si la connexion est invalide.
            }
            SocketManager.GAME_SEND_PV_PACKET(survivor.getGameClient(), ""); // Informe le client que le groupe est dissous.
        } else { // Plusieurs joueurs restent en place.
            SocketManager.GAME_SEND_PM_DEL_PACKET_TO_GROUP(this, player.getId()); // Diffuse la sortie à tout le groupe.
        }
    }

    /**
     * Déplace les joueurs vers la cellule du maître.
     * <p>
     * Utilisé lorsque le maître change de position et que les membres doivent le rejoindre.
     * </p>
     */
    public void moveAllPlayersToMaster(final GameCase cell) {
        if (this.master != null) { // Sans maître défini, aucune action n'est possible.
            this.players.stream() // Première étape : gèle les déplacements des suiveurs valides.
                    .filter(follower1 -> isWithTheMaster(follower1, false)) // Filtre uniquement les membres synchronisés.
                    .forEach(follower -> follower.setBlockMovement(true)); // Empêche les mouvements parasites durant le recalcul.
            this.players.stream() // Deuxième étape : calcule et applique la trajectoire vers la cellule cible.
                    .filter(follower1 -> isWithTheMaster(follower1, false)) // Refiltre pour éviter les écarts entre les deux flux.
                    .forEach(follower -> { // Traite chaque membre éligible.
                        try { // Encapsule les calculs pour capturer les erreurs inattendues.
                            final GameCase newCell = cell != null ? cell : this.master.getCurCell(); // Choisit la cellule destination.
                            String path = PathFinding.getShortestStringPathBetween(this.master.getCurMap(), follower.getCurCell().getId(), newCell.getId(), 0); // Calcule un chemin court.
                            if (path != null) { // Si un chemin valide existe...
                                follower.getCurCell().removePlayer(follower); // Retire le joueur de son ancienne cellule.
                                follower.setCurCell(newCell); // Met à jour la position logique.
                                follower.getCurCell().addPlayer(follower); // Ajoute à la nouvelle cellule pour le moteur de carte.
                                SocketManager.GAME_SEND_GA_PACKET_TO_MAP(follower.getCurMap(), "0", 1, String.valueOf(follower.getId()), path); // Notifie le déplacement à tous.
                            }
                        } catch (Exception ignored) { // Erreurs rares : volontairement ignorées mais confinées.
                        }
                    });
            this.players.stream() // Troisième étape : libère les déplacements.
                    .filter(follower1 -> isWithTheMaster(follower1, false)) // Maintient la symétrie du filtrage.
                    .forEach(follower -> follower.setBlockMovement(false)); // Rétablit le contrôle manuel pour chaque joueur.
        }
    }

    /**
     * Indique si un joueur suit correctement le maître.
     * @param follower joueur vérifié.
     * @param inFight précise si l'on est en combat (modifie la condition sur les combats).
     * @return {@code true} si le joueur est synchronisé.
     */
    public boolean isWithTheMaster(Player follower, boolean inFight) {
        return follower != null // Vérifie la référence.
                && !follower.isEsclave() // Les esclaves ne sont pas considérés comme suiveurs.
                && !follower.getName().equals(this.master.getName()) // Évite de comparer le maître avec lui-même.
                && this.players.contains(follower) // S'assure que le joueur fait bien partie du groupe.
                && follower.getGameClient() != null // Nécessite une connexion valide pour le suivi.
                && this.master.getCurMap().getId() == follower.getCurMap().getId() // Même carte pour rester groupé.
                && (inFight ? follower.getFight() == this.master.getFight() : follower.getFight() == null); // Condition supplémentaire selon le contexte combat.
    }

    /**
     * Téléporte tous les esclaves vers le maître lorsque c'est autorisé.
     */
    public void teleportAllEsclaves() {
        for (Player follower : players) { // Parcourt chaque membre pour valider la téléportation.
            if (follower.getExchangeAction() != null) { // Si le joueur est occupé...
                follower.sendMessage("Vous n'avez pas pu être téléporté car vous êtes occupé."); // Informe le joueur bloqué.
                master.sendMessage("Le joueur " + follower.getName() + " est occupé et n'a pas pu être téléporté."); // Prévient le maître pour diagnostic.
                continue; // Passe au joueur suivant.
            }
            if (master.getCurMap().getId() != follower.getCurMap().getId() && GameMap.IsInDj(master.getCurMap())) { // Vérifie la possibilité de téléportation.
                follower.teleport(master.getCurMap().getId(), master.getCurCell().getId()); // Exécute la téléportation effective.
            }
        }
    }

    /** Notes pédagogiques */
    /*
     * - Maintenir l'invariant "chef ∈ joueurs" évite des NullPointerException lors des paquets réseau.
     * - Utiliser setChief() après un switch de héros assure la cohérence côté client.
     * - La méthode leave() n'assigne pas automatiquement un nouveau chef : traiter ce cas explicitement.
     * - Les flux stream sont filtrés trois fois afin de conserver un état stable pendant les déplacements groupés.
     */
}
