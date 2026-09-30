package com.khazoda.basicweapons.materialpack;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.khazoda.basicweapons.Constants;
import com.khazoda.basicweapons.platform.Services;
import com.khazoda.basicweapons.registry.WeaponRegistry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Tier;
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
 * and reads the material stats from custom_materials/ which it stores for
 * the WeaponRegistry to use during registration.
 */

public class MaterialPackLoader {
  private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
  private static final Set<String> VANILLA_MATERIAL_NAMES = Set.of("wooden", "stone", "iron", "golden", "diamond", "netherite");
  private static final Map<String, Tier> loadedMaterials = new LinkedHashMap<>();
  private static final Map<String, String> materialToDatapackName = new LinkedHashMap<>();
  private static final Set<String> initiallyLoadedPacks = new LinkedHashSet<>();
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

    File[] packFiles = materialPacksFolder.listFiles(file ->
        file.isDirectory() || file.getName().toLowerCase(Locale.ROOT).endsWith(".zip"));
    if (packFiles == null) {
      Constants.LOG.error("Failed to read material packs folder {}", materialPacksFolder.getName());
      return;
    }
    if (!cleanTargetFolders()) {
      throw new IllegalStateException("Failed to clean generated material packs; aborting startup to avoid missing custom weapons. Check the target folders' permissions and close programs locking their files.");
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
    Constants.LOG.info("Loaded the following material packs for Basic Weapons: {}", String.join(", ", initiallyLoadedPacks));
    hasInitialized = true;
  }

