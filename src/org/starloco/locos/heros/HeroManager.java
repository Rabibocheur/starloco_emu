package org.starloco.locos.heros;

import org.starloco.locos.area.map.GameCase;
import org.starloco.locos.area.map.GameMap;
import org.starloco.locos.client.Player;
import org.starloco.locos.client.other.Party;
import org.starloco.locos.database.Database;
import org.starloco.locos.common.SocketManager;
import org.starloco.locos.game.GameClient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gère le mode héros pour un maître et ses personnages associés.
 */
public final class HeroManager {

    private static final int HERO_LIMIT = 3;
    private static final HeroManager INSTANCE = new HeroManager();

    private final Map<Integer, HeroGroup> groups = new ConcurrentHashMap<>();
    private final Map<Integer, Integer> heroToMaster = new ConcurrentHashMap<>();
    private HeroManager() {
    }

    /**
     * Retourne l'instance unique du gestionnaire de héros.
     *
     * @return gestionnaire.
     */
    public static HeroManager getInstance() {
        return INSTANCE;
    }

    /**
     * Ajoute un héros au groupe du maître.
     *
     * @param master joueur maître.
     * @param heroId identifiant du héros.
     * @return résultat de l'opération.
     */
    public synchronized HeroOperationResult addHero(Player master, int heroId) {
        if (master == null) {
            return HeroOperationResult.error("Maître introuvable.");
        }
        Player hero = master.getAccount().getPlayers().get(heroId);
        if (hero == null) {
            return HeroOperationResult.error("Aucun personnage avec cet identifiant sur le compte.");
        }
        if (hero.getId() == master.getId()) {
            return HeroOperationResult.error("Le maître ne peut pas être ajouté comme héros.");
        }
        if (hero.isOnline()) {
            return HeroOperationResult.error("Ce personnage est déjà connecté.");
        }
        if (hero.getAccount().getId() != master.getAccount().getId()) {
            return HeroOperationResult.error("Le personnage doit appartenir au même compte.");
        }
        if (heroToMaster.containsKey(hero.getId())) {
            return HeroOperationResult.error("Ce héros est déjà actif.");
        }
        HeroGroup group = groups.computeIfAbsent(master.getId(), id -> new HeroGroup());
        if (group.heroes.size() >= HERO_LIMIT) {
            return HeroOperationResult.error("Vous avez déjà trois héros actifs.");
        }
        // Supprime tout état résiduel d'une précédente session héros avant rattachement.
        detachHero(hero);
        initializeHeroPosition(master, hero);
        group.heroes.put(hero.getId(), hero);
        heroToMaster.put(hero.getId(), master.getId());
        hero.setEsclave(true);
        attachToParty(master, hero);
        updatePositionsAfterJoin(master);
        return HeroOperationResult.success("Héros " + hero.getName() + " activé.");
    }

    /**
     * Retire un héros du groupe du maître.
     *
     * @param master joueur maître.
     * @param heroId identifiant du héros.
     * @return résultat de l'opération.
     */
    public synchronized HeroOperationResult removeHero(Player master, int heroId) {
        if (master == null) {
            return HeroOperationResult.error("Maître introuvable.");
        }
        HeroGroup group = groups.get(master.getId());
        if (group == null) {
            return HeroOperationResult.error("Aucun héros actif.");
        }
        Player hero = group.heroes.remove(heroId);
        if (hero == null) {
            return HeroOperationResult.error("Ce héros n'est pas actif.");
        }
        heroToMaster.remove(hero.getId());
        Party party = hero.getParty();
        if (party != null) {
            party.leave(hero);
        }
        detachHero(hero);
        hero.setEsclave(false);
        if (group.heroes.isEmpty()) {
            groups.remove(master.getId());
        }
        return HeroOperationResult.success("Héros " + hero.getName() + " déconnecté.");
    }

