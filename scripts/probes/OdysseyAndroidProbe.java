import android.graphics.*;
import android.hardware.HardwareBuffer;
import android.media.ImageReader;
import android.os.Looper;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;

/** Runs with app_process on a physical Android 13+ device, without an APK or test dependency. */
public final class OdysseyAndroidProbe {
    static int WIDTH = 320, HEIGHT = 200;
    static float currentTravel;
    static float[][] quiet, active;
    static final java.util.Map<RuntimeShader, java.util.Set<String>> names = new java.util.IdentityHashMap<>();
    static final java.util.IdentityHashMap<RuntimeShader, String> sources = new java.util.IdentityHashMap<>();
    static RuntimeShader read(String folder, String name) throws Exception {
        String source = new String(Files.readAllBytes(Paths.get(folder, name + ".sksl")));
        RuntimeShader shader = new RuntimeShader(source);
        sources.put(shader, source);
        return shader;
    }
    static float[][] readRecipes(String folder, String name) throws Exception {
        java.util.List<String> lines = Files.readAllLines(Paths.get(folder, name + ".txt"));
        float[][] rows = new float[lines.size()][];
        for (int i = 0; i < rows.length; i++) {
            String[] parts = lines.get(i).trim().split("\\s+");
            rows[i] = new float[parts.length];
            for (int j = 0; j < parts.length; j++) rows[i][j] = Float.parseFloat(parts[j]);
        }
        return rows;
    }
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
    static void child(RuntimeShader shader, String name, RuntimeShader value) {
        if (declares(shader, name)) shader.setInputShader(name, value);
    }
    // Six slots from row `first`: district start relative to the eye, length, safety, seed, then the recipe.
    static void journey(RuntimeShader shader, float travel, float[][] rows, int first) {
        float[] districts = new float[24], recipes = new float[192], tails = new float[24];
        for (int slot = 0; slot < 6; slot++) {
            float[] row = rows[first + slot];
            districts[slot * 4] = row[0] - travel; districts[slot * 4 + 1] = row[1];
            districts[slot * 4 + 2] = row[2]; districts[slot * 4 + 3] = row[3];
            System.arraycopy(row, 4, recipes, slot * 32, 32);
            System.arraycopy(row, 36, tails, slot * 4, 4);
        }
        set(shader, "uDistricts", districts);
        set(shader, "uRecipe", recipes);
        set(shader, "uRecipeTail", tails);
        set(shader, "uShape", 8, 0, 0, 0);
    }
    static void camera(RuntimeShader shader, float travel, float x, float y, float yaw, float pitch, float bank) {
        double px = travel * .025, py = travel * .061, pz = travel * .021;
        double ox = (11 * Math.sin(px) + 3.5 * Math.sin(py)) * .65;
        double oy = 4 * Math.sin(pz) * .65;
        double fx = (11 * Math.sin(px + 7 * .025) + 3.5 * Math.sin(py + 7 * .061)) * .65 - ox;
        double fy = 4 * Math.sin(pz + 7 * .021) * .65 - oy, fz = 7;
        double len = Math.sqrt(fx * fx + fy * fy + fz * fz);
        fx /= len; fy /= len; fz /= len;
        double h = Math.sqrt(fx * fx + fz * fz), rx = fz / h, ry = 0, rz = -fx / h;
        double ux = fy * rz, uy = fz * rx - fx * rz, uz = -fy * rx;
        double cy = Math.cos(yaw), sy = Math.sin(yaw), cp = Math.cos(pitch), sp = Math.sin(pitch);
        double lx = fx * cy + rx * sy, ly = fy * cy + ry * sy, lz = fz * cy + rz * sy;
        rx = rx * cy - fx * sy; ry = ry * cy - fy * sy; rz = rz * cy - fz * sy;
        fx = lx * cp + ux * sp; fy = ly * cp + uy * sp; fz = lz * cp + uz * sp;
        ux = ux * cp - lx * sp; uy = uy * cp - ly * sp; uz = uz * cp - lz * sp;
        double cb = Math.cos(bank), sb = Math.sin(bank);
        set(shader, "uCamera", (float)ox + x, (float)oy + y, 0, (float)fx, (float)fy, (float)fz,
            (float)(rx * cb + ux * sb), (float)(ry * cb + uy * sb), (float)(rz * cb + uz * sb),
            (float)(ux * cb - rx * sb), (float)(uy * cb - ry * sb), (float)(uz * cb - rz * sb));
    }
    static void inputs(RuntimeShader shader, float travel, float[][] rows, int first, RuntimeShader palette, RuntimeShader bands) {
        currentTravel = travel;
        set(shader, "uResolution", WIDTH, HEIGHT);
        journey(shader, travel, rows, first);
        set(shader, "uRoutePhase", travel * .025f, travel * .061f, travel * .021f);
        set(shader, "uRoute", .65f, .75f, 1.376382f, 72);
        camera(shader, travel, .25f, -.12f, .28f, -.08f, -.12f);
        set(shader, "uDynamics", -.12f, .65f, .4f, .6f);
        set(shader, "uWave", 12, .65f, 24, .3f);
        set(shader, "uSound", .6f, .5f, .7f, .6f);
        set(shader, "uHits", .4f, .2f, .2f, .1f);
        set(shader, "uResponse", .85f, 1, .85f, .8f);
        set(shader, "uFinish", .35f, .4f, .85f, 88);
        set(shader, "uColour", .1f);
        set(shader, "uParticleTravel", travel % 96);
        set(shader, "uEnergy", .6f);
        set(shader, "uExposure", .714f);
        child(shader, "uPaletteTex", palette);
        child(shader, "uBandsTex", bands);
        // Android retains unused child declarations as well.
        child(shader, "uScopeTex", bands);
        child(shader, "uHistoryTex", bands);
    }
    public static void main(String[] args) {
        try { run(args); System.exit(0); }
        catch (Throwable failure) { failure.printStackTrace(); System.exit(1); }
    }

