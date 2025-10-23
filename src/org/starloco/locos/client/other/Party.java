package org.starloco.locos.client.other;

import ch.qos.logback.classic.Logger;
import org.slf4j.LoggerFactory;
import org.starloco.locos.area.map.GameCase;
import org.starloco.locos.area.map.GameMap;
import org.starloco.locos.client.Player;
import org.starloco.locos.common.PathFinding;
import org.starloco.locos.common.SocketManager;

import java.util.ArrayList;

/**
 * Gère un groupe de joueurs en conservant l'ordre de recrutement.
 */
public class Party {

    private static final Logger LOGGER = (Logger) LoggerFactory.getLogger(Party.class);

    public final Player chief;
    public final ArrayList<Player> players = new ArrayList<>();
    public Player master;

    /**
     * Crée un groupe avec un chef obligatoire et un second membre éventuel.
     *
     * @param p1 chef du groupe.
     * @param p2 membre à ajouter immédiatement, peut être {@code null}.
     */
    public Party(Player p1, Player p2) {
        this.chief = p1;
        registerPlayer(p1);
        registerPlayer(p2);
    }


    /**
     * @return joueurs enregistrés dans le groupe.
     */
    public ArrayList<Player> getPlayers() {
        return this.players;
    }

    public Player getChief() {
        return this.chief;
    }

    public boolean isChief(int id) {
        return this.chief.getId() == id;
    }
    
    public Player getMaster() {
        return master;
    }
    
    public void setMaster(Player master) {
        this.master = master;
    }

    /**
     * Ajoute un joueur si son identifiant n'est pas déjà présent.
     *
     * @param player membre potentiel.
     * @return {@code true} si l'ajout a été effectué.
     */
    public boolean addPlayer(Player player) {
        return registerPlayer(player);
    }

    public void leave(Player player) {
        if (!this.players.contains(player)) return;

        player.follow = null;
        player.follower.clear();
        player.setParty(null);
        this.players.remove(player);

        for(Player member : this.players) {
            if(member.follow == player) member.follow = null;
            if(member.follower.containsKey(player.getId())) member.follower.remove(player.getId());
        }

        if (this.players.size() == 1) {
            Player remaining = this.players.get(0);
            remaining.setParty(null);
            if (remaining.getAccount() != null && remaining.getAccount().getGameClient() != null) {
                SocketManager.GAME_SEND_PV_PACKET(remaining.getGameClient(), "");
            }
        } else {
            for (Player member : this.players) {
                if (member.getAccount() != null && member.getAccount().getGameClient() != null) {
                    SocketManager.GAME_SEND_PM_DEL_PACKET_TO_GROUP(this, player.getId());
                }
            }
        }
    }
    
    public void moveAllPlayersToMaster(final GameCase cell) {
        if(this.master != null) {
            this.players.stream().filter((follower1) -> isWithTheMaster(follower1, false)).forEach(follower -> follower.setBlockMovement(true));
            this.players.stream().filter((follower1) -> isWithTheMaster(follower1, false)).forEach(follower -> {
                try {
                    final GameCase newCell = cell != null ? cell : this.master.getCurCell();
                    String path = PathFinding.getShortestStringPathBetween(this.master.getCurMap(), follower.getCurCell().getId(), newCell.getId(), 0);
                    if (path != null) {
                            follower.getCurCell().removePlayer(follower);
                            follower.setCurCell(newCell);
                            follower.getCurCell().addPlayer(follower);

                        SocketManager.GAME_SEND_GA_PACKET_TO_MAP(follower.getCurMap(), "0", 1, String.valueOf(follower.getId()), path);
                    }
                } catch (Exception ignored) {}
            });
            this.players.stream().filter((follower1) -> isWithTheMaster(follower1, false)).forEach(follower -> follower.setBlockMovement(false));
        }
    }
    
    public boolean isWithTheMaster(Player follower, boolean inFight) {
        return follower != null && !follower.getName().equals(this.master.getName()) &&  this.players.contains(follower) && follower.getGameClient()
                != null && this.master.getCurMap().getId() == follower.getCurMap().getId() && (inFight ? follower.getFight() == this.master.getFight() : follower.getFight() == null);
    }
    
    public void teleportAllEsclaves()
        {
        for (Player follower : players)
                {
                if(follower.getExchangeAction() != null)
		{
            follower.sendMessage("Vous n'avez pas pu être téléporté car vous êtes occupé.");
            master.sendMessage("Le joueur "+follower.getName()+" est occupé et n'a pas pu être téléporté.");
            continue;
		}
		if(master.getCurMap().getId() != follower.getCurMap().getId() && GameMap.IsInDj(master.getCurMap()))
			follower.teleport(master.getCurMap().getId(), master.getCurCell().getId());
		}

        }

    private boolean registerPlayer(Player player) {
        if (player == null) {
            return false;
        }
        boolean alreadyPresent = this.players.stream().anyMatch(member -> member.getId() == player.getId());
        if (alreadyPresent) {
            LOGGER.debug("Ignoré: joueur {} ({}) déjà présent dans le groupe du chef {}.",
                    player.getName(), player.getId(), this.chief != null ? this.chief.getId() : -1);
            return false;
        }
        this.players.add(player);
        LOGGER.info("Ajout du joueur {} ({}) dans le groupe du chef {}.",
                player.getName(), player.getId(), this.chief != null ? this.chief.getId() : -1);
        return true;
    }

}
