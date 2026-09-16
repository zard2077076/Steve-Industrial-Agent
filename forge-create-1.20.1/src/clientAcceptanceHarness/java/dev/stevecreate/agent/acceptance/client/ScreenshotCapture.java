package dev.stevecreate.agent.acceptance.client;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

/** Minecraft-native framebuffer capture, completed only after the screenshot callback fires. */
final class ScreenshotCapture {
    private ScreenshotCapture() {}

    static CompletableFuture<Map<String, Object>> capture(Minecraft minecraft, String filename) {
        if (filename == null || !filename.matches("[A-Za-z0-9._-]{1,100}\\.png")) {
            throw new BridgeRefusal("SCREENSHOT_FILENAME_INVALID", String.valueOf(filename));
        }
        CompletableFuture<Map<String, Object>> finished = new CompletableFuture<>();
        Path screenshot = minecraft.gameDirectory.toPath().resolve("screenshots")
                .resolve(filename).normalize();
        Path screenshotRoot = minecraft.gameDirectory.toPath().resolve("screenshots").normalize();
        if (!screenshot.startsWith(screenshotRoot)) {
            throw new BridgeRefusal("SCREENSHOT_PATH_ESCAPE", filename);
        }
        Screenshot.grab(minecraft.gameDirectory, filename, minecraft.getMainRenderTarget(), message -> {
            try {
                if (!Files.isRegularFile(screenshot) || Files.size(screenshot) < 24) {
                    finished.completeExceptionally(new BridgeRefusal(
                            "SCREENSHOT_NOT_WRITTEN", message.getString()));
                    return;
                }
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("status", "CAPTURED");
                result.put("path", screenshot.toAbsolutePath().toString());
                result.put("bytes", Files.size(screenshot));
                result.put("backend", "Minecraft Screenshot.grab");
                result.put("message", message.getString());
                finished.complete(result);
            } catch (Exception error) {
                finished.completeExceptionally(error);
            }
        });
        return finished;
    }
}
