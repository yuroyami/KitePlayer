import android.graphics.RuntimeShader;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;

/** Shared helpers for the device probes, which run with app_process without an APK or test dependency. */
final class ProbeSupport {
    static int WIDTH = 320, HEIGHT = 200;
    static final java.util.Map<RuntimeShader, java.util.Set<String>> names = new java.util.IdentityHashMap<>();
    static final java.util.IdentityHashMap<RuntimeShader, String> sources = new java.util.IdentityHashMap<>();

    /** Compiles `name.sksl` from [folder] with this device's own runtime shader compiler. */
    static RuntimeShader read(String folder, String name) throws Exception {
        String source = new String(Files.readAllBytes(Paths.get(folder, name + ".sksl")));
        RuntimeShader shader = new RuntimeShader(source);
        sources.put(shader, source);
        return shader;
    }

    /** Whether the program declares a uniform of this name; setting an undeclared one throws. */
    static boolean declares(RuntimeShader shader, String name) {
        java.util.Set<String> found = names.get(shader);
        if (found == null) {
            found = new java.util.HashSet<>();
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("uniform\\s+\\w+\\s+(\\w+)").matcher(sources.get(shader));
            while (matcher.find()) found.add(matcher.group(1));
            names.put(shader, found);
        }
        return found.contains(name);
    }

    static void set(RuntimeShader shader, String name, float... values) {
        if (declares(shader, name)) shader.setFloatUniform(name, values);
    }

    /** The image's pixels as opaque ARGB, row by row. */
    static int[] pixels(android.media.Image image) {
        android.media.Image.Plane plane = image.getPlanes()[0];
        ByteBuffer bytes = plane.getBuffer();
        int[] pixels = new int[WIDTH * HEIGHT];
        for (int y = 0; y < HEIGHT; y++) for (int x = 0; x < WIDTH; x++) {
            int at = y * plane.getRowStride() + x * plane.getPixelStride();
            pixels[y * WIDTH + x] = 0xff000000 | (bytes.get(at) & 255) << 16 |
                (bytes.get(at + 1) & 255) << 8 | bytes.get(at + 2) & 255;
        }
        return pixels;
    }
}
