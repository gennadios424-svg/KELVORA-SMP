package net.kelvora.smp;

import java.util.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

final class SpawnerManager implements Listener {
    private static final String TITLE = "KELVORA // SPAWNER";
    private static final long INTERVAL = 25_000L;
    private final JavaPlugin plugin;
    private final NamespacedKey crateKey, managedKey, stackKey, ownerKey, autoKey, lastKey;
    private final NamespacedKey bonesKey, bowsKey, arrowsKey, stringKey, eyeKey, ingotKey, poppyKey;
    private final Random random = new Random();

    SpawnerManager(JavaPlugin plugin) {
        this.plugin=plugin;
        crateKey=new NamespacedKey(plugin,"spawner-reward-entity");
        managedKey=new NamespacedKey(plugin,"kelvora-storage-spawner");
        stackKey=new NamespacedKey(plugin,"spawner-stack-count");
        ownerKey=new NamespacedKey(plugin,"spawner-owner");
        autoKey=new NamespacedKey(plugin,"spawner-auto-collection");
        lastKey=new NamespacedKey(plugin,"spawner-last-production");
        bonesKey=new NamespacedKey(plugin,"stored-bones"); bowsKey=new NamespacedKey(plugin,"stored-bows");
        arrowsKey=new NamespacedKey(plugin,"stored-arrows"); stringKey=new NamespacedKey(plugin,"stored-string");
        eyeKey=new NamespacedKey(plugin,"stored-spider-eyes"); ingotKey=new NamespacedKey(plugin,"stored-iron-ingots");
        poppyKey=new NamespacedKey(plugin,"stored-poppies");
    }
    void register() {
        Bukkit.getPluginManager().registerEvents(this,plugin);
        Bukkit.getScheduler().runTaskTimer(plugin,this::generateForLoadedSpawners,20L,20L);
    }

    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void onSpawnerPlace(BlockPlaceEvent e) {
        ItemStack hand=e.getItemInHand();
        if(hand==null||!hand.hasItemMeta()) return;
        String mob=hand.getItemMeta().getPersistentDataContainer().get(crateKey,PersistentDataType.STRING);
        if(mob==null) return;
        Block placed=e.getBlockPlaced();
        Bukkit.getScheduler().runTask(plugin,()->{
            if(placed.getType()!=Material.SPAWNER||!(placed.getState() instanceof CreatureSpawner s)) return;
            try{s.setSpawnedType(EntityType.valueOf(mob));}catch(IllegalArgumentException ex){return;}
            PersistentDataContainer p=s.getPersistentDataContainer();
            p.set(managedKey,PersistentDataType.BYTE,(byte)1); p.set(stackKey,PersistentDataType.INTEGER,1);
            p.set(ownerKey,PersistentDataType.STRING,e.getPlayer().getUniqueId().toString());
            p.set(autoKey,PersistentDataType.BYTE,(byte)0); p.set(lastKey,PersistentDataType.LONG,System.currentTimeMillis());
            for(NamespacedKey k:storageKeys()) p.set(k,PersistentDataType.INTEGER,0);
            s.update(true,false);
        });
    }

    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=false)
    public void onSpawnerInteract(PlayerInteractEvent e) {
        if(e.getAction()!=Action.RIGHT_CLICK_BLOCK||e.getClickedBlock()==null) return;
        Block b=e.getClickedBlock();
        if(!(b.getState() instanceof CreatureSpawner s)||!managed(s)) return;
        Player p=e.getPlayer();
        ItemStack hand=e.getItem();
        String mob=hand!=null&&hand.getType()==Material.SPAWNER&&hand.hasItemMeta()
            ?hand.getItemMeta().getPersistentDataContainer().get(crateKey,PersistentDataType.STRING):null;
        if(mob!=null) {
            e.setCancelled(true);
            if(!mob.equals(s.getSpawnedType().name())) { p.sendMessage(Component.text("Only the same spawner type can be stacked.",NamedTextColor.RED)); return; }
            int amount=Math.max(1,hand.getAmount());
            int current=stack(s);
            int add=Math.min(amount, Integer.MAX_VALUE-current);
            if(add<=0) {p.sendMessage(Component.text("Spawner stack is full.",NamedTextColor.RED));return;}
            s.getPersistentDataContainer().set(stackKey,PersistentDataType.INTEGER,current+add);
            s.update(true,false);
            if(p.getGameMode()!=GameMode.CREATIVE) {
                if(hand.getAmount()<=add) p.getInventory().setItemInMainHand(null);
                else hand.setAmount(hand.getAmount()-add);
            }
            p.sendMessage(Component.text("Spawner stacked: x"+(current+add),NamedTextColor.GREEN));
            p.playSound(p.getLocation(),Sound.BLOCK_NOTE_BLOCK_PLING,0.7f,1.5f);
            return;
        }
        e.setCancelled(true);
        openMenu(p,b.getLocation(),false);
    }

    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=false)
    public void onSpawnerBreak(BlockBreakEvent e) {
        Block b=e.getBlock();
        if(!(b.getState() instanceof CreatureSpawner s)||!managed(s)) return;
        e.setCancelled(true); e.setDropItems(false); e.setExpToDrop(0);
        int have=stack(s), remove=e.getPlayer().isSneaking()?Math.min(64,have):1;
        int left=have-remove;
        if(left>0) {
            s.getPersistentDataContainer().set(stackKey,PersistentDataType.INTEGER,left);
            s.update(true,false);
        } else b.setType(Material.AIR,false);
        ItemStack reward=createSpawnerItem(s.getSpawnedType(),remove);
        Map<Integer,ItemStack> extra=e.getPlayer().getInventory().addItem(reward);
        extra.values().forEach(it->b.getWorld().dropItemNaturally(b.getLocation(),it));
        e.getPlayer().sendMessage(Component.text("Collected "+remove+" spawner(s). Remaining: "+left,NamedTextColor.GREEN));
    }

    private boolean managed(CreatureSpawner s){return s.getPersistentDataContainer().has(managedKey,PersistentDataType.BYTE);}
    private int stack(CreatureSpawner s){return Math.max(1,s.getPersistentDataContainer().getOrDefault(stackKey,PersistentDataType.INTEGER,1));}
    private ItemStack createSpawnerItem(EntityType type,int amount){
        ItemStack it=new ItemStack(Material.SPAWNER,amount); ItemMeta m=it.getItemMeta();
        m.displayName(Component.text(type.name().replace('_',' ')+" Spawner",NamedTextColor.GREEN));
        m.getPersistentDataContainer().set(crateKey,PersistentDataType.STRING,type.name()); it.setItemMeta(m); return it;
    }
    private List<NamespacedKey> storageKeys(){return List.of(bonesKey,bowsKey,arrowsKey,stringKey,eyeKey,ingotKey,poppyKey);}
    private NamespacedKey key(Material m){return switch(m){case BONE->bonesKey;case BOW->bowsKey;case ARROW->arrowsKey;case STRING->stringKey;case SPIDER_EYE->eyeKey;case IRON_INGOT->ingotKey;case POPPY->poppyKey;default->bonesKey;};}
    private List<Material> drops(EntityType t){
        return switch(t){
            case SKELETON->{List<Material> l=new ArrayList<>(List.of(Material.BONE));if(plugin.getConfig().getBoolean("spawners.bows-enabled",true))l.add(Material.BOW);if(plugin.getConfig().getBoolean("spawners.arrows-enabled",true))l.add(Material.ARROW);yield l;}
            case SPIDER->List.of(Material.STRING,Material.SPIDER_EYE);
            case IRON_GOLEM->List.of(Material.IRON_INGOT,Material.POPPY);
            default->List.of(Material.BONE);
        };
    }
    private int count(PersistentDataContainer p,NamespacedKey k){return Math.max(0,p.getOrDefault(k,PersistentDataType.INTEGER,0));}
    private ItemStack named(Material type,String title,List<String> lore,String action){
        ItemStack it=new ItemStack(type);ItemMeta m=it.getItemMeta();m.displayName(Component.text(title));
        if(lore!=null)m.lore(lore.stream().map(x->Component.text(x,NamedTextColor.GRAY)).toList());
        if(action!=null)m.getPersistentDataContainer().set(new NamespacedKey(plugin,"spawner-menu-action"),PersistentDataType.STRING,action);
        it.setItemMeta(m);return it;
    }
    private void openMenu(Player p,Location loc,boolean settings){
        if(!(loc.getBlock().getState() instanceof CreatureSpawner s)||!managed(s))return;
        SpawnerMenu holder=new SpawnerMenu(loc.clone(),settings);
        Inventory inv=Bukkit.createInventory(holder,27,Component.text(TITLE,NamedTextColor.DARK_GREEN));holder.inventory=inv;
        ItemStack pane=named(Material.GRAY_STAINED_GLASS_PANE," ",List.of(),null);for(int i=0;i<27;i++)inv.setItem(i,pane);
        PersistentDataContainer data=s.getPersistentDataContainer();
        if(settings){
            boolean auto=data.getOrDefault(autoKey,PersistentDataType.BYTE,(byte)0)==1;
            inv.setItem(11,named(auto?Material.HOPPER:Material.CHEST,"Collection: "+(auto?"AUTO":"MANUAL"),
                List.of(auto?"Generated items go to the placer when online.":"Items stay stored until collected here.","Click to switch collection mode."),"toggle-auto"));
            inv.setItem(13,named(Material.CLOCK,"Production: every 25 seconds",
                List.of("Each cycle gives 2–4 items per spawner in the stack.","Stack x"+stack(s)+" = "+(2*stack(s))+"–"+(4*stack(s))+" items per cycle."),null));
            inv.setItem(15,named(Material.ARROW,"Back",List.of("Return to stored drops."),"back"));
        }else{
            EntityType type=s.getSpawnedType();int n=stack(s);
            inv.setItem(4,named(Material.SPAWNER,type.name().replace('_',' ')+" Spawner",
                List.of("Stack size: x"+n,"Production: every 25 seconds","Next production: "+nextIn(data)+"s","Each cycle: "+(2*n)+"–"+(4*n)+" total items"),null));
            int[] slots={11,13,15};List<Material> ds=drops(type);
            for(int i=0;i<ds.size();i++){Material m=ds.get(i);inv.setItem(slots[i],named(m,m.name().replace('_',' ')+": "+count(data,key(m)),
                List.of("Stored items: "+count(data,key(m)),"Click to collect all stored items."),"collect:"+m.name()));}
            inv.setItem(22,named(Material.COMPARATOR,"Spawner settings",List.of("Change how generated drops are collected."),"settings"));
        }
        inv.setItem(26,named(Material.BARRIER,"Close",List.of(), "close"));p.openInventory(inv);
    }
    private long nextIn(PersistentDataContainer p){long last=p.getOrDefault(lastKey,PersistentDataType.LONG,System.currentTimeMillis());return Math.max(0,(INTERVAL-(System.currentTimeMillis()-last)+999)/1000);}
    @EventHandler public void onMenuClick(InventoryClickEvent e){
        if(!(e.getView().getTopInventory().getHolder() instanceof SpawnerMenu menu))return;e.setCancelled(true);
        if(!(e.getWhoClicked() instanceof Player p)||e.getClickedInventory()!=e.getView().getTopInventory())return;
        ItemStack it=e.getCurrentItem();if(it==null||!it.hasItemMeta())return;
        String action=it.getItemMeta().getPersistentDataContainer().get(new NamespacedKey(plugin,"spawner-menu-action"),PersistentDataType.STRING);
        if(action==null)return;if(action.equals("close")){p.closeInventory();return;}
        if(!(menu.location.getBlock().getState() instanceof CreatureSpawner s)||!managed(s)){p.closeInventory();return;}
        if(action.equals("settings")){openMenu(p,menu.location,true);return;}
        if(action.equals("back")){openMenu(p,menu.location,false);return;}
        if(action.equals("toggle-auto")){
            PersistentDataContainer d=s.getPersistentDataContainer();byte next=(byte)(d.getOrDefault(autoKey,PersistentDataType.BYTE,(byte)0)==1?0:1);
            d.set(autoKey,PersistentDataType.BYTE,next);s.update(true,false);openMenu(p,menu.location,true);return;
        }
        if(action.startsWith("collect:")){
            Material m=Material.valueOf(action.substring(8));PersistentDataContainer d=s.getPersistentDataContainer();int amount=count(d,key(m));if(amount<=0)return;
            boolean auto=d.getOrDefault(autoKey,PersistentDataType.BYTE,(byte)0)==1;
            d.set(key(m),PersistentDataType.INTEGER,0);s.update(true,false);
            if(auto){p.sendMessage(Component.text("Auto collection is for the spawner placer; switch to manual to collect here.",NamedTextColor.YELLOW));d.set(key(m),PersistentDataType.INTEGER,amount);s.update(true,false);}
            else dropInFront(p,m,amount);
            openMenu(p,menu.location,false);
        }
    }
    @EventHandler public void onMenuDrag(InventoryDragEvent e){if(e.getView().getTopInventory().getHolder() instanceof SpawnerMenu)e.setCancelled(true);}
    private void generateForLoadedSpawners(){
        int min=Math.max(1,plugin.getConfig().getInt("spawners.minimum-items",2)),max=Math.max(min,plugin.getConfig().getInt("spawners.maximum-items",4));
        long now=System.currentTimeMillis();
        for(World w:Bukkit.getWorlds())for(Chunk c:w.getLoadedChunks())for(BlockState state:c.getTileEntities()){
            if(!(state instanceof CreatureSpawner s)||!managed(s))continue;
            PersistentDataContainer d=s.getPersistentDataContainer();long last=d.getOrDefault(lastKey,PersistentDataType.LONG,now);
            if(now-last<INTERVAL)continue;d.set(lastKey,PersistentDataType.LONG,now);
            List<Material> choices=drops(s.getSpawnedType());if(choices.isEmpty())continue;
            Material chosen=choices.get(random.nextInt(choices.size()));int amount=(min+random.nextInt(max-min+1))*stack(s);
            boolean auto=d.getOrDefault(autoKey,PersistentDataType.BYTE,(byte)0)==1;
            UUID owner=null;try{String raw=d.get(ownerKey,PersistentDataType.STRING);if(raw!=null)owner=UUID.fromString(raw);}catch(IllegalArgumentException ignored){}
            Player placer=owner==null?null:Bukkit.getPlayer(owner);
            if(auto&&placer!=null){Map<Integer,ItemStack> extra=placer.getInventory().addItem(new ItemStack(chosen,amount));extra.values().forEach(it->w.dropItemNaturally(s.getLocation(),it));}
            else {NamespacedKey k=key(chosen);long total=(long)count(d,k)+amount;d.set(k,PersistentDataType.INTEGER,(int)Math.min(Integer.MAX_VALUE,total));}
            s.update(true,false);
        }
    }
    private void dropInFront(Player p,Material m,int amount){
        Vector dir=p.getLocation().getDirection().setY(0).normalize();if(dir.lengthSquared()<0.01)dir=new Vector(0,0,1);
        Location at=p.getLocation().clone().add(0,1,0).add(dir.clone().multiply(.8));
        while(amount>0){int n=Math.min(amount,m.getMaxStackSize());Item item=p.getWorld().dropItem(at,new ItemStack(m,n));item.setVelocity(dir.clone().multiply(.25).setY(.12));amount-=n;}
    }
    private static final class SpawnerMenu implements InventoryHolder{
        private final Location location;private final boolean settings;private Inventory inventory;
        private SpawnerMenu(Location location,boolean settings){this.location=location;this.settings=settings;}
        @Override public Inventory getInventory(){return inventory;}
    }
}