    /**
     * Transfère le contrôle du groupe vers un héros existant.
     * <p>
     * Exemple : {@code switchMaster(master, 123)} incarne le héros #123 si disponible.<br>
     * Erreur fréquente : tenter un switch durant un combat, ce qui retourne un message bloquant.<br>
     * Invariant : le maître et le héros restent membres du même {@link Party} après l'appel.
     * </p>
     *
     * @param master maître actuel.
     * @param heroId identifiant du héros à incarner.
     * @return résultat de l'opération.
     */
    public synchronized HeroOperationResult switchMaster(Player master, int heroId) {
        if (master == null) { // Bloc logique : sans maître, aucune action de switch n'est possible.
            return HeroOperationResult.error("Maître introuvable.");
        }
        HeroGroup group = groups.get(master.getId()); // Recherche du groupe associé au maître courant.
        if (group == null || group.heroes.isEmpty()) { // Vérifie qu'il reste bien des héros disponibles.
            return HeroOperationResult.error("Aucun héros actif.");
        }
        Player candidate = group.heroes.get(heroId); // Cible potentielle pour devenir le nouveau maître.
        if (candidate == null) { // Bloc logique : l'identifiant ne correspond à aucun héros actif.
            return HeroOperationResult.error("Ce héros n'est pas actif.");
        }
        if (candidate.getAccount() == null || master.getAccount() == null || candidate.getAccount().getId() != master.getAccount().getId()) { // Vérifie que les deux personnages partagent bien le même compte.
            return HeroOperationResult.error("Ce personnage n'appartient pas à votre compte.");
        }
        if (candidate.getId() == master.getId()) { // Empêche de switcher vers le maître déjà incarné.
            return HeroOperationResult.error("Le maître actuel est déjà incarné.");
        }
        if (master.getFight() != null) { // Bloc logique : aucun switch autorisé durant un combat.
            return HeroOperationResult.error("Impossible de switch pendant un combat.");
        }
        if (master.getGameAction() != null || master.getDoAction() || master.getExchangeAction() != null) { // Vérifie l'absence d'actions critiques avant de changer de maître.
            return HeroOperationResult.error("Synchronisation en cours, réessayez dans un instant.");
        }
        GameMap map = master.getCurMap(); // Capture la carte actuelle pour réutilisation post-switch.
        GameCase cell = master.getCurCell(); // Capture la cellule pour replacer le nouveau maître.
        if (map == null || cell == null) { // Bloc logique : sans position valide, impossible de repositionner le héros.
            return HeroOperationResult.error("Position du maître inconnue.");
        }
        GameClient client = master.getGameClient(); // Récupère la connexion afin d'injecter le nouveau personnage.
        if (client == null) { // Bloc logique : protège contre les maîtres déconnectés.
            return HeroOperationResult.error("Client de jeu introuvable.");
        }

        group.heroes.remove(heroId); // Retire temporairement le candidat de la liste des héros pour l'incarner.
        heroToMaster.remove(heroId); // Supprime le lien maître -> héros afin de le recalculer proprement.

        SocketManager.GAME_SEND_ERASE_ON_MAP_TO_MAP(map, master.getId()); // Efface l'ancien maître côté clients.
        if (master.getCurCell() != null) { // Bloc logique : nettoie la cellule occupée seulement si elle est connue.
            master.getCurCell().removePlayer(master); // Libère la cellule pour éviter les doublons de personnages.
        }

        int orientation = master.get_orientation();

        master.setEsclave(true);
        master.setOnline(false);
        master.setDoAction(false);
        master.setGameAction(null);

        Party party = master.getParty();

        group.heroes.put(master.getId(), master); // L'ancien maître rejoint désormais la liste des héros actifs.

        groups.remove(master.getId());
        groups.put(candidate.getId(), group); // Le groupe est réindexé sur le nouvel incarné.

        for (Player hero : group.heroes.values()) {
            heroToMaster.put(hero.getId(), candidate.getId()); // Réaffecte chaque héros au nouveau maître logique.
        }

        if (party != null) { // Bloc logique : synchronise le groupe uniquement si un party existe.
            party.setMaster(candidate); // Le suivi de groupe doit pointer vers le nouveau contrôleur.
            party.setChief(candidate); // Met à jour le chef affiché pour corriger l'incohérence côté client.
            candidate.setParty(party); // Le héros incarné est officiellement membre du groupe.
            master.setParty(party); // L'ancien maître reste dans la party comme héros virtuel.
            broadcastLeaderChange(party, candidate, master); // Diffuse les paquets réseau pour refléter le changement.
        }

        candidate.setAccount(master.getAccount());
        candidate.setEsclave(false);
        candidate.setDoAction(false);
        candidate.setGameAction(null);
        candidate.setCurMap(map);
        candidate.setCurCell(cell);
        candidate.set_orientation(orientation);
        master.set_orientation(orientation);

        master.getAccount().setCurrentPlayer(candidate);
        client.switchActivePlayer(candidate);

        Database.getStatics().getPlayerData().updateLogged(master.getId(), 0);

        candidate.OnJoinGame();
        candidate.sendGameCreate();

        updatePositionsAfterJoin(candidate);

        candidate.sendInformationMessage("Contrôle transféré sur " + candidate.getName() + ".");

        return HeroOperationResult.success("Contrôle transféré sur " + candidate.getName() + ".");
    }

