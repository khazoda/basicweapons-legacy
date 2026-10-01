package com.khazoda.basicweapons.registry;

import com.khazoda.basicweapons.material.ConditionalToolMaterials;
import com.khazoda.basicweapons.struct.WeaponType;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ToolMaterial;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;

import static com.khazoda.basicweapons.BasicWeaponsCommon.ITEM_REGISTRAR;
import static com.khazoda.basicweapons.BasicWeaponsCommon.bronze_mod_loaded;
import static com.khazoda.basicweapons.Constants.ID;
import static com.khazoda.basicweapons.struct.WeaponType.BasicWeaponType;
import static com.khazoda.basicweapons.struct.WeaponType.WeaponTypeInterface;

public class WeaponRegistry {
  /* Raw suppliers for registration */
  private static final Map<String, Supplier<Item>> ITEMS = new LinkedHashMap<>();
  private static final Map<WeaponTypeInterface, List<Supplier<Item>>> ALL_ITEMS_BY_TYPE = new HashMap<>();
  private static final Map<WeaponTypeInterface, List<Supplier<Item>>> BUILTIN_ITEMS_BY_TYPE = new HashMap<>();
  private static final Map<WeaponTypeInterface, List<Supplier<Item>>> MATERIALPACK_ITEMS_BY_TYPE = new HashMap<>();
  private static final Map<ToolMaterial, List<Supplier<Item>>> ITEMS_BY_MATERIAL = new HashMap<>();

  /* Cached maps for runtime retrieval */
  private static final Map<ToolMaterial, List<Item>> CACHED_MATERIAL_ITEMS = new ConcurrentHashMap<>();
  private static final Map<ITEMS_BY_TYPE, Map<WeaponTypeInterface, List<Item>>> CACHED_TYPED_ITEMS = new ConcurrentHashMap<>();

  public static final List<MaterialEntry> VANILLA_MATERIALS = Arrays.asList(new MaterialEntry(ToolMaterial.WOOD, "wooden"), new MaterialEntry(ToolMaterial.STONE, "stone"), new MaterialEntry(ToolMaterial.COPPER, "copper"), new MaterialEntry(ToolMaterial.IRON, "iron"), new MaterialEntry(ToolMaterial.GOLD, "golden"), new MaterialEntry(ToolMaterial.DIAMOND, "diamond"), new MaterialEntry(ToolMaterial.NETHERITE, "netherite", Item.Properties::fireResistant));
  public static final List<MaterialEntry> COMPAT_MATERIALS = Arrays.asList(new MaterialEntry(ConditionalToolMaterials.BRONZE, "bronze"), new MaterialEntry(ConditionalToolMaterials.TIN, "tin"));

  public static void init() {
    for (MaterialEntry material : VANILLA_MATERIALS) {
      registerAllWeaponsForMaterial(material);
    }
    if (bronze_mod_loaded) { //TODO: Make sure you comment this when running DATAGEN
      for (MaterialEntry material : COMPAT_MATERIALS) {
        registerAllWeaponsForMaterial(material);
      }
    }
  }

  public static void registerWeaponForMaterial(WeaponTypeInterface type, MaterialEntry material) {
    String itemId = material.prefix() + "_" + type.getId();
    Item.Properties itemSettings = material.settingsModifier().apply(new Item.Properties());

    float damageModifier = WeaponType.getDamageModifier(type, material.material());
    float speedModifier = WeaponType.getSpeedModifier(type, material.material());
    float reachModifier = WeaponType.getReachModifier(type, material.material());

    ResourceKey<Item> resourceKey = ResourceKey.create(Registries.ITEM, ID(itemId));
    Item.Properties finalProperties = itemSettings.setId(resourceKey);

    Supplier<Item> itemSupplier = ITEM_REGISTRAR.register(itemId, () -> type.create(material.material, damageModifier, speedModifier, reachModifier, finalProperties));

    ITEMS.put(itemId, itemSupplier);

    if (VANILLA_MATERIALS.contains(material) || COMPAT_MATERIALS.contains(material)) {
      BUILTIN_ITEMS_BY_TYPE.computeIfAbsent(type, k -> new ArrayList<>()).add(itemSupplier);
    } else {
      MATERIALPACK_ITEMS_BY_TYPE.computeIfAbsent(type, k -> new ArrayList<>()).add(itemSupplier);
    }
    ALL_ITEMS_BY_TYPE.computeIfAbsent(type, k -> new ArrayList<>()).add(itemSupplier);
    ITEMS_BY_MATERIAL.computeIfAbsent(material.material(), k -> new ArrayList<>()).add(itemSupplier);
  }

  public static void registerAllWeaponsForMaterial(MaterialEntry material) {
    for (BasicWeaponType type : BasicWeaponType.values()) {
      registerWeaponForMaterial(type, material);
    }
  }

  public static void registerWeaponsForMaterial(MaterialEntry material, Iterable<? extends WeaponTypeInterface> weaponTypes) {
    for (WeaponTypeInterface type : weaponTypes) {
      registerWeaponForMaterial(type, material);
    }
  }

  /* ITEMS_BY_TYPE retrieval options for tab registry */
  public enum ITEMS_BY_TYPE {
    ALL, BUILTIN, MATERIALPACK
  }

  /* Retrieve items by type (built-in/materialpack) */
  public static List<Item> getItemsByType(ITEMS_BY_TYPE selection, WeaponTypeInterface type) {
    return CACHED_TYPED_ITEMS.computeIfAbsent(selection, k -> new ConcurrentHashMap<>()).computeIfAbsent(type, t -> {
      List<Supplier<Item>> suppliers = switch (selection) {
        case BUILTIN -> BUILTIN_ITEMS_BY_TYPE.getOrDefault(t, Collections.emptyList());
        case MATERIALPACK -> MATERIALPACK_ITEMS_BY_TYPE.getOrDefault(t, Collections.emptyList());
        default -> ALL_ITEMS_BY_TYPE.getOrDefault(t, Collections.emptyList());
      };
      return suppliers.stream().map(Supplier::get).filter(Objects::nonNull).toList();
    });
  }

  /* Retrieve items by material */
  public static List<Item> getItemsByMaterial(ToolMaterial material) {
    return CACHED_MATERIAL_ITEMS.computeIfAbsent(material, mat -> ITEMS_BY_MATERIAL.getOrDefault(mat, Collections.emptyList()).stream().map(Supplier::get).filter(Objects::nonNull).toList());
  }

  public record MaterialEntry(ToolMaterial material, String prefix,
                              Function<Item.Properties, Item.Properties> settingsModifier) {
    public MaterialEntry(ToolMaterial material, String prefix) {
      this(material, prefix, settings -> settings);
    }
  }
}