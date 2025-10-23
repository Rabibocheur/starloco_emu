package org.starloco.locos.heros;

import org.starloco.locos.area.map.GameCase;
import org.starloco.locos.area.map.GameMap;
import org.starloco.locos.client.Player;
import org.starloco.locos.client.other.Party;
import org.starloco.locos.common.PathFinding;
import org.starloco.locos.common.SocketManager;
import org.starloco.locos.object.GameObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Gère l'apparition et la vie des héros associés à un maître.
 */
public final class HeroManager {

    private static final HeroManager INSTANCE = new HeroManager();
    private static final int MAX_HEROES = 3;

    private final Map<Integer, Map<Integer, HeroInstance>> heroesByMaster = new HashMap<>();
    private final Map<Integer, Integer> heroOwners = new HashMap<>();
    private final AtomicInteger cloneSequence = new AtomicInteger(-200_000);

    private HeroManager() {
    }

    /**
     * Fournit l'instance singleton du gestionnaire.
     *
     * @return gestionnaire global.
     */
    public static HeroManager getInstance() {
        return INSTANCE;
    }

    /**
     * Ajoute un héros pour le maître donné en vérifiant les contraintes métier.
     *
     * @param master   joueur actuellement connecté.
     * @param original personnage source à cloner.
     * @return résultat détaillé de l'opération.
     */
    public synchronized HeroOperationResult addHero(Player master, Player original) {
        if (master == null || master.getAccount() == null) {
            return HeroOperationResult.error("Maître invalide.");
        }
        if (original == null) {
            return HeroOperationResult.error("Personnage introuvable.");
        }
        if (master.getId() == original.getId()) {
            return HeroOperationResult.error("Le maître ne peut pas être héros.");
        }
        if (!master.getAccount().getPlayers().containsKey(original.getId())) {
            return HeroOperationResult.error("Le personnage doit appartenir au compte.");
        }
        if (original.isOnline()) {
            return HeroOperationResult.error("Ce personnage est déjà connecté.");
        }
        if (heroOwners.containsKey(original.getId())) {
            return HeroOperationResult.error("Ce personnage est déjà actif comme héros.");
        }
        Map<Integer, HeroInstance> currentHeroes = heroesByMaster.computeIfAbsent(master.getId(), id -> new HashMap<>());
        if (currentHeroes.size() >= MAX_HEROES) {
            return HeroOperationResult.error("Nombre maximum de héros atteint.");
        }

        GameMap map = master.getCurMap();
        GameCase baseCell = master.getCurCell();
        if (map == null || baseCell == null) {
            return HeroOperationResult.error("Carte ou cellule du maître introuvable.");
        }

        Player clone = createClone(master, original);
        GameCase targetCell = findPlacementCell(master);
        if (targetCell == null) {
            return HeroOperationResult.error("Aucune cellule disponible pour apparaître le héros.");
        }

        clone.setCurMap(map);
        clone.setCurCell(targetCell);
        map.addPlayer(clone);

        addHeroToParty(master, clone);

        currentHeroes.put(original.getId(), new HeroInstance(original, clone));
        heroOwners.put(original.getId(), master.getId());

        return HeroOperationResult.success("Héros " + original.getName() + " activé.");
    }

    /**
     * Supprime un héros spécifique du maître donné.
     *
     * @param master   joueur maître.
     * @param heroId   identifiant du personnage source.
     * @return résultat de suppression.
     */
    public synchronized HeroOperationResult removeHero(Player master, int heroId) {
        Map<Integer, HeroInstance> currentHeroes = heroesByMaster.get(master.getId());
        if (currentHeroes == null || !currentHeroes.containsKey(heroId)) {
            return HeroOperationResult.error("Aucun héros actif avec cet identifiant.");
        }
        HeroInstance instance = currentHeroes.remove(heroId);
        heroOwners.remove(heroId);
        dismissClone(master, instance.getClone());
        if (currentHeroes.isEmpty()) {
            heroesByMaster.remove(master.getId());
        }
        return HeroOperationResult.success("Héros " + instance.getOriginal().getName() + " retiré.");
    }

    /**
     * Supprime tous les héros actifs d'un maître, utilisé notamment lors d'une déconnexion.
     *
     * @param master maître concerné.
     */
    public synchronized void removeAllHeroes(Player master) {
        Map<Integer, HeroInstance> currentHeroes = heroesByMaster.remove(master.getId());
        if (currentHeroes == null) {
            return;
        }
        for (HeroInstance instance : currentHeroes.values()) {
            heroOwners.remove(instance.getOriginal().getId());
            dismissClone(master, instance.getClone());
        }
    }

    /**
     * Retourne la liste des héros actifs pour le maître.
     *
     * @param master maître ciblé.
     * @return liste non modifiable des héros actifs.
     */
    public synchronized List<HeroInstance> getHeroes(Player master) {
        Map<Integer, HeroInstance> currentHeroes = heroesByMaster.get(master.getId());
        if (currentHeroes == null) {
            return Collections.emptyList();
        }
        return new ArrayList<>(currentHeroes.values());
    }

