package org.pvpbotformation;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PvpBotFormationAddon implements ModInitializer {

    public static final String MOD_ID = "pvpbot_formation";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("PVP Bot Formation addon loaded!");

        boolean groupsPresent = GroupsBridge.isGroupsAddonPresent();
        if (groupsPresent) {
            LOGGER.info("PVP Bot Formation: pvpbot-groups detected — group formation commands enabled.");
        } else {
            LOGGER.info("PVP Bot Formation: pvpbot-groups not found — group formation commands disabled.");
        }

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            FormationCommand.register(dispatcher, groupsPresent);
            LOGGER.info("PVP Bot Formation: commands registered.");
        });
    }
}
