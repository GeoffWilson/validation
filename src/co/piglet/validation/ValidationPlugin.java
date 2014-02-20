package co.piglet.validation;

import com.sk89q.worldedit.Vector;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.bukkit.block.Skull;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.material.MaterialData;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.io.*;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import static com.sk89q.worldguard.bukkit.BukkitUtil.toVector;

public class ValidationPlugin extends JavaPlugin implements Listener {
    // WorldGuard
    private WorldGuardPlugin worldGuard;

    // Redis connection pool
    private static JedisPool pool = new JedisPool(new JedisPoolConfig(), "localhost");

    // Monitoring thread time
    private Timer timer;
    private AndroidServer androidServer;

    private int fireWorkTaskID;
    private int pigletRainTaskID;
    private int pigletUndoTaskID;

    private ArrayList<FireworkLocation> fireworkSpawnerLocations;
    private int fireworkCounter = 0;

    private ConcurrentLinkedQueue<Pig> pigRain;

    private Color[] colors;

    private WorldGuardPlugin getWorldGuard() {
        Plugin plugin = getServer().getPluginManager().getPlugin("WorldGuard");
        if (plugin == null || !(plugin instanceof WorldGuardPlugin)) {
            return null; // Maybe you want throw an exception instead
        }
        return (WorldGuardPlugin) plugin;
    }

    /**
     * This is called once a minute to report the current server status into Redis
     */
    private class MonitorTask extends TimerTask {
        @Override
        public void run() {
            // Bukkit docs indicate this is a thread safe method, so happy to call it from here
            Server server = Bukkit.getServer();
            Jedis redis = pool.getResource();

            // The "getOnlinePlayers()" method probably isn't safe, but we're only accessing the count
            redis.hset("_server", "players.online", String.valueOf(server.getOnlinePlayers().length));
            redis.hset("_server", "check.timestamp", String.valueOf(System.currentTimeMillis()));
            pool.returnResource(redis);
        }
    }

    /**
     * Called when the plugin is enabled by Bukkit
     */
    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        worldGuard = getWorldGuard();

        colors = new Color[]{Color.AQUA, Color.BLUE, Color.FUCHSIA, Color.GREEN, Color.LIME, Color.MAROON, Color.fromRGB(0xF03D7B), Color.NAVY, Color.ORANGE, Color.PURPLE, Color.RED, Color.SILVER, Color.TEAL, Color.WHITE, Color.YELLOW, Color.fromRGB(255, 123, 0)};

        this.pigRain = new ConcurrentLinkedQueue<>();
        this.fireworkSpawnerLocations = new ArrayList<>();

        try {
            FileInputStream fileInputStream = new FileInputStream("fireworks.obj");
            ObjectInputStream objectInputStream = new ObjectInputStream(fileInputStream);
            fireworkSpawnerLocations = (ArrayList<FireworkLocation>) objectInputStream.readObject();
        } catch (IOException | ClassNotFoundException e) {
            e.printStackTrace();
        }

        timer = new Timer();
        timer.schedule(new MonitorTask(), 0, 60000);
        getLogger().info("PigletValidation v1.0 Monitoring thread scheduled");

