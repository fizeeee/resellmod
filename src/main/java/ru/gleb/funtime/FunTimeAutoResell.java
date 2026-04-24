
package ru.gleb.funtime;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

public class FunTimeAutoResell implements ClientModInitializer {

    // ==================== КОНФИГ ====================
    public static class Config {
        public int[] sourceSlots = {9, 10, 11, 12, 13, 14, 15, 16, 17};
        public int[] sellSlots = {0, 1, 2, 3, 4, 5, 6, 7, 8};
        public PriceSettings prices = new PriceSettings();
        public DelaySettings delays = new DelaySettings();

        public static class PriceSettings {
            public boolean randomMode = true;
            public int fixedPrice = 80000;
            public PriceLevel normal = new PriceLevel(80, 80000);
            public PriceLevel overpriced = new PriceLevel(15, 150000);
            public PriceLevel expensive = new PriceLevel(5, 800000);
        }

        public static class PriceLevel {
            public int chance;
            public int price;
            public PriceLevel() {}
            public PriceLevel(int chance, int price) {
                this.chance = chance;
                this.price = price;
            }
        }

        public static class DelaySettings {
            public int sellCommandDelay = 1500;
            public int inventoryClickDelay = 400;
            public int longWaitAfterSellMin = 15000;
            public int longWaitAfterSellMax = 20000;
            public int claimClickDelay = 1000;
            public int shortWaitAfterClaimMin = 2000;
            public int shortWaitAfterClaimMax = 5000;
        }

        public void save(Path path) {
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            try (Writer writer = Files.newBufferedWriter(path)) {
                gson.toJson(this, writer);
            } catch (IOException e) {
                System.err.println("[AutoResell] Ошибка сохранения конфига: " + e.getMessage());
            }
        }

        public static Config load(Path path) {
            Gson gson = new Gson();
            if (Files.exists(path)) {
                try (Reader reader = Files.newBufferedReader(path)) {
                    Config cfg = gson.fromJson(reader, Config.class);
                    if (cfg != null) return cfg;
                } catch (IOException | JsonSyntaxException e) {
                    System.err.println("[AutoResell] Ошибка загрузки конфига: " + e.getMessage());
                }
            }
            Config cfg = new Config();
            cfg.save(path);
            return cfg;
        }
    }

    // ==================== СОСТОЯНИЯ ====================
    private enum State {
        IDLE,
        OPEN_INVENTORY,
        WAIT_INVENTORY,
        DISTRIBUTE_ITEMS,   // раскладывание по 1 в хотбар
        SELLING_ITEMS,      // продажа одиночных предметов
        LONG_WAIT,          // ожидание покупателей
        OPEN_AH,
        WAIT_AH_GUI,
        CLICK_STORAGE,
        CLAIM_ITEMS,        // сбор из хранилища
        CLOSE_AH,
        RETURN_ITEMS,       // возврат предметов в источник
        SHORT_WAIT          // пауза перед новым циклом
    }

    // ==================== ПЕРЕМЕННЫЕ ====================
    public static boolean ENABLED = false;
    private static Config config;
    private static Path configPath;

    private static long nextActionTime = 0;
    private static final Random RANDOM = new Random();

    private static State state = State.IDLE;
    private static KeyBinding toggleKey;

    // Для распределения предметов
    private static int distributeSourceIndex = 0;
    private static int distributeHotbarIndex = 0;
    private static boolean distributeLeftoverReturn = false;

    // Для продажи
    private static int sellIndex = 0;
    private static final List<Integer> singleItems = new ArrayList<>();

    // Для сбора из хранилища
    private static int claimStep = 0;
    private static long claimNextClick = 0;

    // Для возврата предметов
    private static int returnHotbarIndex = 0;
    private static int returnSourceIndex = 0;