    /**
     * Retire tous les héros liés au maître.
     *
     * @param master joueur maître.
     */
    public synchronized void removeAllForMaster(Player master) {
        if (master == null) {
            return;
        }
        HeroGroup group = groups.remove(master.getId());
        if (group == null) {
            return;
        }
        List<Player> heroes = new ArrayList<>(group.heroes.values());
        for (Player hero : heroes) {
            heroToMaster.remove(hero.getId());
            Party party = hero.getParty();
            if (party != null) {
                party.leave(hero);
            }
            detachHero(hero);
            hero.setEsclave(false);
        }
    }

    /**
     * Retourne la liste des héros actifs pour le maître.
     *
     * @param master joueur maître.
     * @return liste de héros.
     */
    public synchronized List<Player> getActiveHeroes(Player master) {
        HeroGroup group = master == null ? null : groups.get(master.getId());
        if (group == null) {
            return Collections.emptyList();
        }
        return new ArrayList<>(group.heroes.values());
    }

    /**
     * Indique si le joueur est enregistré comme héros.
     *
     * @param player joueur à vérifier.
     * @return {@code true} si héros actif.
     */
    public boolean isHero(Player player) {
        return player != null && heroToMaster.containsKey(player.getId());
    }

    /**
     * Appelé lors d'un changement de carte du maître.
     *
     * @param player maître concerné.
     * @param newMap nouvelle carte.
     */
    public void onPlayerMapUpdated(Player player, GameMap newMap) {
        if (player == null || isHero(player)) {
            return;
        }
        HeroGroup group = groups.get(player.getId());
        if (group == null) {
            return;
        }
        // Réplique uniquement l'état logique : aucune inscription réseau n'est effectuée ici.
        for (Player hero : group.heroes.values()) {
            if (hero == null) {
                continue;
            }
            hero.setVirtualPosition(newMap, null);
        }
    }

    /**
     * Appelé lors d'un changement de cellule du maître.
     *
     * @param player maître concerné.
     * @param newCell nouvelle cellule.
     */
    public void onPlayerCellUpdated(Player player, GameCase newCell) {
        if (player == null || isHero(player)) {
            return;
        }
        HeroGroup group = groups.get(player.getId());
        if (group == null) {
            return;
        }
        // Les héros restent "fantômes" : seule la position mémoire est alignée sur celle du maître.
        for (Player hero : group.heroes.values()) {
            if (hero == null) {
                continue;
            }
            GameMap masterMap = player.getCurMap();
            if (masterMap != null && newCell != null) {
                hero.setVirtualPosition(masterMap.getId(), newCell.getId());
            } else {
                hero.setVirtualPosition(masterMap, newCell);
            }
        }
    }

    /**
     * Construit un résumé des héros pour affichage.
     *
     * @param master joueur maître.
     * @return message formaté.
     */
    public synchronized String describe(Player master) {
        StringBuilder builder = new StringBuilder();
        builder.append("<b>Maître :</b> ")
                .append(master.getName())
                .append(" (#")
                .append(master.getId())
                .append(")\n");
        List<Player> heroes = getActiveHeroes(master);
        if (heroes.isEmpty()) {
            builder.append("<b>Héros actifs :</b> Aucun\n");
        } else {
            builder.append("<b>Héros actifs :</b> ");
            boolean first = true;
            for (Player hero : heroes) {
                if (!first) {
                    builder.append(", ");
                }
                builder.append(hero.getName()).append(" (#").append(hero.getId()).append(")");
                first = false;
            }
            builder.append("\n");
        }
        builder.append("<b>Personnages disponibles :</b> ");
        boolean first = true;
        for (Player candidate : master.getAccount().getPlayers().values()) {
            if (candidate == null || candidate.getId() == master.getId() || isHero(candidate)) {
                continue;
            }
            if (!first) {
                builder.append(", ");
            }
            builder.append(candidate.getName()).append(" (#").append(candidate.getId()).append(")");
            first = false;
        }
        if (first) {
            builder.append("Aucun");
        }
        return builder.toString();
    }

