package dev.phoneagent.vd;

import android.content.Context;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplayConfig;
import android.media.Image;
import android.media.ImageReader;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;

/**
 * Feasibility probe for the background mode: creates a virtual display as the shell user (what
 * Shizuku would do later), with an ImageReader of our own as its surface, and prints its id and
 * frame stats until killed. Apps are then started on it with `am start --display <id>`.
 *
 * Plain Java run by app_process, so it needs no app and no Kotlin runtime. Only counts and mean
 * brightness are printed; no pixels are kept or written.
 *
 * Usage: app_process -Djava.class.path=/data/local/tmp/vd.jar / dev.phoneagent.vd.VirtualDisplayTool
 *        <width> <height> <dpi> [fps] [FLAG,FLAG,...]
 */
public final class VirtualDisplayTool {
    // What scrcpy's --new-display asks for on Android 14 (names of DisplayManager.VIRTUAL_DISPLAY_FLAG_*), plus
    // STEAL_TOP_FOCUS_DISABLED: without it any input on this display makes it the top focused one, and the
    // Enter key typed on the main display then goes to the app here.
    private static final String DEFAULT_FLAGS = "PUBLIC,OWN_CONTENT_ONLY,SUPPORTS_TOUCH,ROTATES_WITH_CONTENT,"
            + "DESTROY_CONTENT_ON_REMOVAL,TRUSTED,OWN_DISPLAY_GROUP,ALWAYS_UNLOCKED,TOUCH_FEEDBACK_DISABLED,"
            + "OWN_FOCUS,DEVICE_DISPLAY_GROUP,STEAL_TOP_FOCUS_DISABLED";
    private static final long REPORT_MS = 2_000;

    private static long frames;
    private static long framesAtReport;
    private static long lastReport;

    public static void main(String[] args) throws Exception {
        int width = Integer.parseInt(args[0]);
        int height = Integer.parseInt(args[1]);
        int dpi = Integer.parseInt(args[2]);
        float fps = args.length > 3 ? Float.parseFloat(args[3]) : 0f;
        String names = args.length > 4 ? args[4] : DEFAULT_FLAGS;

        Looper.prepareMainLooper();
        ImageReader reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
        reader.setOnImageAvailableListener(VirtualDisplayTool::onImage, new Handler(Looper.myLooper()));

        int flags = flags(names);
        VirtualDisplayConfig.Builder config = new VirtualDisplayConfig.Builder("phone-agent", width, height, dpi)
                .setFlags(flags)
                .setSurface(reader.getSurface());
        if (fps > 0) config.setRequestedRefreshRate(fps);
        int id = create(config.build());
        System.out.println("display " + id + " " + width + "x" + height + " dpi " + dpi + " fps " + (fps > 0 ? fps : "default")
                + " flags 0x" + Integer.toHexString(flags) + " (" + names + ")");
        lastReport = SystemClock.uptimeMillis();
        Looper.loop();
    }

    /** Through IDisplayManager directly, as the shell package: the public API needs a Context we don't have. */
    private static int create(VirtualDisplayConfig config) throws Exception {
        IBinder service = (IBinder) Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class).invoke(null, Context.DISPLAY_SERVICE);
        Object manager = Class.forName("android.hardware.display.IDisplayManager$Stub")
                .getMethod("asInterface", IBinder.class).invoke(null, service);
        // The system keeps the display while this binder lives, so the display goes when this process does.
        Binder token = new Binder();
        Class<?> callbackType = Class.forName("android.hardware.display.IVirtualDisplayCallback");
        Object callback = Proxy.newProxyInstance(callbackType.getClassLoader(), new Class<?>[]{callbackType},
                (proxy, method, a) -> method.getDeclaringClass() == Object.class ? method.invoke(token, a)
                        : method.getName().equals("asBinder") ? token : null);
        for (Method method : manager.getClass().getMethods()) {
            if (method.getName().equals("createVirtualDisplay") && method.getParameterCount() == 4) {
                return (int) method.invoke(manager, config, callback, null, "com.android.shell");
            }
        }
        throw new NoSuchMethodException("IDisplayManager.createVirtualDisplay(config, callback, projection, package)");
    }

    private static int flags(String names) throws Exception {
        int flags = 0;
        for (String name : names.split(",")) {
            flags |= DisplayManager.class.getField("VIRTUAL_DISPLAY_FLAG_" + name.trim()).getInt(null);
        }
        return flags;
    }

    private static void onImage(ImageReader reader) {
        Image image = reader.acquireLatestImage();
        if (image == null) return;
        frames++;
        long now = SystemClock.uptimeMillis();
        if (now - lastReport >= REPORT_MS) {
            double fps = (frames - framesAtReport) * 1000.0 / (now - lastReport);
            System.out.printf("frames %d  fps %.1f  luma %.0f%n", frames, fps, luma(image));
            framesAtReport = frames;
            lastReport = now;
        }
        image.close();
    }

    /** Mean brightness on a sparse grid: 0 means black (nothing drawn, or a FLAG_SECURE window). */
    private static double luma(Image image) {
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer pixels = plane.getBuffer();
        int stride = plane.getRowStride();
        long sum = 0;
        int count = 0;
        for (int y = 0; y < image.getHeight(); y += 32) {
            for (int x = 0; x < image.getWidth(); x += 32) {
                int at = y * stride + x * 4;
                sum += (299 * (pixels.get(at) & 0xff) + 587 * (pixels.get(at + 1) & 0xff) + 114 * (pixels.get(at + 2) & 0xff)) / 1000;
                count++;
            }
        }
        return (double) sum / count;
    }
}
