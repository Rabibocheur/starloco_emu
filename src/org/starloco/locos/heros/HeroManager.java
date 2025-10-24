package org.starloco.locos.heros;

import org.starloco.locos.area.map.GameCase;
import org.starloco.locos.area.map.GameMap;
import org.starloco.locos.client.Player;
import org.starloco.locos.client.other.Party;
import org.starloco.locos.database.Database;
import org.starloco.locos.common.SocketManager;
import org.starloco.locos.game.GameClient;
import org.starloco.locos.fight.Fight;
import org.starloco.locos.fight.Fighter;
import org.starloco.locos.game.world.World;

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
    private final Map<Integer, HeroSnapshot> heroSnapshots = new ConcurrentHashMap<>(); // Mémorise la position réelle de chaque héros pour les futures restaurations.
    private final Map<Integer, Player> activeHeroControl = new ConcurrentHashMap<>(); // Associe un maître au héros actuellement piloté en combat.
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
        activeHeroControl.remove(master.getId()); // Sécurise la carte de contrôle : aucun héros n'est considéré comme actif à l'ajout.
        if (group.heroes.size() >= HERO_LIMIT) {
            return HeroOperationResult.error("Vous avez déjà trois héros actifs.");
        }
        rememberHeroPosition(hero); // Capture la position d'origine pour permettre un retour propre en mode joueur.
        detachHero(hero); // Supprime tout état résiduel d'une précédente session héros avant rattachement.
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
        releaseControlFor(master, hero); // Coupe immédiatement la redirection de commandes si le héros était contrôlé.
        Party party = hero.getParty();
        if (party != null) {
            party.leave(hero);
        }
        detachHero(hero);
        hero.setEsclave(false);
        restoreHeroState(hero); // Replace immédiatement le héros sur sa carte d'origine pour une future connexion directe.
        if (group.heroes.isEmpty()) {
            groups.remove(master.getId());
            activeHeroControl.remove(master.getId()); // Purge les informations de contrôle quand le groupe disparaît.
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
        rememberHeroPosition(master); // Conserve l'état réel du maître avant qu'il ne devienne héros virtuel.
        GameClient client = master.getGameClient(); // Récupère la connexion afin d'injecter le nouveau personnage.
        if (client == null) { // Bloc logique : protège contre les maîtres déconnectés.
            return HeroOperationResult.error("Client de jeu introuvable.");
        }

        group.heroes.remove(heroId); // Retire temporairement le candidat de la liste des héros pour l'incarner.
        heroToMaster.remove(heroId); // Supprime le lien maître -> héros afin de le recalculer proprement.
        heroSnapshots.remove(candidate.getId()); // Purge l'instantané du héros promu pour éviter un retour intempestif à son ancien point.

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

        Player transferredControl = activeHeroControl.remove(master.getId()); // Conserve l'état de contrôle manuel lors du changement de maître.
        if (transferredControl != null) { // Bloc logique : ne réaffecte que si un héros était effectivement piloté.
            activeHeroControl.put(candidate.getId(), transferredControl); // Le nouveau maître reprend immédiatement la main sur le même héros.
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
        activeHeroControl.remove(master.getId()); // Reset du suivi de contrôle manuel puisque le groupe est détruit.
        List<Player> heroes = new ArrayList<>(group.heroes.values());
        for (Player hero : heroes) {
            heroToMaster.remove(hero.getId());
            Party party = hero.getParty();
            if (party != null) {
                party.leave(hero);
            }
            detachHero(hero);
            hero.setEsclave(false);
            restoreHeroState(hero); // Restaure une carte tangible pour éviter toute reconnexion sur un état virtuel.
        }
    }

    /**
     * Prépare la prise de contrôle d'un combattant juste avant le début d'un tour.
     * <p>
     * Exemple : {@code prepareTurnControl(fight, fighter)} déclenche l'envoi des statistiques et paquets GIC/GAS du héros au maître.<br>
     * Invariant : si le combattant n'est pas un héros, aucun changement de contrôle n'est effectué.<br>
     * Effet de bord : en cas d'échec (maître absent), le tour devra être passé côté combat et aucun identifiant de contrôle n'est conservé.
     * </p>
     *
     * @param fight   combat en cours.
     * @param fighter combattant qui s'apprête à jouer.
     * @return {@code true} si le tour peut être joué normalement, {@code false} si le serveur doit passer le tour.
     */
    public synchronized boolean prepareTurnControl(Fight fight, Fighter fighter) {
        if (fighter == null) { // Bloc logique : aucune synchronisation requise sans combattant.
            return true;
        }
        Player actor = fighter.getPersonnage();
        if (actor == null) { // Bloc logique : les invocations ignorent totalement la logique héros.
            return true;
        }
        if (!isHero(actor)) { // Bloc logique : un joueur standard conserve la main sans traitement supplémentaire.
            releaseControlFor(actor, null); // Garantit que l'interface revient sur le maître si nécessaire.
            return true;
        }
        Player master = findMaster(actor);
        if (master == null) { // Bloc logique : sans maître on force le passage de tour.
            return false;
        }
        GameClient client = master.getGameClient();
        if (client == null || master.getFight() != fight) { // Bloc logique : maîtrise impossible si le client est absent ou sur un autre combat.
            if (client != null) { // Bloc logique : réinitialise la session si le client répond encore.
                client.resetControlledFighter(); // Bloc logique : évite de conserver un identifiant obsolète côté réseau.
            }
            activeHeroControl.remove(master.getId()); // Supprime toute redirection résiduelle avant de forcer le passage de tour.
            return false;
        }
        activeHeroControl.put(master.getId(), actor);
        client.setControlledFighterId(fighter.getId()); // Bloc logique : mémorise l'identifiant du combattant contrôlé côté session réseau.
        SocketManager.GAME_SEND_GIC_PACKET(master, fighter); // Bloc logique : force le focus de l'interface combat sur le héros ciblé.
        SocketManager.GAME_SEND_GAS_PACKET(master, fighter.getId()); // Bloc logique : actualise l'ordre de jeu client pour ce combattant.
        SocketManager.GAME_SEND_GTL_PACKET(master, fight); // Bloc logique : rafraîchit la timeline côté maître afin d'afficher les PA/PM corrects.
        SocketManager.GAME_SEND_GTM_PACKET(master, fight); // Bloc logique : synchronise les informations de points de vie et positions du héros actif.
        SocketManager.GAME_SEND_STATS_PACKET(client, actor); // Met à jour la fiche de caractéristiques côté maître.
        SocketManager.GAME_SEND_SPELL_LIST(client, actor); // Synchronise la barre de sorts avec ceux du héros actif.
        SocketManager.GAME_SEND_Ow_PACKET(client, actor); // Actualise les points de pods pour éviter des incohérences d'inventaire.
        return true;
    }

    /**
     * Termine la session de contrôle d'un héros à la fin de son tour.
     * <p>
     * Exemple : {@code finalizeTurnControl(fighter)} renvoie instantanément le contrôle sur le maître propriétaire.<br>
     * Invariant : si le héros n'était pas maîtrisé, aucun paquet n'est envoyé.<br>
     * Effet de bord : remet en avant les statistiques et paquets GIC/GAS/GTL/GTM du maître.
     * </p>
     *
     * @param fighter combattant dont le tour vient de s'achever.
     */
    public synchronized void finalizeTurnControl(Fighter fighter) {
        if (fighter == null) { // Bloc logique : rien à restaurer sans combattant défini.
            return;
        }
        Player actor = fighter.getPersonnage();
        if (actor == null || !isHero(actor)) { // Bloc logique : uniquement pour les héros virtuels.
            return;
        }
        Player master = findMaster(actor);
        if (master == null) { // Bloc logique : aucune restauration si le maître a disparu.
            return;
        }
        releaseControlFor(master, actor); // Libère l'association maître -> héros si elle correspond au combattant terminé.
    }

    /**
     * Retourne l'acteur effectivement contrôlé par la session du maître.
     * <p>
     * Exemple : {@code resolveControlledActor(master)} permet d'orienter un déplacement de combat vers le héros actif.<br>
     * Invariant : un héros incarné en tant que personnage principal est retourné tel quel.<br>
     * Effet de bord : aucun, la méthode ne modifie jamais la table de contrôle.
     * </p>
     *
     * @param sessionPlayer joueur rattaché à la session réseau.
     * @return héros actuellement piloté ou le joueur original si aucun héros n'est ciblé.
     */
    public Player resolveControlledActor(Player sessionPlayer) {
        if (sessionPlayer == null) { // Bloc logique : aucune résolution sans session.
            return null;
        }
        if (isHero(sessionPlayer)) { // Bloc logique : quand la session incarne déjà un héros, on le retourne directement.
            return sessionPlayer;
        }
        Player hero = activeHeroControl.get(sessionPlayer.getId());
        return hero != null ? hero : sessionPlayer;
    }

    /**
     * Force la sortie du mode héros pour un personnage lors d'une reconnexion directe.
     * <p>
     * Exemple : {@code ensureStandalone(hero)} avant {@link Player#OnJoinGame()} évite les erreurs de carte nulle.<br>
     * Invariant : à la fin de l'appel, {@link #isHero(Player)} retourne toujours {@code false} pour le joueur fourni.<br>
     * Effet de bord : le joueur est retiré de son groupe et sa position est restaurée si possible.
     * </p>
     *
     * @param player personnage qui s'apprête à jouer en mode autonome.
     */
    public synchronized void ensureStandalone(Player player) {
        if (player == null) { // Bloc logique : aucune opération n'est réalisée sans cible valide.
            return;
        }
        Integer masterId = heroToMaster.remove(player.getId());
        if (masterId != null) { // Bloc logique : uniquement si le joueur était bien enregistré comme héros.
            HeroGroup group = groups.get(masterId);
            if (group != null) { // Bloc logique : évite les NullPointerException si le groupe est déjà supprimé.
                group.heroes.remove(player.getId());
                if (group.heroes.isEmpty()) { // Bloc logique : détruit le groupe si plus aucun héros n'est actif.
                    groups.remove(masterId);
                }
            }
            Player master = World.world.getPlayer(masterId); // Bloc logique : tente d'obtenir le maître connecté pour nettoyer l'interface.
            if (master != null) { // Bloc logique : assure l'arrêt du contrôle manuel côté client si nécessaire.
                releaseControlFor(master, player);
            } else { // Bloc logique : supprime tout résidu de contrôle si le maître est hors-ligne.
                activeHeroControl.remove(masterId);
            }
        }
        Party party = player.getParty();
        if (party != null) { // Bloc logique : quitte proprement le groupe pour éviter les doublons à la reconnexion.
            party.leave(player); // Effet de bord : notifie les autres membres de la sortie du héros.
        }
        player.setEsclave(false);
        restoreHeroState(player); // Replace le personnage sur une carte cohérente avant la reprise de session.
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
     * Retrouve le maître associé à un héros actif.
     * <p>
     * Exemple : {@code findMaster(hero)} renvoie le joueur contrôleur si le héros appartient encore au groupe.<br>
     * Invariant : la méthode ne crée aucune nouvelle association, elle se contente de retourner une référence existante.<br>
     * Effet de bord : aucun, le cache interne n'est pas modifié.
     * </p>
     *
     * @param hero personnage héros dont on souhaite obtenir le maître.
     * @return instance du maître ou {@code null} si inconnue.
     */
    public synchronized Player findMaster(Player hero) {
        if (hero == null) { // Bloc logique : impossible de trouver un maître sans héros fourni.
            return null;
        }
        Integer masterId = heroToMaster.get(hero.getId()); // Recherche directe du maître via l'index interne.
        if (masterId == null) { // Bloc logique : aucun lien maître -> héros n'est enregistré.
            return null;
        }
        Player master = World.world.getPlayer(masterId); // Tente de récupérer le maître actuellement connecté.
        if (master != null) { // Bloc logique : si le maître est en mémoire active, on peut le retourner immédiatement.
            return master;
        }
        HeroGroup group = groups.get(masterId); // Récupère la structure pour un éventuel maître non chargé.
        if (group == null) { // Bloc logique : le maître n'a plus de groupe actif.
            return null;
        }
        return hero.getAccount() != null // Bloc logique : tente un fallback via le compte si disponible.
                ? hero.getAccount().getPlayers().get(masterId)
                : null;
    }

    /**
     * Replace tous les héros virtuels du maître sur la carte à la fin d'un combat.
     * <p>
     * Exemple : après un combat, {@code restoreGroupAfterFight(master)} repositionne chaque héros sur la cellule du maître.<br>
     * Invariant : les héros conservent le statut d'esclave ({@link Player#isEsclave()}) et restent invisibles sur la carte.<br>
     * Effet de bord : réinitialise {@link Player#setFight(org.starloco.locos.fight.Fight)} à {@code null} pour les héros.
     * </p>
     *
     * @param master joueur maître dont les héros doivent redevenir virtuels.
     */
    public synchronized void restoreGroupAfterFight(Player master) {
        if (master == null) { // Bloc logique : rien à synchroniser sans maître défini.
            return;
        }
        if (isHero(master)) { // Bloc logique : un héros ne peut pas être traité comme maître ici.
            return;
        }
        HeroGroup group = groups.get(master.getId()); // Récupère le groupe des héros actifs pour ce maître.
        if (group == null || group.heroes.isEmpty()) { // Bloc logique : aucun héros actif, rien à faire.
            return;
        }
        GameMap map = master.getCurMap(); // Capture la carte actuelle du maître pour aligner les héros.
        GameCase cell = master.getCurCell(); // Capture la cellule du maître pour repositionner précisément chaque héros.
        for (Player hero : group.heroes.values()) { // Parcourt chaque héros actif du groupe.
            if (hero == null) { // Bloc logique : sécurise contre les références nulles.
                continue;
            }
            hero.setFight(null); // Effet de bord contrôlé : retire toute référence de combat résiduelle.
            hero.setVirtualPosition(map, cell); // Aligne la position virtuelle sur celle du maître pour rester invisible.
            hero.refreshLife(false); // Harmonise les points de vie affichés avec ceux calculés pendant le combat.
            hero.setReady(true); // Prépare le héros pour un prochain combat afin de ne pas bloquer la phase de placement.
        }
        releaseControlFor(master, null); // Restaure immédiatement l'interface du maître après la fin du combat.
        updatePositionsAfterJoin(master); // Relance les callbacks de synchronisation pour éviter les décalages ultérieurs.
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

    private void releaseControlFor(Player master, Player expectedHero) {
        if (master == null) { // Bloc logique : sans maître, aucun contrôle n'est à libérer.
            return;
        }
        Player active = activeHeroControl.get(master.getId());
        if (active == null) { // Bloc logique : rien à faire si aucun héros n'est enregistré.
            return;
        }
        if (expectedHero != null && active.getId() != expectedHero.getId()) { // Bloc logique : ignore si ce n'est pas le héros concerné.
            return;
        }
        activeHeroControl.remove(master.getId());
        GameClient client = master.getGameClient();
        if (client == null) { // Bloc logique : aucun envoi si le maître est déjà déconnecté.
            return;
        }
        client.resetControlledFighter(); // Bloc logique : restaure l'identifiant de contrôle par défaut côté session.
        Fight fight = master.getFight(); // Bloc logique : capture le combat courant pour resynchroniser l'affichage.
        if (fight != null) { // Bloc logique : envoie les paquets combat uniquement si le maître est toujours en affrontement.
            Fighter masterFighter = fight.getFighterByPerso(master); // Bloc logique : retrouve le combattant représentant le maître.
            if (masterFighter != null) { // Bloc logique : évite les paquets incohérents si le maître a quitté le combat.
                SocketManager.GAME_SEND_GIC_PACKET(master, masterFighter); // Bloc logique : remet le focus de l'UI sur le maître.
                SocketManager.GAME_SEND_GAS_PACKET(master, masterFighter.getId()); // Bloc logique : confirme l'identifiant du combattant actif.
                SocketManager.GAME_SEND_GTL_PACKET(master, fight); // Bloc logique : rafraîchit la timeline pour refléter les PA/PM du maître.
                SocketManager.GAME_SEND_GTM_PACKET(master, fight); // Bloc logique : répercute les PV et la position du maître.
            }
        }
        SocketManager.GAME_SEND_STATS_PACKET(client, master); // Réaffiche les caractéristiques du maître côté client.
        SocketManager.GAME_SEND_SPELL_LIST(client, master); // Restaure la barre de sorts d'origine.
        SocketManager.GAME_SEND_Ow_PACKET(client, master); // Recalcule les pods visibles pour éviter toute confusion.
    }

    private void rememberHeroPosition(Player hero) {
        if (hero == null) { // Bloc logique : aucun enregistrement sans cible réelle.
            return;
        }
        HeroSnapshot snapshot = HeroSnapshot.capture(hero);
        if (snapshot == null) { // Bloc logique : si la capture échoue (carte inconnue), mieux vaut purger tout résidu.
            heroSnapshots.remove(hero.getId());
        } else {
            heroSnapshots.put(hero.getId(), snapshot);
        }
    }

    private void restoreHeroState(Player hero) {
        if (hero == null) { // Bloc logique : sans personnage, aucune restauration n'est nécessaire.
            return;
        }
        HeroSnapshot snapshot = heroSnapshots.remove(hero.getId());
        if (snapshot != null && snapshot.apply(hero)) { // Bloc logique : si la restauration mémoire fonctionne, inutile d'aller plus loin.
            return;
        }
        reloadSavedPosition(hero); // Fallback : tente une reconstruction à partir des données persistées en base.
    }

    /**
     * Recalcule la position logique d'un héros à partir des données persistées.
     * <p>
     * Exemple : après un redémarrage serveur, {@code reloadSavedPosition(hero)} replace le personnage sur la dernière carte
     * enregistrée en base au lieu de le renvoyer à son zaap.<br>
     * Cas d'erreur fréquent : un enregistrement partiel (carte sans cellule) déclenche le repli sur le point de sauvegarde.
     * </p>
     *
     * @param hero personnage ciblé pour la restauration.
     */
    private void reloadSavedPosition(Player hero) {
        if (hero == null) { // Sécurise l'appel pour éviter toute NullPointerException inutile.
            return;
        }

        GameMap persistedMap = hero.getCurMap(); // Carte issue de la dernière sauvegarde complète (map + cell).
        GameCase persistedCell = hero.getCurCell(); // Cellule correspondante chargée depuis la base de données.

        if (persistedMap != null && persistedCell != null) { // Bloc logique : données complètes disponibles, inutile de chercher ailleurs.
            hero.setVirtualPosition(persistedMap, persistedCell); // Applique directement la paire carte/cellule persistée.
            return; // Étape finale : rien d'autre à faire si la position était déjà valide.
        }

        int[] saved = hero.getSavePositionIdentifiers(); // Bloc logique : repli sur le point de sauvegarde officiel (zaap) uniquement si nécessaire.
        if (saved == null) { // Protection supplémentaire : aucun repli possible sans identifiants valides.
            return;
        }

        GameMap map = World.world.getMap((short) saved[0]); // Carte associée au zaap : utile lors d'un défaut de données persistées.
        if (map == null) { // Bloc logique : abandonne si la carte de secours est devenue inaccessible.
            return;
        }

        GameCase cell = map.getCase(saved[1]); // Cellule attendue du zaap, généralement libre.
        if (cell == null) { // Bloc logique : filets de sécurité si la cellule d'origine n'existe plus.
            int fallbackCell = map.getRandomFreeCellId(); // Recherche une cellule libre pour éviter un crash au chargement.
            cell = fallbackCell >= 0 ? map.getCase(fallbackCell) : null; // Convertit l'identifiant en cellule exploitable.
        }

        hero.setVirtualPosition(map, cell); // Applique finalement le repli vers le point de sauvegarde.
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

    /**
     * Instantané minimal de l'état d'un héros pour le restaurer en mode joueur.
     */
    private static final class HeroSnapshot {
        private final short mapId;
        private final int cellId;
        private final int orientation;

        private HeroSnapshot(short mapId, int cellId, int orientation) {
            this.mapId = mapId;
            this.cellId = cellId;
            this.orientation = orientation;
        }

        static HeroSnapshot capture(Player hero) {
            if (hero == null) { // Bloc logique : rien à capturer sans référence valide.
                return null;
            }
            GameMap map = hero.getCurMap(); // Recherche la carte actuelle connue du personnage.
            if (map == null) { // Bloc logique : impossible d'enregistrer une carte inexistante.
                return null;
            }
            GameCase cell = hero.getCurCell(); // Capture la cellule si elle est définie.
            int storedCell = cell != null ? cell.getId() : -1; // Bloc logique : -1 signale une cellule inconnue.
            return new HeroSnapshot(map.getId(), storedCell, hero.get_orientation()); // Enregistre l'orientation pour un retour naturel.
        }

        boolean apply(Player hero) {
            if (hero == null) { // Bloc logique : sécurise l'appel si le personnage a été déchargé.
                return false;
            }
            GameMap map = World.world.getMap(this.mapId); // Retrouve la carte d'origine via l'identifiant sauvegardé.
            if (map == null) { // Bloc logique : abandonne si la carte n'existe plus côté serveur.
                return false;
            }
            GameCase cell = this.cellId >= 0 ? map.getCase(this.cellId) : null; // Bloc logique : tente d'abord la cellule exacte.
            if (cell == null) { // Bloc logique : déclenche un repli si la cellule a disparu ou était inconnue.
                int fallbackCell = map.getRandomFreeCellId(); // Propose une cellule libre pour éviter les crashs à la connexion.
                cell = fallbackCell >= 0 ? map.getCase(fallbackCell) : null; // Bloc logique : accepte le fallback uniquement s'il est exploitable.
            }
            hero.setVirtualPosition(map, cell); // Restaure la référence logique carte/cellule.
            hero.set_orientation(this.orientation); // Replace l'orientation initiale pour conserver la cohérence visuelle.
            return true;
        }
    }

    private static final class HeroGroup {
        private final Map<Integer, Player> heroes = new LinkedHashMap<>();
    }

    /** Notes pédagogiques
     * - Utiliser {@link #getActiveHeroes(Player)} avant un combat pour récupérer l'ordre d'apparition des héros.
     * - {@link #prepareTurnControl(Fight, Fighter)} déclenche désormais l'envoi ciblé des paquets GIC/GAS/GTL/GTM pour mettre à jour l'UI du maître.
     * - {@link #finalizeTurnControl(Fighter)} restitue automatiquement l'interface du maître en réinitialisant {@link GameClient#resetControlledFighter()}.
     * - {@link #resolveControlledActor(Player)} reste un filet de sécurité lorsque l'identifiant de combattant contrôlé n'est plus disponible.
     * - Les instantanés ({@link HeroSnapshot}) restent indispensables pour restaurer proprement les positions hors combat.
     * - {@link #releaseControlFor(Player, Player)} centralise la remise à zéro des paquets et du suivi de contrôle côté session.
     */
}
