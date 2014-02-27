package co.piglet.validation;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.plugin.java.JavaPlugin;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.sql.ResultSet;
import java.util.Timer;
import java.util.TimerTask;

public class ValidationPlugin extends JavaPlugin implements Listener {

    // Redis connection pool
    private static JedisPool pool = new JedisPool(new JedisPoolConfig(), "localhost");

    // Monitoring thread time
    private Timer timer;
    private AndroidServer androidServer;


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


        timer = new Timer();
        timer.schedule(new MonitorTask(), 0, 60000);
        getLogger().info("PigletValidation v1.0 Monitoring thread scheduled");

        androidServer = new AndroidServer();
        Thread t = new Thread(androidServer);
        t.start();
        getLogger().info("PigletValidation v1.0 Android Interface started!");
    }



    /**
     * Called when the plugin is disabled by Bukkit
     */
    @Override
    public void onDisable() {
        timer.cancel();
        getLogger().info("PigletValidation v1.0 Monitoring thread terminated");


        androidServer.shutdown();
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

}