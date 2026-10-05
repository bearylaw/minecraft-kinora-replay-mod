package dev.kinora.mc.playback;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.world.clock.ClockNetworkState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.vehicle.VehicleEntity;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Render-time changes to a replay's scene: time of day, weather, and what is visible. They are
 * re-applied after every replay tick, so recorded time and weather packets never undo them, and
 * they never feed back into the recording.
 */
public final class SceneOverrides {
    /** Groups of entities that can be hidden together. */
    public enum Category { PLAYERS, RECORDED_PLAYER, HOSTILE, PASSIVE, ITEMS, PROJECTILES, VEHICLES, OTHER }

    public enum Weather { RECORDED, CLEAR, RAIN, THUNDER }

    private long timeOfDay = -1;
    private Weather weather = Weather.RECORDED;
    private final Set<Category> hiddenCategories = EnumSet.noneOf(Category.class);
    private final IntSet hiddenEntities = new IntOpenHashSet();
    private final IntSet highlighted = new IntOpenHashSet();
    private static final String HIDE_NAMES_FLAG = "hideNames";
    private boolean hideNametags;
    private boolean hideChat;
    /** Set by a render from the shot's world tracks; wins over the settings above. NaN: none. */
    private double renderTime = Double.NaN;
    private double renderWeather = Double.NaN;

    public void reset() {
        timeOfDay = -1;
        weather = Weather.RECORDED;
        hiddenCategories.clear();
        hiddenEntities.clear();
        highlighted.clear();
        hideNametags = dev.kinora.mc.ui.UiState.flag(HIDE_NAMES_FLAG);
        hideChat = false;
    }

    /** Fixes the time of day (in ticks, 0 = sunrise, 6000 = noon); negative follows the recording. */
    public void setTimeOfDay(long ticks) {
        timeOfDay = ticks;
        apply();
    }

    public long timeOfDay() {
        return timeOfDay;
    }

    public void setWeather(Weather weather) {
        this.weather = weather;
        apply();
    }

    public Weather weather() {
        return weather;
    }

    public void toggle(Category category) {
        if (!hiddenCategories.remove(category)) {
            hiddenCategories.add(category);
        }
    }

    public boolean hidden(Category category) {
        return hiddenCategories.contains(category);
    }

    public void toggleEntity(int id) {
        if (!hiddenEntities.remove(id)) {
            hiddenEntities.add(id);
        }
    }

    public void toggleHighlight(int id) {
        if (!highlighted.remove(id)) {
            highlighted.add(id);
        }
    }

    public boolean highlighted(Entity entity) {
        return highlighted.contains(entity.getId());
    }

    public boolean hideNametags() {
        return hideNametags;
    }

    /** Hides or shows the name tags over players and named mobs; remembered for later replays. */
    public void setHideNametags(boolean hide) {
        hideNametags = hide;
        dev.kinora.mc.ui.UiState.setFlag(HIDE_NAMES_FLAG, hide);
    }

    public void toggleNametags() {
        setHideNametags(!hideNametags);
    }

    public boolean hideChat() {
        return hideChat;
    }

    public void setHideChat(boolean hide) {
        hideChat = hide;
    }

    /** Whether an entity may be drawn. */
    public boolean visible(Entity entity, int recordedPlayerId) {
        if (hiddenEntities.contains(entity.getId())) {
            return false;
        }
        if (hiddenCategories.isEmpty()) {
            return true;
        }
        return !hiddenCategories.contains(categorize(entity, recordedPlayerId));
    }

    static Category categorize(Entity entity, int recordedPlayerId) {
        if (entity.getId() == recordedPlayerId) {
            return Category.RECORDED_PLAYER;
        }
        if (entity instanceof Player) {
            return Category.PLAYERS;
        }
        if (entity instanceof ItemEntity || entity instanceof ExperienceOrb) {
            return Category.ITEMS;
        }
        if (entity instanceof Projectile) {
            return Category.PROJECTILES;
        }
        if (entity instanceof VehicleEntity) {
            return Category.VEHICLES;
        }
        MobCategory mob = entity.getType().getCategory();
        if (mob == MobCategory.MONSTER) {
            return Category.HOSTILE;
        }
        if (mob != MobCategory.MISC) {
            return Category.PASSIVE;
        }
        return Category.OTHER;
    }

    /**
     * Time of day (ticks, fractional) and weather (0 clear, 1 rain, 2 thunder, fractional fades)
     * from a render's world tracks; NaN leaves that one to the replay settings.
     */
    public void setRenderWorld(double timeOfDay, double weather) {
        renderTime = timeOfDay;
        renderWeather = weather;
        apply();
    }

    /** Re-applies time and weather; called after every replay tick and on change. */
    public void apply() {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        ClientPacketListener listener = mc.getConnection();
        if (level == null || listener == null) {
            return;
        }
        double time = !Double.isNaN(renderTime) ? renderTime : timeOfDay;
        if (time >= 0) {
            level.dimensionType().defaultClock().ifPresent(clock -> {
                long day = level.getDefaultClockTime();
                long base = day - Math.floorMod(day, 24000L);
                long whole = (long) Math.floor(time);
                listener.clockManager().handleUpdates(level.getGameTime(),
                        Map.of(clock, new ClockNetworkState(base + whole, (float) (time - whole), 0.0f)));
                level.environmentAttributes().invalidateTickCache();
            });
        }
        if (!Double.isNaN(renderWeather)) {
            level.setRainLevel((float) Math.max(0, Math.min(1, renderWeather)));
            level.setThunderLevel((float) Math.max(0, Math.min(1, renderWeather - 1)));
            return;
        }
        switch (weather) {
            case RECORDED -> { }
            case CLEAR -> {
                level.setRainLevel(0);
                level.setThunderLevel(0);
            }
            case RAIN -> {
                level.setRainLevel(1);
                level.setThunderLevel(0);
            }
            case THUNDER -> {
                level.setRainLevel(1);
                level.setThunderLevel(1);
            }
        }
    }
}
