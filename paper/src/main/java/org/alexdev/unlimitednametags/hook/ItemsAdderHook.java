package org.alexdev.unlimitednametags.hook;

import com.google.common.collect.Maps;
import dev.lone.itemsadder.api.Events.ItemsAdderPackCompressedEvent;
import lombok.Getter;
import net.kyori.adventure.key.Key;
import org.alexdev.unlimitednametags.UnlimitedNameTags;
import org.alexdev.unlimitednametags.hook.creative.CreativeHook;
import org.alexdev.unlimitednametags.hook.creative.CustomMinecraftResourcePackReaderImpl;
import org.alexdev.unlimitednametags.hook.creative.JsonModelHeightResolver;
import org.alexdev.unlimitednametags.hook.hat.HatHook;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import team.unnamed.creative.ResourcePack;
import team.unnamed.creative.model.Model;
import dev.lone.itemsadder.api.CustomStack;

import java.io.File;
import java.nio.file.Path;
import java.util.Map;
import java.util.OptionalDouble;

/**
 * ItemsAdder hat height. The generated pack is read lazily, off the server thread, and only when
 * {@code performance.helmetHeightCompensation} is enabled; only its model JSON is kept in memory.
 */
@Getter
public class ItemsAdderHook extends Hook implements Listener, HatHook, CreativeHook {

    private static final Path generatedPath = new File(Bukkit.getPluginsFolder(),"ItemsAdder" + File.separator + "output" + File.separator + "generated.zip").toPath();

    private final Map<Key, Map<Integer, Model>> cmdCache;
    private final Map<Key, Set<Integer>> cmdMissCache;
    private volatile ResourcePack resourcePack;
    private volatile JsonModelHeightResolver jsonModelHeightResolver;
    /** A pack load has been requested at least once (lazy first load). */
    private volatile boolean loadRequested;
    /** A load task is queued and has not started yet; further requests are merged into it. */
    private final AtomicBoolean loadQueued = new AtomicBoolean();

    public ItemsAdderHook(@NotNull UnlimitedNameTags plugin) {
        super(plugin);
        this.cmdCache = Maps.newConcurrentMap();
        this.cmdMissCache = Maps.newConcurrentMap();
        if (isCompensationEnabled()) {
            requestLoad();
        }
    }

    private boolean isCompensationEnabled() {
        return plugin.getConfigManager().getSettings().getPerformance().isHelmetHeightCompensation();
    }

    /** Loads (or reloads) the pack asynchronously. Never blocks the caller. */
    private void requestLoad() {
        loadRequested = true;
        if (!loadQueued.compareAndSet(false, true)) {
            return;
        }
        try {
            plugin.getTaskScheduler().runTaskAsynchronously(() -> {
                loadQueued.set(false);
                loadTexture();
            });
        } catch (RuntimeException e) {
            loadQueued.set(false);
            plugin.getLogger().log(java.util.logging.Level.WARNING, "Could not schedule ItemsAdder pack load", e);
        }
    }

    @Override
    public double getHigh(@NotNull UUID playerId) {
        if (!isCompensationEnabled()) {
            return 0;
        }
        if (!loadRequested) {
            // Compensation was enabled after startup (reload): load now, report 0 until it is ready.
            requestLoad();
            return 0;
        }
        final Player player = plugin.getPlayerListener().getPlayer(playerId);
        if (player == null) {
            return 0;
        }
        return CreativeHook.super.getHigh(player);
    }

    public Optional<Model> findModel(@NotNull ItemStack item) {
        final ResourcePack pack = resourcePack;
        if (pack != null) {
            final CustomStack stack = CustomStack.byItemStack(item);
            if (stack != null) {
                final String modelPath = stack.getModelPath();
                if (modelPath != null) {
                    final net.kyori.adventure.key.Key key = net.kyori.adventure.key.Key.key(stack.getNamespace(), modelPath);
                    final Model model = pack.model(key);
                    if (model != null) {
                        return Optional.of(model);
                    }
                }
            }
        }
        return CreativeHook.super.findModel(item);
    }

    @Override
    public double getHigh(@NotNull ItemStack helmet) {
        final double creativeHeight = CreativeHook.super.getHigh(helmet);
        final JsonModelHeightResolver resolver = jsonModelHeightResolver;
        if (creativeHeight > 0 || resolver == null) {
            return creativeHeight;
        }
        final OptionalDouble height = resolver.heightForItem(helmet);
        return height.orElse(creativeHeight);
    }

    @EventHandler
    public void onLoad(ItemsAdderPackCompressedEvent event) {
        if (!isCompensationEnabled()) {
            // Nothing to keep in memory while compensation is off; a later enable reloads lazily.
            resourcePack = null;
            jsonModelHeightResolver = null;
            loadRequested = false;
            cmdCache.clear();
            cmdMissCache.clear();
            return;
        }
        requestLoad();
        plugin.getLogger().info("ItemsAdder pack rebuilt, reloading hat models asynchronously");
    }

    @Override
    public void onEnable() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @Override
    public void onDisable() {
        cmdCache.clear();
        cmdMissCache.clear();
    }

    @Override
    public synchronized void loadTexture() {
        final File generated = generatedPath.toFile();
        if (!generated.exists()) {
            plugin.getLogger().warning("ItemsAdder generated.zip not found, skipping");
            return;
        }

        try {
            resourcePack = CustomMinecraftResourcePackReaderImpl.MODELS_ONLY.readFromZipFile(generated);
            jsonModelHeightResolver = new JsonModelHeightResolver(generated);
            plugin.getLogger().info("ItemsAdder's resource pack models loaded from " + generated.getAbsolutePath());
        } catch (Throwable e) {
            resourcePack = null;
            jsonModelHeightResolver = new JsonModelHeightResolver(generated);
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "Failed to load ItemsAdder resource pack for file at " + generated.getAbsolutePath(), e);
        } finally {
            cmdCache.clear();
            cmdMissCache.clear();
        }
    }
}
