package com.tobmistaketracker.detector;

import com.tobmistaketracker.TobBossNames;
import com.tobmistaketracker.TobMistake;
import com.tobmistaketracker.TobRaider;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.Hitsplat;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.Projectile;
import net.runelite.api.Prayer;
import net.runelite.api.Varbits;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.NpcSpawned;
import net.runelite.api.events.ProjectileMoved;
import net.runelite.client.eventbus.Subscribe;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Singleton
public class SotetsegMistakeDetector extends BaseTobMistakeDetector {

    private static final int RECENT_PRAYER_ORB_TICKS = 1;
    private static final int RECENT_DEATH_BALL_TICKS = 4;

    // Sotetseg projectile IDs
    private static final int DEATH_BALL = 1604;
    private static final int MAGIC_ORB = 1606;
    private static final int RANGE_ORB = 1607;

    private NPC sotetseg;
    private final Set<String> playersHitByMageOrbWithoutVengeance;
    private final Set<String> playersHitByRangeOrbWithoutPrayer;
    private final Set<String> playersDeadToBall;
    private final Map<String, Integer> recentMageOrbTickByPlayer;
    private final Map<String, Integer> recentRangeOrbTickByPlayer;
    private final Map<String, Integer> recentDeathBallTickByPlayer;
    private final Map<String, Integer> recentDeathBallHitsplatTickByPlayer;
    private final Map<String, Integer> recentDeathBallHitsplatCountByPlayer;
    private final Map<String, Integer> recentDeathBallDamageHitsplatCountByPlayer;
    private int recentDeathBallTick;

    @Inject
    public SotetsegMistakeDetector() {
        this.playersHitByMageOrbWithoutVengeance = new HashSet<>();
        this.playersHitByRangeOrbWithoutPrayer = new HashSet<>();
        this.playersDeadToBall = new HashSet<>();
        this.recentMageOrbTickByPlayer = new HashMap<>();
        this.recentRangeOrbTickByPlayer = new HashMap<>();
        this.recentDeathBallTickByPlayer = new HashMap<>();
        this.recentDeathBallHitsplatTickByPlayer = new HashMap<>();
        this.recentDeathBallHitsplatCountByPlayer = new HashMap<>();
        this.recentDeathBallDamageHitsplatCountByPlayer = new HashMap<>();
        this.recentDeathBallTick = -1;
    }

    @Override
    protected void computeDetectingMistakes() {
        if (!detectingMistakes && findSotetseg() != null) {
            detectingMistakes = true;
        }
    }

    @Override
    public void shutdown() {
        super.shutdown();
        sotetseg = null;
        playersHitByMageOrbWithoutVengeance.clear();
        playersHitByRangeOrbWithoutPrayer.clear();
        playersDeadToBall.clear();
        recentMageOrbTickByPlayer.clear();
        recentRangeOrbTickByPlayer.clear();
        recentDeathBallTickByPlayer.clear();
        recentDeathBallHitsplatTickByPlayer.clear();
        recentDeathBallHitsplatCountByPlayer.clear();
        recentDeathBallDamageHitsplatCountByPlayer.clear();
        recentDeathBallTick = -1;
    }

    @Override
    public List<TobMistake> detectMistakes(@NonNull TobRaider raider) {
        List<TobMistake> mistakes = new ArrayList<>();

        String raiderName = raider.getName();

        // Combine mage and range orb mistakes into a single Sotetseg Prayer mistake
        if (playersHitByMageOrbWithoutVengeance.contains(raiderName)
                || playersHitByRangeOrbWithoutPrayer.contains(raiderName)) {
            mistakes.add(TobMistake.SOTETSEG_PRAYER);
        }

        if (playersDeadToBall.contains(raiderName) && !hasDoubleDamageHitsplats(raiderName)) {
            mistakes.add(TobMistake.SOTETSEG_DEATH_BALL);
        }

        return mistakes;
    }

    @Override
    public void afterDetect() {
        playersHitByMageOrbWithoutVengeance.clear();
        playersHitByRangeOrbWithoutPrayer.clear();
        playersDeadToBall.clear();
        pruneOldOrbTicks(client.getTickCount());
        pruneOldDeathBallHitsplats(client.getTickCount());
    }

    @Subscribe
    public void onNpcSpawned(NpcSpawned event) {
        NPC npc = event.getNpc();
        if (npc.getName() != null && npc.getName().equals(TobBossNames.SOTETSEG)) {
            sotetseg = npc;
            detectingMistakes = true;
        }
    }

    @Subscribe
    public void onGameTick(GameTick event) {
        if (sotetseg == null) {
            sotetseg = findSotetseg();
        }
    }

    @Subscribe
    public void onActorDeath(ActorDeath event) {
        Actor actor = event.getActor();

        // Stop detecting when Sotetseg dies
        if (sotetseg != null && actor == sotetseg) {
            shutdown();
            return;
        }

        if (sotetseg == null) {
            return;
        }

        if (actor instanceof Player) {
            Player player = (Player) actor;
            String playerName = player.getName();

            if (playersDeadToBall.contains(playerName)) {
                return;
            }

            // Only count death if this player was targeted by the death ball or was on same
            // tile stacking
            Integer deathBallTick = recentDeathBallTickByPlayer.get(playerName);
            if (isRecent(deathBallTick, client.getTickCount(), RECENT_DEATH_BALL_TICKS)) {
                playersDeadToBall.add(playerName);
                log.debug("Player " + playerName + " died to death ball they were targeted by");
            }
        }
    }

