package com.hm.achievement.config;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.text.StringEscapeUtils;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import com.hm.achievement.AdvancedAchievements;
import com.hm.achievement.category.Category;
import com.hm.achievement.category.CommandAchievements;
import com.hm.achievement.category.MultipleAchievements;
import com.hm.achievement.category.NormalAchievements;
import com.hm.achievement.exception.PluginLoadError;
import com.hm.achievement.utils.StringHelper;

/**
 * Class in charge of parsing the config.yml, lang.yml and gui.yml configuration files. It loads the files and populates
 * common data structures used in other parts of the plugin. Basic validation is performed on the achievements.
 *
 * @author Pyves
 */
@Singleton
public class ConfigurationParser {

	private final YamlConfiguration mainConfig;
	private final YamlConfiguration langConfig;
	private final YamlConfiguration guiConfig;
	private final Map<String, String> namesToDisplayNames;
	private final Map<String, String> displayNamesToNames;
	private final Map<String, List<Long>> sortedThresholds;
	private final Set<Category> disabledCategories;
	private final Set<String> enabledCategoriesWithSubcategories;
	private final StringBuilder pluginHeader;
	private final Logger logger;
	private final int serverVersion;
	private final YamlUpdater yamlUpdater;
	private final AdvancedAchievements plugin;

	@Inject
	public ConfigurationParser(@Named("main") YamlConfiguration mainConfig, @Named("lang") YamlConfiguration langConfig,
			@Named("gui") YamlConfiguration guiConfig, @Named("ntd") Map<String, String> namesToDisplayNames,
			@Named("dtn") Map<String, String> displayNamesToNames, Map<String, List<Long>> sortedThresholds,
			Set<Category> disabledCategories, Set<String> enabledCategoriesWithSubcategories, StringBuilder pluginHeader,
			Logger logger, int serverVersion, YamlUpdater yamlUpdater, AdvancedAchievements plugin) {
		this.mainConfig = mainConfig;
		this.langConfig = langConfig;
		this.guiConfig = guiConfig;
		this.namesToDisplayNames = namesToDisplayNames;
		this.displayNamesToNames = displayNamesToNames;
		this.sortedThresholds = sortedThresholds;
		this.disabledCategories = disabledCategories;
		this.enabledCategoriesWithSubcategories = enabledCategoriesWithSubcategories;
		this.pluginHeader = pluginHeader;
		this.logger = logger;
		this.serverVersion = serverVersion;
		this.yamlUpdater = yamlUpdater;
		this.plugin = plugin;
	}

	/**
	 * Loads the files and populates common data structures used in other parts of the plugin. Performs basic validation
	 * on the achievements.
	 *
	 * @throws PluginLoadError
	 */
	public void loadAndParseConfiguration() throws PluginLoadError {
		logger.info("Backing up and loading configuration files...");
		backupAndLoadConfiguration("config.yml", "config.yml", mainConfig);
		String langName = mainConfig.getString("LanguageFileName");
		backupAndLoadConfiguration(langName, langName, langConfig);
		backupAndLoadConfiguration(serverVersion < 13 ? "gui-legacy.yml" : "gui.yml", "gui.yml", guiConfig);
		parseHeader();
		parseDisabledCategories();
		parseEnabledCategoriesWithSubcategories();
		parseAchievements();
		logLoadingMessages();
	}