  private static void processPackFolder(File packFolder, String packName) {
    if (initiallyLoadedPacks.stream().anyMatch(name -> name.equalsIgnoreCase(packName))) {
      Constants.LOG.warn("Skipping material pack {} because another source with the same name was already loaded", packName);
      return;
    }

    Optional<List<ValidatedMaterial>> validatedMaterials = validateMaterialsFromPack(packFolder, packName);
    if (validatedMaterials.isEmpty()) return;

    commitMaterials(packName, validatedMaterials.get());
    copyResourcePackContent(packFolder, packName);
    copyDataPackContent(packFolder, packName);
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

  private static void copyResourcePackContent(File packFolder, String packName) {
    /* Internal /assets folder inside material pack */
    File assetsFolder = new File(packFolder, ASSETS_PATH);
    if (!assetsFolder.exists()) return;

    /* Destination for resourcepack generated from /assets */
    File resourcepacksFolder = new File(RESOURCEPACK_TARGET);
    if (!resourcepacksFolder.exists()) {
      resourcepacksFolder.mkdirs();
    }

    File targetFolder = new File(resourcepacksFolder, packName);
    try {
      // Copy /assets folder contents (excluding pack.mcmeta, that's handled separately)
      File[] assetContents = assetsFolder.listFiles(file -> !file.getName().equals("pack.mcmeta"));
      if (assetContents != null) {
        for (File file : assetContents) {
          if (file.isDirectory()) {
            FileUtils.copyDirectory(file, new File(targetFolder, ASSETS_PATH + "/" + file.getName()));
          } else {
            FileUtils.copyFile(file, new File(targetFolder, ASSETS_PATH + "/" + file.getName()));
          }
        }
      }

      // Copy pack.png if it exists
      File packIcon = new File(packFolder, "pack.png");
      if (packIcon.exists()) {
        FileUtils.copyFile(packIcon, new File(targetFolder, "pack.png"));
      }

      // Copy assets/pack.mcmeta to root of target
      File sourcePackMcmeta = new File(assetsFolder, "pack.mcmeta");
      if (sourcePackMcmeta.exists()) {
        FileUtils.copyFile(sourcePackMcmeta, new File(targetFolder, "pack.mcmeta"));
      } else {
        Constants.LOG.warn("No pack.mcmeta found in assets folder for {}", packName);
      }
    } catch (IOException e) {
      Constants.LOG.error("Failed to copy resourcepack content from {}: {}", packName, e.getMessage());
    }
  }

  private static void copyDataPackContent(File packFolder, String packName) {
    /* Internal /data folder inside material pack */
    File dataFolder = new File(packFolder, DATA_PATH);
    if (!dataFolder.exists()) return;

    /* Destination for datapack generated from /data */
    File datapacksFolder = new File(DATAPACK_TARGET);
    if (!datapacksFolder.exists()) {
      datapacksFolder.mkdirs();
    }

    File targetFolder = new File(datapacksFolder, packName);
    try {
      // Copy /data folder contents (excluding pack.mcmeta, that's handled separately)
      File[] dataContents = dataFolder.listFiles(file -> !file.getName().equals("pack.mcmeta"));
      if (dataContents != null) {
        for (File file : dataContents) {
          if (file.isDirectory()) {
            FileUtils.copyDirectory(file, new File(targetFolder, DATA_PATH + "/" + file.getName()));
          } else {
            FileUtils.copyFile(file, new File(targetFolder, DATA_PATH + "/" + file.getName()));
          }
        }
      }

      // Copy pack.png if it exists
      File packIcon = new File(packFolder, "pack.png");
      if (packIcon.exists()) {
        FileUtils.copyFile(packIcon, new File(targetFolder, "pack.png"));
      }

      // Copy data/pack.mcmeta to root of target
      File sourcePackMcmeta = new File(dataFolder, "pack.mcmeta");
      if (sourcePackMcmeta.exists()) {
        FileUtils.copyFile(sourcePackMcmeta, new File(targetFolder, "pack.mcmeta"));
      } else {
        Constants.LOG.warn("No pack.mcmeta found in data folder for {}", packName);
      }
    } catch (IOException e) {
      Constants.LOG.error("Failed to copy datapack content from {}: {}", packName, e.getMessage());
    }
  }

  /* Returns empty if the materialpack should not be loaded. */
  private static Optional<List<ValidatedMaterial>> validateMaterialsFromPack(File packFolder, String packName) {
    // Check materialpack loading requirements first
    File requirementsFile = new File(packFolder, "loading_requirements.json");
    if (requirementsFile.exists()) {
      try (BufferedReader reader = new BufferedReader(new FileReader(requirementsFile))) {
        JsonObject json = GSON.fromJson(reader, JsonObject.class);
        if (json.has("requires_mod")) {
          String requiredMod = json.get("requires_mod").getAsString();
          if (!requiredMod.isEmpty() && !Services.PLATFORM.isModLoaded(requiredMod)) {
            Constants.LOG.info("Skipping material pack {} - required mod {} is not loaded",
                packName, requiredMod);
            return Optional.empty();
          }
        }
      } catch (Exception e) {
        Constants.LOG.error("Failed to read loading requirements for pack {}: {}. It won't be enabled.",
            packName, e.getMessage());
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
        if (materialName.isEmpty() || !ResourceLocation.isValidPath(materialName)) {
          throw new IllegalArgumentException("material_name must be a non-empty lowercase Minecraft path");
        }
        if (VANILLA_MATERIAL_NAMES.contains(materialName)
            || (materialName.equals("bronze") && Services.PLATFORM.isModLoaded("bronze"))) {
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
        float attack_speed_bonus = json.get("attack_speed_bonus").getAsFloat();
        float reach_bonus = json.get("reach_bonus").getAsFloat();
        int enchantability = json.get("enchantability").getAsInt();
        String repair_ingredient = json.get("repair_ingredient").getAsString();
        String repairIngredientId = repair_ingredient.startsWith("#") ? repair_ingredient.substring(1) : repair_ingredient;
        if (ResourceLocation.tryParse(repairIngredientId) == null) {
          throw new IllegalArgumentException("repair_ingredient must be a valid item or item tag identifier");
        }

        EarlyLoadedMaterial material = new EarlyLoadedMaterial(materialName, durability, attack_damage_bonus, attack_speed_bonus, reach_bonus, enchantability, repair_ingredient);
        validatedMaterials.add(new ValidatedMaterial(materialName, material.createTier()));
      } catch (Exception e) {
        Constants.LOG.error("Rejecting material pack {} because {} is invalid: {}", packName, file.getName(), e.getMessage());
        return Optional.empty();
      }
    }
    return Optional.of(validatedMaterials);
  }

  private static void commitMaterials(String packName, List<ValidatedMaterial> validatedMaterials) {
    for (ValidatedMaterial material : validatedMaterials) {
      loadedMaterials.put(material.name(), material.tier());
      materialToDatapackName.put(material.name(), packName);
    }

    for (ValidatedMaterial material : validatedMaterials) {
      Constants.LOG.info("'{}' material found. smithing new weapons..", material.name());
      WeaponRegistry.registerAllWeaponsForMaterial(material.name());
    }
  }

  private record ValidatedMaterial(String name, Tier tier) {}

  public static Tier getMaterial(String name) {
    return loadedMaterials.get(name);
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

  private static boolean cleanTargetFolders() {
    // Clean config/basicweapons/bwmp_resources and config/basicweapons/bwmp_data to make sure materialpacks are always fresh
    boolean resourcepacksCleaned = ableToDeleteDirectory(new File(RESOURCEPACK_TARGET));
    if (!resourcepacksCleaned)
      Constants.LOG.error("Failed to clean resource pack target folder. Please report this on the Basic Weapons issue tracker");
    boolean datapacksCleaned = ableToDeleteDirectory(new File(DATAPACK_TARGET));
    if (!datapacksCleaned)
      Constants.LOG.error("Failed to clean datapack target folder. Please report this on the Basic Weapons issue tracker");
    return resourcepacksCleaned && datapacksCleaned;
  }

  private static boolean ableToDeleteDirectory(File dir) {
    if (!dir.exists()) return true;

    try {
      FileUtils.deleteDirectory(dir);
      return true;
    } catch (IOException e) {
      return false;
    }
  }
}