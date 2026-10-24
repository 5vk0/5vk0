package com.example.visuals;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/**
 * Simple Visuals - Fabric 26.1.2 (Mojang names, Java 25).
 * Wszystko w jednym pliku: config, HUD, efekty uderzeń i ekran ustawień.
 * Klawisz: Right Shift = menu.
 */
public class SimpleVisualsClient implements ClientModInitializer {
    public static final String MOD_ID = "simplevisuals";
    private static KeyMapping openConfig;

    @Override
    public void onInitializeClient() {
        Config.load();

        KeyMapping.Category category = KeyMapping.Category.register(
                Identifier.fromNamespaceAndPath(MOD_ID, "main"));

        openConfig = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.simplevisuals.config",
                InputConstants.Type.KEYSYM,
                InputConstants.KEY_RSHIFT,
                category));

        HudElementRegistry.attachElementBefore(
                VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath(MOD_ID, "hud"),
                Hud::render);

        AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            if (player instanceof LocalPlayer) HitEffects.onAttack(entity);
            return InteractionResult.PASS; // nie zmieniamy mechaniki gry
        });

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (openConfig.consumeClick()) {
                mc.setScreen(new ConfigScreen(mc.screen));
            }
            HitEffects.tick(mc);
            Hud.tick(mc);
        });
    }

    // =====================================================================
    //  CONFIG  (config/simplevisuals.json)
    // =====================================================================
    public static class Config {
        private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
        private static final Path FILE =
                FabricLoader.getInstance().getConfigDir().resolve("simplevisuals.json");

        public static Config I = new Config();

        public boolean targetHud = true;
        public boolean potionHud = true;
        public boolean cooldownHud = true;
        public boolean hitParticles = true;
        public boolean smoothBars = true;

        public int targetX = 20, targetY = 20;
        public int potionX = 6, potionY = 60;
        public int scalePct = 100;       // 50..200
        public int particleCount = 8;    // 0..24
        public int accent = 0xFF4FA3FF;  // ARGB

        public static void load() {
            try {
                if (Files.exists(FILE)) {
                    Config c = GSON.fromJson(Files.readString(FILE), Config.class);
                    if (c != null) I = c;
                }
            } catch (Exception e) {
                System.err.println("[SimpleVisuals] Nie udało się wczytać configu: " + e);
            }
            save();
        }

        public static void save() {
            try {
                Files.writeString(FILE, GSON.toJson(I));
            } catch (Exception e) {
                System.err.println("[SimpleVisuals] Nie udało się zapisać configu: " + e);
            }
        }

        public float scale() { return scalePct / 100f; }
    }

    // =====================================================================
    //  EFEKTY UDERZEŃ - mało wanilijnych cząsteczek, tylko po faktycznym trafieniu
    // =====================================================================
    public static final class HitEffects {
        private static LivingEntity pending;
        private static int pendingTicks;
        private static int cooldown;

        private HitEffects() {}

        static void onAttack(Entity e) {
            if (!Config.I.hitParticles) return;
            if (e instanceof LivingEntity le) {
                pending = le;
                pendingTicks = 8; // czekamy na odpowiedź serwera (ping)
            }
        }

        static void tick(Minecraft mc) {
            if (cooldown > 0) cooldown--;
            if (pending == null) return;
            if (mc.level == null || !pending.isAlive() || --pendingTicks <= 0) { pending = null; return; }
            if (pending.hurtTime <= 0 || cooldown > 0) return;

            LivingEntity e = pending;
            pending = null;
            cooldown = 4; // anty-spam

            String status = mc.options.particles().get().name();
            if (status.equals("MINIMAL")) return;

            int n = Math.min(Config.I.particleCount, 24);
            if (status.equals("DECREASED")) n /= 2;

            RandomSource r = e.getRandom();
            double w = e.getBbWidth(), h = e.getBbHeight();
            for (int i = 0; i < n; i++) {
                mc.level.addParticle(ParticleTypes.CRIT,
                        e.getX() + (r.nextDouble() - 0.5) * w,
                        e.getY() + r.nextDouble() * h,
                        e.getZ() + (r.nextDouble() - 0.5) * w,
                        (r.nextDouble() - 0.5) * 0.6, r.nextDouble() * 0.4, (r.nextDouble() - 0.5) * 0.6);
            }
        }
    }

    // =====================================================================
    //  HUD - TargetHUD, timery mikstur, cooldowny
    // =====================================================================
    public static final class Hud {
        private static final int TARGET_W = 140, TARGET_H = 42;
        private static final long TARGET_HOLD_MS = 3000;

        private static final Item[] CD_ITEMS = {
                Items.ENDER_PEARL, Items.GOLDEN_APPLE, Items.ENCHANTED_GOLDEN_APPLE,
                Items.CHORUS_FRUIT, Items.SHIELD, Items.TRIDENT
        };
        private static final ItemStack[] CD_STACKS = new ItemStack[CD_ITEMS.length];
        static { for (int i = 0; i < CD_ITEMS.length; i++) CD_STACKS[i] = new ItemStack(CD_ITEMS[i]); }

        private static LivingEntity target;
        private static long lastSeen;
        private static float shownHealth = -1f;

        private static float cachedHpValue = -1f, cachedHpMax = -1f;
        private static String cachedHpText = "";
        private static LivingEntity cachedNameFor;
        private static String cachedName = "";

        private Hud() {}

        /** Co tick: aktualizacja celu (tanie). */
        static void tick(Minecraft mc) {
            if (mc.player == null) { target = null; return; }
            if (mc.crosshairPickEntity instanceof LivingEntity le && le != mc.player) {
                if (le != target) shownHealth = le.getHealth();
                target = le;
                lastSeen = System.currentTimeMillis();
            } else if (target != null
                    && (!target.isAlive() || System.currentTimeMillis() - lastSeen > TARGET_HOLD_MS)) {
                target = null;
            }
        }

        static void render(GuiGraphicsExtractor g, DeltaTracker deltaTracker) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.options.hideGui) return;

            Config c = Config.I;
            boolean preview = mc.screen instanceof ConfigScreen;
            Font font = mc.font;
            float s = c.scale();
            float delta = deltaTracker.getGameTimeDeltaPartialTick(false);

            if (c.targetHud) {
                LivingEntity t = preview ? mc.player : target;
                if (t != null) drawTarget(g, font, mc, t, c, s);
            }
            if (c.potionHud) drawPotions(g, font, mc, c, s);
            if (c.cooldownHud) drawCooldowns(g, mc, c, delta);
        }

        private static void drawTarget(GuiGraphicsExtractor g, Font font, Minecraft mc,
                                       LivingEntity t, Config c, float s) {
            float hp = t.getHealth(), max = Math.max(1f, t.getMaxHealth());
            if (shownHealth < 0 || !c.smoothBars) shownHealth = hp;
            else shownHealth += (hp - shownHealth) * 0.15f;

            if (t != cachedNameFor) { cachedNameFor = t; cachedName = t.getName().getString(); }
            if (hp != cachedHpValue || max != cachedHpMax) {
                cachedHpValue = hp; cachedHpMax = max;
                cachedHpText = String.format("%.1f / %.0f", hp, max);
            }

            g.pose().pushMatrix();
            g.pose().translate((float) c.targetX, (float) c.targetY);
            g.pose().scale(s, s);

            g.fill(0, 0, TARGET_W, TARGET_H, 0xB0101014);
            g.fill(0, 0, TARGET_W, 2, c.accent);

            g.text(font, cachedName, 6, 7, 0xFFFFFFFF, true);

            int bx = 6, by = 20, bw = TARGET_W - 12, bh = 6;
            g.fill(bx, by, bx + bw, by + bh, 0xFF25252B);
            float ratio = Math.max(0f, Math.min(1f, shownHealth / max));
            g.fill(bx, by, bx + (int) (bw * ratio), by + bh, hpColor(ratio));

            g.text(font, cachedHpText, 6, 30, 0xFFCCCCCC, false);
            String right = (int) mc.player.distanceTo(t) + "m  A" + t.getArmorValue();
            g.text(font, right, TARGET_W - 6 - font.width(right), 30, 0xFF9AA0A6, false);

            g.pose().popMatrix();
        }

        private static int hpColor(float r) {
            int red = (int) (255 * Math.min(1f, (1f - r) * 2f));
            int green = (int) (255 * Math.min(1f, r * 2f));
            return 0xFF000000 | (red << 16) | (green << 8) | 0x40;
        }

        private static void drawPotions(GuiGraphicsExtractor g, Font font, Minecraft mc, Config c, float s) {
            Collection<MobEffectInstance> effects = mc.player.getActiveEffects();
            if (effects.isEmpty()) return;

            g.pose().pushMatrix();
            g.pose().translate((float) c.potionX, (float) c.potionY);
            g.pose().scale(s, s);

            int y = 0;
            for (MobEffectInstance e : effects) {
                Identifier id = BuiltInRegistries.MOB_EFFECT.getKey(e.getEffect().value());
                g.fill(0, y, 58, y + 18, 0x90101014);
                g.fill(0, y, 2, y + 18, c.accent);
                if (id != null) {
                    g.blitSprite(RenderPipelines.GUI_TEXTURED, id.withPrefix("mob_effect/"), 4, y + 1, 16, 16);
                }
                String txt;
                int col = 0xFFFFFFFF;
                if (e.isInfiniteDuration()) {
                    txt = "inf";
                } else {
                    int sec = e.getDuration() / 20;
                    txt = (sec / 60) + ":" + (sec % 60 < 10 ? "0" : "") + (sec % 60);
                    if (sec <= 10) col = 0xFFFF5555;
                }
                g.text(font, txt, 24, y + 5, col, true);
                if (e.getAmplifier() > 0) {
                    String lvl = String.valueOf(e.getAmplifier() + 1);
                    g.text(font, lvl, 55 - font.width(lvl), y + 9, 0xFFAAAAAA, false);
                }
                y += 20;
            }
            g.pose().popMatrix();
        }

        private static void drawCooldowns(GuiGraphicsExtractor g, Minecraft mc, Config c, float delta) {
            int cx = g.guiWidth() / 2;
            int cy = g.guiHeight() / 2;

            float atk = mc.player.getAttackStrengthScale(0f);
            if (atk < 1f) {
                int w = 40;
                g.fill(cx - w / 2, cy + 12, cx + w / 2, cy + 14, 0x90000000);
                g.fill(cx - w / 2, cy + 12, cx - w / 2 + (int) (w * atk), cy + 14, c.accent);
            }

            int x = cx - 10;
            for (int i = 0; i < CD_STACKS.length; i++) {
                float p = mc.player.getCooldowns().getCooldownPercent(CD_STACKS[i], delta);
                if (p <= 0f) continue;
                g.item(CD_STACKS[i], x, cy + 18);
                int h = (int) (16 * p);
                g.fill(x, cy + 18 + 16 - h, x + 16, cy + 18 + 16, 0x80FFFFFF);
                x += 18;
            }
        }
    }

    // =====================================================================
    //  EKRAN USTAWIEŃ (Right Shift)
    // =====================================================================
    public static class ConfigScreen extends Screen {
        private static final int[] COLORS = {
                0xFF4FA3FF, 0xFFFF5577, 0xFF55FF88, 0xFFFFC040, 0xFFB070FF, 0xFFFFFFFF
        };

        private final Screen parent;

        public ConfigScreen(Screen parent) {
            super(Component.literal("Simple Visuals"));
            this.parent = parent;
        }

        @Override
        protected void init() {
            Config c = Config.I;
            int cx = width / 2, left = cx - 155, right = cx + 5, w = 150, y = 36, g = 24;

            // lewa kolumna: przełączniki
            addRenderableWidget(toggle(left, y, w, "TargetHUD", () -> c.targetHud, v -> c.targetHud = v));
            addRenderableWidget(toggle(left, y + g, w, "Timery mikstur", () -> c.potionHud, v -> c.potionHud = v));
            addRenderableWidget(toggle(left, y + g * 2, w, "Cooldowny", () -> c.cooldownHud, v -> c.cooldownHud = v));
            addRenderableWidget(toggle(left, y + g * 3, w, "Efekty uderzeń", () -> c.hitParticles, v -> c.hitParticles = v));
            addRenderableWidget(toggle(left, y + g * 4, w, "Płynne paski", () -> c.smoothBars, v -> c.smoothBars = v));
            addRenderableWidget(Button.builder(Component.literal("Kolor akcentu"), b -> {
                int idx = 0;
                for (int i = 0; i < COLORS.length; i++) if (COLORS[i] == c.accent) idx = i;
                c.accent = COLORS[(idx + 1) % COLORS.length];
            }).bounds(left, y + g * 5, w, 20).build());

            // prawa kolumna: suwaki
            addRenderableWidget(new IntSlider(right, y, w, "Skala %", 50, 200, () -> c.scalePct, v -> c.scalePct = v));
            addRenderableWidget(new IntSlider(right, y + g, w, "Cząsteczki", 0, 24, () -> c.particleCount, v -> c.particleCount = v));
            addRenderableWidget(new IntSlider(right, y + g * 2, w, "Target X", 0, Math.max(1, width - 150), () -> c.targetX, v -> c.targetX = v));
            addRenderableWidget(new IntSlider(right, y + g * 3, w, "Target Y", 0, Math.max(1, height - 50), () -> c.targetY, v -> c.targetY = v));
            addRenderableWidget(new IntSlider(right, y + g * 4, w, "Mikstury X", 0, Math.max(1, width - 70), () -> c.potionX, v -> c.potionX = v));
            addRenderableWidget(new IntSlider(right, y + g * 5, w, "Mikstury Y", 0, Math.max(1, height - 30), () -> c.potionY, v -> c.potionY = v));

            addRenderableWidget(Button.builder(Component.literal("Reset"), b -> {
                Config.I = new Config();
                rebuildWidgets();
            }).bounds(left, y + g * 7, w, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Gotowe"), b -> onClose())
                    .bounds(right, y + g * 7, w, 20).build());
        }

        private Button toggle(int x, int y, int w, String name, BooleanSupplier get, Consumer<Boolean> set) {
            return Button.builder(label(name, get.getAsBoolean()), b -> {
                set.accept(!get.getAsBoolean());
                b.setMessage(label(name, get.getAsBoolean()));
            }).bounds(x, y, w, 20).build();
        }

        private static Component label(String name, boolean on) {
            return Component.literal(name + ": " + (on ? "§aON" : "§cOFF"));
        }

        @Override
        public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
            super.extractRenderState(graphics, mouseX, mouseY, delta);
            Font f = this.font;
            graphics.text(f, this.title, width / 2 - f.width(this.title) / 2, 14, 0xFFFFFFFF, true);
        }

        @Override
        public void onClose() {
            Config.save();
            this.minecraft.setScreen(parent);
        }

        @Override
        public void removed() { Config.save(); }

        private static class IntSlider extends AbstractSliderButton {
            private final String name;
            private final int min, max;
            private final IntConsumer set;

            IntSlider(int x, int y, int w, String name, int min, int max, IntSupplier get, IntConsumer set) {
                super(x, y, w, 20, Component.empty(), (double) (get.getAsInt() - min) / (max - min));
                this.name = name; this.min = min; this.max = max; this.set = set;
                updateMessage(); // super() woła updateMessage zanim pola są ustawione
            }

            @Override
            protected void updateMessage() {
                if (name == null) return;
                setMessage(Component.literal(name + ": " + (int) Math.round(min + value * (max - min))));
            }

            @Override
            protected void applyValue() {
                set.accept((int) Math.round(min + value * (max - min)));
            }
        }
    }
}
