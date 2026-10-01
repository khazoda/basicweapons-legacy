package com.khazoda.basicweapons.materialpack;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.khazoda.basicweapons.Constants;
import com.khazoda.basicweapons.platform.Services;
import com.khazoda.basicweapons.registry.WeaponRegistry;
import com.khazoda.basicweapons.registry.WeaponRegistry.MaterialEntry;
import com.khazoda.basicweapons.struct.WeaponType;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.ToolMaterial;
import org.apache.commons.io.FileUtils;

import java.io.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static com.khazoda.basicweapons.materialpack.MaterialPackConstants.*;

/**
 * This class handles detecting a materialpack in basicweapons_materialpacks and sending the files to the
 * right places. It generates resource and datapacks from the assets/ and data/ folders, which it sends to
 * config/basicweapons/bwmp_resources and config/basicweapons/bwmp_data,
 * and reads the material stats from custom_materials/ in loadMaterialsFromPack() which it stores for
 * the WeaponRegistry to use during registration.
 */

public class MaterialPackLoader {
  private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
  public static final Map<String, EarlyLoadedMaterial> loadedMaterials = new HashMap<>();
  private static final Map<ToolMaterial, EarlyLoadedMaterial> toolMaterialMap = new HashMap<>();
  private static final Map<String, String> materialToDatapackName = new HashMap<>();
  private static final Set<String> initiallyLoadedPacks = new HashSet<>();
  private static boolean hasInitialized = false;

  public static void loadPacks() {
    if (hasInitialized) {
      Constants.LOG.warn("Attempted to load material packs after initialization - skipping");
      return;
    }

    File materialPacksFolder = new File(MATERIALPACK_SOURCE);
    File[] packFiles = materialPacksFolder.listFiles(file -> file.isDirectory() || file.getName().endsWith(".zip"));

    if (!materialPacksFolder.exists()) {
      if (materialPacksFolder.mkdir()) {
        Constants.LOG.info("Created material packs folder {}", materialPacksFolder.getName());
      } else {
        Constants.LOG.error("Failed to create basicweapons_materials folder. This should never happen.");
        return;
      }
    } else {
      /* Generate bwmp_data and bwmp_resources */
      File resourcepacksFolder = new File(RESOURCEPACK_TARGET);
      File datapacksFolder = new File(DATAPACK_TARGET);
      createFolder(resourcepacksFolder);
      createFolder(datapacksFolder);
      cleanTargetFolders(); // On every load the target resource & data folders are cleaned to handle users removing materialpacks
    }

    if (packFiles == null || packFiles.length == 0) {
      Constants.LOG.info("No material packs found in {}", materialPacksFolder.getName());
      return;
    }

    for (File packFile : packFiles) {
      String packName = packFile.getName();
      if (packFile.isDirectory()) {
        processPackFolder(packFile);
      } else {
        // For ZIP files, extract to a temp directory with the same name before processing
        File extractDir = new File(materialPacksFolder, packName.substring(0, packName.length() - 4));
        try {
          if (extractDir.exists()) {
            FileUtils.deleteDirectory(extractDir); // Clean up any previous extraction just in case
          }
          extractZip(packFile, extractDir);
          processPackFolder(extractDir);
          FileUtils.deleteDirectory(extractDir); // Clean up the temporary directory
        } catch (IOException e) {
          Constants.LOG.error("Failed to process ZIP pack {}: {}.", packName, e.getMessage());
        }
      }
    }
    Constants.LOG.info("Loaded the following material packs: [{}]", initiallyLoadedPacks.stream().map(Object::toString).collect(Collectors.joining(", ")));
    hasInitialized = true;
  }

  private static void processPackFolder(File packFolder) {
    if (!loadMaterialsFromPack(packFolder)) return;
    copyPackContent(packFolder, ASSETS_PATH, RESOURCEPACK_TARGET);
    copyPackContent(packFolder, DATA_PATH, DATAPACK_TARGET);
    initiallyLoadedPacks.add(packFolder.getName());
  }

  private static void extractZip(File zipFile, File targetDir) throws IOException {
    try (ZipFile zip = new ZipFile(zipFile)) {
      Enumeration<? extends ZipEntry> entries = zip.entries();
      while (entries.hasMoreElements()) {
        ZipEntry entry = entries.nextElement();
        File entryFile = new File(targetDir, entry.getName());

        if (entry.isDirectory()) {
          entryFile.mkdirs();
        } else {
          entryFile.getParentFile().mkdirs();
          try (InputStream in = zip.getInputStream(entry); FileOutputStream out = new FileOutputStream(entryFile)) {
            byte[] buffer = new byte[1024];
            int len;
            while ((len = in.read(buffer)) > 0) {
              out.write(buffer, 0, len);
            }
          }
        }
      }
    }
  }

