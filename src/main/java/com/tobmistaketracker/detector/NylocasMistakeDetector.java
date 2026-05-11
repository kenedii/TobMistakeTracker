package com.tobmistaketracker.detector;

import com.tobmistaketracker.TobBossNames;
import com.tobmistaketracker.TobMistake;
import com.tobmistaketracker.TobRaider;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.HeadIcon;
import net.runelite.api.Hitsplat;
import net.runelite.api.HitsplatID;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.Prayer;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.NpcSpawned;
import net.runelite.client.eventbus.Subscribe;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@Singleton
public class NylocasMistakeDetector extends BaseTobMistakeDetector {

    private static final Set<Integer> NYLO_BOSS_IDS = Set.of(
            10786, 10787, 10788, 10789,
            8355, 8356, 8357,
            10804, 10805, 10806,
            10807, 10808, 10809, 10810);

    private static final Set<Integer> NYLO_MELEE_IDS = Set.of(8355, 10804, 10808);
    private static final Set<Integer> NYLO_MAGIC_IDS = Set.of(8356, 10805, 10809);
    private static final Set<Integer> NYLO_RANGE_IDS = Set.of(8357, 10806, 10810);

    private static final List<String> MELEE_WEAPONS = Arrays.asList(
            "Abyssal whip", "Abyssal tentacle", "Dual macuahuitl", "Osmumten's fang",
            "Scythe of vitur", "Dragon scimitar", "Blade of saeldor", "Noxious halberd",
            "Zombie axe", "Inquisitor's mace", "Dragon claws", "Bandos godsword",
            "Crystal halberd", "Burning claws", "Dragon halberd", "Voidwaker",
            "Dragon warhammer", "Sulphur blades", "Glacial temotli", "Guthan's spear",
            "Soulreaper axe", "Ghrazi rapier", "Dharok's greataxe", "Rune claws",
            "Arkan blade", "Rune scimitar", "3rd age longsword", "Abyssal dagger",
            "Dragon dagger", "Swift blade", "Goblin paint cannon", "Ham joint",
            "Zamorakian spear", "Torag's hammers");

    private static final List<String> RANGED_WEAPONS = Arrays.asList(
            "Toxic blowpipe", "Eclipse atlatl", "Rosewood blowpipe", "Magic shortbow(i)",
            "Magic shortbow", "Dorgeshuun crossbow", "Karil's crossbow", "Zaryte crossbow",
            "Twisted bow", "Bow of faerdhinen", "Camphor blowpipe", "Dragon hunter crossbow",
            "Dragon crossbow", "Tonalztics of ralos", "Armadyl crossbow",
            "Hunters' sunlight crossbow", "Heavy ballista", "Dark bow", "Rune crossbow");

    private static final List<String> MAGIC_WEAPONS = Arrays.asList(
            "Eye of ayak", "Toxic staff of the dead", "Staff of the dead",
            "Tumeken's shadow", "Kodai wand", "Nightmare staff", "Sanguinesti staff",
            "Toxic trident of the swamp", "Trident of the swamp", "Ancient sceptre",
            "Warped sceptre", "Iban's staff", "Accursed sceptre", "Thammaron's sceptre",
            "Staff of water", "Water battlestaff", "Ahrim's staff", "Blue moon spear");

    private static final int WEAPON_SLOT = 3;

    private final Set<String> playersHitByWrongNyloPrayer;
    private final Set<String> playersHitByWrongNyloHeal;
    private final Set<String> nyloHealCandidates;
    private NPC nylocasBoss;
    private int recentNyloHealTick;

    @Inject
    public NylocasMistakeDetector() {
        this.playersHitByWrongNyloPrayer = new HashSet<>();
        this.playersHitByWrongNyloHeal = new HashSet<>();
        this.nyloHealCandidates = new HashSet<>();
        this.recentNyloHealTick = -1;
    }

    @Override
    protected void computeDetectingMistakes() {
        if (!detectingMistakes) {
            nylocasBoss = findNylocasBoss();
            detectingMistakes = nylocasBoss != null;
        }
    }

    @Override
    public void shutdown() {
        super.shutdown();
        playersHitByWrongNyloPrayer.clear();
        playersHitByWrongNyloHeal.clear();
        nyloHealCandidates.clear();
        nylocasBoss = null;
        recentNyloHealTick = -1;
    }

    @Override
    public List<TobMistake> detectMistakes(@NonNull TobRaider raider) {
        List<TobMistake> mistakes = new ArrayList<>();

        if (playersHitByWrongNyloPrayer.contains(raider.getName())) {
            mistakes.add(TobMistake.NYLOCAS_PRAYER);
        }

        if (playersHitByWrongNyloHeal.contains(raider.getName())) {
            mistakes.add(TobMistake.NYLOCAS_HEAL);
        }

        return mistakes;
    }

    @Override
    public void afterDetect() {
        playersHitByWrongNyloPrayer.clear();
        playersHitByWrongNyloHeal.clear();
        nyloHealCandidates.clear();
        recentNyloHealTick = -1;
    }

    @Subscribe
    public void onNpcSpawned(NpcSpawned event) {
        NPC npc = event.getNpc();
        if (isNylocasBoss(npc)) {
            nylocasBoss = npc;
            detectingMistakes = true;
        }
    }

    @Subscribe
    public void onActorDeath(ActorDeath event) {
        Actor actor = event.getActor();

        if (!(actor instanceof NPC)) {
            return;
        }

        NPC npc = (NPC) actor;
        if (!isNylocasBoss(npc)) {
            return;
        }

        nylocasBoss = findNylocasBoss();
        if (nylocasBoss == null) {
            shutdown();
        }
    }

