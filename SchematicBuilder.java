package com.schembuilder;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.schembuilder.schem.GridTransform;
import com.schembuilder.schem.ProgressTracker;
import com.schembuilder.schem.SchematicFile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.AnvilBlock;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.CraftingTableBlock;
import net.minecraft.world.level.block.DiodeBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Consumer;

/**
 * Plans and places a schematic.
 *
 * <p>Order of work: layers are kept sorted bottom-up. Every tick the builder looks at the layers the
 * player can reach and works on the LOWEST layer that has something placeable right now. A block that has
 * nothing to attach to yet (or whose support is missing) simply fails its placement search and is retried
 * a few ticks later, so it is automatically filled as soon as its support exists and the player is back
 * in range - that is the "skip it, come back down for it" behaviour.
 *
 * <p>Orientation: instead of guessing, every candidate (clicked block, face, hit point, yaw, pitch) is run
 * through the block's own getStateForPlacement and compared with the wanted state, so stairs, slabs, logs,
 * hoppers, doors, signs etc. come out right. Yaw/pitch are sent to the server in rotation packets, so the
 * player does not have to look the right way.
 */
public final class SchematicBuilder {
    private static final int MAX_ATTEMPTS = 4;
    private static final Direction[] SEARCH_ORDER = {
            Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, Direction.UP
    };
    /** Properties that placement decides. Everything else (shape, connections, waterlogged, powered...) is left to the game. */
    private static final Set<String> PLACEMENT_PROPS =
            Set.of("facing", "half", "axis", "hinge", "face", "attachment", "orientation", "rotation");

    static final class Target {
        final BlockPos pos;
        final BlockState want;
        final Item item;
        int attempts;
        long nextCheck;
        boolean done;

        Target(BlockPos pos, BlockState want, Item item) {
            this.pos = pos;
            this.want = want;
            this.item = item;
        }
    }

    private record Placement(BlockPos ref, Direction face, Vec3 hit, boolean spoofRotation, float yaw, float pitch) {}

    private SchematicFile file;
    private BlockState[] paletteStates;
    private final Set<String> unknownBlocks = new TreeSet<>();
    private final TreeMap<Integer, List<Target>> layers = new TreeMap<>();
    private final Map<Item, Integer> missing = new HashMap<>();

    private boolean running;
    private long tick;
    private long lastPlacedTick;
    private long lastReportTick;
    private int total;
    private int remaining;
    private int failed;
    private int unplaceable;
    private int failWrongBlock;
    private int failSurvive;
    private int failNoFace;

    private final ProgressTracker tracker = new ProgressTracker(30_000);
    /** Active ticks in a row in which nothing could be placed (used to detect "placed everything it can"). */
    private int idleTicks;
    /** Identifies the placement (file + origin + rotation + mirror) the current/last build used. */
    private String startKey;
    private String previewKey;
    private List<Target> previewList = List.of();

    // ------------------------------------------------------------------ loading

