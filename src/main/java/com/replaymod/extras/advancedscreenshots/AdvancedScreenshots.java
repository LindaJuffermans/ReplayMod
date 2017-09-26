package com.replaymod.extras.advancedscreenshots;

import com.replaymod.core.ReplayMod;
import com.replaymod.extras.Extra;
import com.replaymod.render.RenderSettings;
import com.replaymod.render.capturer.RenderInfo;
import com.replaymod.render.hooks.ChunkLoadingRenderGlobal;
import com.replaymod.render.rendering.Pipelines;
import com.replaymod.replay.events.ReplayDispatchKeypressesEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiControls;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.ScreenShotHelper;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.lwjgl.input.Keyboard;
import org.lwjgl.util.Dimension;
import org.lwjgl.util.ReadableDimension;

import java.io.File;

public class AdvancedScreenshots implements Extra {

    private final Minecraft mc = Minecraft.getMinecraft();

    @Override
    public void register(ReplayMod mod) throws Exception {
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onDispatchKeypresses(ReplayDispatchKeypressesEvent.Pre event) {
        int i = Keyboard.getEventKey() == 0 ? Keyboard.getEventCharacter() : Keyboard.getEventKey();

        // all the conditions required to trigger a screenshot condensed in a single if statement
        if (i != 0 && !Keyboard.isRepeatEvent()
                && (!(mc.currentScreen instanceof GuiControls) || ((GuiControls) mc.currentScreen).time <= mc.getSystemTime() - 20L)
                && Keyboard.getEventKeyState()
                && i == mc.gameSettings.keyBindScreenshot.getKeyCode()) {

            if (GuiScreen.isCtrlKeyDown()) {
                ReplayMod.instance.runLater(() -> {
                    // take 360° screenshot
                    File screenshotFolder = new File(mc.mcDataDir, "screenshots");
                    screenshotFolder.mkdir();
                    File screenshotFile = ScreenShotHelper.getTimestampedPNGFileForDirectory(screenshotFolder);

                    int width = 8640;
                    int height = 4320;

                    int displayWidthBefore = mc.displayWidth;
                    int displayHeightBefore = mc.displayHeight;

                    ChunkLoadingRenderGlobal clrg = new ChunkLoadingRenderGlobal(mc.renderGlobal);

                    Pipelines.newEquirectangularPipeline(new RenderInfo() {
                        @Override
                        public ReadableDimension getFrameSize() {
                            return new Dimension(width, height);
                        }

                        @Override
                        public int getTotalFrames() {
                            return 1;
                        }

                        @Override
                        public float updateForNextFrame() {
                            return mc.timer.renderPartialTicks;
                        }

                        @Override
                        public RenderSettings getRenderSettings() {
                            return new RenderSettings(
                                    null, null, width, height, 0, 0, null,
                                    true, true, true, true, null,
                                    false, RenderSettings.AntiAliasing.NONE, null, null, false
                            );
                        }
                    }, new ScreenshotWriter(screenshotFile)).run();

                    clrg.uninstall();

                    // the Equirectangular rendering changes mc.displayWidth and mc.displayHeight,
                    // so we have to reset it to the previous value
                    mc.resize(displayWidthBefore, displayHeightBefore);
                });

                event.setCanceled(true);
            }
        }
    }
}