  private static void copyPackContent(File packFolder, String subPath, String targetRootPath) {
    File sourceFolder = new File(packFolder, subPath);
    if (!sourceFolder.exists()) return;

    File targetRootFolder = new File(targetRootPath);
    createFolder(targetRootFolder);

    File targetFolder = new File(targetRootFolder, packFolder.getName());
    try {
      File[] contents = sourceFolder.listFiles(file -> !file.getName().equals("pack.mcmeta"));
      if (contents != null) {
        for (File file : contents) {
          File targetDir = new File(targetFolder, subPath + "/" + file.getName());
          if (file.isDirectory()) {
            copyDirectoryFiltered(file, targetDir, packFolder);
          } else {
            if (shouldSkipSwordAxeFile(file.getName(), packFolder)) continue;
            FileUtils.copyFile(file, targetDir);
          }
        }
      }

      File packIcon = new File(packFolder, "pack.png");
      if (packIcon.exists()) {
        FileUtils.copyFile(packIcon, new File(targetFolder, "pack.png"));
      }

      File sourcePackMcmeta = new File(sourceFolder, "pack.mcmeta");
      if (sourcePackMcmeta.exists()) {
        FileUtils.copyFile(sourcePackMcmeta, new File(targetFolder, "pack.mcmeta"));
      } else {
        Constants.LOG.warn("No pack.mcmeta found in {} folder for {}", subPath, packFolder.getName());
      }
    } catch (IOException e) {
      Constants.LOG.error("Failed to copy pack content from {}: {}", packFolder.getName(), e.getMessage());
    }
  }

  /**
   * Recursively copies directories, filtering out sword/axe files that don't have textures.
   * Used for both resource pack and data pack copying. Stops annoying console errors.
   */
  private static void copyDirectoryFiltered(File sourceDir, File targetDir, File packFolder) throws IOException {
    if (!targetDir.exists()) {
      targetDir.mkdirs();
    }

    File[] files = sourceDir.listFiles();
    if (files == null) return;

    for (File file : files) {
      if (file.isDirectory()) {
        copyDirectoryFiltered(file, new File(targetDir, file.getName()), packFolder);
      } else {
        if (shouldSkipSwordAxeFile(file.getName(), packFolder)) continue;
        FileUtils.copyFile(file, new File(targetDir, file.getName()));
      }
    }
  }

  /**
   * Checks if a file should be skipped because it's for a sword/axe item that doesn't have a texture.
   */
  private static boolean shouldSkipSwordAxeFile(String fileName, File packFolder) {
    if (!fileName.endsWith("_sword.json") && !fileName.endsWith("_axe.json")) {
      return false;
    }

    String itemName = fileName.substring(0, fileName.length() - 5);
    String weaponType = itemName.endsWith("_sword") ? "sword" : "axe";
    String materialName = itemName.substring(0, itemName.length() - weaponType.length() - 1);

    File textureFile = new File(packFolder, ASSETS_PATH + "/basicweapons/textures/item/" + materialName + "_" + weaponType + ".png");
    return !textureFile.exists();
  }