    @Subscribe
    public void onProjectileMoved(ProjectileMoved event) {
        if (sotetseg == null) {
            return;
        }

        Projectile projectile = event.getProjectile();
        int projectileId = projectile.getId();
        int currentTick = client.getTickCount();

        if (projectileId == DEATH_BALL) {
            recentDeathBallTick = currentTick;
            markPrayerOrbTarget(projectile, recentDeathBallTickByPlayer, currentTick);
            log.debug("Death ball projectile detected");
        } else if (projectileId == MAGIC_ORB) {
            markPrayerOrbTarget(projectile, recentMageOrbTickByPlayer, currentTick);
        } else if (projectileId == RANGE_ORB) {
            markPrayerOrbTarget(projectile, recentRangeOrbTickByPlayer, currentTick);
        }
    }

    @Subscribe
    public void onHitsplatApplied(HitsplatApplied event) {
        if (sotetseg == null) {
            return;
        }

        Actor actor = event.getActor();
        if (actor instanceof Player) {
            Player player = (Player) actor;
            Hitsplat hitsplat = event.getHitsplat();
            int currentTick = client.getTickCount();
            String playerName = player.getName();
            Integer recentMageOrbTick = recentMageOrbTickByPlayer.get(playerName);
            Integer recentRangeOrbTick = recentRangeOrbTickByPlayer.get(playerName);
            Integer recentDeathBallTick = recentDeathBallTickByPlayer.get(playerName);

            if (isRecent(recentDeathBallTick, currentTick, RECENT_DEATH_BALL_TICKS)) {
                recordDeathBallHitsplat(playerName, hitsplat, currentTick);
            }

            if (isSameTick(recentMageOrbTick, currentTick)
                    && hitsplat.getAmount() > 0
                    && !client.isPrayerActive(Prayer.PROTECT_FROM_MAGIC)
                    && client.getVarbitValue(Varbits.VENGEANCE_ACTIVE) == 0) {
                playersHitByMageOrbWithoutVengeance.add(playerName);
                log.debug("Player " + playerName + " hit by mage orb without vengeance (damage: "
                        + hitsplat.getAmount() + ")");
            }

            if (isSameTick(recentRangeOrbTick, currentTick)
                    && hitsplat.getAmount() > 0
                    && !client.isPrayerActive(Prayer.PROTECT_FROM_MISSILES)
                    && client.getVarbitValue(Varbits.VENGEANCE_ACTIVE) == 0) {
                playersHitByRangeOrbWithoutPrayer.add(playerName);
                log.debug("Player " + playerName + " hit by range orb without prayer (damage: "
                        + hitsplat.getAmount() + ")");
            }
        }
    }

    private boolean isSameTick(Integer attackTick, int currentTick) {
        return attackTick != null && attackTick == currentTick;
    }

    private void markPrayerOrbTarget(Projectile projectile, Map<String, Integer> orbTicksByPlayer, int currentTick) {
        Actor target = projectile.getInteracting();
        if (!(target instanceof Player)) {
            return;
        }

        Player targetPlayer = (Player) target;
        String targetName = targetPlayer.getName();
        if (targetName == null) {
            return;
        }

        orbTicksByPlayer.put(targetName, currentTick);
    }

    private void recordDeathBallHitsplat(String playerName, Hitsplat hitsplat, int currentTick) {
        if (!isSameTick(recentDeathBallHitsplatTickByPlayer.get(playerName), currentTick)) {
            recentDeathBallHitsplatTickByPlayer.put(playerName, currentTick);
            recentDeathBallHitsplatCountByPlayer.put(playerName, 0);
            recentDeathBallDamageHitsplatCountByPlayer.put(playerName, 0);
        }

        recentDeathBallHitsplatCountByPlayer.put(playerName,
                recentDeathBallHitsplatCountByPlayer.get(playerName) + 1);

        if (hitsplat.getAmount() > 0) {
            recentDeathBallDamageHitsplatCountByPlayer.put(playerName,
                    recentDeathBallDamageHitsplatCountByPlayer.get(playerName) + 1);
        }
    }

    private boolean hasDoubleDamageHitsplats(String playerName) {
        Integer hitsplatCount = recentDeathBallHitsplatCountByPlayer.get(playerName);
        Integer damageHitsplatCount = recentDeathBallDamageHitsplatCountByPlayer.get(playerName);
        return hitsplatCount != null && damageHitsplatCount != null && hitsplatCount > 1 && damageHitsplatCount > 1;
    }

    private void pruneOldOrbTicks(int currentTick) {
        recentMageOrbTickByPlayer.entrySet()
                .removeIf(entry -> !isRecent(entry.getValue(), currentTick, RECENT_PRAYER_ORB_TICKS));
        recentRangeOrbTickByPlayer.entrySet()
                .removeIf(entry -> !isRecent(entry.getValue(), currentTick, RECENT_PRAYER_ORB_TICKS));
    }

    private void pruneOldDeathBallHitsplats(int currentTick) {
        recentDeathBallHitsplatTickByPlayer.entrySet()
                .removeIf(entry -> !isRecent(entry.getValue(), currentTick, RECENT_DEATH_BALL_TICKS));
        recentDeathBallHitsplatCountByPlayer.keySet().removeIf(player -> !recentDeathBallHitsplatTickByPlayer.containsKey(player));
        recentDeathBallDamageHitsplatCountByPlayer.keySet().removeIf(player -> !recentDeathBallHitsplatTickByPlayer.containsKey(player));
    }

    private NPC findSotetseg() {
        return client.getNpcs().stream()
                .filter(npc -> TobBossNames.SOTETSEG.equals(npc.getName()))
                .findFirst()
                .orElse(null);
    }

    private boolean isRecent(int attackTick) {
        return isRecent(attackTick, client.getTickCount(), RECENT_DEATH_BALL_TICKS);
    }

    private boolean isRecent(Integer attackTick, int currentTick, int maxRecentTicks) {
        return attackTick != null && attackTick > -1 && currentTick - attackTick <= maxRecentTicks;
    }
}
