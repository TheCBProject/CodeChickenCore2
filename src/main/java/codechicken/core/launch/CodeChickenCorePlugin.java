package codechicken.core.launch;

import codechicken.core.asm.CodeChickenCoreModContainer;
import codechicken.core.asm.DelegatedTransformer;
import codechicken.core.asm.MCPDeobfuscationTransformer;
import codechicken.core.asm.TweakTransformer;
import codechicken.lib.asm.ASMHelper;
import net.minecraftforge.fml.common.FMLLog;
import net.minecraftforge.fml.common.versioning.DefaultArtifactVersion;
import net.minecraftforge.fml.common.versioning.VersionParser;
import net.minecraftforge.fml.relauncher.CoreModManager;
import net.minecraftforge.fml.relauncher.FMLInjectionData;
import net.minecraftforge.fml.relauncher.IFMLCallHook;
import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;
import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin.TransformerExclusions;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.swing.*;
import javax.swing.event.HyperlinkEvent;
import javax.swing.event.HyperlinkListener;
import java.awt.*;
import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

@TransformerExclusions(value = { "codechicken.core.asm", "codechicken.obfuscator" })
public class CodeChickenCorePlugin implements IFMLLoadingPlugin, IFMLCallHook {
    public static final String mcVersion = "[1.10.2]";
    public static final String version = "${mod_version}";

    public static File minecraftDir;
    public static String currentMcVersion;
    public static Logger logger = LogManager.getLogger("CodeChickenCore");

    public CodeChickenCorePlugin() {
        if (minecraftDir != null) {
            return;//get called twice, once for IFMLCallHook
        }

        minecraftDir = (File) FMLInjectionData.data()[6];
        currentMcVersion = (String) FMLInjectionData.data()[4];

        DepLoader.load();
        injectDeobfPlugin();
        CodeChickenCoreModContainer.loadConfig();
    }

    private void injectDeobfPlugin() {
        try {
            Class<?> wrapperClass = Class.forName("net.minecraftforge.fml.relauncher.CoreModManager$FMLPluginWrapper");
            Constructor wrapperConstructor = wrapperClass.getConstructor(String.class, IFMLLoadingPlugin.class, File.class, Integer.TYPE, String[].class);
            Field f_loadPlugins = CoreModManager.class.getDeclaredField("loadPlugins");
            wrapperConstructor.setAccessible(true);
            f_loadPlugins.setAccessible(true);
            ((List) f_loadPlugins.get(null)).add(2, wrapperConstructor.newInstance("CCCDeobfPlugin", new MCPDeobfuscationTransformer.LoadPlugin(), null, 0, new String[0]));
        } catch (Exception e) {
            logger.error("Failed to inject MCPDeobfuscation Transformer", e);
        }
    }

    @Deprecated
    public static void versionCheck(String reqVersion, String mod) {
        String mcVersion = (String) FMLInjectionData.data()[4];
        if (!VersionParser.parseRange(reqVersion).containsVersion(new DefaultArtifactVersion(mcVersion))) {

            if (CodeChickenCoreModContainer.config.getTag("ignoreInvalidMCVersion").getBooleanValue(false)) {
                FMLLog.log("CodeChickenCore", Level.FATAL, "CodeChickenCore is attempting to load mod [%s] for an invalid version of minecraft as per the set config value. If this should not be happening check your CodeChickenCore Config for \"ignoreInvalidMCVersion\".", mod);
                return;
            }

            String err = "This version of " + mod + " does not support minecraft version " + mcVersion;
            logger.error(err);

            JEditorPane ep = new JEditorPane("text/html", "<html>" +
                    err +
                    "<br>Remove it from your coremods folder and check <a href=\"http://www.minecraftforum.net/topic/909223-\">here</a> for updates" +
                    "<br>If you are %100 sure you know what you are doing optionally you can set \"ignoreInvalidMCVersion\" in the CodeChickenCore Config to true." +
                    "</html>");

            ep.setEditable(false);
            ep.setOpaque(false);
            ep.addHyperlinkListener(new HyperlinkListener() {
                @Override
                public void hyperlinkUpdate(HyperlinkEvent event) {
                    try {
                        if (event.getEventType().equals(HyperlinkEvent.EventType.ACTIVATED)) {
                            Desktop.getDesktop().browse(event.getURL().toURI());
                        }
                    } catch (Exception ignored) {
                    }
                }
            });

            JOptionPane.showMessageDialog(null, ep, "Fatal error", JOptionPane.ERROR_MESSAGE);
            System.exit(1);
        }
    }

    @Override
    public String[] getASMTransformerClass() {
        //versionCheck(mcVersion, "CodeChickenCore");
        return new String[] { /*"codechicken.core.asm.InterfaceDependancyTransformer", */"codechicken.core.asm.TweakTransformer", "codechicken.core.asm.DelegatedTransformer", "codechicken.core.asm.DefaultImplementationTransformer" };
    }

    @Override
    public String getAccessTransformerClass() {
        return "codechicken.core.asm.CodeChickenAccessTransformer";
    }

    @Override
    public String getModContainerClass() {
        return "codechicken.core.asm.CodeChickenCoreModContainer";
    }

    @Override
    public String getSetupClass() {
        return getClass().getName();
    }

    @Override
    public void injectData(Map<String, Object> data) {
    }

    @Override
    public Void call() {
        TweakTransformer.load();
        scanCodeChickenMods();
        new ASMHelper();
        return null;
    }

    private void scanCodeChickenMods() {
        File modsDir = new File(minecraftDir, "mods");
        for (File file : modsDir.listFiles()) {
            scanMod(file);
        }
        File versionModsDir = new File(minecraftDir, "mods/" + currentMcVersion);
        if (versionModsDir.exists()) {
            for (File file : versionModsDir.listFiles()) {
                scanMod(file);
            }
        }
    }

    private void scanMod(File file) {
        if (!file.getName().endsWith(".jar") && !file.getName().endsWith(".zip")) {
            return;
        }

        try {
            JarFile jar = new JarFile(file);
            try {
                Manifest manifest = jar.getManifest();
                if (manifest == null) {
                    return;
                }
                Attributes attr = manifest.getMainAttributes();
                if (attr == null) {
                    return;
                }

                String transformer = attr.getValue("CCTransformer");
                if (transformer != null) {
                    DelegatedTransformer.addTransformer(transformer, jar, file);
                }
            } finally {
                jar.close();
            }
        } catch (Exception e) {
            logger.error("CodeChickenCore: Failed to read jar file: " + file.getName(), e);
        }
    }
}