  /* Returns false if materialpack shouldn't be loaded (loading_requirements.json).
   * This will skip resource and datapack injection for that materialpack */
  private static boolean loadMaterialsFromPack(File packFolder) {
    // Check materialpack loading requirements first
    File requirementsFile = new File(packFolder, "loading_requirements.json");
    if (requirementsFile.exists()) {
      try (BufferedReader reader = new BufferedReader(new FileReader(requirementsFile))) {
        JsonObject json = GSON.fromJson(reader, JsonObject.class);
        if (json.has("requires_mod")) {
          String requiredMod = json.get("requires_mod").getAsString();
          if (!requiredMod.isEmpty() && !Services.PLATFORM.isModLoaded(requiredMod)) {
            Constants.LOG.info("Skipping material pack {} - required mod {} is not loaded", packFolder.getName(), requiredMod);
            return false;
          }
        }
      } catch (Exception e) {
        Constants.LOG.error("Failed to read loading requirements for pack {}: {}. It won't be enabled.", packFolder.getName(), e.getMessage());
        return false;
      }
    }

    // Check if any materials exist (they should)
    File materialFolder = new File(packFolder, CUSTOM_MATERIALS_PATH);
    if (!materialFolder.exists()) {
      Constants.LOG.warn("Pack {} does not contain materials at expected path", packFolder.getName());
      return false;
    }
    File[] materialFiles = materialFolder.listFiles((dir, name) -> name.endsWith(".json"));
    if (materialFiles == null || materialFiles.length == 0) {
      Constants.LOG.warn("No material files found in pack {}", packFolder.getName());
      return false;
    }

    // Process each material from this materialpack individually
    for (File file : materialFiles) {
      try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
        JsonObject json = GSON.fromJson(reader, JsonObject.class);

        String material_name = json.get("material_name").getAsString();
        int durability = json.get("durability").getAsInt();
        float attack_damage_bonus = json.get("attack_damage_bonus").getAsFloat();

        // mining_speed is optional for backwards compatibility
        boolean hasMiningSpeed = json.has("mining_speed");
        float mining_speed;
        if (hasMiningSpeed) {
          mining_speed = json.get("mining_speed").getAsFloat();
        } else {
          // Backwards compatibility: use attack_speed_bonus as mining speed if mining_speed is missing
          mining_speed = json.get("attack_speed_bonus").getAsFloat();
        }

        // attack_speed_bonus is optional for backwards compatibility
        float attack_speed_bonus;
        if (hasMiningSpeed && json.has("attack_speed_bonus")) {
          attack_speed_bonus = json.get("attack_speed_bonus").getAsFloat(); // 1.21.10+ format
        } else {
          attack_speed_bonus = 0.0f; // 1.21.1 format
        }

        float reach_bonus = json.get("reach_bonus").getAsFloat();
        int enchantability = json.get("enchantability").getAsInt();
        String repair_ingredient = json.get("repair_ingredient").getAsString();

        if (json.has("fireproof") && !GsonHelper.isBooleanValue(json, "fireproof")) {
          throw new IllegalArgumentException("fireproof must be a boolean");
        }
        boolean fireproof = GsonHelper.getAsBoolean(json, "fireproof", false);
        Rarity rarity = Rarity.valueOf(GsonHelper.getAsString(json, "rarity", "common").toUpperCase(Locale.ROOT));

        EarlyLoadedMaterial material = new EarlyLoadedMaterial(material_name, durability, attack_damage_bonus, mining_speed, attack_speed_bonus, reach_bonus, enchantability, repair_ingredient);
        ToolMaterial toolMaterial = material.createToolMaterial();
        toolMaterialMap.put(toolMaterial, material);
        loadedMaterials.put(material_name, material);
        materialToDatapackName.put(material_name, packFolder.getName());

        List<WeaponType.WeaponTypeInterface> supportedTypes = new ArrayList<>();
        supportedTypes.addAll(Arrays.asList(WeaponType.BasicWeaponType.values()));
        supportedTypes.addAll(Arrays.asList(WeaponType.VanillaWeaponType.values()));

        File textureFolder = new File(packFolder, ASSETS_PATH + "/basicweapons/textures/item");
        String texturePrefix = material_name + "_";
        List<WeaponType.WeaponTypeInterface> weaponTypes = supportedTypes.stream().filter(type -> new File(textureFolder, texturePrefix + type.getId() + ".png").isFile()).toList();
        File[] materialTextures = textureFolder.listFiles(textureFile -> textureFile.isFile() && textureFile.getName().startsWith(texturePrefix) && textureFile.getName().endsWith(".png") && !textureFile.getName().endsWith("_held.png"));
        int invalidTextures = materialTextures == null ? 0 : materialTextures.length - weaponTypes.size();

        WeaponRegistry.registerWeaponsForMaterial(new MaterialEntry(toolMaterial, material_name, settings -> {
          if (fireproof) settings.fireResistant();
          return settings.rarity(rarity);
        }), weaponTypes);
        Constants.LOG.info("'{}' material found. adding {} weapons and skipping {} invalid ones", material_name, weaponTypes.size(), invalidTextures);
      } catch (Exception e) {
        Constants.LOG.error("Failed to load material file {} from pack {}: {}", file.getName(), packFolder.getName(), e.getMessage());
      }
    }
    return true;
  }

  public static ToolMaterial getMaterial(String name) {
    EarlyLoadedMaterial material = loadedMaterials.get(name);
    return material != null ? material.createToolMaterial() : null;
  }

  public static Collection<String> getMaterialNames() {
    return loadedMaterials.keySet();
  }

  public static Collection<String> getDatapackNames() {
    return materialToDatapackName.values();
  }

  public static boolean wasPackLoadedInitially(String packName) {
    return initiallyLoadedPacks.contains(packName);
  }

  public static float getMiningSpeed(ToolMaterial toolMaterial) {
    EarlyLoadedMaterial material = toolMaterialMap.get(toolMaterial);
    return material != null ? material.getMiningSpeed() : 0f;
  }

  public static float getAttackSpeedBonus(ToolMaterial toolMaterial) {
    EarlyLoadedMaterial material = toolMaterialMap.get(toolMaterial);
    return material != null ? material.getAttackSpeedBonus() : 0f;
  }

  public static float getReachBonus(ToolMaterial toolMaterial) {
    EarlyLoadedMaterial material = toolMaterialMap.get(toolMaterial);
    return material != null ? material.getReachBonus() : 0f;
  }

  /* Returns true if folder was created, false if not or if it already exists */
  private static boolean createFolder(File folder) {
    if (!folder.exists()) {
      return folder.mkdirs();
    }
    return false;
  }

  private static void cleanTargetFolders() {
    // Clean config/basicweapons/bwmp_resources and config/basicweapons/bwmp_data to make sure materialpacks are always fresh
    if (!ableToDeleteDirectory(new File(RESOURCEPACK_TARGET)))
      Constants.LOG.error("Failed to clean bwmp_resources target folder. This is probably fine but if you experience issues please report this on the Basic Weapons issue tracker.");
    if (!ableToDeleteDirectory(new File(DATAPACK_TARGET)))
      Constants.LOG.error("Failed to clean bwmp_data target folder. This is probably fine but if you experience issues please report this on the Basic Weapons issue tracker.");
  }

  private static boolean ableToDeleteDirectory(File dir) {
    if (dir.exists()) {
      try {
        FileUtils.deleteDirectory(dir);
        return true;
      } catch (IOException e) {
        return false;
      }
    }
    return false;
  }
}