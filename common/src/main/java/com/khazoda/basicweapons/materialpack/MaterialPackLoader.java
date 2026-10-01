package com.khazoda.basicweapons.materialpack;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.khazoda.basicweapons.Constants;
import com.khazoda.basicweapons.platform.Services;
import com.khazoda.basicweapons.registry.WeaponRegistry;
import com.khazoda.basicweapons.registry.WeaponRegistry.MaterialEntry;
import com.khazoda.basicweapons.struct.WeaponType;
import net.minecraft.resources.Identifier;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.ToolMaterial;
import org.apache.commons.io.FileUtils;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.*;
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
    if (!materialPacksFolder.exists()) {
      if (materialPacksFolder.mkdir()) {
        Constants.LOG.info("Created material packs folder {}", materialPacksFolder.getName());
      } else {
        Constants.LOG.error("Failed to create basicweapons_materials folder. This should never happen.");
        return;
      }
    }

    File[] packFiles = materialPacksFolder.listFiles(file -> file.isDirectory() || file.getName().toLowerCase(Locale.ROOT).endsWith(".zip"));
    if (packFiles == null) {
      Constants.LOG.error("Failed to read material packs folder {}", materialPacksFolder.getName());
      return;
    }
    if (!cleanTargetFolders()) {
      throw new IllegalStateException("Failed to clean generated material packs; aborting startup to avoid loading stale custom weapons. Check the target folders' permissions and close programs locking their files.");
    }
    if (packFiles.length == 0) {
      Constants.LOG.info("No material packs found in {}", materialPacksFolder.getName());
      hasInitialized = true;
      return;
    }

    Arrays.sort(packFiles, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER).thenComparing(File::getName));

    for (File packFile : packFiles) {
      String packName = packFile.getName();
      if (packFile.isDirectory()) {
        processPackFolder(packFile, packName);
      } else {
        String logicalPackName = packName.substring(0, packName.length() - 4);
        Path extractDir = null;
        try {
          extractDir = Files.createTempDirectory("basicweapons-materialpack-");
          extractZip(packFile, extractDir.toFile());
          processPackFolder(extractDir.toFile(), logicalPackName);
        } catch (IOException | InvalidPathException e) {
          Constants.LOG.error("Failed to process ZIP pack {}: {}.", packName, e.getMessage());
        } finally {
          try {
            if (extractDir != null) FileUtils.deleteDirectory(extractDir.toFile());
          } catch (IOException e) {
            Constants.LOG.warn("Failed to remove temporary extraction folder for {}: {}", packName, e.getMessage());
          }
        }
      }
    }
    Constants.LOG.info("Loaded the following material packs: [{}]", String.join(", ", initiallyLoadedPacks));
    hasInitialized = true;
  }

  private static void processPackFolder(File packFolder, String packName) {
    if (initiallyLoadedPacks.stream().anyMatch(name -> name.equalsIgnoreCase(packName))) {
      Constants.LOG.warn("Skipping material pack {} because another source with the same name was already loaded", packName);
      return;
    }

    Optional<List<ValidatedMaterial>> validatedMaterials = validateMaterialsFromPack(packFolder, packName);
    if (validatedMaterials.isEmpty()) return;

    commitMaterials(packFolder, packName, validatedMaterials.get());
    copyPackContent(packFolder, packName, ASSETS_PATH, RESOURCEPACK_TARGET);
    copyPackContent(packFolder, packName, DATA_PATH, DATAPACK_TARGET);
    initiallyLoadedPacks.add(packName);
  }

  private static void extractZip(File zipFile, File targetDir) throws IOException {
    Path targetRoot = targetDir.toPath().toAbsolutePath().normalize();
    try (ZipFile zip = new ZipFile(zipFile)) {
      Enumeration<? extends ZipEntry> entries = zip.entries();
      while (entries.hasMoreElements()) {
        ZipEntry entry = entries.nextElement();
        Path entryPath = targetRoot.resolve(entry.getName()).normalize();
        if (!entryPath.startsWith(targetRoot)) {
          throw new IOException("ZIP entry escapes material pack root: " + entry.getName());
        }

        if (entry.isDirectory()) {
          Files.createDirectories(entryPath);
        } else {
          Path parent = entryPath.getParent();
          if (parent != null) Files.createDirectories(parent);
          try (InputStream in = zip.getInputStream(entry); OutputStream out = Files.newOutputStream(entryPath)) {
            in.transferTo(out);
          }
        }
      }
    }
  }

  private static void copyPackContent(File packFolder, String packName, String subPath, String targetRootPath) {
    File sourceFolder = new File(packFolder, subPath);
    if (!sourceFolder.exists()) return;

    File targetRootFolder = new File(targetRootPath);
    createFolder(targetRootFolder);

    File targetFolder = new File(targetRootFolder, packName);
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
        Constants.LOG.warn("No pack.mcmeta found in {} folder for {}", subPath, packName);
      }
    } catch (IOException e) {
      Constants.LOG.error("Failed to copy pack content from {}: {}", packName, e.getMessage());
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


  private static Optional<List<ValidatedMaterial>> validateMaterialsFromPack(File packFolder, String packName) {
    // Check materialpack loading requirements first
    File requirementsFile = new File(packFolder, "loading_requirements.json");
    if (requirementsFile.exists()) {
      try (BufferedReader reader = new BufferedReader(new FileReader(requirementsFile))) {
        JsonObject json = GSON.fromJson(reader, JsonObject.class);
        if (json.has("requires_mod")) {
          String requiredMod = json.get("requires_mod").getAsString();
          if (!requiredMod.isEmpty() && !Services.PLATFORM.isModLoaded(requiredMod)) {
            Constants.LOG.info("Skipping material pack {} - required mod {} is not loaded", packName, requiredMod);
            return Optional.empty();
          }
        }
      } catch (Exception e) {
        Constants.LOG.error("Failed to read loading requirements for pack {}: {}. It won't be enabled.", packName, e.getMessage());
        return Optional.empty();
      }
    }

    // Check if any materials exist (they should)
    File materialFolder = new File(packFolder, CUSTOM_MATERIALS_PATH);
    if (!materialFolder.exists()) {
      Constants.LOG.warn("Pack {} does not contain materials at expected path", packName);
      return Optional.empty();
    }
    File[] materialFiles = materialFolder.listFiles((dir, name) -> name.endsWith(".json"));
    if (materialFiles == null || materialFiles.length == 0) {
      Constants.LOG.warn("No material files found in pack {}", packName);
      return Optional.empty();
    }

    Arrays.sort(materialFiles, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER).thenComparing(File::getName));

    List<ValidatedMaterial> validatedMaterials = new ArrayList<>(materialFiles.length);
    Set<String> materialNames = new HashSet<>();
    for (File file : materialFiles) {
      try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
        JsonObject json = GSON.fromJson(reader, JsonObject.class);

        String materialName = json.get("material_name").getAsString();
        if (materialName.isEmpty() || materialName.indexOf('/') >= 0 || !Identifier.isValidPath(materialName)) {
          throw new IllegalArgumentException("material_name must be a simple lowercase Minecraft path");
        }
        if (WeaponRegistry.isBuiltInMaterialName(materialName)) {
          throw new IllegalArgumentException("material '" + materialName + "' conflicts with a built-in material");
        }
        if (!materialNames.add(materialName)) {
          throw new IllegalArgumentException("material '" + materialName + "' is declared more than once in this pack");
        }
        if (loadedMaterials.containsKey(materialName)) {
          throw new IllegalArgumentException("material '" + materialName + "' was already declared by an earlier pack");
        }
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
        validateRepairIngredient(repair_ingredient);

        if (json.has("fireproof") && !GsonHelper.isBooleanValue(json, "fireproof")) {
          throw new IllegalArgumentException("fireproof must be a boolean");
        }
        boolean fireproof = GsonHelper.getAsBoolean(json, "fireproof", false);
        Rarity rarity = Rarity.valueOf(GsonHelper.getAsString(json, "rarity", "common").toUpperCase(Locale.ROOT));

        EarlyLoadedMaterial material = new EarlyLoadedMaterial(materialName, durability, attack_damage_bonus, mining_speed, attack_speed_bonus, reach_bonus, enchantability, repair_ingredient);
        ToolMaterial toolMaterial = material.createToolMaterial();
        validatedMaterials.add(new ValidatedMaterial(materialName, material, toolMaterial, fireproof, rarity));
      } catch (Exception e) {
        Constants.LOG.error("Rejecting material pack {} because {} is invalid: {}", packName, file.getName(), e.getMessage());
        return Optional.empty();
      }
    }
    return Optional.of(validatedMaterials);
  }

  private static void commitMaterials(File packFolder, String packName, List<ValidatedMaterial> validatedMaterials) {
    for (ValidatedMaterial validatedMaterial : validatedMaterials) {
      String materialName = validatedMaterial.name();
      EarlyLoadedMaterial material = validatedMaterial.material();
      toolMaterialMap.put(validatedMaterial.toolMaterial(), material);
      loadedMaterials.put(materialName, material);
      materialToDatapackName.put(materialName, packName);
    }

    for (ValidatedMaterial validatedMaterial : validatedMaterials) {
      List<WeaponType.WeaponTypeInterface> supportedTypes = new ArrayList<>();
      supportedTypes.addAll(Arrays.asList(WeaponType.BasicWeaponType.values()));
      supportedTypes.addAll(Arrays.asList(WeaponType.VanillaWeaponType.values()));

      File textureFolder = new File(packFolder, ASSETS_PATH + "/basicweapons/textures/item");
      String texturePrefix = validatedMaterial.name() + "_";
      List<WeaponType.WeaponTypeInterface> weaponTypes = supportedTypes.stream().filter(type -> new File(textureFolder, texturePrefix + type.getId() + ".png").isFile()).toList();
      File[] materialTextures = textureFolder.listFiles(file -> file.isFile() && file.getName().startsWith(texturePrefix) && file.getName().endsWith(".png") && !file.getName().endsWith("_held.png"));
      int invalidTextures = materialTextures == null ? 0 : materialTextures.length - weaponTypes.size();

      WeaponRegistry.registerWeaponsForMaterial(new MaterialEntry(validatedMaterial.toolMaterial(), validatedMaterial.name(), settings -> {
        if (validatedMaterial.fireproof()) settings.fireResistant();
        return settings.rarity(validatedMaterial.rarity());
      }), weaponTypes);
      Constants.LOG.info("'{}' material found. adding {} weapons and skipping {} invalid ones", validatedMaterial.name(), weaponTypes.size(), invalidTextures);
    }
  }

  private record ValidatedMaterial(String name, EarlyLoadedMaterial material, ToolMaterial toolMaterial,
                                   boolean fireproof, Rarity rarity) {
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

  private static void validateRepairIngredient(String repairIngredient) {
    String identifier = repairIngredient.startsWith("#") ? repairIngredient.substring(1) : repairIngredient;
    try {
      Identifier.parse(identifier);
    } catch (RuntimeException e) {
      throw new IllegalArgumentException("repair_ingredient must be a valid item or item tag identifier");
    }
  }

  /* Returns true if folder was created, false if not or if it already exists */
  private static boolean createFolder(File folder) {
    if (!folder.exists()) {
      return folder.mkdirs();
    }
    return false;
  }

  private static boolean cleanTargetFolders() {
    // Clean config/basicweapons/bwmp_resources and config/basicweapons/bwmp_data to make sure materialpacks are always fresh
    boolean resourcesCleaned = ableToDeleteDirectory(new File(RESOURCEPACK_TARGET));
    boolean dataCleaned = ableToDeleteDirectory(new File(DATAPACK_TARGET));
    return resourcesCleaned && dataCleaned;
  }

  private static boolean ableToDeleteDirectory(File dir) {
    if (!dir.exists()) return true;
    try {
      FileUtils.deleteDirectory(dir);
      return true;
    } catch (IOException e) {
      Constants.LOG.error("Failed to clean generated material pack folder {}: {}", dir, e.getMessage());
      return false;
    }
  }
}