package com.etka.lune.client;

import com.etka.lune.util.LuneLanguages;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.LanguageInfo;
import net.minecraft.server.packs.resources.ResourceManager;

/**
 * The game client's answers to what {@link LuneLanguages} asks: the game's name for a language,
 * and the resources a resource pack may add Lune's lines to. Installed first thing by each
 * loader's client entry point.
 */
public final class ClientLanguages implements LuneLanguages.Client {

    private ClientLanguages() {}

    public static void install() {
        LuneLanguages.install(new ClientLanguages());
    }

    @Override
    public String languageName(String code) {
        LanguageInfo info = Minecraft.getInstance().getLanguageManager().getLanguage(code);
        return info == null ? null : info.name() + " (" + info.region() + ")";
    }

    @Override
    public ResourceManager resources() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft == null ? null : minecraft.getResourceManager();
    }
}