    @Subscribe
    public void onGameTick(GameTick event) {
        if (!detectingMistakes) {
            return;
        }

        if (nylocasBoss == null || nylocasBoss.isDead()) {
            nylocasBoss = findNylocasBoss();
            if (nylocasBoss == null || nylocasBoss.isDead()) {
                shutdown();
            }
        }
    }

    @Subscribe
    public void onHitsplatApplied(HitsplatApplied event) {
        if (!detectingMistakes || nylocasBoss == null) {
            return;
        }

        int currentTick = client.getTickCount();
        Hitsplat hitsplat = event.getHitsplat();

        if (event.getActor() == nylocasBoss) {
            if (isHealHitsplat(hitsplat)) {
                recentNyloHealTick = currentTick;
                playersHitByWrongNyloHeal.addAll(nyloHealCandidates);
                log.debug("Nylocas heal hit detected for boss id {}", nylocasBoss.getId());
            }

            return;
        }

        Actor actor = event.getActor();
        if (!(actor instanceof Player)) {
            return;
        }

        Player player = (Player) actor;
        if (player.getName() == null || !plugin.isLoadedRaider(player.getName())) {
            return;
        }

        if (hitsplat.getAmount() <= 0) {
            return;
        }

        if (player.getInteracting() == nylocasBoss) {
            nyloHealCandidates.add(player.getName());

            if (isSameTick(recentNyloHealTick, currentTick)) {
                playersHitByWrongNyloHeal.add(player.getName());
                log.debug("Nylocas wrong style heal detected for {} (boss id {})", player.getName(),
                        nylocasBoss.getId());
            }

            // Check if the player's weapon is in the whitelist for the boss's current form
            if (!isWeaponInWhitelist(player, nylocasBoss.getId())) {
                playersHitByWrongNyloHeal.add(player.getName());
                log.debug("Nylocas wrong style weapon detected for {} (boss id {})", player.getName(),
                        nylocasBoss.getId());
            }
        }

        Prayer expectedPrayer = expectedPrayerForNylocasId(nylocasBoss.getId());
        if (expectedPrayer == null) {
            return;
        }

        HeadIcon overheadIcon = player.getOverheadIcon();
        if (overheadIcon != null && !isCorrectOverheadPrayer(overheadIcon, expectedPrayer)) {
            playersHitByWrongNyloPrayer.add(player.getName());
            log.debug("Nylocas wrong prayer hit detected for {} (boss id {})", player.getName(), nylocasBoss.getId());
        }
    }

    private NPC findNylocasBoss() {
        return client.getNpcs().stream()
                .filter(this::isNylocasBoss)
                .findFirst()
                .orElse(null);
    }

    private boolean isNylocasBoss(NPC npc) {
        if (npc == null) {
            return false;
        }

        return NYLO_BOSS_IDS.contains(npc.getId())
                || TobBossNames.NYLO_BOSS.equals(npc.getName())
                || TobBossNames.NYLO_DEMI_BOSS.equals(npc.getName());
    }

    private Prayer expectedPrayerForNylocasId(int nylocasId) {
        if (NYLO_MELEE_IDS.contains(nylocasId)) {
            return Prayer.PROTECT_FROM_MELEE;
        }

        if (NYLO_MAGIC_IDS.contains(nylocasId)) {
            return Prayer.PROTECT_FROM_MAGIC;
        }

        if (NYLO_RANGE_IDS.contains(nylocasId)) {
            return Prayer.PROTECT_FROM_MISSILES;
        }

        return null;
    }

    private boolean isCorrectOverheadPrayer(HeadIcon headIcon, Prayer expectedPrayer) {
        if (headIcon == null) {
            return false;
        }

        switch (headIcon) {
            case MELEE:
                return expectedPrayer == Prayer.PROTECT_FROM_MELEE;
            case MAGIC:
                return expectedPrayer == Prayer.PROTECT_FROM_MAGIC;
            case RANGED:
                return expectedPrayer == Prayer.PROTECT_FROM_MISSILES;
            default:
                return false;
        }
    }

    private boolean isHealHitsplat(Hitsplat hitsplat) {
        return hitsplat.getHitsplatType() == HitsplatID.HEAL;
    }

    private boolean isSameTick(int attackTick, int currentTick) {
        return attackTick > -1 && attackTick == currentTick;
    }

    private boolean isWeaponInWhitelist(Player player, int bossId) {
        ItemContainer equipment = client.getItemContainer(InventoryID.EQUIPMENT);
        if (equipment == null) {
            return false;
        }

        Item weaponItem = equipment.getItem(WEAPON_SLOT);
        String weaponName;
        if (weaponItem == null) {
            weaponName = "no weapon";
        } else {
            weaponName = client.getItemDefinition(weaponItem.getId()).getName().toLowerCase();
        }

        List<String> whitelist = getWhitelistForBossForm(bossId);
        if (whitelist == null) {
            return false;
        }

        for (String allowedWeapon : whitelist) {
            if (weaponName.contains(allowedWeapon.toLowerCase())) {
                return true;
            }
        }

        return false;
    }

    private List<String> getWhitelistForBossForm(int bossId) {
        if (NYLO_MELEE_IDS.contains(bossId)) {
            return MELEE_WEAPONS;
        }

        if (NYLO_MAGIC_IDS.contains(bossId)) {
            return MAGIC_WEAPONS;
        }

        if (NYLO_RANGE_IDS.contains(bossId)) {
            return RANGED_WEAPONS;
        }

        return null;
    }
}