    static android.media.Image render(HardwareRenderer renderer, RenderNode node, ImageReader reader,
            RuntimeShader source, RuntimeShader refine, RuntimeShader shade, boolean reference, boolean depthOnly) {
        Paint paint = new Paint();
        paint.setShader(source);
        RecordingCanvas canvas = node.beginRecording(WIDTH, HEIGHT);
        canvas.drawColor(Color.BLACK);
        if (!reference) canvas.drawRect(0, 0, (float)Math.ceil(WIDTH * .25), (float)Math.ceil(HEIGHT * .25), paint);
        node.endRecording();
        set(refine, "uDepthScale", reference ? 1 : .25f);
        RenderEffect effect = RenderEffect.createRuntimeShaderEffect(refine, "uDepth");
        if (!depthOnly) effect = RenderEffect.createChainEffect(RenderEffect.createRuntimeShaderEffect(shade, "uDepth"), effect);
        node.setRenderEffect(effect);
        int sync = renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw();
        android.media.Image image = reader.acquireNextImage();
        if (image == null) throw new AssertionError("No GPU image, sync=" + sync);
        // Accessing the CPU plane waits for the GPU producer. PNG encoding is outside the timer.
        image.getPlanes()[0].getBuffer().get(0);
        return image;
    }

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

    static double depth(int pixel) {
        return (((pixel >>> 16) & 127) * 65025.0 + ((pixel >>> 8) & 255) * 255.0 + (pixel & 255)) * 128.0 / 8258175.0;
    }

    static void compareDepth(String name, int[] expected, int[] actual) {
        int hit = 0, lost = 0, different = 0, nearer = 0;
        for (int i = 0; i < expected.length; i++) {
            if ((expected[i] & 0x800000) == 0) continue;
            hit++;
            if ((actual[i] & 0x800000) == 0) { lost++; continue; }
            double reference = depth(expected[i]);
            // Different conservative starts can settle on different sides of the hit epsilon.
            double tolerance = Math.max(.01, reference * 3.0 / (HEIGHT * 1.376382));
            if (Math.abs(reference - depth(actual[i])) > tolerance) {
                different++;
                if (depth(actual[i]) < reference) nearer++;
            }
        }
        System.out.printf("DEPTH %s: reference hits=%d lost=%d displaced=%d nearer=%d%n", name, hit, lost, different, nearer);
        if (hit < expected.length / 10) throw new AssertionError(name + " reference has no architecture");
        // The reference also has a finite step budget. A nearer hit can recover a small feature
        // it skipped, so guard lost/receding surfaces rather than rejecting that extra detail.
        if (lost > hit * .005 || lost + different - nearer > hit * .01) {
            throw new AssertionError(name + " prepass lost or displaced visible geometry");
        }
    }