	/**
	 * Loads and backs up a configuration file.
	 *
	 * @param defaultConfigName
	 * @param userConfigName
	 * @param userConfig
	 *
	 * @throws PluginLoadError
	 */
	private void backupAndLoadConfiguration(String defaultConfigName, String userConfigName, YamlConfiguration userConfig)
			throws PluginLoadError {
		File configFile = new File(plugin.getDataFolder(), userConfigName);
		try {
			File backupFile = new File(plugin.getDataFolder(), userConfigName + ".bak");
			// Overwrite previous backup only if a newer version of the file exists.
			if (configFile.lastModified() > backupFile.lastModified()) {
				Files.copy(configFile.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException e) {
			logger.log(Level.SEVERE, "Failed to back up " + userConfigName + ":", e);
		}

		try {
			if (!configFile.exists()) {
				configFile.getParentFile().mkdir();
				Files.copy(plugin.getResource(defaultConfigName), configFile.toPath());
			}
			userConfig.load(configFile);
			yamlUpdater.update(defaultConfigName, userConfigName, userConfig);
		} catch (IOException | InvalidConfigurationException e) {
			throw new PluginLoadError("Failed to load " + userConfigName
					+ ". Verify its syntax on yaml-online-parser.appspot.com and use the following logs.", e);
		}
	}

	/**
	 * Parses the plugin's header, used throughout the project.
	 */
	private void parseHeader() {
		pluginHeader.setLength(0);
		String icon = StringEscapeUtils.unescapeJava(mainConfig.getString("Icon"));
		if (StringUtils.isNotBlank(icon)) {
			String coloredIcon = ChatColor.getByChar(mainConfig.getString("Color")) + icon;
			pluginHeader
					.append(ChatColor.translateAlternateColorCodes('&',
							StringUtils.replace(mainConfig.getString("ChatHeader"), "%ICON%", coloredIcon)))
					.append(" ");
		}
		pluginHeader.trimToSize();
	}

	/**
	 * Extracts disabled categories from the configuration file.
	 * 
	 * @throws PluginLoadError
	 */
	private void parseDisabledCategories() throws PluginLoadError {
		extractDisabledCategoriesFromConfig();
		// Need PetMaster with a minimum version of 1.4 for PetMasterGive and PetMasterReceive categories.
		if ((!disabledCategories.contains(NormalAchievements.PETMASTERGIVE)
				|| !disabledCategories.contains(NormalAchievements.PETMASTERRECEIVE))
				&& (!Bukkit.getPluginManager().isPluginEnabled("PetMaster") || getPetMasterMinorVersion() < 4)) {
			disabledCategories.add(NormalAchievements.PETMASTERGIVE);
			disabledCategories.add(NormalAchievements.PETMASTERRECEIVE);
			logger.warning("Overriding configuration: disabling PetMasterGive and PetMasterReceive categories.");
			logger.warning(
					"Ensure you have placed Pet Master with a minimum version of 1.4 in your plugins folder or add PetMasterGive and PetMasterReceive to the DisabledCategories list in config.yml.");
		}
		// Need Jobs for JobsReborn category.
		if (!disabledCategories.contains(MultipleAchievements.JOBSREBORN)
				&& !Bukkit.getPluginManager().isPluginEnabled("Jobs")) {
			disabledCategories.add(MultipleAchievements.JOBSREBORN);
			logger.warning("Overriding configuration: disabling JobsReborn category.");
			logger.warning(
					"Ensure you have placed JobsReborn in your plugins folder or add JobsReborn to the DisabledCategories list in config.yml.");
		}
		// Elytras introduced in Minecraft 1.9.
		if (!disabledCategories.contains(NormalAchievements.DISTANCEGLIDING) && serverVersion < 9) {
			disabledCategories.add(NormalAchievements.DISTANCEGLIDING);
			logger.warning("Overriding configuration: disabling DistanceGliding category.");
			logger.warning(
					"Elytra are not available in your Minecraft version, please add DistanceGliding to the DisabledCategories list in config.yml.");
		}
		// Llamas introduced in Minecraft 1.11.
		if (!disabledCategories.contains(NormalAchievements.DISTANCELLAMA) && serverVersion < 11) {
			disabledCategories.add(NormalAchievements.DISTANCELLAMA);
			logger.warning("Overriding configuration: disabling DistanceLlama category.");
			logger.warning(
					"Llamas not available in your Minecraft version, please add DistanceLlama to the DisabledCategories list in config.yml.");
		}
		// Breeding event introduced in Bukkit 1.10.2.
		if (!disabledCategories.contains(MultipleAchievements.BREEDING) && serverVersion < 10) {
			disabledCategories.add(MultipleAchievements.BREEDING);
			logger.warning("Overriding configuration: disabling Breeding category.");
			logger.warning(
					"The breeding event is not available in your server version, please add Breeding to the DisabledCategories list in config.yml.");
		}
		// Proper ProjectileHitEvent introduced in Bukkit 1.11.
		if (!disabledCategories.contains(MultipleAchievements.TARGETSSHOT) && serverVersion < 11) {
			disabledCategories.add(MultipleAchievements.TARGETSSHOT);
			logger.warning("Overriding configuration: disabling TargetsShot category.");
			logger.warning(
					"The projectile hit event is not fully available in your server version, please add TargetsShot to the DisabledCategories list in config.yml.");
		}
		// Raids introduced in 1.14.
		if (!disabledCategories.contains(NormalAchievements.RAIDSWON) && serverVersion < 14) {
			disabledCategories.add(NormalAchievements.RAIDSWON);
			logger.warning("Overriding configuration: disabling RaidsWon category.");
			logger.warning(
					"Raids are not available in your server version, please add RaidsWon to the DisabledCategories list in config.yml.");
		}
		// Advancements introduced in 1.12.
		if (!disabledCategories.contains(NormalAchievements.ADVANCEMENTSCOMPLETED) && serverVersion < 12) {
			disabledCategories.add(NormalAchievements.ADVANCEMENTSCOMPLETED);
			logger.warning("Overriding configuration: disabling Advancements category.");
			logger.warning(
					"Advancements are not available in your server version, please add AdvancementsCompleted to the DisabledCategories list in config.yml.");
		}
		// Riptides introduced in 1.13.
		if (!disabledCategories.contains(NormalAchievements.RIPTIDES) && serverVersion < 13) {
			disabledCategories.add(NormalAchievements.RIPTIDES);
			logger.warning("Overriding configuration: disabling Riptides category.");
			logger.warning(
					"Riptides are not available in your server version, please add Riptides to the DisabledCategories list in config.yml.");
		}
	}

	/**
	 * Retrieves Pet Master's minor version number (e.g. "1.4" -> 4).
	 * 
	 * @return Pet Master's minor version number
	 */
	private int getPetMasterMinorVersion() {
		Plugin petMaster = Bukkit.getPluginManager().getPlugin("PetMaster");
		String minorVersionString = StringUtils.split(petMaster.getDescription().getVersion(), '.')[1];
		return Integer.parseInt(minorVersionString);
	}

	/**
	 * Performs validation for the DisabledCategories list and maps the values to Category instances.
	 * 
	 * @throws PluginLoadError
	 */
	private void extractDisabledCategoriesFromConfig() throws PluginLoadError {
		disabledCategories.clear();
		for (String disabledCategory : mainConfig.getStringList("DisabledCategories")) {
			Category category = CommandAchievements.COMMANDS.toString().equals(disabledCategory)
					? CommandAchievements.COMMANDS
					: null;
			if (category == null) {
				category = NormalAchievements.getByName(disabledCategory);
			}
			if (category == null) {
				category = MultipleAchievements.getByName(disabledCategory);
			}
			if (category == null) {
				List<String> allCategories = new ArrayList<>();
				Arrays.stream(NormalAchievements.values()).forEach(n -> allCategories.add(n.toString()));
				Arrays.stream(MultipleAchievements.values()).forEach(m -> allCategories.add(m.toString()));
				allCategories.add(CommandAchievements.COMMANDS.toString());
				throw new PluginLoadError("Category " + disabledCategory + " specified in DisabledCategories is misspelt. "
						+ "Did you mean " + StringHelper.getClosestMatch(disabledCategory, allCategories) + "?");
			}
			disabledCategories.add(category);
		}
	}

	/**
	 * Extracts all enabled categories from the configuration and adds subcategories if relevant. Ignores the Commands
	 * category.
	 */
	private void parseEnabledCategoriesWithSubcategories() {
		enabledCategoriesWithSubcategories.clear();
		for (MultipleAchievements category : MultipleAchievements.values()) {
			if (!disabledCategories.contains(category)) {
				for (String subcategory : mainConfig.getConfigurationSection(category.toString()).getKeys(false)) {
					enabledCategoriesWithSubcategories.add(category + "." + StringUtils.deleteWhitespace(subcategory));
				}
			}
		}
		for (NormalAchievements category : NormalAchievements.values()) {
			if (!disabledCategories.contains(category)) {
				enabledCategoriesWithSubcategories.add(category.toString());
			}
		}
	}

	/**
	 * Goes through all the achievements for non-disabled categories.
	 * 
	 * Populates relevant data structures and performs basic validation.
	 *
	 * @throws PluginLoadError If an achievement fails to parse due to misconfiguration.
	 */
	private void parseAchievements() throws PluginLoadError {
		namesToDisplayNames.clear();
		displayNamesToNames.clear();
		sortedThresholds.clear();

		// Enumerate Commands achievements.
		if (!disabledCategories.contains(CommandAchievements.COMMANDS)) {
			Set<String> commands = mainConfig.getConfigurationSection(CommandAchievements.COMMANDS.toString())
					.getKeys(false);
			if (commands.isEmpty()) {
				disabledCategories.add(CommandAchievements.COMMANDS);
			} else {
				for (String ach : commands) {
					parseAchievement(CommandAchievements.COMMANDS + "." + ach);
				}
			}
		}

		// Enumerate the normal achievements.
		for (NormalAchievements category : NormalAchievements.values()) {
			if (!disabledCategories.contains(category)) {
				if (mainConfig.getConfigurationSection(category.toString()).getKeys(false).isEmpty()) {
					disabledCategories.add(category);
				} else {
					parseAchievements(category.toString());
				}
			}
		}

		// Enumerate the achievements with multiple categories.
		for (MultipleAchievements category : MultipleAchievements.values()) {
			if (!disabledCategories.contains(category)) {
				Set<String> keys = mainConfig.getConfigurationSection(category.toString()).getKeys(false);
				if (keys.isEmpty()) {
					disabledCategories.add(category);
				} else {
					for (String section : keys) {
						parseAchievements(category + "." + section);
					}
				}
			}
		}
	}

	/**
	 * Parses all achievements for a given category or category + subcategory. Populates the sortedThresholds map.
	 *
	 * @param path category or category.subcategory
	 * @throws PluginLoadError If an achievement fails to parse due to misconfiguration.
	 */
	private void parseAchievements(String path) throws PluginLoadError {
		Set<String> keys = mainConfig.getConfigurationSection(path).getKeys(false);
		List<Long> thresholds = new ArrayList<>();
		for (String threshold : keys) {
			parseAchievement(path + "." + threshold);
			thresholds.add(Long.valueOf(threshold));
		}
		thresholds.sort(null);
		sortedThresholds.put(path, thresholds);
	}

	/**
	 * Performs validation for a single achievement and populates an entry in the namesToDisplayNames map.
	 *
	 * @param path
	 * @throws PluginLoadError If the achievement fails to parse due to misconfiguration.
	 */
	private void parseAchievement(String path) throws PluginLoadError {
		String achName = mainConfig.getString(path + ".Name");
		if (achName == null) {
			throw new PluginLoadError("Achievement with path (" + path + ") is missing its Name parameter in config.yml.");
		} else if (namesToDisplayNames.containsKey(achName)) {
			throw new PluginLoadError(
					"Duplicate achievement Name (" + achName + "). " + "Please ensure each Name is unique in config.yml.");
		} else if (mainConfig.getString(path + ".Message") == null) {
			throw new PluginLoadError(
					"Achievement with path (" + path + ") is missing its Message parameter in config.yml.");
		} else {
			namesToDisplayNames.put(achName, mainConfig.getString(path + ".DisplayName", ""));
			String formattedDisplayName = StringHelper
					.removeFormattingCodes(mainConfig.getString(path + ".DisplayName", achName)).toLowerCase();
			displayNamesToNames.put(formattedDisplayName, achName);
		}
	}

	private void logLoadingMessages() {
		int disabledCategoryCount = disabledCategories.size();
		int categories = NormalAchievements.values().length + MultipleAchievements.values().length + 1
				- disabledCategoryCount;
		logger.info("Loaded " + namesToDisplayNames.size() + " achievements in " + categories + " categories.");

		if (!disabledCategories.isEmpty()) {
			String noun = disabledCategoryCount == 1 ? "category" : "categories";
			logger.info(disabledCategoryCount + " disabled " + noun + ": " + disabledCategories.toString());
		}
	}

}