    public String load(Path path) throws IOException {
        SchematicFile f = SchematicFile.read(path);
        BlockState[] states = new BlockState[f.palette.length];
        unknownBlocks.clear();
        for (int i = 0; i < f.palette.length; i++) {
            try {
                states[i] = BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, f.palette[i], false).blockState();
            } catch (CommandSyntaxException e) {
                states[i] = null;
                unknownBlocks.add(f.palette[i]);
            }
        }
        this.file = f;
        this.paletteStates = states;
        this.running = false;
        this.layers.clear();
        this.startKey = null;
        this.previewKey = null;
        this.total = 0;
        this.remaining = 0;
        this.failed = 0;
        this.tracker.reset();
        String msg = String.format("Loaded %s (%d x %d x %d, %d palette entries).",
                f.name, f.width, f.height, f.length, f.palette.length);
        if (!unknownBlocks.isEmpty()) {
            msg += " Unknown blocks will be skipped: " + String.join(", ", unknownBlocks);
        }
        return msg;
    }

    public boolean hasSchematic() {
        return file != null;
    }

    public String loadedName() {
        return file == null ? null : file.name;
    }

    /** Footprint {width, height, length} after the current rotation. */
    public int[] outputSize() {
        if (file == null) return new int[]{0, 0, 0};
        return new int[]{
                GridTransform.outWidth(file.width, file.length, Config.rotationIndex),
                file.height,
                GridTransform.outLength(file.width, file.length, Config.rotationIndex)};
    }

    // ------------------------------------------------------------------ planning

    /** Start building with the schematic's minimum corner at {@code origin}. */
    public String start(BlockPos origin) {
        if (file == null) return "Load a schematic first with /schem load <file>.";
        layers.clear();
        total = 0;
        failed = 0;
        for (Target t : planTargets(origin)) {
            layers.computeIfAbsent(t.pos.getY(), k -> new ArrayList<>()).add(t);
            total++;
        }
        startKey = placementKey(origin);
        tracker.reset();
        idleTicks = 0;
        remaining = total;
        running = total > 0;
        lastPlacedTick = tick;
        lastReportTick = tick;
        return String.format("Building %d blocks across %d layers at %d %d %d (%d skipped: no item / unknown block). "
                        + "Stay within reach; I work bottom-up and come back to anything I had to skip.",
                total, layers.size(), origin.getX(), origin.getY(), origin.getZ(), unplaceable);
    }

    private String placementKey(BlockPos origin) {
        return file.name + "|" + origin.asLong() + "|" + Config.rotationIndex + "|" + Config.mirrorIndex;
    }

    /** Every block the schematic wants at {@code origin}, with the current rotation/mirror applied. */
    private List<Target> planTargets(BlockPos origin) {
        List<Target> out = new ArrayList<>();
        unplaceable = 0;
        // Rotation/mirror from the GUI: the block states are turned with the game's own rotate/mirror,
        // and the footprint is turned with the same convention (see GridTransform).
        Rotation rot = Config.rotation();
        Mirror mir = Config.mirror();
        BlockState[] transformed = new BlockState[paletteStates.length];
        for (int i = 0; i < transformed.length; i++) {
            transformed[i] = paletteStates[i] == null ? null : paletteStates[i].mirror(mir).rotate(rot);
        }
        final int sw = file.width;
        final int sl = file.length;
        for (int y = 0; y < file.height; y++) {
            for (int z = 0; z < file.length; z++) {
                for (int x = 0; x < file.width; x++) {
                    BlockState st = transformed[file.paletteIndexAt(x, y, z)];
                    if (st == null) {
                        unplaceable++;
                        continue;
                    }
                    if (!shouldBuild(st)) continue;
                    Item item = st.getBlock().asItem();
                    if (item == Items.AIR) {
                        unplaceable++;
                        continue;
                    }
                    int[] r = GridTransform.apply(x, z, sw, sl, Config.rotationIndex, Config.mirrorIndex);
                    BlockPos pos = origin.offset(r[0], y, r[1]);
                    out.add(new Target(pos, st, item));
                }
            }
        }
        return out;
    }

    private static boolean shouldBuild(BlockState st) {
        Block b = st.getBlock();
        if (st.isAir()) return false;
        if (b instanceof LiquidBlock) return false;
        if (b == Blocks.PISTON_HEAD || b == Blocks.MOVING_PISTON || b == Blocks.STRUCTURE_VOID) return false;
        // Second halves are created together with the first half by the item.
        if (st.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                && st.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) return false;
        if (st.hasProperty(BlockStateProperties.BED_PART)
                && st.getValue(BlockStateProperties.BED_PART) == BedPart.HEAD) return false;
        return true;
    }

    public void stop() {
        running = false;
    }

    public boolean isRunning() {
        return running;
    }

    public String status() {
        if (file == null) return "No schematic loaded.";
        return String.format("%s: %d/%d placed, %d remaining, %d failed. %s",
                file.name, total - remaining - failed, total, remaining, failed, running ? "Running." : "Idle.");
    }

    // ------------------------------------------------------------------ progress / materials / hologram data

    public int total() {
        return total;
    }

    public int placedCount() {
        return Math.max(0, total - remaining - failed);
    }

    public int failedCount() {
        return failed;
    }

    /** Whole-number percent; only reaches 100 when everything is placed. */
    public int percent() {
        return total == 0 ? 0 : (int) Math.floor(100.0 * placedCount() / total);
    }

    /** True when it is running, blocks are left, and nothing has been placeable for ~2 seconds of active time. */
    public boolean isStalled() {
        return running && remaining > 0 && idleTicks >= 40;
    }

    public String etaText() {
        if (!running) return total > 0 && remaining == 0 ? "done" : "not building";
        if (isStalled()) return "waiting (nothing placeable right now)";
        long eta = tracker.etaSeconds(remaining);
        return eta < 0 ? "calculating..." : "~" + ProgressTracker.format(eta) + " at current pace";
    }

    /** One line per item the build still needs, most-short items first. */
    public record Material(Item item, int need, int have) {
        public int missing() {
            return Math.max(0, need - have);
        }
    }

    public List<Material> materials(LocalPlayer player) {
        Map<Item, Integer> need = new HashMap<>();
        forEachHologramTarget(t -> {
            int n = (t.want.getBlock() instanceof SlabBlock && t.want.getValue(SlabBlock.TYPE) == SlabType.DOUBLE) ? 2 : 1;
            need.merge(t.item, n, Integer::sum);
        });
        Inventory inv = player.getInventory();
        List<Material> out = new ArrayList<>();
        for (Map.Entry<Item, Integer> e : need.entrySet()) {
            int have = 0;
            if (player.isCreative()) {
                have = Integer.MAX_VALUE;
            } else {
                for (int i = 0; i < 36; i++) {
                    ItemStack s = inv.getItem(i);
                    if (s.is(e.getKey())) have += s.getCount();
                }
            }
            out.add(new Material(e.getKey(), e.getValue(), have));
        }
        out.sort(Comparator.comparingInt((Material m) -> -m.missing()).thenComparingInt(m -> -m.need()));
        return out;
    }

    /**
     * Blocks that still have to be built: the remaining targets while a build is active, otherwise a preview of
     * the loaded schematic at the origin chosen in the screen (null-safe, nothing if no origin was chosen).
     */
    void forEachHologramTarget(Consumer<Target> out) {
        if (file == null) return;
        if (!layers.isEmpty()) {
            for (List<Target> list : layers.values()) {
                for (Target t : list) {
                    if (!t.done) out.accept(t);
                }
            }
            return;
        }
        if (!Config.originSet) return;
        BlockPos origin = new BlockPos(Config.originX, Config.originY, Config.originZ);
        String key = placementKey(origin);
        if (key.equals(startKey)) return; // this exact placement was already built
        if (!key.equals(previewKey)) {
            previewList = planTargets(origin);
            previewKey = key;
        }
        for (Target t : previewList) out.accept(t);
    }

    // ------------------------------------------------------------------ tick loop

    public void tick(Minecraft mc) {
        tick++;
        if (!running) return;
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null || mc.gameMode == null || mc.getConnection() == null) {
            running = false;
            return;
        }
        tracker.sample(System.currentTimeMillis(), placedCount());
        if (Config.actionBarProgress && tick % 10 == 0) {
            player.displayClientMessage(Component.literal(String.format("Schem %d%%  %d/%d  ETA %s",
                    percent(), placedCount(), total, etaText())), true);
        }
        // Our own screen may stay open (it shows progress); any other menu owns the inventory, so wait.
        if (mc.screen != null && !(mc.screen instanceof SchemScreen)) return;

        if (tick % 40 == 0) sweep(level);
        if (remaining <= 0) {
            running = false;
            Chat.say(String.format("Done. %d placed, %d failed.", total - failed, failed));
            return;
        }

        failWrongBlock = 0;
        failSurvive = 0;
        failNoFace = 0;
        missing.clear();

        Vec3 eye = player.getEyePosition();
        double reach = Config.reach;
        double scan = reach + 1.0;
        int budget = Config.blocksPerTick;
        int placedNow = 0;

        int lo = (int) Math.floor(eye.y - scan);
        int hi = (int) Math.ceil(eye.y + scan);
        NavigableMap<Integer, List<Target>> window = layers.subMap(lo, true, hi, true);

        for (Map.Entry<Integer, List<Target>> layer : window.entrySet()) {
            List<Target> cands = new ArrayList<>();
            for (Target t : layer.getValue()) {
                if (t.done || t.nextCheck > tick) continue;
                if (eye.distanceToSqr(t.pos.getCenter()) > scan * scan) continue;
                cands.add(t);
            }
            cands.sort(Comparator.comparingDouble(t -> eye.distanceToSqr(t.pos.getCenter())));

            for (Target t : cands) {
                if (placedNow >= budget) break;
                if (!level.hasChunkAt(t.pos)) {
                    t.nextCheck = tick + 20;
                    continue;
                }
                if (matches(t.want, level.getBlockState(t.pos))) {
                    finish(t, true);
                    continue;
                }
                if (t.attempts >= MAX_ATTEMPTS) {
                    finish(t, false);
                    Chat.say("Gave up on " + t.want.getBlock().getName().getString() + " at "
                            + t.pos.getX() + " " + t.pos.getY() + " " + t.pos.getZ());
                    continue;
                }
                if (!hasMaterial(player, t.item)) {
                    missing.merge(t.item, 1, Integer::sum);
                    t.nextCheck = tick + 20;
                    continue;
                }
                Placement p = findPlacement(mc, t, eye, reach);
                if (p == null) {
                    t.nextCheck = tick + 10; // retry later: support may exist by then
                    continue;
                }
                if (place(mc, t, p)) {
                    t.attempts++;
                    t.nextCheck = tick + 6; // give the server time to confirm before re-checking
                    placedNow++;
                    lastPlacedTick = tick;
                    idleTicks = 0;
                }
            }
            // Lowest layer with anything to do goes first; don't climb until it has nothing left this tick.
            if (placedNow > 0) break;
        }

        if (placedNow == 0) idleTicks++;
        if (placedNow == 0 && tick - lastPlacedTick >= 100 && tick - lastReportTick >= 200) {
            lastReportTick = tick;
            Chat.say(describeStuck(eye));
        }
    }

    private void finish(Target t, boolean ok) {
        if (t.done) return;
        t.done = true;
        remaining--;
        if (!ok) failed++;
    }

    /** Drop finished targets and verify placed ones that are loaded, wherever they are. */
    private void sweep(ClientLevel level) {
        int left = 0;
        var it = layers.entrySet().iterator();
        while (it.hasNext()) {
            List<Target> list = it.next().getValue();
            list.removeIf(t -> {
                if (!t.done && level.hasChunkAt(t.pos) && matches(t.want, level.getBlockState(t.pos))) {
                    finish(t, true);
                }
                return t.done;
            });
            left += list.size();
            if (list.isEmpty()) it.remove();
        }
        remaining = left;
    }

    private String describeStuck(Vec3 eye) {
        StringBuilder sb = new StringBuilder("Nothing placeable in reach. ");
        Target nearest = null;
        for (var layer : layers.entrySet()) {
            double best = Double.MAX_VALUE;
            for (Target t : layer.getValue()) {
                if (t.done) continue;
                double d = eye.distanceToSqr(t.pos.getCenter());
                if (d < best) {
                    best = d;
                    nearest = t;
                }
            }
            if (nearest != null) {
                sb.append(String.format("Lowest unfinished layer is Y=%d; nearest block there is at %d %d %d (%.0f blocks away). ",
                        layer.getKey(), nearest.pos.getX(), nearest.pos.getY(), nearest.pos.getZ(), Math.sqrt(best)));
                break;
            }
        }
        if (!missing.isEmpty()) {
            sb.append("Out of: ");
            missing.forEach((item, n) -> sb.append(item.getName(new ItemStack(item)).getString()).append(" x").append(n).append(", "));
        }
        if (failNoFace > 0) sb.append(failNoFace).append(" blocks have no clickable face yet (floating, or out of line of sight). ");
        if (failSurvive > 0) sb.append(failSurvive).append(" blocks can't exist there yet (missing support, or you are standing in the spot). ");
        if (failWrongBlock > 0) sb.append(failWrongBlock).append(" spots hold a different block (I don't break blocks). ");
        return sb.toString().trim();
    }

    // ------------------------------------------------------------------ placement search

    static boolean matches(BlockState want, BlockState have) {
        if (want.getBlock() != have.getBlock()) return false;
        for (Property<?> p : want.getProperties()) {
            String n = p.getName();
            boolean relevant = PLACEMENT_PROPS.contains(n) || (n.equals("type") && want.getBlock() instanceof SlabBlock);
            if (!relevant) continue;
            if (!have.hasProperty(p)) return false;
            if (!want.getValue(p).equals(have.getValue(p))) return false;
        }
        return true;
    }

    private Placement findPlacement(Minecraft mc, Target t, Vec3 eye, double reach) {
        ClientLevel level = mc.level;
        LocalPlayer player = mc.player;
        BlockPos pos = t.pos;
        BlockState cur = level.getBlockState(pos);
        BlockState want = t.want;
        Block block = want.getBlock();

        // Double slabs are built as a bottom slab first, then merged by clicking the slab itself.
        boolean merge = false;
        if (block instanceof SlabBlock && want.getValue(SlabBlock.TYPE) == SlabType.DOUBLE) {
            if (cur.getBlock() == block && cur.getValue(SlabBlock.TYPE) != SlabType.DOUBLE) {
                merge = true;
            } else {
                want = want.setValue(SlabBlock.TYPE, SlabType.BOTTOM);
            }
        }

        if (!merge && !cur.isAir() && !cur.canBeReplaced()) {
            failWrongBlock++;
            return null;
        }
        if (!want.canSurvive(level, pos)) {
            failSurvive++;
            return null;
        }
        if (!want.getCollisionShape(level, pos).isEmpty() && player.getBoundingBox().intersects(new AABB(pos))) {
            failSurvive++;
            return null;
        }

        List<BlockPos> refs = new ArrayList<>();
        List<Direction> faces = new ArrayList<>();
        if (merge) {
            refs.add(pos);
            faces.add(Direction.UP);
            refs.add(pos);
            faces.add(Direction.DOWN);
        } else {
            for (Direction dir : SEARCH_ORDER) {
                BlockPos r = pos.relative(dir);
                if (usableRef(level, r)) {
                    refs.add(r);
                    faces.add(dir.getOpposite());
                }
            }
        }
        if (refs.isEmpty()) {
            failNoFace++;
            return null;
        }

        boolean needsRot = false;
        boolean sixteen = false;
        for (Property<?> p : want.getProperties()) {
            String n = p.getName();
            if (n.equals("facing") || n.equals("orientation")) needsRot = true;
            if (n.equals("rotation")) {
                needsRot = true;
                sixteen = true;
            }
        }
        float curYaw = player.getYRot();
        float curPitch = player.getXRot();
        List<float[]> rots = new ArrayList<>();
        rots.add(new float[]{curYaw, curPitch, 0f}); // try the real rotation first: no spoofing needed
        if (needsRot) {
            if (sixteen) {
                for (int i = 0; i < 16; i++) rots.add(new float[]{i * 22.5f, 0f, 1f});
            } else {
                for (float yaw : new float[]{0f, 90f, 180f, 270f}) {
                    for (float pitch : new float[]{0f, 90f, -90f}) rots.add(new float[]{yaw, pitch, 1f});
                }
            }
        }

        ItemStack stack = new ItemStack(t.item);
        try {
            for (int i = 0; i < refs.size(); i++) {
                BlockPos ref = refs.get(i);
                Direction face = faces.get(i);
                for (Vec3 hit : hitPoints(ref, face)) {
                    if (eye.distanceTo(hit) > reach) continue;
                    if (!Config.exploit && !visible(level, player, eye, hit, ref, face)) continue;
                    BlockHitResult hr = new BlockHitResult(hit, face, ref, false);
                    for (float[] r : rots) {
                        player.setYRot(r[0]);
                        player.setXRot(r[1]);
                        BlockPlaceContext ctx = new BlockPlaceContext(level, player, InteractionHand.MAIN_HAND, stack, hr);
                        if (!ctx.canPlace() || !ctx.getClickedPos().equals(pos)) continue;
                        BlockState sim = block.getStateForPlacement(ctx);
                        if (sim != null && matches(want, sim)) {
                            return new Placement(ref, face, hit, r[2] > 0f, r[0], r[1]);
                        }
                    }
                }
            }
        } finally {
            player.setYRot(curYaw);
            player.setXRot(curPitch);
        }
        failNoFace++;
        return null;
    }

    private static boolean usableRef(ClientLevel level, BlockPos r) {
        if (!level.hasChunkAt(r)) return false;
        BlockState s = level.getBlockState(r);
        if (s.isAir() || !s.getFluidState().isEmpty() || s.canBeReplaced()) return false;
        Block b = s.getBlock();
        // Clicking these would open a GUI / toggle them instead of placing.
        return !(b instanceof BaseEntityBlock || b instanceof DoorBlock || b instanceof TrapDoorBlock
                || b instanceof FenceGateBlock || b instanceof ButtonBlock || b instanceof LeverBlock
                || b instanceof CraftingTableBlock || b instanceof BedBlock || b instanceof NoteBlock
                || b instanceof DiodeBlock || b instanceof AnvilBlock);
    }

    /** Points on a block face to click: centre first, then offsets that select the top/bottom half or door hinge side. */
    private static List<Vec3> hitPoints(BlockPos ref, Direction face) {
        Vec3 c = ref.getCenter().add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
        double o = Config.exploit ? 0.4 : 0.3;
        double[] offs = {0.0, o, -o};
        List<Vec3> out = new ArrayList<>();
        for (double a : offs) {
            for (double b : offs) {
                double dx = 0;
                double dy = 0;
                double dz = 0;
                switch (face.getAxis()) {
                    case X -> {
                        dy = a;
                        dz = b;
                    }
                    case Y -> {
                        dx = a;
                        dz = b;
                    }
                    case Z -> {
                        dx = a;
                        dy = b;
                    }
                }
                out.add(c.add(dx, dy, dz));
            }
        }
        return out;
    }

    /** True if a ray from the eyes reaches this exact face first (what vanilla aiming would require). */
    private static boolean visible(ClientLevel level, LocalPlayer player, Vec3 eye, Vec3 hit, BlockPos ref, Direction face) {
        Vec3 dir = hit.subtract(eye);
        if (dir.lengthSqr() < 1.0E-6) return false;
        Vec3 end = hit.add(dir.normalize().scale(0.1));
        BlockHitResult r = level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        return r.getType() == HitResult.Type.BLOCK && r.getBlockPos().equals(ref) && r.getDirection() == face;
    }

    // ------------------------------------------------------------------ inventory + packets

    private static boolean hasMaterial(LocalPlayer player, Item item) {
        if (player.isCreative()) return true;
        Inventory inv = player.getInventory();
        for (int i = 0; i < 36; i++) {
            if (inv.getItem(i).is(item)) return true;
        }
        return false;
    }

    /** Put {@code item} in the selected hotbar slot (spawning it in creative, swapping it up in survival). */
    private boolean selectItem(Minecraft mc, Item item) {
        LocalPlayer player = mc.player;
        Inventory inv = player.getInventory();
        for (int i = 0; i < 9; i++) {
            if (inv.getItem(i).is(item)) {
                inv.setSelectedSlot(i);
                return true;
            }
        }
        int slot = Math.max(0, Math.min(8, Config.hotbarSlot));
        if (player.isCreative()) {
            mc.gameMode.handleCreativeModeItemAdd(new ItemStack(item, 64), 36 + slot);
            inv.setSelectedSlot(slot);
            return true;
        }
        for (int i = 9; i < 36; i++) {
            if (inv.getItem(i).is(item)) {
                mc.gameMode.handleInventoryMouseClick(player.inventoryMenu.containerId, i, slot, ClickType.SWAP, player);
                inv.setSelectedSlot(slot);
                return true;
            }
        }
        return false;
    }

    private boolean place(Minecraft mc, Target t, Placement p) {
        LocalPlayer player = mc.player;
        if (!selectItem(mc, t.item)) return false;

        float oldYaw = player.getYRot();
        float oldPitch = player.getXRot();
        if (p.spoofRotation()) {
            // The server decides orientation from ITS copy of our rotation, so tell it before clicking.
            player.setYRot(p.yaw());
            player.setXRot(p.pitch());
            mc.getConnection().send(new ServerboundMovePlayerPacket.Rot(p.yaw(), p.pitch(), player.onGround(), player.horizontalCollision));
        }
        mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, new BlockHitResult(p.hit(), p.face(), p.ref(), false));
        player.swing(InteractionHand.MAIN_HAND);
        if (p.spoofRotation()) {
            player.setYRot(oldYaw);
            player.setXRot(oldPitch);
            mc.getConnection().send(new ServerboundMovePlayerPacket.Rot(oldYaw, oldPitch, player.onGround(), player.horizontalCollision));
        }
        return true;
    }
}
