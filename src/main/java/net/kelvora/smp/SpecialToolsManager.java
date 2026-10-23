package net.kelvora.smp;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.BlockFace;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.block.Action;
import org.bukkit.command.*;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

final class SpecialToolsManager implements Listener, CommandExecutor, TabCompleter {
    private static final List<String> TOOLS=List.of("drill","gainer","sellaxe","treetrooper");
    private final JavaPlugin plugin;
    private final KelvorasManager currency;
    private final WorthManager worth;
    private final NamespacedKey idKey, createdKey, expiresKey, progressKey, targetKey;
    private final Set<String> processing=ConcurrentHashMap.newKeySet();
    private final Set<String> placedBlocks=ConcurrentHashMap.newKeySet();
    private final Map<UUID, BlockFace> lastMiningFace=new ConcurrentHashMap<>();
    private final File dataFile;
    private org.bukkit.configuration.file.YamlConfiguration data;
    private final java.security.SecureRandom random=new java.security.SecureRandom();
    private Economy economy;

    SpecialToolsManager(JavaPlugin plugin,KelvorasManager currency,WorthManager worth){
        this.plugin=plugin;this.currency=currency;this.worth=worth;
        idKey=new NamespacedKey(plugin,"kelvora-tool-id");createdKey=new NamespacedKey(plugin,"kelvora-tool-created");
        expiresKey=new NamespacedKey(plugin,"kelvora-tool-expires");progressKey=new NamespacedKey(plugin,"kelvora-gainer-progress");
        targetKey=new NamespacedKey(plugin,"kelvora-gainer-target");
        dataFile=new File(plugin.getDataFolder(),"special-tools-data.yml");
    }
    void register(){
        data=org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(dataFile);
        for(String s:data.getStringList("placed-blocks"))placedBlocks.add(s);
        Bukkit.getPluginManager().registerEvents(this,plugin);
        if(plugin.getCommand("kelvoratools")!=null){plugin.getCommand("kelvoratools").setExecutor(this);plugin.getCommand("kelvoratools").setTabCompleter(this);}
        RegisteredServiceProvider<Economy> rsp=Bukkit.getServicesManager().getRegistration(Economy.class);
        if(rsp!=null)economy=rsp.getProvider();
    }
    private boolean admin(CommandSender s){return s.isOp()||s.hasPermission("kelvora.tools.admin");}
    private String id(ItemStack item){if(item==null||!item.hasItemMeta())return null;return item.getItemMeta().getPersistentDataContainer().get(idKey,PersistentDataType.STRING);}
    private long expires(ItemStack item){return item.getItemMeta().getPersistentDataContainer().getOrDefault(expiresKey,PersistentDataType.LONG,0L);}
    private String pretty(String id){return switch(id){case "drill"->"Kelvora Drill";case "gainer"->"Kelvora Gainer";case "sellaxe"->"Kelvora Sellaxe";default->"Kelvora Tree Trooper";};}
    private Material material(String id){return switch(id){case "drill"->Material.DIAMOND_PICKAXE;case "gainer"->Material.NETHERITE_PICKAXE;case "sellaxe","treetrooper"->Material.NETHERITE_AXE;default->Material.STONE_PICKAXE;};}
    private ItemStack makeTool(String id){
        ItemStack item=new ItemStack(material(id));ItemMeta meta=item.getItemMeta();
        meta.displayName(Component.text(pretty(id),NamedTextColor.GREEN));
        List<Component> lore=new ArrayList<>();
        switch(id){
            case "drill"->{meta.addEnchant(Enchantment.EFFICIENCY,5,true);meta.addEnchant(Enchantment.UNBREAKING,3,true);meta.addEnchant(Enchantment.FORTUNE,3,true);meta.getPersistentDataContainer().set(createdKey,PersistentDataType.LONG,System.currentTimeMillis());meta.getPersistentDataContainer().set(expiresKey,PersistentDataType.LONG,System.currentTimeMillis()+plugin.getConfig().getLong("kelvora-tools.drill.lifetime-seconds",604800)*1000L);lore.add(Component.text("Mines a protected 3x3 plane",NamedTextColor.GRAY));}
            case "gainer"->{meta.addEnchant(Enchantment.EFFICIENCY,5,true);meta.addEnchant(Enchantment.UNBREAKING,3,true);meta.addEnchant(Enchantment.FORTUNE,3,true);meta.getPersistentDataContainer().set(progressKey,PersistentDataType.INTEGER,0);meta.getPersistentDataContainer().set(targetKey,PersistentDataType.INTEGER,random.nextInt(Math.max(1,plugin.getConfig().getInt("kelvora-tools.gainer.max-interval",168)))+1);lore.add(Component.text("May find hidden Kelvoras while mining",NamedTextColor.AQUA));}
            case "sellaxe"->{meta.addEnchant(Enchantment.EFFICIENCY,5,true);meta.addEnchant(Enchantment.UNBREAKING,3,true);meta.addEnchant(Enchantment.FORTUNE,3,true);lore.add(Component.text("Automatically sells eligible drops for Money",NamedTextColor.GOLD));}
            default->{meta.addEnchant(Enchantment.EFFICIENCY,5,true);meta.addEnchant(Enchantment.UNBREAKING,3,true);meta.addEnchant(Enchantment.FORTUNE,3,true);lore.add(Component.text("Chops connected tree logs",NamedTextColor.GREEN));}
        }
        meta.getPersistentDataContainer().set(idKey,PersistentDataType.STRING,id);
        if(id.equals("drill"))lore.add(Component.text("Lifetime: 7 days from issue",NamedTextColor.RED));
        lore.add(Component.text("Kelvora Special Tool",NamedTextColor.DARK_GRAY));meta.lore(lore);item.setItemMeta(meta);return item;
    }
    @Override public boolean onCommand(CommandSender s,Command c,String label,String[] args){
        if(!admin(s)){s.sendMessage("You do not have permission.");return true;}
        if(args.length!=3||!args[0].equalsIgnoreCase("give")){s.sendMessage("Usage: /kelvoratools give <player> <drill|gainer|sellaxe|treetrooper>");return true;}
        Player target=Bukkit.getPlayerExact(args[1]);String type=args[2].toLowerCase(Locale.ROOT);
        if(target==null||!TOOLS.contains(type)){s.sendMessage("Target must be online. Tools: "+String.join(", ",TOOLS));return true;}
        ItemStack tool=makeTool(type);Map<Integer,ItemStack> overflow=target.getInventory().addItem(tool);
        for(ItemStack leftover:overflow.values())target.getWorld().dropItemNaturally(target.getLocation(),leftover);
        s.sendMessage("Gave "+pretty(type)+" to "+target.getName()+".");target.sendMessage(Component.text("You received "+pretty(type)+".",NamedTextColor.GREEN));return true;
    }
    @Override public List<String> onTabComplete(CommandSender s,Command c,String a,String[] args){if(args.length==1)return List.of("give");if(args.length==2)return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();if(args.length==3)return TOOLS;return List.of();}

    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void trackPlaced(BlockPlaceEvent e){String key=blockKey(e.getBlockPlaced());placedBlocks.add(key);data.set("placed-blocks",new ArrayList<>(placedBlocks));save();}
    private String blockKey(Block b){return b.getWorld().getName()+":"+b.getX()+":"+b.getY()+":"+b.getZ();}
    private boolean eligible(Block b){return b!=null&&!b.getType().isAir()&&b.getType().isBlock()&&!Set.of(Material.BEDROCK,Material.BARRIER,Material.SPAWNER,Material.TRIAL_SPAWNER,Material.VAULT,Material.CHEST,Material.TRAPPED_CHEST,Material.BARREL,Material.ENDER_CHEST,Material.SHULKER_BOX).contains(b.getType())&&!b.getType().name().contains("COMMAND_BLOCK")&&!b.getType().name().contains("PORTAL");}
    private boolean natural(Block b){return !placedBlocks.contains(blockKey(b));}
    private boolean expired(ItemStack item){return "drill".equals(id(item))&&expires(item)>0&&System.currentTimeMillis()>=expires(item);}
    private void updateDrillLore(Player player, ItemStack item) {
        long left=Math.max(0,expires(item)-System.currentTimeMillis());
        long seconds=left/1000L, days=seconds/86400L, hours=(seconds%86400L)/3600L, minutes=(seconds%3600L)/60L;
        ItemMeta meta=item.getItemMeta();
        List<Component> lore=meta.lore()==null?new ArrayList<>():new ArrayList<>(meta.lore());
        for(int i=0;i<lore.size();i++){if(lore.get(i).toString().contains("Lifetime:")){lore.set(i,Component.text("Remaining: "+days+"d "+hours+"h "+minutes+"m",NamedTextColor.RED));}}
        meta.lore(lore);item.setItemMeta(meta);player.getInventory().setItemInMainHand(item);
    }
    private void downgrade(Player p,ItemStack item){
        ItemStack plain=new ItemStack(Material.DIAMOND_PICKAXE);ItemMeta old=item.getItemMeta();ItemMeta m=plain.getItemMeta();
        for(Map.Entry<Enchantment,Integer> e:old.getEnchants().entrySet())m.addEnchant(e.getKey(),e.getValue(),true);
        plain.setItemMeta(m);p.getInventory().setItemInMainHand(plain);
        p.sendMessage(Component.text("Your Kelvora Drill has expired and became an ordinary diamond pickaxe.",NamedTextColor.RED));
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void onBreak(BlockBreakEvent event){
        Player p=event.getPlayer();if(processing.contains(blockKey(event.getBlock())))return;ItemStack tool=p.getInventory().getItemInMainHand();String type=id(tool);if(type==null)return;
        if(type.equals("drill")&&expired(tool)){event.setCancelled(true);if(plugin.getConfig().getString("kelvora-tools.drill.expired-policy","convert-to-diamond-pickaxe").equalsIgnoreCase("remove")){p.getInventory().setItemInMainHand(null);p.sendMessage(Component.text("Your Kelvora Drill has expired and was removed.",NamedTextColor.RED));}else downgrade(p,tool);return;}
        if(type.equals("drill"))updateDrillLore(p,tool);
        if(type.equals("drill")&&restricted(event.getBlock())){event.setCancelled(true);return;}
        if(!p.getGameMode().equals(GameMode.SURVIVAL)&&!p.getGameMode().equals(GameMode.ADVENTURE))return;
        if(type.equals("gainer")){
            if(p.getGameMode()!=GameMode.SURVIVAL||!eligible(event.getBlock())||!natural(event.getBlock())||!plugin.getConfig().getStringList("kelvora-tools.gainer.eligible-blocks").contains(event.getBlock().getType().name()))return;
            int count=tool.getItemMeta().getPersistentDataContainer().getOrDefault(progressKey,PersistentDataType.INTEGER,0)+1;
            int target=tool.getItemMeta().getPersistentDataContainer().getOrDefault(targetKey,PersistentDataType.INTEGER,random.nextInt(168)+1);
            ItemMeta meta=tool.getItemMeta();meta.getPersistentDataContainer().set(progressKey,PersistentDataType.INTEGER,count);
            if(count>=target){
                int min=Math.max(1,plugin.getConfig().getInt("kelvora-tools.gainer.reward-min",5));
                int max=Math.max(min,plugin.getConfig().getInt("kelvora-tools.gainer.reward-max",75));
                double reward=min+random.nextInt(max-min+1);
                if(currency.deposit(p,reward)){
                    p.sendActionBar(Component.text("✦ +"+KelvorasManager.format(reward)+" Kelvoras",NamedTextColor.AQUA));
                    meta.getPersistentDataContainer().set(progressKey,PersistentDataType.INTEGER,0);
                    meta.getPersistentDataContainer().set(targetKey,PersistentDataType.INTEGER,random.nextInt(Math.max(1,plugin.getConfig().getInt("kelvora-tools.gainer.max-interval",168)))+1);
                }
            }
            tool.setItemMeta(meta);p.getInventory().setItemInMainHand(tool);return;
        }
        if(type.equals("sellaxe")&&natural(event.getBlock())&&plugin.getConfig().getStringList("kelvora-tools.sellaxe.eligible-blocks").contains(event.getBlock().getType().name())){
            if(economy==null){RegisteredServiceProvider<Economy> rsp=Bukkit.getServicesManager().getRegistration(Economy.class);if(rsp!=null)economy=rsp.getProvider();}
            if(economy==null)return;
            String sellKey=blockKey(event.getBlock());
            if(!processing.add(sellKey))return;
            try {
                List<ItemStack> drops=new ArrayList<>(event.getBlock().getDrops(tool,p));
                double total=0;for(ItemStack drop:drops)total+=worth.priceOf(drop.getType())*drop.getAmount();
                if(total<=0||!Double.isFinite(total))return;
                var response=economy.depositPlayer(p,total);
                if(response.transactionSuccess()){event.setDropItems(false);p.sendActionBar(Component.text("+$"+String.format(Locale.US,"%.2f",total),NamedTextColor.GREEN));}
            } finally { processing.remove(sellKey); }
            return;
        }
        if(type.equals("drill")||type.equals("treetrooper")){
            if(!eligible(event.getBlock()))return;
            List<Block> blocks=type.equals("drill")?drillPlane(event.getBlock(),p):treeBlocks(event.getBlock());
            if(blocks.size()<=1)return;
            event.setCancelled(true);
            String token="ability:"+blockKey(event.getBlock());
            if(!processing.add(token))return;
            try{int broken=0;for(Block b:blocks){if(broken>=maxBlocks(type))break;if(b.getType().isAir()||!eligible(b))continue;if(breakProtectedAware(p,b,tool,type)){broken++;if(tool.getType().getMaxDurability()>0)damage(tool,p);}}
                p.sendActionBar(Component.text((type.equals("drill")?"Drill mined ":"Tree Trooper chopped ")+broken+" blocks",NamedTextColor.GREEN));
            }finally{processing.remove(token);}
        }
    }
    private int maxBlocks(String type){return type.equals("drill")?9:Math.max(1,plugin.getConfig().getInt("kelvora-tools.treetrooper.max-logs",64));}
    @EventHandler(priority=EventPriority.LOWEST)
    public void rememberMiningFace(PlayerInteractEvent event) {
        if(event.getAction()==Action.LEFT_CLICK_BLOCK && event.getClickedBlock()!=null
                && "drill".equals(id(event.getItem())) && event.getBlockFace()!=null) {
            lastMiningFace.put(event.getPlayer().getUniqueId(), event.getBlockFace());
        }
    }
    private List<Block> drillPlane(Block target,Player p){
        List<Block> out=new ArrayList<>();
        BlockFace face=lastMiningFace.getOrDefault(p.getUniqueId(),p.getFacing());
        for(int a=-1;a<=1;a++)for(int b=-1;b<=1;b++){
            Block block;
            if(face==BlockFace.UP||face==BlockFace.DOWN)block=target.getRelative(a,0,b);
            else if(face==BlockFace.EAST||face==BlockFace.WEST)block=target.getRelative(0,a,b);
            else block=target.getRelative(a,b,0);
            if(eligible(block)&&!restricted(block))out.add(block);
        }return out;
    }
    private boolean restricted(Block b){
        if(b.getType().name().contains("SPAWNER")||b.getState() instanceof org.bukkit.block.Container||b.getType()==Material.BEDROCK||b.getType()==Material.END_PORTAL_FRAME)return true;
        return plugin.getConfig().getStringList("kelvora-tools.drill.restricted-blocks").contains(b.getType().name());
    }
    private List<Block> treeBlocks(Block target){
        if(!isLog(target.getType()))return List.of(target);
        int limit=Math.max(1,plugin.getConfig().getInt("kelvora-tools.treetrooper.max-logs",64));
        List<Block> found=new ArrayList<>();ArrayDeque<Block> queue=new ArrayDeque<>();Set<String> seen=new HashSet<>();queue.add(target);
        while(!queue.isEmpty()&&found.size()<limit){Block b=queue.removeFirst();String key=blockKey(b);if(!seen.add(key)||!isLog(b.getType())||restricted(b)||!natural(b))continue;found.add(b);
            for(int x=-1;x<=1;x++)for(int y=-1;y<=1;y++)for(int z=-1;z<=1;z++)if(x!=0||y!=0||z!=0){Block n=b.getRelative(x,y,z);if(!seen.contains(blockKey(n))&&isLog(n.getType()))queue.add(n);}
        }return found;
    }
    private boolean isLog(Material m){String n=m.name();return n.endsWith("_LOG")||n.endsWith("_WOOD")||n.equals("CRIMSON_STEM")||n.equals("WARPED_STEM")||n.equals("STRIPPED_CRIMSON_STEM")||n.equals("STRIPPED_WARPED_STEM");}
    private boolean breakProtectedAware(Player p,Block b,ItemStack tool,String type){
        if(!eligible(b)||restricted(b)||!natural(b))return false;
        String key=blockKey(b);if(!processing.add(key))return false;
        try{
            BlockBreakEvent check=new BlockBreakEvent(b,p);Bukkit.getPluginManager().callEvent(check);if(check.isCancelled())return false;
            Collection<ItemStack> drops=b.getDrops(tool,p);
            for(ItemStack drop:drops){if(drop==null||drop.getType().isAir())continue;Map<Integer,ItemStack> overflow=p.getInventory().addItem(drop.clone());for(ItemStack rest:overflow.values())b.getWorld().dropItemNaturally(b.getLocation(),rest);}
            b.setType(Material.AIR,false);
            return true;
        }finally{processing.remove(key);}
    }
    private void damage(ItemStack tool,Player p){
        if(tool==null||!tool.hasItemMeta())return;ItemMeta meta=tool.getItemMeta();if(!(meta instanceof Damageable d))return;
        int damage=d.getDamage()+1;if(tool.containsEnchantment(Enchantment.UNBREAKING)){int level=tool.getEnchantmentLevel(Enchantment.UNBREAKING);if(random.nextInt(level+1)!=0)return;}
        if(damage>=tool.getType().getMaxDurability()){p.getInventory().setItemInMainHand(null);p.playSound(p.getLocation(),Sound.ENTITY_ITEM_BREAK,1,1);}else{d.setDamage(damage);tool.setItemMeta(meta);}
    }
    private boolean save(){try{data.set("placed-blocks",new ArrayList<>(placedBlocks));data.save(dataFile);return true;}catch(IOException e){plugin.getLogger().severe("Could not save special tool state: "+e.getMessage());return false;}}
}