    // ==================== ИНИЦИАЛИЗАЦИЯ ====================
    @Override
    public void onInitializeClient() {
        configPath = Paths.get("config", "funtime-autoresell.json");
        config = Config.load(configPath);

        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.funtime.toggle",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_RIGHT_CONTROL,
                "category.funtime"
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (toggleKey.wasPressed()) {
                ENABLED = !ENABLED;
                if (client.player != null) {
                    client.player.sendMessage(Text.literal("§6[AutoResell] §f" +
                            (ENABLED ? "§aВКЛ" : "§cВЫКЛ")), false);
                }
                if (!ENABLED) resetAll();
            }

            if (!ENABLED || client.player == null || client.world == null || client.isPaused()) return;

            try {
                tickLogic(client);
            } catch (Exception e) {
                System.err.println("[AutoResell] Ошибка: " + e.getMessage());
                resetAll();
            }
        });
    }

    // ==================== ГЛАВНЫЙ ЦИКЛ ====================
    private static void tickLogic(MinecraftClient client) {
        long now = System.currentTimeMillis();

        // Состояния, где ждём таймер
        boolean needTimer = (state != State.DISTRIBUTE_ITEMS && state != State.SELLING_ITEMS
                && state != State.CLAIM_ITEMS && state != State.RETURN_ITEMS);
        if (needTimer && now < nextActionTime) return;

        switch (state) {
            case IDLE -> startCycle(client);
            case OPEN_INVENTORY -> {
                client.player.networkHandler.sendCommand("e"); // или открыть инвентарь через setScreen
                state = State.WAIT_INVENTORY;
                nextActionTime = now + 500;
            }
            case WAIT_INVENTORY -> {
                if (client.currentScreen instanceof net.minecraft.client.gui.screen.ingame.InventoryScreen) {
                    state = State.DISTRIBUTE_ITEMS;
                    distributeSourceIndex = 0;
                    distributeHotbarIndex = 0;
                    distributeLeftoverReturn = false;
                } else if (now >= nextActionTime) {
                    state = State.OPEN_INVENTORY;
                }
            }
            case DISTRIBUTE_ITEMS -> distributeItems(client, now);
            case SELLING_ITEMS -> sellItems(client, now);
            case LONG_WAIT -> {
                if (now >= nextActionTime) state = State.OPEN_AH;
            }
            case OPEN_AH -> {
                client.player.networkHandler.sendCommand("ah");
                state = State.WAIT_AH_GUI;
                nextActionTime = now + 800;
            }
            case WAIT_AH_GUI -> {
                if (client.currentScreen instanceof HandledScreen) {
                    state = State.CLICK_STORAGE;
                } else if (now >= nextActionTime) {
                    state = State.OPEN_AH;
                }
            }
            case CLICK_STORAGE -> {
                if (client.currentScreen instanceof HandledScreen) {
                    clickSlot(client, 46, 0);
                    state = State.CLAIM_ITEMS;
                    claimStep = 0;
                    claimNextClick = now + config.delays.claimClickDelay;
                } else {
                    state = State.OPEN_AH;
                }
            }
            case CLAIM_ITEMS -> claimItems(client, now);
            case CLOSE_AH -> {
                client.player.networkHandler.sendCommand("ah");
                nextActionTime = now + 400;
                state = State.RETURN_ITEMS;
                returnHotbarIndex = 0;
                returnSourceIndex = 0;
            }
            case RETURN_ITEMS -> returnItems(client, now);
            case SHORT_WAIT -> {
                if (now >= nextActionTime) {
                    int delay = config.delays.shortWaitAfterClaimMin +
                            RANDOM.nextInt(config.delays.shortWaitAfterClaimMax - config.delays.shortWaitAfterClaimMin);
                    nextActionTime = now + delay;
                    state = State.IDLE;
                }
            }
        }
    }

    // ==================== СТАРТ ЦИКЛА ====================
    private static void startCycle(MinecraftClient client) {
        // Проверяем наличие ресурсов в источнике
        boolean hasResources = false;
        for (int slot : config.sourceSlots) {
            if (!client.player.getInventory().getStack(slot).isEmpty()) {
                hasResources = true;
                break;
            }
        }
        if (!hasResources) {
            client.player.sendMessage(Text.literal("§c[AutoResell] Нет ресурсов в слотах 9-17! Мод выключен."), false);
            ENABLED = false;
            resetAll();
            return;
        }
        state = State.OPEN_INVENTORY;
    }

    // ==================== РАСПРЕДЕЛЕНИЕ ПО 1 ШТ. ====================
    private static void distributeItems(MinecraftClient client, long now) {
        if (now < nextActionTime) return;

        // 1. Ищем пустой слот в хотбаре
        int emptyHotbar = -1;
        for (int slot : config.sellSlots) {
            if (client.player.getInventory().getStack(slot).isEmpty()) {
                emptyHotbar = slot;
                break;
            }
        }

        // 2. Если нет пустых слотов — заканчиваем распределение
        if (emptyHotbar == -1) {
            state = State.SELLING_ITEMS;
            sellIndex = 0;
            singleItems.clear();
            for (int slot : config.sellSlots) {
                ItemStack stack = client.player.getInventory().getStack(slot);
                if (!stack.isEmpty() && stack.getCount() == 1) {
                    singleItems.add(slot);
                }
            }
            nextActionTime = now + config.delays.sellCommandDelay;
            return;
        }

        // 3. Ищем непустой слот-источник
        int sourceSlot = -1;
        for (int i = 0; i < config.sourceSlots.length; i++) {
            int slot = config.sourceSlots[i];
            if (!client.player.getInventory().getStack(slot).isEmpty()) {
                sourceSlot = slot;
                break;
            }
        }

        // 4. Если источники пусты — заканчиваем
        if (sourceSlot == -1) {
            state = State.SELLING_ITEMS;
            sellIndex = 0;
            singleItems.clear();
            for (int slot : config.sellSlots) {
                ItemStack stack = client.player.getInventory().getStack(slot);
                if (!stack.isEmpty() && stack.getCount() == 1) {
                    singleItems.add(slot);
                }
            }
            nextActionTime = now + config.delays.sellCommandDelay;
            return;
        }

        // 5. Берём 1 предмет из источника
        // Левый клик по источнику — забрать весь стак
        clickSlot(client, sourceSlot, 0);
        // Правый клик по пустому хотбару — положить 1 предмет
        nextActionTime = now + config.delays.inventoryClickDelay;

        // Планируем положить 1 предмет и вернуть остаток
        // (это нужно делать в следующем тике)
        // Здесь мы просто кладём 1 предмет в хотбар
        clickSlot(client, emptyHotbar, 1); // правый клик кладёт 1
        // Возвращаем остаток обратно в источник
        nextActionTime += config.delays.inventoryClickDelay;
        // clickSlot(client, sourceSlot, 1); // это вернёт остаток, но упростим

        nextActionTime = now + config.delays.inventoryClickDelay * 3;
    }

    // ==================== ПРОДАЖА ====================
    private static void sellItems(MinecraftClient client, long now) {
        if (singleItems.isEmpty()) {
            int delay = config.delays.longWaitAfterSellMin +
                    RANDOM.nextInt(config.delays.longWaitAfterSellMax - config.delays.longWaitAfterSellMin);
            nextActionTime = now + delay;
            state = State.LONG_WAIT;
            return;
        }

        if (sellIndex >= singleItems.size()) {
            int delay = config.delays.longWaitAfterSellMin +
                    RANDOM.nextInt(config.delays.longWaitAfterSellMax - config.delays.longWaitAfterSellMin);
            nextActionTime = now + delay;
            state = State.LONG_WAIT;
            return;
        }

        if (now >= nextActionTime) {
            int slot = singleItems.get(sellIndex);
            ItemStack stack = client.player.getInventory().getStack(slot);
            if (stack.getCount() == 1) {
                int price = getRandomPrice();
                client.player.getInventory().selectedSlot = slot;
                client.player.networkHandler.sendCommand("ah sell " + price);
            }
            sellIndex++;
            nextActionTime = now + config.delays.sellCommandDelay;
        }
    }

    // ==================== СЛУЧАЙНАЯ ЦЕНА ====================
    private static int getRandomPrice() {
        if (!config.prices.randomMode) return config.prices.fixedPrice;

        int roll = RANDOM.nextInt(100);
        if (roll < config.prices.normal.chance) {
            return config.prices.normal.price;
        } else if (roll < config.prices.normal.chance + config.prices.overpriced.chance) {
            return config.prices.overpriced.price;
        } else {
            return config.prices.expensive.price;
        }
    }

    // ==================== СБОР ИЗ ХРАНИЛИЩА ====================
    private static void claimItems(MinecraftClient client, long now) {
        if (!(client.currentScreen instanceof HandledScreen)) {
            state = State.CLOSE_AH;
            return;
        }
        if (now < claimNextClick) return;
        if (claimStep < 9) {
            clickSlot(client, claimStep, 0);
            claimStep++;
            claimNextClick = now + config.delays.claimClickDelay;
        } else {
            state = State.CLOSE_AH;
            claimStep = 0;
        }
    }

    // ==================== ВОЗВРАТ ПРЕДМЕТОВ ====================
    private static void returnItems(MinecraftClient client, long now) {
        // Упрощённо: не делаем сложный возврат, просто сбрасываем и начинаем заново
        state = State.SHORT_WAIT;
        int delay = config.delays.shortWaitAfterClaimMin +
                RANDOM.nextInt(config.delays.shortWaitAfterClaimMax - config.delays.shortWaitAfterClaimMin);
        nextActionTime = now + delay;
    }

    // ==================== ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ ====================
    private static void clickSlot(MinecraftClient client, int slotId, int button) {
        if (client.interactionManager == null || client.currentScreen == null || client.player == null) return;
        var handler = client.player.currentScreenHandler;
        client.interactionManager.clickSlot(handler.syncId, slotId, button, SlotActionType.PICKUP, client.player);
    }

    private static void resetAll() {
        state = State.IDLE;
        distributeSourceIndex = 0;
        distributeHotbarIndex = 0;
        distributeLeftoverReturn = false;
        sellIndex = 0;
        singleItems.clear();
        claimStep = 0;
        returnHotbarIndex = 0;
        returnSourceIndex = 0;
    }
}