        androidServer = new AndroidServer();
        Thread t = new Thread(androidServer);
        t.start();
        getLogger().info("PigletValidation v1.0 Android Interface started!");
    }

    private class PigRainRunnable implements Runnable {
        private World world;
        private Player player;
        private JavaPlugin parent;

        private AtomicInteger pigCounter = new AtomicInteger(0);

        public PigRainRunnable(World world, Player player, JavaPlugin parent) {
            this.world = world;
            this.player = player;
            this.parent = parent;

            world.setStorm(true);
            world.setThundering(true);
        }

        @Override
        public void run() {
            int x = pigCounter.incrementAndGet();

            int randomX = (int) (Math.random() * 15);
            int randomZ = (int) (Math.random() * 15);

            Location playerLocation = player.getLocation();

            boolean flip = Math.random() >= 0.50D;
            int newX = flip ? playerLocation.getBlockX() + randomX : playerLocation.getBlockX() - randomX;
            flip = Math.random() >= 0.50D;
            int newZ = flip ? playerLocation.getBlockZ() + randomZ : playerLocation.getBlockZ() - randomZ;

            Location spawnLocation = new Location(player.getWorld(), newX, 128, newZ);

            if (Math.random() > 0.75D) world.strikeLightning(spawnLocation.add(100, 0, 0));

            Pig piglet = (Pig) this.world.spawnEntity(spawnLocation, EntityType.PIG);
            piglet.setBaby();
            piglet.setMetadata("pig_rain", new FixedMetadataValue(parent, true));

            pigRain.add(piglet);

            if (x >= 100) {
                getServer().getScheduler().cancelTask(pigletRainTaskID);
                pigletUndoTaskID = getServer().getScheduler().scheduleSyncRepeatingTask(parent, new PigletRemoverRunnable(), 100, 15);
                world.setStorm(false);
                world.setThundering(false);
                pigletRainTaskID = 0;
            }
        }
    }

    private class PigletRemoverRunnable implements Runnable {
        @Override
        public void run() {
            if (pigRain.size() > 0) {
                Pig pig = pigRain.poll();
                pig.remove();
            } else {
                getServer().getScheduler().cancelTask(pigletUndoTaskID);
            }
        }
    }

    private class FireworkRunnable implements Runnable {

        private Location[] spanwerLocations;

        private World world;

        private long startTime;
        private int mode;

        private JavaPlugin parent;

        public FireworkRunnable(World world, int mode, JavaPlugin parent, Location[] locations){
            this.world = world;
            this.mode = mode;
            this.parent = parent;
            this.spanwerLocations = locations;

            startTime = System.currentTimeMillis();
        }

        @Override
        public void run() {

            for (Location l : spanwerLocations){
                Firework firework = (Firework) world.spawnEntity(l, EntityType.FIREWORK);
                spawnBallFirework(firework);
            }

            switch (mode) {
                case 0:
                    if (System.currentTimeMillis() - startTime > 30000) {
                        getServer().getScheduler().cancelTask(fireWorkTaskID);

                        // Schedule new task
                        fireWorkTaskID = getServer().getScheduler().scheduleSyncRepeatingTask(parent, new FireworkRunnable(getServer().getWorld("world"), 1, parent, spanwerLocations), 20L, 10L);
                    }

                    break;
                case 1:
                    if (System.currentTimeMillis() - startTime > 10000)
                        getServer().getScheduler().cancelTask(fireWorkTaskID);
            }
        }
    }

    /**
     * Called when the plugin is disabled by Bukkit
     */
    @Override
    public void onDisable() {
        timer.cancel();
        getLogger().info("PigletValidation v1.0 Monitoring thread terminated");

        try {
            FileOutputStream fileOutput = new FileOutputStream("fireworks.obj");
            ObjectOutputStream oos = new ObjectOutputStream(fileOutput);
            oos.writeObject(fireworkSpawnerLocations);
        } catch (IOException e) {
            e.printStackTrace();
        }

        androidServer.shutdown();
    }

    /**
     * Monitors interaction events, used for the gambling machine in the Jazz club
     *
     * @param event PlayerInteractEvent to look at
     */
    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();

        if (player.getItemInHand().getType() == Material.STICK) {
            if (player.hasPermission("validation.fireworks")) {
                if (event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK) {
                    spawnFireworks(player);
                }
            }
        }
    }

    /**
     * Logs the death to Redis
     *
     * @param event EntityDeathEvent to log
     */
    @EventHandler
    public void onEntityDeath(EntityDeathEvent event) {
        Jedis redis = pool.getResource();
        Entity entity = event.getEntity();
        if (entity instanceof Animals) {
            Player player = ((Animals) entity).getKiller();
            if (player != null) {
                redis.hincrBy(player.getName(), "combat.animalsKilled", 1);
                redis.hincrBy("_server", "combat.animalsKilled", 1);
            }
        } else if (entity instanceof Monster) {
            Player player = ((Monster) entity).getKiller();
            if (player != null) {
                redis.hincrBy(player.getName(), "combat.mobsKilled", 1);
                redis.hincrBy("_server", "combat.mobsKilled", 1);
            }
        } else if (entity instanceof Player) {
            Player player = (Player) entity;
            redis.hincrBy(player.getName(), "combat.deaths", 1);
            redis.hincrBy("_server", "combat.deaths", 1);
        }
        pool.returnResource(redis);
    }

    /**
     * Monitors player join events, writes the join time to Redis for calculating total play time.
     * Checks the player for their user_id metadata and assigns it where it is no present (assuming they
     * have registered on the site)
     *
     * @param event PlayerJoinEvent to log
     */
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        Jedis redis = pool.getResource();
        redis.hset(player.getName(), "time.lastJoin", String.valueOf((System.currentTimeMillis() / 1000)));

        // Check if the user has an ID set
        if (!player.hasMetadata("piglet.user_id")) {
            // No user_id set so we need to look it up in the database and set it here
            Database database = new Database(getLogger());
            int userID = database.getUserIDByMinecraftName(player.getName());
            if (userID > 0) player.setMetadata("piglet.user_id", new FixedMetadataValue(this, userID));
        }

        pool.returnResource(redis);
    }

    /**
     * Monitors player leave events, writes their playtime for this session to Redis,and also dumps their
     * current inventory in JSON format into Redis for display on the website
     *
     * @param event PlayerLeaveEvent to log
     */
    @EventHandler
    public void onPlayerLeave(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        PlayerInventory playerInventory = player.getInventory();

        Jedis redis = pool.getResource();
        long joinTime = Long.parseLong(redis.hget(player.getName(), "time.lastJoin"));
        long leaveTime = System.currentTimeMillis() / 1000;

        long timePlayedInSeconds = leaveTime - joinTime;
        redis.hincrBy(player.getName(), "time.total", timePlayedInSeconds);
        redis.hset(player.getName(), "time.lastLeave", String.valueOf(leaveTime));

        String inventoryJSON = "{ \"inventory\" : [ ";

        for (ItemStack item : playerInventory) {
            if (item != null) {
                int count = item.getAmount();
                String type = item.getType().toString();
                int id = item.getTypeId();
                byte data = item.getData().getData();
                inventoryJSON += "{ \"id\" : " + id + ", \"count\" : " + count + ", \"string\" : \"" + type + "\", \"data\" : " + data + "},";
            }
        }

        inventoryJSON = inventoryJSON.substring(0, inventoryJSON.length() - 1);
        inventoryJSON += "] }";

        redis.hset(player.getName(), "inventory", inventoryJSON);
        pool.returnResource(redis);
    }

    /**
     * Handles events caused by a block being broken. Logs the event to the Redis database for the player
     * who broke the block.
     *
     * @param event BlockBreakEvent is the event fired by the broken block
     */
    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Player p = event.getPlayer();
        Jedis redis = pool.getResource();
        redis.hincrBy(p.getName(), "block.break", 1);
        pool.returnResource(redis);

        if (p.isOp() && inSpawn(p)) {
            Block b = event.getBlock();
            if (b.getType() == Material.SKULL) {
                Skull skull = (Skull) b.getState();
                System.out.println(skull.hasOwner());
                System.out.println(skull.getSkullType());
                System.out.println(skull.getOwner());
                if (skull.getOwner().equals("MHF_TNT2")) {
                    if (fireworkSpawnerLocations.contains(new FireworkLocation(skull.getLocation()))) {
                        fireworkSpawnerLocations.remove(new FireworkLocation(skull.getLocation()));
                    }
                }
            }
        }
    }

    /**
     * Handles events caused by a block being placed, logs the event to the Redis database for the player
     * who placed the block
     *
     * @param event BlockPlacedEvent
     */
    @EventHandler
    public void onBlockPlaced(BlockPlaceEvent event) {

        Player p = event.getPlayer();
        Jedis redis = pool.getResource();
        redis.hincrBy(p.getName(), "block.placed", 1);
        pool.returnResource(redis);

        if (p.isOp() && inSpawn(p))  {
            Block b = event.getBlock();
            if (b.getType() == Material.SKULL) {
                if (p.getItemInHand().getType() == Material.SKULL_ITEM) {
                    if (((SkullMeta)p.getItemInHand().getItemMeta()).getOwner().equals("MHF_TNT2")){
                        System.out.println("Block Added");
                        fireworkSpawnerLocations.add(new FireworkLocation(b.getLocation()));
                    }
                }
            }
        }
    }

    @EventHandler
    public void onEntityDamage(EntityDamageEvent event) {
        Entity victim = event.getEntity();

        if (victim instanceof Pig) {
            Pig pig = (Pig) victim;
            if (pig.hasMetadata("pig_rain")) {
                if (event.getCause() == EntityDamageEvent.DamageCause.FIRE) {
                    event.setCancelled(true);
                }

                if (event.getCause() == EntityDamageEvent.DamageCause.FALL) {
                    event.setCancelled(true);
                }

                if (event.getCause() == EntityDamageEvent.DamageCause.LIGHTNING) {
                    event.setCancelled(true);
                }
            }
        }
    }

    /**
     * Monitors entities for damage and takes action in certain situations
     * 1) Punishes players with lighting strike for attacking piglets
     * 2) Spawns the "P.I.G" to deal retribution for PVP in spawn and other protected zones
     *
     * @param event EntityDamageByEntityEvent to check for infractions
     */
    @EventHandler
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        Entity entity = event.getDamager();
        Entity victim = event.getEntity();

        if (entity instanceof Player) {
            Player player = (Player) entity;

            // Piglet protection
            if (victim.getType() == EntityType.PIG) {
                Pig pig = (Pig) victim;
                if (!pig.isAdult()) {
                    //player.getWorld().strikeLightning(player.getLocation());
                    spawnPIG(player);
                }
                return;
            }

            // P.I.G code
            if (victim.getType() == EntityType.PLAYER) {
                if (inSpawn(player)) {
                    spawnPIG(player);
                }
            }
        }
    }

    private void spawnPIG(Player player) {
        Location location = player.getLocation();

        int x = location.getBlockX();
        int y = location.getBlockY();
        int z = location.getBlockZ();

        int[] xLocations = {x - 5, x + 5, x - 5, x + 5};
        int[] zLocations = {z + 5, z + 5, z - 5, z - 5};

        for (int i = 0; i < 4; i++) {
            Location spawnLocation = new Location(player.getWorld(), xLocations[i], y, zLocations[i]);
            PigZombie guard = (PigZombie) player.getWorld().spawnEntity(spawnLocation, EntityType.PIG_ZOMBIE);
            EntityEquipment ee = guard.getEquipment();

            //player.getWorld().strikeLightning(player.getLocation()); // Fire hazard to removed
            HashMap<Enchantment, Integer> enchantmentIntegerHashMap = new HashMap<>();
            enchantmentIntegerHashMap.put(Enchantment.PROTECTION_ENVIRONMENTAL, 3);
            enchantmentIntegerHashMap.put(Enchantment.THORNS, 3);

            ee.setHelmet(setArmorColor(Color.BLACK, new ItemStack(Material.LEATHER_HELMET)));
            ee.setChestplate(setArmorColor(Color.BLACK, new ItemStack(Material.LEATHER_CHESTPLATE)));
            ee.getChestplate().addEnchantments(enchantmentIntegerHashMap);
            ee.setLeggings(setArmorColor(Color.BLACK, new ItemStack(Material.LEATHER_LEGGINGS)));
            ee.setBoots(setArmorColor(Color.BLACK, new ItemStack(Material.LEATHER_BOOTS)));

            ItemStack sword = new ItemStack(Material.DIAMOND_SWORD);
            sword.addEnchantment(Enchantment.FIRE_ASPECT, 2);
            sword.addEnchantment(Enchantment.DAMAGE_ALL, 5);
            ee.setItemInHand(sword);

            /*ItemStack bow = new ItemStack(Material.BOW);
            ee.setItemInHand(bow);    */

            guard.setMetadata("guard.target", new FixedMetadataValue(this, player.getName()));

            guard.setAngry(true);
            guard.setTarget(player);
        }
    }

    /**
     * Used to monitor the P.I.G spawned entities and remove them if they switch from their
     * original target
     *
     * @param event EntityTargetEvent to check
     * @return True if the event was OK, False if not (not really used)
     */
    @EventHandler
    public boolean onEntityTarget(EntityTargetEvent event) {
        if (event.getEntity() instanceof PigZombie) {
            PigZombie guard = (PigZombie) event.getEntity();
            if (guard.hasMetadata("guard.target")) {
                String target = guard.getMetadata("guard.target").get(0).asString();

                Entity targetEntity = event.getTarget();
                if (targetEntity instanceof Player) {
                    Player targetPlayer = (Player) targetEntity;
                    if (!targetPlayer.getName().equalsIgnoreCase(target)) {
                        guard.remove();
                    }
                }
            }
        }
        return true;
    }

    /**
     * Takes an item stack and applies color to the meat data (use for coloring Armor)
     *
     * @param color     The color to apply to the armor
     * @param itemStack The item stack containing the armor item
     * @return Colored item stack
     */
    private ItemStack setArmorColor(Color color, ItemStack itemStack) {
        ItemMeta meta = itemStack.getItemMeta();
        LeatherArmorMeta armorMeta = (LeatherArmorMeta) meta;
        armorMeta.setColor(color);
        itemStack.setItemMeta(armorMeta);
        return itemStack;
    }

    private void spawnStarFirework(Firework fireWork) {
        double random = Math.random() * (colors.length - 1);
        int intRandom = (int) Math.round(random);

        FireworkMeta fireworkMeta = fireWork.getFireworkMeta();

        FireworkEffect.Builder effectBuilder = FireworkEffect.builder();
        effectBuilder.withColor(colors[intRandom]);
        effectBuilder.with(FireworkEffect.Type.STAR);
        fireworkMeta.addEffect(effectBuilder.build());

        fireworkMeta.setPower(3);

        fireWork.setFireworkMeta(fireworkMeta);
    }

    private void spawnBurstFirework(Firework fireWork) {
        double random = Math.random() * (colors.length - 1);
        int intRandom = (int) Math.round(random);

        FireworkMeta fireworkMeta = fireWork.getFireworkMeta();

        FireworkEffect.Builder effectBuilder = FireworkEffect.builder();
        effectBuilder.withColor(colors[intRandom]);
        effectBuilder.with(FireworkEffect.Type.BURST);

        random = Math.random();
        if (random < 0.3D) effectBuilder.trail(true);
        else if (random < 0.6D) effectBuilder.flicker(true);

        fireworkMeta.addEffect(effectBuilder.build());
        fireworkMeta.setPower(3);

        fireWork.setFireworkMeta(fireworkMeta);
    }

    private void spawnCreeperFirework(Firework fireWork) {
        FireworkMeta fireworkMeta = fireWork.getFireworkMeta();

        FireworkEffect.Builder effectBuilder = FireworkEffect.builder();
        effectBuilder.withColor(Color.GREEN);
        effectBuilder.with(FireworkEffect.Type.CREEPER);

        double random = Math.random();
        if (random < 0.3D) effectBuilder.trail(true);
        else if (random < 0.6D) effectBuilder.flicker(true);

        fireworkMeta.addEffect(effectBuilder.build());
        fireworkMeta.setPower(3);
        fireWork.setFireworkMeta(fireworkMeta);
    }

    private void spawnBallFirework(Firework fireWork) {
        double random = Math.random() * (colors.length - 1);
        int intRandom = (int) Math.round(random);

        FireworkMeta fireworkMeta = fireWork.getFireworkMeta();

        FireworkEffect.Builder effectBuilder = FireworkEffect.builder();
        effectBuilder.withColor(colors[intRandom]);
        effectBuilder.with(FireworkEffect.Type.BALL_LARGE);
        fireworkMeta.addEffect(effectBuilder.build());

        random = Math.random() * (colors.length - 1);
        intRandom = (int) Math.round(random);
        effectBuilder = FireworkEffect.builder();
        effectBuilder.withColor(colors[intRandom]);
        effectBuilder.with(FireworkEffect.Type.BALL);

        random = Math.random();
        if (random < 0.3D) effectBuilder.trail(true);
        else if (random < 0.6D) effectBuilder.flicker(true);

        fireworkMeta.addEffect(effectBuilder.build());
        fireworkMeta.setPower(3);

        ItemStack s = new ItemStack(Material.SKULL_ITEM, 3);
        SkullMeta m = (SkullMeta) s.getItemMeta();
        MaterialData md = s.getData();

        fireWork.setFireworkMeta(fireworkMeta);
    }

    private void spawnFireworks(Player player) {
        World world = player.getWorld();
        Location location = player.getLocation();

        for (int i = 0; i < 5; i++) {
            location.setX(location.getBlockX() + i);

            Firework fireWork = (Firework) world.spawnEntity(location, EntityType.FIREWORK);

            int random = (int) Math.round(Math.random() * 10);

            switch (random) {
                case 1:
                    spawnBurstFirework(fireWork);
                    break;
                case 2:
                    spawnCreeperFirework(fireWork);
                    break;
                case 3:
                    spawnStarFirework(fireWork);
                    break;
                default:
                    spawnBallFirework(fireWork);
            }
        }
    }

    /**
     * Process commands that this plugins supports
     *
     * @param sender The entity who sent the command (Player, Block, Console)
     * @param cmd    The command the entity sent
     * @param label  ??
     * @param args   The parameters provided by the sender
     * @return True if the command was execute, False if the command was ignored/cancelled)
     */
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        switch (cmd.getName()) {

            case "fwblock":
                if (sender instanceof BlockCommandSender) {
                    fireworkCounter ++;
                    if (fireworkCounter >= 7) {
                        fireworkCounter = 0;
                        ArrayList<Location> locations = new ArrayList<>();
                        for (FireworkLocation fwl : fireworkSpawnerLocations) {
                            locations.add(new Location(Bukkit.getWorld("world"), fwl.x, fwl.y, fwl.z));
                        }
                        fireWorkTaskID = getServer().getScheduler().scheduleSyncRepeatingTask(this, new FireworkRunnable(Bukkit.getWorld("world"), 0, this, locations.toArray(new Location[locations.size()])), 20L, 20L);
                    }
                }

                break;

            case "fw":
                if (sender instanceof Player) {
                    Player player = (Player) sender;
                    if (player.isOp()) {
                        ArrayList<Location> locations = new ArrayList<>();
                        for (FireworkLocation fwl : fireworkSpawnerLocations) {
                            locations.add(new Location(player.getWorld(), fwl.x, fwl.y, fwl.z));
                        }
                        fireWorkTaskID = getServer().getScheduler().scheduleSyncRepeatingTask(this, new FireworkRunnable(Bukkit.getWorld("world"), 0, this, locations.toArray(new Location[locations.size()])), 20L, 20L);
                    }
                }
                break;

            case "rain":
                if (sender instanceof Player) {
                    Player player = (Player) sender;
                    if (pigletRainTaskID > 0) {
                        player.sendMessage(ChatColor.RED + "It's already raining!");
                    } else {
                        Bukkit.broadcastMessage(ChatColor.BLUE + "A pigletstorm is brewing...");
                        pigletRainTaskID = getServer().getScheduler().scheduleSyncRepeatingTask(this, new PigRainRunnable(this.getServer().getWorld("world"), player, this), 5L, 5L);
                    }
                }
                break;

            case "tnt":
                if (sender instanceof Player) {
                    Player player = (Player) sender;

                    if (player.isOp()) {
                        for (int i = 0; i < Integer.parseInt(args[0]); i++) {
                            player.getWorld().spawnEntity(player.getLocation(), EntityType.PRIMED_TNT);
                        }

                        return true;
                    }
                }
                break;

            case "ynennjgkamvpctbfrcrz":
                if (sender instanceof BlockCommandSender) {
                    BlockCommandSender block = (BlockCommandSender) sender;
                    Location l = block.getBlock().getLocation();
                    l.setZ(l.getBlockZ() + 1);
                    l.setY(l.getBlockY());
                    l.setX(l.getBlockX());
                    l.getWorld().getBlockAt(l).setTypeId(46);
                    return true;
                }
                break;


            case "validate":
                if (sender instanceof Player) {
                    Player player = (Player) sender;
                    if (args.length == 1) {
                        try {
                            Database database = new Database(getLogger());
                            int playerID = database.getPlayerByAuthKey(args[0]);

                            if (playerID > 0) {
                                if (!database.checkMinecraftAccountNotLinked(player.getName())) {
                                    // Register this MC account
                                    if (database.linkMinecraftAccount(player.getName(), playerID)) {
                                        /*PermissionUser user = PermissionsEx.getUser(player);
                                        user.setGroups(new String[]{"members"});      */
                                        player.sendMessage(ChatColor.GREEN + "Registration Completed!");

                                        // Assign a vault to the user
                                        ResultSet result = database.getNextFreeVault();
                                        if (result != null) {
                                            if (result.next()) {
                                                int vaultID = result.getInt("id");
                                                // Allocate this vault to the user
                                                if (vaultID > 0) if (database.allocateVault(playerID, vaultID)) {
                                                    // Update the sign
                                                    int x = result.getInt("x");
                                                    int y = result.getInt("y");
                                                    int z = result.getInt("z");

                                                    int level = Math.abs(((y + 1) / 5) - 13);
                                                    int vault = z == -24 || z == -34 || z == -14 ? 1 : 2;
                                                    String color = "";

                                                    switch (vault) {
                                                        case 1:
                                                            if (x == 517 && z == -24) color = "BLUE";
                                                            else if (x == 537 && z == -24) color = "RED";
                                                            else if (x == 527 && z == -34) color = "GREEN";
                                                            else color = "YELLOW";
                                                            break;
                                                        case 2:
                                                            if (x == 537 && z == 19) color = "BLUE";
                                                            else if (x == 517 && z == 19) color = "RED";
                                                            else if (x == 527 && z == 29) color = "GREEN";
                                                            else color = "YELLOW";
                                                            break;
                                                    }

                                                    Jedis redis = pool.getResource();
                                                    redis.hset(player.getName(), "bank.level", String.valueOf(level));
                                                    redis.hset(player.getName(), "bank.vault", String.valueOf(vault));
                                                    redis.hset(player.getName(), "bank.color", color.toUpperCase());
                                                    redis.hset(player.getName(), "bank.hasBank", "1");

                                                    // BLU V:1 X: -1535 Z: -1106
                                                    // GRN V:1 X: -1525 Z: -1115
                                                    // RED V:1 X: -1515 Z: -1106
                                                    // YEL V:1 X: -1525 Z: -1096

                                                    // BLU V:2 X: -1515 Z: -1063
                                                    // GRN V:2 X: -1525 Z: -1053
                                                    // RED V:2 X: -1535 Z: -1063
                                                    // YEL V:2 X: -1525 Z: -1073

                                                    switch (color) {
                                                        case "RED":
                                                            color = ChatColor.RED + color;
                                                            break;
                                                        case "BLUE":
                                                            color = ChatColor.BLUE + color;
                                                            break;
                                                        case "GREEN":
                                                            color = ChatColor.GREEN + color;
                                                            break;
                                                        case "YELLOW":
                                                            color = ChatColor.YELLOW + color;
                                                            break;
                                                    }

                                                    player.sendMessage(ChatColor.GOLD + "Your bank vault is located at");
                                                    player.sendMessage(String.format("VAULT: %d LEVEL: %d COLOUR: %s", vault, level, color));

                                                    Location signLocation = new Location(player.getWorld(), x, y + 2, z);

                                                    Block signBlock = player.getLocation().getWorld().getBlockAt(signLocation);
                                                    BlockState signState = signBlock.getState();

                                                    if (signState instanceof Sign) {
                                                        Sign sign = (Sign) signState;
                                                        sign.setLine(0, player.getName());
                                                        sign.update();
                                                    }
                                                }
                                            }
                                        }
                                    }
                                } else {
                                    player.sendMessage(ChatColor.RED + "This Minecraft account is already registered on piglet.co.");
                                }
                            } else {
                                player.sendMessage(ChatColor.RED + "Invalid token provided. Please check and try again.");
                            }
                        } catch (Exception e) {
                            getLogger().info(e.getLocalizedMessage());
                        }
                    } else return false;

                }
                break;
        }

        return true;
    }

    public boolean inSpawn(Player player) {
        Vector pt = toVector(player.getLocation()); // This also takes a location

        RegionManager regionManager = worldGuard.getRegionManager(player.getWorld());
        ApplicableRegionSet set = regionManager.getApplicableRegions(pt);

        for (ProtectedRegion region : set) {
            if (region.getId().equalsIgnoreCase("spawn")) {
                return true;
            }
        }

        return false;
    }
}