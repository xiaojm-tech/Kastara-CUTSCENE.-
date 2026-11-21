package com.kastara.cutscene;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class KastaraCutscene extends JavaPlugin implements Listener {
    private final Map<UUID, Session> active = new HashMap<>();
    private final Set<UUID> completed = new HashSet<>();
    private final Set<UUID> pending = new HashSet<>();
    private File playerDataFile;
    private YamlConfiguration playerData;
    private World cinematicWorld;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadPlayerData();
        cinematicWorld = createCinematicWorld();
        if (cinematicWorld != null) buildCinematicStage(cinematicWorld);
        Bukkit.getPluginManager().registerEvents(this, this);
        getLogger().info("KASTARA-CUTSCENE v" + getDescription().getVersion() + " enabled.");
    }

    @Override
    public void onDisable() {
        for (UUID uuid : new ArrayList<>(active.keySet())) {
            Player player = Bukkit.getPlayer(uuid);
            stopSession(player, true, true);
        }
        savePlayerData();
    }

    private void loadPlayerData() {
        if (!getDataFolder().exists()) getDataFolder().mkdirs();
        playerDataFile = new File(getDataFolder(), "players.yml");
        playerData = YamlConfiguration.loadConfiguration(playerDataFile);
        for (String raw : playerData.getStringList("pending")) {
            try { pending.add(UUID.fromString(raw)); } catch (IllegalArgumentException ignored) { }
        }
        for (String raw : playerData.getStringList("completed")) {
            try { completed.add(UUID.fromString(raw)); }
            catch (IllegalArgumentException ignored) { }
        }
    }

    private void savePlayerData() {
        if (playerData == null) return;
        List<String> values = completed.stream().map(UUID::toString).sorted().toList();
        playerData.set("completed", values);
        playerData.set("pending", pending.stream().map(UUID::toString).sorted().toList());
        try { playerData.save(playerDataFile); }
        catch (IOException exception) { getLogger().severe("Could not save players.yml: " + exception.getMessage()); }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!getConfig().getBoolean("settings.first-join-only", true) || completed.contains(player.getUniqueId())) return;
        if (player.hasPlayedBefore() && !pending.contains(player.getUniqueId())) return;
        pending.add(player.getUniqueId());
        savePlayerData();
        long delay = Math.max(1L, getConfig().getLong("settings.start-delay-ticks", 40L));
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (player.isOnline() && !active.containsKey(player.getUniqueId())) startSession(player);
        }, delay);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (active.containsKey(player.getUniqueId())) {
            stopSession(player, true, true);
        }
    }

    private void startSession(Player player) {
        stopSession(player, false, false);
        List<Waypoint> points = loadWaypoints();
        if (points.size() < 2) {
            player.sendMessage(color("&cKASTARA-CUTSCENE: minimal dua waypoint kamera diperlukan."));
            return;
        }
        Location returnLocation = player.getLocation().clone();
        GameMode originalMode = player.getGameMode();

        Location initial = points.get(0).toLocation();
        if (initial == null) {
            player.sendMessage(color("&cKASTARA-CUTSCENE: world waypoint pertama tidak ditemukan."));
            return;
        }

        ArmorStand rig = initial.getWorld().spawn(initial, ArmorStand.class, stand -> {
            stand.setInvisible(true);
            stand.setMarker(true);
            stand.setGravity(false);
            stand.setInvulnerable(true);
            stand.setSilent(true);
            stand.setPersistent(false);
            stand.setCollidable(false);
            stand.setCustomName("KASTARA_CAMERA");
            stand.setCustomNameVisible(false);
        });

        player.setGameMode(GameMode.SPECTATOR);
        player.teleport(initial);
        player.setSpectatorTarget(rig);

        String audio = getConfig().getString("settings.audio.sound", "kastara:intro");
        if (getConfig().getBoolean("settings.audio.enabled", true)) {
            float volume = (float) getConfig().getDouble("settings.audio.volume", 1.0);
            float pitch = (float) getConfig().getDouble("settings.audio.pitch", 1.0);
            try {
                player.playSound(player.getLocation(), audio, SoundCategory.MASTER, volume, pitch);
            } catch (RuntimeException exception) {
                getLogger().warning("Could not play resource-pack sound '" + audio + "': " + exception.getMessage());
            }
        }

        Session session = new Session(player.getUniqueId(), rig, returnLocation, originalMode, points);
        active.put(player.getUniqueId(), session);
        session.task = new BukkitRunnable() {
            @Override
            public void run() {
                Player current = Bukkit.getPlayer(session.playerId);
                if (current == null || !current.isOnline() || !rig.isValid()) {
                    cancel();
                    stopSession(current, false, false);
                    return;
                }
                session.elapsedTicks++;
                double seconds = session.elapsedTicks / 20.0;
                Location cameraLocation = interpolate(points, seconds);
                if (cameraLocation != null) rig.teleport(cameraLocation);

                showDueScene(current, session, seconds);
                runSummoningEffects(session, seconds);

                double finalTime = Math.max(1.0, points.get(points.size() - 1).time);
                if (seconds >= finalTime) {
                    cancel();
                    finishSession(current, session);
                }
            }
        }.runTaskTimer(this, 1L, 1L);
    }

    private void showDueScene(Player player, Session session, double seconds) {
        List<Map<?, ?>> scenes = getConfig().getMapList("scenes");
        while (session.sceneIndex < scenes.size()) {
            Map<?, ?> scene = scenes.get(session.sceneIndex);
            double at = number(scene.get("time"), 0);
            if (seconds < at) break;
            String title = scene.get("title") == null ? "" : String.valueOf(scene.get("title"));
            String subtitle = scene.get("subtitle") == null ? "" : String.valueOf(scene.get("subtitle"));
            player.sendTitle(color(title), color(subtitle), 5, 45, 10);
            session.sceneIndex++;
        }
    }

    private void runSummoningEffects(Session session, double seconds) {
        if (!getConfig().getBoolean("effects.summoning.enabled", true)) return;
        double start = getConfig().getDouble("effects.summoning.start-time", 19);
        double end = getConfig().getDouble("effects.summoning.end-time", 36);
        if (seconds < start || seconds > end) return;
        Location at = session.camera.getLocation();
        Particle particle;
        try { particle = Particle.valueOf(getConfig().getString("effects.summoning.particle", "PORTAL").toUpperCase()); }
        catch (IllegalArgumentException exception) { particle = Particle.PORTAL; }
        int count = Math.max(1, getConfig().getInt("effects.summoning.count-per-tick", 8));
        at.getWorld().spawnParticle(particle, at, count, 0.8, 0.8, 0.8, 0.05);
        if (!session.summonSoundPlayed && seconds >= start) {
            String sound = getConfig().getString("effects.summoning.sound", "block.beacon.ambient");
            at.getWorld().playSound(at, sound, 0.8f, 0.8f);
            session.summonSoundPlayed = true;
        }
    }

    private void finishSession(Player player, Session session) {
        if (player == null) {
            cleanup(session);
            active.remove(session.playerId);
            return;
        }
        completed.add(session.playerId);
        pending.remove(session.playerId);
        savePlayerData();
        cleanup(session);
        active.remove(session.playerId);
        player.setSpectatorTarget(null);
        player.teleport(session.returnLocation);
        player.setGameMode(parseGameMode(getConfig().getString("settings.finish-gamemode", "SURVIVAL")));
        player.sendTitle(
                color(getConfig().getString("settings.finish.title", "&6&lWELCOME TO KASTARA")),
                color(getConfig().getString("settings.finish.subtitle", "&7Your destiny begins now")),
                getConfig().getInt("settings.finish.title-fade-in", 10),
                getConfig().getInt("settings.finish.title-stay", 70),
                getConfig().getInt("settings.finish.title-fade-out", 20)
        );
        if (getConfig().getBoolean("effects.arrival.enabled", true)) {
            Location at = session.returnLocation.clone().add(0, 1, 0);
            try {
                Particle particle = Particle.valueOf(getConfig().getString("effects.arrival.particle", "END_ROD").toUpperCase());
                at.getWorld().spawnParticle(particle, at, getConfig().getInt("effects.arrival.count", 100), 1.2, 1.5, 1.2, 0.08);
            } catch (IllegalArgumentException ignored) { }
            String sound = getConfig().getString("effects.arrival.sound", "entity.enderman.teleport");
            at.getWorld().playSound(at, sound, 1.0f, 1.0f);
        }
    }

    private void stopSession(Player player, boolean teleportToSpawn, boolean restoreSurvival) {
        if (player == null) return;
        Session session = active.remove(player.getUniqueId());
        if (session == null) return;
        if (session.task != null) session.task.cancel();
        cleanup(session);
        player.setSpectatorTarget(null);
        String audio = getConfig().getString("settings.audio.sound", "kastara:intro");
        player.stopSound(audio);
        if (restoreSurvival) player.setGameMode(session.originalMode);
        if (teleportToSpawn) player.teleport(session.returnLocation);
    }

    private void cleanup(Session session) {
        if (session.task != null) session.task.cancel();
        if (session.camera != null && session.camera.isValid()) session.camera.remove();
    }

    private List<Waypoint> loadWaypoints() {
        List<Waypoint> p = new ArrayList<>();
        if (cinematicWorld == null) return p;
        String w = cinematicWorld.getName();
        p.add(new Waypoint(0,w,-24,108,-22,45,18));
        p.add(new Waypoint(7,w,-13,94,-12,45,22));
        p.add(new Waypoint(13,w,-6,86,-5,35,10));
        p.add(new Waypoint(20,w,0,84,0,180,15));
        p.add(new Waypoint(28,w,6,91,5,225,12));
        p.add(new Waypoint(36,w,3,87,2,180,20));
        p.add(new Waypoint(45,w,0,84,0,180,5));
        return p;
    }

    private World createCinematicWorld() {
        String name = getConfig().getString("cinematic-world.name", "kastara_cinematic");
        World world = Bukkit.getWorld(name);
        if (world != null) return world;
        WorldCreator creator = new WorldCreator(name);
        creator.environment(World.Environment.NORMAL);
        creator.generateStructures(false);
        world = creator.createWorld();
        if (world != null) {
            world.setGameRule(org.bukkit.GameRule.DO_MOB_SPAWNING, false);
            world.setGameRule(org.bukkit.GameRule.DO_DAYLIGHT_CYCLE, false);
            world.setTime(18000);
            world.setStorm(false);
            world.setThundering(false);
        }
        return world;
    }

    private void buildCinematicStage(World w) {
        org.bukkit.NamespacedKey key = new org.bukkit.NamespacedKey(this, "stage-built");
        if (w.getPersistentDataContainer().has(key, org.bukkit.persistence.PersistentDataType.BYTE)) return;
        int y=80;
        for(int x=-30;x<=30;x++) for(int z=-24;z<=24;z++) {
            if(Math.abs(x)<5 || Math.abs(z)<4)
                w.getBlockAt(x,y,z).setType((Math.abs(x)==4 || Math.abs(z)==3) ? Material.CRACKED_STONE_BRICKS : Material.STONE_BRICKS,false);
        }
        for(int x=-20;x<=20;x++) for(int z : new int[]{-16,16}) {
            if(Math.abs(x)<7 || Math.abs(x)>13) {
                int h=3+Math.floorMod(x*7+z,5);
                for(int j=1;j<=h;j++) w.getBlockAt(x,y+j,z).setType(j==h?Material.MOSSY_STONE_BRICKS:Material.STONE_BRICKS,false);
            }
        }
        for(int z=-16;z<=16;z++) for(int x : new int[]{-20,20}) {
            if(Math.abs(z)<5 || Math.abs(z)>10)
                for(int j=1;j<=5+Math.floorMod(z*3+x,4);j++) w.getBlockAt(x,y+j,z).setType(Material.MOSSY_STONE_BRICKS,false);
        }
        int[][] pillars={{-9,-8},{9,-8},{-9,8},{9,8},{-13,0},{13,0}};
        for(int[] p:pillars) for(int j=1;j<=4+Math.floorMod(p[0]*p[1],4);j++)
            w.getBlockAt(p[0],y+j,p[1]).setType(j%3==0?Material.CHISELED_STONE_BRICKS:Material.POLISHED_ANDESITE,false);
        for(int x=-4;x<=4;x++) for(int z=-4;z<=4;z++) {
            if(x*x+z*z<=16) w.getBlockAt(x,y+1,z).setType(Material.POLISHED_BLACKSTONE_BRICKS,false);
            if(x*x+z*z<=9) w.getBlockAt(x,y+2,z).setType(Material.PURPUR_BLOCK,false);
        }
        w.getBlockAt(0,y+3,0).setType(Material.CRYING_OBSIDIAN,false);
        w.getBlockAt(0,y+4,0).setType(Material.BEACON,false);
        int[][] crystals={{-3,-3},{3,-3},{-3,3},{3,3}};
        for(int[] p:crystals) {
            w.getBlockAt(p[0],y+3,p[1]).setType(Material.AMETHYST_BLOCK,false);
            w.getBlockAt(p[0],y+4,p[1]).setType(Material.END_ROD,false);
        }
        w.getPersistentDataContainer().set(key,org.bukkit.persistence.PersistentDataType.BYTE,(byte)1);
        w.setSpawnLocation(0,y+5,0);
        getLogger().info("Generated KASTARA ruined kingdom and summoning altar in "+w.getName());
    }

    private Location interpolate(List<Waypoint> points, double seconds) {
        if (seconds <= points.get(0).time) return points.get(0).toLocation();
        for (int i = 0; i < points.size() - 1; i++) {
            Waypoint a = points.get(i), b = points.get(i + 1);
            if (seconds <= b.time) {
                World world = Bukkit.getWorld(a.world);
                if (world == null) return null;
                double span = Math.max(0.001, b.time - a.time);
                double t = Math.max(0, Math.min(1, (seconds - a.time) / span));
                if (getConfig().getString("camera.interpolation", "smooth").equalsIgnoreCase("smooth")) {
                    t = t * t * (3 - 2 * t);
                }
                double x = lerp(a.x, b.x, t), y = lerp(a.y, b.y, t), z = lerp(a.z, b.z, t);
                float yaw = (float) (a.yaw + wrapDegrees(b.yaw - a.yaw) * t);
                float pitch = (float) lerp(a.pitch, b.pitch, t);
                return new Location(world, x, y, z, yaw, pitch);
            }
        }
        return points.get(points.size() - 1).toLocation();
    }

    private double number(Object value, double fallback) {
        return value instanceof Number n ? n.doubleValue() : fallback;
    }
    private double lerp(double a, double b, double t) { return a + (b - a) * t; }
    private double wrapDegrees(double degrees) {
        while (degrees < -180) degrees += 360;
        while (degrees >= 180) degrees -= 360;
        return degrees;
    }
    private GameMode parseGameMode(String value) {
        try { return GameMode.valueOf(value.toUpperCase()); }
        catch (IllegalArgumentException exception) { return GameMode.SURVIVAL; }
    }
    private String color(String value) {
        return ChatColor.translateAlternateColorCodes('&', value == null ? "" : value);
    }
    private void sendConfigured(CommandSender sender, String key) {
        sender.sendMessage(color(getConfig().getString("messages." + key, "")));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("skipintro")) {
            if (!(sender instanceof Player player)) { sendConfigured(sender, "only-player"); return true; }
            Session session = active.get(player.getUniqueId());
            if (session == null) { sendConfigured(sender, "skipped"); return true; }
            finishSession(player, session);
            sendConfigured(sender, "skipped");
            return true;
        }
        if (!command.getName().equalsIgnoreCase("kastaracutscene")) return false;
        if (!sender.hasPermission("kastara.cutscene.admin")) { sendConfigured(sender, "no-permission"); return true; }
        if (args.length == 0) { sendConfigured(sender, "usage"); return true; }
        switch (args[0].toLowerCase()) {
            case "reload" -> {
                reloadConfig();
                sendConfigured(sender, "reloaded");
            }
            case "play", "stop" -> {
                if (args.length < 2) {
                    if (!(sender instanceof Player self)) { sendConfigured(sender, "usage"); return true; }
                    if (args[0].equalsIgnoreCase("play")) startSession(self);
                    else stopSession(self, true, true);
                    return true;
                }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) { sendConfigured(sender, "no-player"); return true; }
                if (args[0].equalsIgnoreCase("play")) {
                    startSession(target);
                    sender.sendMessage(color(getConfig().getString("messages.started", "").replace("%player%", target.getName())));
                } else {
                    stopSession(target, true, true);
                    sender.sendMessage(color(getConfig().getString("messages.stopped", "").replace("%player%", target.getName())));
                }
            }
            default -> sendConfigured(sender, "usage");
        }
        return true;
    }

    private record Waypoint(double time, String world, double x, double y, double z, float yaw, float pitch) {
        private Location toLocation() {
            World target = Bukkit.getWorld(world);
            return target == null ? null : new Location(target, x, y, z, yaw, pitch);
        }
    }

    private static final class Session {
        private final UUID playerId;
        private final ArmorStand camera;
        private final Location returnLocation;
        private final GameMode originalMode;
        private final List<Waypoint> points;
        private BukkitTask task;
        private int elapsedTicks;
        private int sceneIndex;
        private boolean summonSoundPlayed;
        private Session(UUID playerId, ArmorStand camera, Location returnLocation, GameMode originalMode, List<Waypoint> points) {
            this.playerId = playerId;
            this.camera = camera;
            this.returnLocation = returnLocation;
            this.originalMode = originalMode;
            this.points = points;
        }
    }
}