    /**
     * Indique si un personnage est déjà utilisé comme héros actif.
     *
     * @param characterId identifiant du personnage.
     * @return {@code true} si actif, sinon {@code false}.
     */
    public synchronized boolean isHeroActive(int characterId) {
        return heroOwners.containsKey(characterId);
    }

    private Player createClone(Player master, Player original) {
        int cloneId = cloneSequence.decrementAndGet();
        Map<Integer, Integer> stats = new HashMap<>(original.getStats().getMap());
        Map<Integer, GameObject> stuff = new HashMap<>(original.GetequipedObjects());
        byte seeAlign = (byte) (original.is_showWings() ? 1 : 0);
        int mountId = original.getMount() != null ? original.getMount().getId() : -1;
        int percentLife = Math.min(100, Math.max(0, original.get_pdvper()));

        Player clone = new Player(cloneId, original.getName(), original.getGroupe() != null ? original.getGroupe().getId() : -1,
                original.getSexe(), original.getClasse(), original.getColor1(), original.getColor2(), original.getColor3(),
                original.getLevel(), original.get_size(), original.getGfxId(), stats, percentLife, seeAlign, mountId,
                original.getALvl(), original.get_align(), stuff);

        clone.setPrestige(original.getPrestige());
        clone.setEnergy(original.getEnergy());
        clone.setPdv(original.getCurPdv());
        clone.set_orientation(master.get_orientation());
        clone.setOnline(false);
        clone.setMountGiveXp(original.getMountXpGive());
        if (original.isOnMount() && clone.getMount() != null) {
            int savedEnergy = clone.getMount().getEnergy();
            clone.toogleOnMount();
            clone.getMount().setEnergy(savedEnergy);
        }
        return clone;
    }

    private GameCase findPlacementCell(Player master) {
        GameMap map = master.getCurMap();
        GameCase baseCell = master.getCurCell();
        if (map == null || baseCell == null) {
            return null;
        }
        char[] directions = {'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h'};
        for (char direction : directions) {
            int neighborId = PathFinding.GetCaseIDFromDirrection(baseCell.getId(), direction, map, false);
            if (neighborId == -1) {
                continue;
            }
            GameCase neighbor = map.getCase(neighborId);
            if (neighbor == null) {
                continue;
            }
            if (!neighbor.isWalkable(true, false, -1)) {
                continue;
            }
            if (!neighbor.getPlayers().isEmpty()) {
                continue;
            }
            return neighbor;
        }
        return baseCell;
    }

    private void addHeroToParty(Player master, Player clone) {
        Party party = master.getParty();
        if (party == null) {
            party = new Party(master, clone);
            party.setMaster(master);
            master.setParty(party);
            clone.setParty(party);
            if (master.getGameClient() != null) {
                SocketManager.GAME_SEND_GROUP_CREATE(master.getGameClient(), party);
                SocketManager.GAME_SEND_PL_PACKET(master.getGameClient(), party);
                SocketManager.GAME_SEND_ALL_PM_ADD_PACKET(master.getGameClient(), party);
            }
            SocketManager.GAME_SEND_PR_PACKET(master);
            return;
        }
        party.addPlayer(clone);
        clone.setParty(party);
        SocketManager.GAME_SEND_PM_ADD_PACKET_TO_GROUP(party, clone);
    }

    private void dismissClone(Player master, Player clone) {
        if (clone == null) {
            return;
        }
        Party party = master.getParty();
        if (party != null && party.getPlayers().contains(clone)) {
            party.leave(clone);
        }
        GameMap map = clone.getCurMap();
        if (map != null) {
            SocketManager.GAME_SEND_ERASE_ON_MAP_TO_MAP(map, clone.getId());
        }
        GameCase cell = clone.getCurCell();
        if (cell != null) {
            cell.removePlayer(clone);
        }
        clone.setCurCell(null);
        clone.setCurMap(null);
    }

    /**
     * Représente un héros actif avec ses deux facettes.
     */
    public static final class HeroInstance {
        private final Player original;
        private final Player clone;

        private HeroInstance(Player original, Player clone) {
            this.original = original;
            this.clone = clone;
        }

        /**
         * @return personnage source.
         */
        public Player getOriginal() {
            return original;
        }

        /**
         * @return clone instancié sur la carte.
         */
        public Player getClone() {
            return clone;
        }
    }

    /**
     * Encapsule le résultat des opérations de gestion des héros.
     */
    public static final class HeroOperationResult {
        private final boolean success;
        private final String message;

        private HeroOperationResult(boolean success, String message) {
            this.success = success;
            this.message = message;
        }

        /**
         * @return vrai en cas de réussite.
         */
        public boolean isSuccess() {
            return success;
        }

        /**
         * @return message associé à l'opération.
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
}