    private void attachToParty(Player master, Player hero) {
        Party party = master.getParty();
        if (party == null) {
            // Crée un groupe minimal afin que le maître et le héros partagent les gains.
            party = new Party(master, hero);
            party.setMaster(master);
            master.setParty(party);
            hero.setParty(party);
            SocketManager.GAME_SEND_GROUP_CREATE(master.getGameClient(), party);
            SocketManager.GAME_SEND_PL_PACKET(master.getGameClient(), party);
            SocketManager.GAME_SEND_ALL_PM_ADD_PACKET(master.getGameClient(), party);
        } else {
            party.addPlayer(hero);
            hero.setParty(party);
            SocketManager.GAME_SEND_PM_ADD_PACKET_TO_GROUP(party, hero);
        }
    }

    private void detachHero(Player hero) {
        if (hero == null) {
            return;
        }
        hero.setVirtualPosition(null, null);
    }

    /**
     * Aligne instantanément la position du héros sur celle du maître.
     */
    private void initializeHeroPosition(Player master, Player hero) {
        GameMap map = master.getCurMap();
        GameCase cell = master.getCurCell();
        if (map != null && cell != null) {
            hero.setVirtualPosition(map.getId(), cell.getId());
        } else {
            hero.setVirtualPosition(map, cell);
        }
        hero.set_orientation(master.get_orientation());
    }

    private void updatePositionsAfterJoin(Player master) {
        // Exécute immédiatement les callbacks de synchronisation pour un héros fraîchement activé.
        if (master.getCurMap() != null) {
            onPlayerMapUpdated(master, master.getCurMap());
        }
        if (master.getCurCell() != null) {
            onPlayerCellUpdated(master, master.getCurCell());
        }
    }

    /**
     * Diffuse les paquets nécessaires après un changement de chef de groupe.
     * <p>
     * Exemple : utilisé après {@link #switchMaster(Player, int)} pour recalculer les affichages côté client.<br>
     * Effet de bord : envoie les paquets {@code PM~} et {@code PL}, ce qui actualise la fenêtre de groupe.
     * </p>
     *
     * @param party groupe concerné (doit rester cohérent avec les joueurs fournis).
     * @param newChief nouveau chef affiché (peut être {@code null} si aucun).
     * @param previousChief chef précédent pour rafraîchir ses informations.
     */
    private void broadcastLeaderChange(Party party, Player newChief, Player previousChief) {
        if (party == null) { // Bloc logique : aucun envoi si la party a été détruite avant notification.
            return;
        }
        if (newChief != null) { // Rafraîchit le membre incarné pour refléter son statut de chef.
            SocketManager.GAME_SEND_PM_MOD_PACKET_TO_GROUP(party, newChief);
        }
        if (previousChief != null) { // Met à jour l'ancien maître devenu héros virtuel.
            SocketManager.GAME_SEND_PM_MOD_PACKET_TO_GROUP(party, previousChief);
        }
        for (Player member : party.getPlayers()) { // Parcourt chaque membre pour diffuser le nouveau chef.
            GameClient memberClient = member != null ? member.getGameClient() : null; // Sécurise l'accès au client réseau.
            if (memberClient == null) { // Ignore les joueurs déconnectés pour éviter les NullPointerException.
                continue;
            }
            SocketManager.GAME_SEND_PL_PACKET(memberClient, party); // Transmet le paquet "nouveau chef" au client.
        }
    }

    /**
     * Représente le résultat d'une opération de gestion des héros.
     */
    public static final class HeroOperationResult {
        private final boolean success;
        private final String message;

        private HeroOperationResult(boolean success, String message) {
            this.success = success;
            this.message = message;
        }

        /**
         * Indique si l'opération s'est déroulée correctement.
         *
         * @return {@code true} si succès.
         */
        public boolean isSuccess() {
            return success;
        }

        /**
         * Message utilisateur associé au résultat.
         *
         * @return message à afficher.
         */
        public String getMessage() {
            return message;
        }

        private static HeroOperationResult success(String message) {
            return new HeroOperationResult(true, message);
        }

        private static HeroOperationResult error(String message) {
            return new HeroOperationResult(false, message);
        }
    }

    private static final class HeroGroup {
        private final Map<Integer, Player> heroes = new LinkedHashMap<>();
    }
}
