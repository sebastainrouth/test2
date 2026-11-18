package com.schembuilder;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * The Right Shift screen. Everything the /schem chat commands do is here as buttons, switches and sliders,
 * plus a progress bar with ETA and a list of the materials the build still needs.
 * Escape closes it. Settings live in {@link Config}, so they survive closing and reopening.
 */
public class SchemScreen extends Screen {
    private static final String[] ROTATION = {"None", "90 clockwise", "180", "90 counter-clockwise"};
    private static final String[] MIRROR = {"None", "Flip north/south", "Flip east/west"};
    private static final int W = 300;
    private static final int HALF = 146;
    private static final int PANEL_W = 210;

    private List<String> files = List.of();
    private int fileIndex;
    private EditBox xBox;
    private EditBox yBox;
    private EditBox zBox;
    private String message = "";

    private int top;
    private int colLeft;
    private int textTop;

    private List<SchematicBuilder.Material> materials = List.of();
    private long materialsAt;

    public SchemScreen() {
        super(Component.literal("Schem Builder"));
    }

    @Override
    public boolean isPauseScreen() {
        return false; // keep the world (and the build) running while the screen is open
    }

    @Override
    protected void init() {
        files = SchemCommands.listFiles();
        fileIndex = Math.max(0, files.indexOf(Config.selectedFile));
        if (!files.isEmpty()) Config.selectedFile = files.get(fileIndex);

        top = Math.max(4, (this.height - 300) / 2);
        colLeft = Math.max(4, (this.width - (W + 12 + PANEL_W)) / 2);
        int left = colLeft;
        int y = top + 14;

        // --- file picker: [<] [name] [>] [Load]
        addRenderableWidget(Button.builder(Component.literal("<"), b -> cycleFile(-1)).bounds(left, y, 20, 20).build());
        addRenderableWidget(Button.builder(fileLabel(), b -> cycleFile(1)).bounds(left + 24, y, 130, 20).build());
        addRenderableWidget(Button.builder(Component.literal(">"), b -> cycleFile(1)).bounds(left + 158, y, 20, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Load"), b -> loadSelected()).bounds(left + 182, y, 118, 20).build());
        y += 28;

        // --- origin (the hologram follows these numbers)
        if (!Config.originSet) {
            BlockPos p = Minecraft.getInstance().player == null ? BlockPos.ZERO : Minecraft.getInstance().player.blockPosition();
            Config.originX = p.getX();
            Config.originY = p.getY();
            Config.originZ = p.getZ();
            Config.originSet = true;
        }
        xBox = coordBox(left + 44, y, Config.originX);
        yBox = coordBox(left + 110, y, Config.originY);
        zBox = coordBox(left + 176, y, Config.originZ);
        addRenderableWidget(xBox);
        addRenderableWidget(yBox);
        addRenderableWidget(zBox);
        addRenderableWidget(Button.builder(Component.literal("My position"), b -> {
            BlockPos q = Minecraft.getInstance().player.blockPosition();
            xBox.setValue(Integer.toString(q.getX()));
            yBox.setValue(Integer.toString(q.getY()));
            zBox.setValue(Integer.toString(q.getZ()));
        }).bounds(left + 242, y, 58, 20).build());
        y += 28;

        // --- rotation / mirror
        addRenderableWidget(Button.builder(rotationLabel(), b -> {
            Config.rotationIndex = (Config.rotationIndex + 1) % 4;
            b.setMessage(rotationLabel());
        }).bounds(left, y, HALF, 20).build());
        addRenderableWidget(Button.builder(mirrorLabel(), b -> {
            Config.mirrorIndex = (Config.mirrorIndex + 1) % 3;
            b.setMessage(mirrorLabel());
        }).bounds(left + 154, y, HALF, 20).build());
        y += 24;

        // --- exploit / hologram switches
        addRenderableWidget(Button.builder(exploitLabel(), b -> {
            Config.setExploit(!Config.exploit);
            rebuildWidgets(); // the reach slider's range depends on this switch
        }).bounds(left, y, HALF, 20).build());
        addRenderableWidget(Button.builder(hologramLabel(), b -> {
            if (Config.hologramAvailable) Config.hologram = !Config.hologram;
            b.setMessage(hologramLabel());
        }).bounds(left + 154, y, HALF, 20).build());
        y += 24;

        // --- sliders
        addRenderableWidget(new ReachSlider(left, y));
        addRenderableWidget(new RangeSlider(left + 154, y));
        y += 24;
        addRenderableWidget(new SpeedSlider(left, y));
        addRenderableWidget(new SlotSlider(left + 154, y));
        y += 28;

        // --- start / stop
        addRenderableWidget(Button.builder(Component.literal("Start building"), b -> startBuild()).bounds(left, y, HALF, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Stop"), b -> {
            SchemClient.BUILDER.stop();
            message = "Stopped.";
        }).bounds(left + 154, y, HALF, 20).build());
        y += 24;

        // --- action bar progress / close
        addRenderableWidget(Button.builder(actionBarLabel(), b -> {
            Config.actionBarProgress = !Config.actionBarProgress;
            b.setMessage(actionBarLabel());
        }).bounds(left, y, HALF, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose()).bounds(left + 154, y, HALF, 20).build());
        y += 28;

        textTop = y;
        materialsAt = 0;
    }

    // ------------------------------------------------------------------ actions

    private void cycleFile(int dir) {
        if (files.isEmpty()) return;
        fileIndex = Math.floorMod(fileIndex + dir, files.size());
        Config.selectedFile = files.get(fileIndex);
        rebuildWidgets();
    }

    private Component fileLabel() {
        if (files.isEmpty()) return Component.literal("No files found");
        String n = files.get(fileIndex);
        return Component.literal(n.length() > 20 ? n.substring(0, 19) + "~" : n);
    }

    private void loadSelected() {
        if (files.isEmpty()) {
            message = "Put .schem or .litematic files in .minecraft/schematics, then reopen.";
            return;
        }
        Path dir = SchemCommands.schematicDir().normalize();
        Path p = dir.resolve(files.get(fileIndex)).normalize();
        if (!p.startsWith(dir)) return;
        try {
            message = SchemClient.BUILDER.load(p);
            materialsAt = 0;
        } catch (IOException e) {
            message = "Could not load: " + e.getMessage();
        }
    }

    private void startBuild() {
        if (!SchemClient.BUILDER.hasSchematic()) {
            message = "Load a schematic first.";
            return;
        }
        BlockPos origin = parseOrigin();
        if (origin == null) {
            message = "Origin X, Y and Z must be whole numbers.";
            return;
        }
        message = SchemClient.BUILDER.start(origin);
        materialsAt = 0;
    }

    /** Copies the origin boxes into Config as they are typed so the hologram follows them. */
    private void syncOrigin() {
        BlockPos p = parseOrigin();
        if (p == null) return;
        Config.originX = p.getX();
        Config.originY = p.getY();
        Config.originZ = p.getZ();
        Config.originSet = true;
        materialsAt = 0;
    }

    private BlockPos parseOrigin() {
        if (xBox == null || yBox == null || zBox == null) return null;
        try {
            return new BlockPos(Integer.parseInt(xBox.getValue().trim()),
                    Integer.parseInt(yBox.getValue().trim()),
                    Integer.parseInt(zBox.getValue().trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ labels

    private static Component rotationLabel() {
        return Component.literal("Rotation: " + ROTATION[Config.rotationIndex]);
    }

    private static Component mirrorLabel() {
        return Component.literal("Mirror: " + MIRROR[Config.mirrorIndex]);
    }

    private static Component exploitLabel() {
        return Component.literal("Exploit mode: " + (Config.exploit ? "ON" : "OFF"));
    }

    private static Component hologramLabel() {
        if (!Config.hologramAvailable) return Component.literal("Hologram: not in this build");
        return Component.literal("Hologram: " + (Config.hologram ? "ON" : "OFF"));
    }

    private static Component actionBarLabel() {
        return Component.literal("Action bar %: " + (Config.actionBarProgress ? "ON" : "OFF"));
    }

    private EditBox coordBox(int x, int y, int value) {
        EditBox box = new EditBox(this.font, x, y, 62, 20, Component.literal("coord"));
        box.setFilter(s -> s.matches("-?\\d{0,9}"));
        box.setValue(Integer.toString(value));
        box.setResponder(s -> syncOrigin()); // set after setValue so it doesn't fire during construction
        return box;
    }

    // ------------------------------------------------------------------ drawing

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        super.render(g, mouseX, mouseY, delta);
        SchematicBuilder b = SchemClient.BUILDER;
        int left = colLeft;

        g.drawString(this.font, "Schem Builder", left, top, 0xFFFFFFFF, true);
        g.drawString(this.font, "Origin", left, top + 14 + 28 + 6, 0xFFAAAAAA, true);
        g.drawString(this.font, "X", left + 36, top + 14 + 28 - 8, 0xFF888888, false);
        g.drawString(this.font, "Y", left + 102, top + 14 + 28 - 8, 0xFF888888, false);
        g.drawString(this.font, "Z", left + 168, top + 14 + 28 - 8, 0xFF888888, false);

        // --- loaded file
        int y = textTop;
        String loaded = b.loadedName();
        if (loaded == null) {
            g.drawString(this.font, "No schematic loaded", left, y, 0xFFFFAA00, true);
        } else {
            int[] s = b.outputSize();
            g.drawString(this.font, loaded + "  (" + s[0] + " x " + s[1] + " x " + s[2] + " after rotation)",
                    left, y, 0xFFFFFFFF, true);
        }

        // --- progress bar
        int by = y + 14;
        int placed = b.placedCount();
        int total = b.total();
        double fraction = total == 0 ? 0.0 : Math.min(1.0, (double) placed / total);
        int fillColor = b.isStalled() ? 0xFFD9A400 : 0xFF3FBF3F;
        g.fill(left - 1, by - 1, left + W + 1, by + 13, 0xFF000000);
        g.fill(left, by, left + W, by + 12, 0xFF2B2B2B);
        g.fill(left, by, left + (int) Math.round(W * fraction), by + 12, fillColor);
        String barText = total == 0 ? "No build started"
                : String.format("%d%%   (%d / %d blocks)", b.percent(), placed, total);
        g.drawString(this.font, barText, left + (W - this.font.width(barText)) / 2, by + 2, 0xFFFFFFFF, true);

        String eta = "ETA: " + b.etaText();
        if (b.failedCount() > 0) eta += "     failed: " + b.failedCount();
        g.drawString(this.font, eta, left, by + 17, b.isStalled() ? 0xFFFFCC44 : 0xFFDDDDDD, true);

        if (!message.isEmpty()) {
            int line = 0;
            for (var part : this.font.split(Component.literal(message), W)) {
                if (line >= 3) break;
                g.drawString(this.font, part, left, by + 30 + line * 10, 0xFFCCCCCC, true);
                line++;
            }
        }

        renderMaterials(g, b);
    }

    /** The "Materials" list on the right: what the build still needs, what you have, and what's short. */
    private void renderMaterials(GuiGraphics g, SchematicBuilder b) {
        int px = colLeft + W + 12;
        int pw = Math.min(PANEL_W, this.width - px - 4);
        if (pw < 120) return; // window too narrow, the progress bar on the left still shows everything important

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        long now = System.currentTimeMillis();
        if (now - materialsAt > 1000) {
            materials = b.materials(mc.player);
            materialsAt = now;
        }

        int y = top;
        g.drawString(this.font, "Materials", px, y, 0xFFFFFFFF, true);
        y += 12;

        if (!b.hasSchematic()) {
            g.drawString(this.font, "Load a schematic to see", px, y, 0xFFAAAAAA, true);
            g.drawString(this.font, "what it needs.", px, y + 10, 0xFFAAAAAA, true);
            return;
        }
        if (b.isStalled()) {
            g.drawString(this.font, "Placed everything it can.", px, y, 0xFFFFCC44, true);
            y += 10;
            g.drawString(this.font, "Still needed to finish:", px, y, 0xFFFFCC44, true);
        } else if (b.isRunning()) {
            g.drawString(this.font, "Building... still needed:", px, y, 0xFFDDDDDD, true);
        } else {
            g.drawString(this.font, "Needed for this build:", px, y, 0xFFDDDDDD, true);
        }
        y += 12;

        if (mc.player.isCreative()) {
            g.drawString(this.font, "Creative mode: free", px, y, 0xFF55FF55, true);
            y += 10;
        }
        if (materials.isEmpty()) {
            g.drawString(this.font, b.total() > 0 ? "Nothing left to place." : "Nothing to build here.",
                    px, y, 0xFF55FF55, true);
            return;
        }

        int maxLines = Math.max(3, (this.height - y - 30) / 10);
        int shown = 0;
        int shortTypes = 0;
        int shortBlocks = 0;
        for (SchematicBuilder.Material m : materials) {
            if (m.missing() > 0) {
                shortTypes++;
                shortBlocks += m.missing();
            }
        }
        for (SchematicBuilder.Material m : materials) {
            if (shown >= maxLines) break;
            String name = new ItemStack(m.item()).getHoverName().getString();
            if (name.length() > 20) name = name.substring(0, 19) + "~";
            boolean creative = m.have() == Integer.MAX_VALUE;
            String line;
            int color;
            if (creative) {
                line = name + " x" + m.need();
                color = 0xFFDDDDDD;
            } else if (m.missing() > 0) {
                line = name + "  " + m.have() + "/" + m.need() + "  (need " + m.missing() + " more)";
                color = 0xFFFF5555;
            } else {
                line = name + "  " + m.have() + "/" + m.need();
                color = 0xFF55FF55;
            }
            g.drawString(this.font, line, px, y, color, true);
            y += 10;
            shown++;
        }
        if (materials.size() > shown) {
            g.drawString(this.font, "+ " + (materials.size() - shown) + " more item types", px, y, 0xFFAAAAAA, true);
            y += 10;
        }
        y += 4;
        if (!mc.player.isCreative()) {
            if (shortTypes > 0) {
                g.drawString(this.font, "Short: " + shortTypes + " item types, " + shortBlocks + " blocks",
                        px, y, 0xFFFF5555, true);
            } else if (b.isStalled()) {
                g.drawString(this.font, "Nothing is missing: the rest is", px, y, 0xFFFFCC44, true);
                g.drawString(this.font, "out of reach or has nothing to", px, y + 10, 0xFFFFCC44, true);
                g.drawString(this.font, "attach to. Walk closer.", px, y + 20, 0xFFFFCC44, true);
            }
        }
    }

    // ------------------------------------------------------------------ sliders

    private static final class ReachSlider extends AbstractSliderButton {
        ReachSlider(int x, int y) {
            super(x, y, HALF, 20, Component.empty(), toValue(Config.reach));
            updateMessage();
        }

        private static double max() {
            return Config.exploit ? Config.EXPLOIT_MAX_REACH : Config.LEGIT_MAX_REACH;
        }

        private static double toValue(double reach) {
            return Math.max(0.0, Math.min(1.0, (reach - 1.0) / (max() - 1.0)));
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.literal(String.format("Reach: %.1f blocks", Config.reach)));
        }

        @Override
        protected void applyValue() {
            Config.reach = Math.round((1.0 + this.value * (max() - 1.0)) * 10.0) / 10.0;
        }
    }

    private static final class RangeSlider extends AbstractSliderButton {
        RangeSlider(int x, int y) {
            super(x, y, HALF, 20, Component.empty(), (Config.hologramRange - 8) / 56.0);
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.literal("Holo range: " + Config.hologramRange));
        }

        @Override
        protected void applyValue() {
            Config.hologramRange = 8 + (int) Math.round(this.value * 56.0);
        }
    }

    private static final class SpeedSlider extends AbstractSliderButton {
        SpeedSlider(int x, int y) {
            super(x, y, HALF, 20, Component.empty(), (Config.blocksPerTick - 1) / 19.0);
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.literal("Speed: " + Config.blocksPerTick + " per tick"));
        }

        @Override
        protected void applyValue() {
            Config.blocksPerTick = 1 + (int) Math.round(this.value * 19.0);
        }
    }

    private static final class SlotSlider extends AbstractSliderButton {
        SlotSlider(int x, int y) {
            super(x, y, HALF, 20, Component.empty(), Config.hotbarSlot / 8.0);
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.literal("Hotbar slot: " + (Config.hotbarSlot + 1)));
        }

        @Override
        protected void applyValue() {
            Config.hotbarSlot = (int) Math.round(this.value * 8.0);
        }
    }
}