    static void save(String path, int[] pixels) throws Exception {
        int visible = 0;
        for (int pixel : pixels) if (Math.max((pixel >>> 16) & 255, Math.max((pixel >>> 8) & 255, pixel & 255)) > 20) visible++;
        if (visible < pixels.length / 10) throw new AssertionError(path + " is black: " + visible);
        Bitmap bitmap = Bitmap.createBitmap(pixels, WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
        try (FileOutputStream out = new FileOutputStream(path)) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        } finally { bitmap.recycle(); }
        System.out.println("PASS: " + path + " GPU render, visible pixels=" + visible);
    }

    static void reactivityInputs(RuntimeShader shader, boolean active) {
        set(shader, "uSound", active ? .9f : .08f, active ? .85f : .12f, active ? .8f : .06f, active ? .9f : .08f);
        set(shader, "uHits", active ? .9f : 0, active ? .75f : 0, active ? .6f : 0, active ? .8f : 0);
        set(shader, "uDynamics", 0, active ? .9f : .05f, active ? .8f : 0, active ? .8f : .05f);
        camera(shader, currentTravel, 0, 0, 0, 0, 0);
        set(shader, "uWave", 10, active ? .9f : 0, 0, 0);
    }

    static void verifyReactivity(String folder, String name, float travel, int first, HardwareRenderer renderer, RenderNode node,
            ImageReader reader, RuntimeShader coarse, RuntimeShader sceneQuarter, RuntimeShader pack, RuntimeShader scene,
            RuntimeShader refine, RuntimeShader shade) throws Exception {
        int[][] depths = new int[2][];
        for (int state = 0; state < 2; state++) {
            float[][] rows = state == 1 ? active : quiet;
            // Every uniform is set before a child is bound, because Android snapshots a child at binding time.
            for (RuntimeShader program : new RuntimeShader[] {sceneQuarter, coarse, scene, refine, shade}) {
                reactivityInputs(program, state == 1);
                journey(program, travel, rows, first);
            }
            coarse.setInputShader("uScene", sceneQuarter);
            pack.setInputShader("uMarch", coarse);
            refine.setInputShader("uScene", scene);
            try (android.media.Image image = render(renderer, node, reader, pack, refine, shade, false, true)) {
                depths[state] = pixels(image);
            }
            try (android.media.Image image = render(renderer, node, reader, pack, refine, shade, false, false)) {
                save(folder + "/" + name + (state == 1 ? "-active" : "-quiet") + ".png", pixels(image));
            }
        }
        int[] reference;
        try (android.media.Image image = render(renderer, node, reader, pack, refine, shade, true, true)) {
            reference = pixels(image);
        }
        compareDepth(name + " active", reference, depths[1]);
        int moved = 0;
        for (int i = 0; i < depths[0].length; i++) {
            if (((depths[0][i] ^ depths[1][i]) & 0x800000) != 0 ||
                    Math.abs(depth(depths[0][i]) - depth(depths[1][i])) > .08) moved++;
        }
        double share = moved / (double)depths[0].length;
        System.out.printf("MOTION %s: %.2f%% pixels change geometry with camera locked%n", name, share * 100);
        if (share < .03) throw new AssertionError(name + " music changes too little actual geometry: " + share);
    }

