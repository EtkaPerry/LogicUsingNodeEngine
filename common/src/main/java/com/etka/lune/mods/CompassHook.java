package com.etka.lune.mods;

import com.etka.lune.util.Lang;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Nature's Compass and Explorer's Compass, driven the way their own screens drive them.
 *
 * <p>Both mods work the same way. The player holds the compass, its screen sends the server one
 * small payload naming a biome or a structure, the server searches on its own time, and the
 * answer comes back written into the compass itself as item data: a state, and the x and z it
 * found. Lune does exactly that, minus the screen - it sends the same payload through vanilla's
 * custom-payload packet and reads the same components off the held stack. The search is the
 * mod's and the server's; Lune only asks and waits.</p>
 *
 * <p>The NeoForge and Fabric builds of each mod keep the same class names for everything used
 * here, which is what lets one hook serve both loaders. The one thing that differs, the packet's
 * second argument, is what {@link Kind} records.</p>
 */
public final class CompassHook extends ModHook {

    /** What the compass looks for, which decides the payload it is sent. */
    public enum Kind { BIOME, STRUCTURE }

    /** What the compass has to say about the last search. */
    public enum Answer { NONE, SEARCHING, FOUND, NOT_FOUND }

    /** A search that came back with a place. Compasses find columns, so there is no y. */
    public record Found(String targetId, int x, int z) {}

    public static final CompassHook NATURE = new CompassHook(Kind.BIOME, "Nature's Compass",
            "lune.mods.natures_compass", "naturescompass",
            "com.chaosthedude.naturescompass.NaturesCompass",
            "com.chaosthedude.naturescompass.item.NaturesCompassItem",
            "com.chaosthedude.naturescompass.network.SearchPacket",
            "com.chaosthedude.naturescompass.util.CompassState", "BIOME_ID");

    public static final CompassHook EXPLORER = new CompassHook(Kind.STRUCTURE, "Explorer's Compass",
            "lune.mods.explorers_compass", "explorerscompass",
            "com.chaosthedude.explorerscompass.ExplorersCompass",
            "com.chaosthedude.explorerscompass.item.ExplorersCompassItem",
            "com.chaosthedude.explorerscompass.network.SearchPacket",
            "com.chaosthedude.explorerscompass.util.CompassState", "STRUCTURE_ID");

    private final Kind kind;
    private final String hookName;
    private final String labelKey;
    private final String modId;
    private final String mainClass;
    private final String itemClassName;
    private final String packetClass;
    private final String stateClass;
    private final String targetField;

    private Class<?> itemClass;
    private Constructor<?> searchPacket;
    private DataComponentType<?> target;
    private DataComponentType<?> state;
    private DataComponentType<?> foundX;
    private DataComponentType<?> foundZ;
    private int searchingId;
    private int foundId;
    private int notFoundId;

    private CompassHook(Kind kind, String hookName, String labelKey, String modId, String mainClass,
                        String itemClassName, String packetClass, String stateClass,
                        String targetField) {
        this.kind = kind;
        this.hookName = hookName;
        this.labelKey = labelKey;
        this.modId = modId;
        this.mainClass = mainClass;
        this.itemClassName = itemClassName;
        this.packetClass = packetClass;
        this.stateClass = stateClass;
        this.targetField = targetField;
    }

    public Kind kind() {
        return kind;
    }

    /** The mod's name as the player knows it. */
    public String label() {
        return Lang.get(labelKey);
    }

    /** The compass item's own name, asked of the item; the mod's name when the item is not there. */
    public String itemName() {
        return BuiltInRegistries.ITEM.get(Identifier.fromNamespaceAndPath(modId, modId))
                .map(holder -> Lang.get(holder.value().getDescriptionId()))
                .orElseGet(this::label);
    }

    @Override
    protected String hookName() {
        return hookName;
    }

    @Override
    protected List<String> modIds() {
        return List.of(modId);
    }

    @Override
    protected void resolve() throws ReflectiveOperationException {
        Class<?> main = type(mainClass);
        target = component(main, targetField);
        state = component(main, "COMPASS_STATE");
        foundX = component(main, "FOUND_X");
        foundZ = component(main, "FOUND_Z");
        itemClass = type(itemClassName);
        Class<?> packet = type(packetClass);
        searchPacket = kind == Kind.BIOME
                ? packet.getConstructor(Identifier.class, BlockPos.class)
                : packet.getConstructor(Identifier.class, boolean.class);
        Class<?> states = type(stateClass);
        Method id = states.getMethod("getID");
        searchingId = stateId(states, id, "SEARCHING");
        foundId = stateId(states, id, "FOUND");
        notFoundId = stateId(states, id, "NOT_FOUND");
    }

    private static DataComponentType<?> component(Class<?> owner, String field)
            throws ReflectiveOperationException {
        return (DataComponentType<?>) owner.getField(field).get(null);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int stateId(Class<?> states, Method id, String name) throws ReflectiveOperationException {
        Object constant = Enum.valueOf((Class<? extends Enum>) states, name);
        return (Integer) id.invoke(constant);
    }

    /** Whether this stack is the compass, whatever it is called in the player's language. */
    public boolean isCompass(ItemStack stack) {
        return ready() && stack != null && !stack.isEmpty() && itemClass.isInstance(stack.getItem());
    }

    public Answer answer(ItemStack compass) {
        return call(() -> {
            Object raw = compass.get(state);
            if (!(raw instanceof Integer id)) {
                return Answer.NONE;
            }
            if (id == foundId) {
                return Answer.FOUND;
            }
            if (id == searchingId) {
                return Answer.SEARCHING;
            }
            return id == notFoundId ? Answer.NOT_FOUND : Answer.NONE;
        }, Answer.NONE);
    }

    /** The biome or structure the compass last searched for, as the mod stores it. */
    public String targetOf(ItemStack compass) {
        return call(() -> compass.get(target) instanceof String id ? id : "", "");
    }

    /** The place the compass found, or null when it has not found one. */
    public Found found(ItemStack compass) {
        return call(() -> {
            if (!(compass.get(foundX) instanceof Integer x) || !(compass.get(foundZ) instanceof Integer z)) {
                return null;
            }
            return new Found(targetOf(compass), x, z);
        }, null);
    }

    /**
     * Asks the server to search, exactly as the compass screen's Search button would. The compass
     * has to be in the player's hand when the request arrives, because that is where the mod
     * looks for the item to write the answer into.
     */
    public boolean search(Minecraft mc, String targetId, BlockPos from) {
        return call(() -> {
            if (mc.getConnection() == null) {
                return false;
            }
            Identifier id = Identifier.parse(targetId);
            Object payload = kind == Kind.BIOME
                    ? searchPacket.newInstance(id, from)
                    : searchPacket.newInstance(id, false);
            mc.getConnection().send(new ServerboundCustomPayloadPacket((CustomPacketPayload) payload));
            return true;
        }, false);
    }
}