    static void run(String[] args) throws Exception {
        Looper.prepareMainLooper();
        String folder = args[0];
        int frames = 0;
        if (args.length > 1) {
            WIDTH = Integer.parseInt(args[1]); HEIGHT = Integer.parseInt(args[2]); frames = Integer.parseInt(args[3]);
            if (WIDTH < 32 || HEIGHT < 32 || WIDTH > 3840 || HEIGHT > 3840 || frames < 0 || frames > 300)
                throw new IllegalArgumentException("Use dimensions 32..3840 and frames 0..300");
        }
        RuntimeShader coarse = read(folder, "coarse"), pack = read(folder, "pack");
        RuntimeShader sceneQuarter = read(folder, "scene"), scene = read(folder, "scene");
        RuntimeShader refine = read(folder, "refine"), shade = read(folder, "shade");
        System.out.println("PASS: all Odyssey programs compile on Android");
        quiet = readRecipes(folder, "recipes-quiet");
        active = readRecipes(folder, "recipes-active");
        RuntimeShader palette = new RuntimeShader("half4 main(float2 p) { return half4(0.48 + 0.38 * cos(6.2831853 * (p.x / 64.0 + float3(0,0.333,0.667))), 1); }");
        RuntimeShader bands = new RuntimeShader("half4 main(float2 p) { return half4(0.3+0.45*pow(0.5+0.5*sin(p.x*0.37),2.0),0,0,1); }");
        ImageReader reader = ImageReader.newInstance(WIDTH, HEIGHT, PixelFormat.RGBA_8888, 2,
            HardwareBuffer.USAGE_GPU_COLOR_OUTPUT | HardwareBuffer.USAGE_CPU_READ_OFTEN);
        HardwareRenderer renderer = new HardwareRenderer();
        RenderNode node = new RenderNode("Odyssey probe");
        node.setPosition(0, 0, WIDTH, HEIGHT);
        renderer.setSurface(reader.getSurface()); renderer.setContentRoot(node);
        try {
            for (int shot = 1; shot <= 12; shot++) {
                String label = String.format("district-%02d", shot);
                int first = shot - 1;
                double[] times = new double[frames];
                for (int frame = -4; frame < Math.max(1, frames); frame++) {
                    float travel = quiet[shot][0] + 12 + Math.max(frame, 0) * .025f;
                    long before = System.nanoTime();
                    inputs(sceneQuarter, travel, quiet, first, palette, bands);
                    set(sceneQuarter, "uResolution", WIDTH * .25f, HEIGHT * .25f);
                    inputs(coarse, travel, quiet, first, palette, bands);
                    set(coarse, "uResolution", WIDTH * .25f, HEIGHT * .25f);
                    coarse.setInputShader("uScene", sceneQuarter);
                    pack.setInputShader("uMarch", coarse);
                    inputs(scene, travel, quiet, first, palette, bands);
                    inputs(refine, travel, quiet, first, palette, bands);
                    refine.setInputShader("uScene", scene);
                    inputs(shade, travel, quiet, first, palette, bands);
                    try (android.media.Image image = render(renderer, node, reader, pack, refine, shade, false, false)) {
                        if (frame >= 0 && frames > 0) times[frame] = (System.nanoTime() - before) / 1e6;
                        if (frame == -4) save(folder + "/" + label + ".png", pixels(image));
                    }
                    if (frame == -4) {
                        int[] reference, accelerated;
                        try (android.media.Image image = render(renderer, node, reader, pack, refine, shade, true, true)) { reference = pixels(image); }
                        try (android.media.Image image = render(renderer, node, reader, pack, refine, shade, false, true)) { accelerated = pixels(image); }
                        compareDepth(label, reference, accelerated);
                        verifyReactivity(folder, label, travel, first, renderer, node, reader, coarse, sceneQuarter, pack, scene, refine, shade);
                    }
                }
                if (frames > 0) {
                    java.util.Arrays.sort(times);
                    System.out.printf("BENCH %s %dx%d frames=%d median=%.2f p95=%.2f ms (upload + draw + GPU readback)%n",
                        label, WIDTH, HEIGHT, frames, times[frames / 2], times[Math.min(frames - 1, (int)(frames * .95))]);
                }
            }
        } finally {
            renderer.destroy(); reader.close();
        }
    }
}
