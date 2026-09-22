package io.github.yuroyami.kiteplayer.audioviz.viz.shader

/**
 * One distance field of generated recipes, accelerated by a coarse depth search and a
 * native-resolution refinement.
 *
 * The recipe interpreter ([GEOMETRY]) is compiled into the distance child and the lighting pass
 * only. Every search reads the field through that child, so no search program holds the field
 * inline, which keeps each program inside Android's expanded-shader budget with room to spare.
 */
internal object OdysseyScene {
    const val DEPTH_SCALE: Float = 0.25f
    private const val HEADER: String = """
uniform float2 uResolution;
float2 view(float2 p) {
    float2 uv = (p - uResolution * 0.5) / (uResolution.y * 0.5);
    return float2(uv.x, -uv.y);
}
float hash21(float2 p) {
    p = fract(p * float2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}
float sdBox(float3 p, float3 b) {
    float3 q = abs(p) - b;
    return length(max(q, float3(0.0))) + min(max(q.x, max(q.y, q.z)), 0.0);
}
"""
    private const val UNIFORMS: String = """
// District start relative to the eye, length, march safety, layout seed.
uniform float4 uDistricts[6];
// Eight fold steps per district slot: operation code and three numbers.
uniform float4 uRecipe[48];
// Per district slot: primitive code, primitive size, cavern flag, cell half size.
uniform float4 uRecipeTail[6];
uniform float3 uRoutePhase;
// Curvature, banking, focal length, far plane.
uniform float4 uRoute;
// Active fold steps; the other three numbers are unused.
uniform float4 uShape;
uniform float4 uSound;
uniform float4 uHits;
// Architecture response, light response, emission, fine detail.
uniform float4 uResponse;
// Atmosphere, world particles, saturation, ray step budget.
uniform float4 uFinish;
// Eye, forward, right and up; computed once per frame, shared by every search and shading pass.
uniform float3 uCamera[4];
uniform float4 uDynamics;
// Two delivered impact fronts: camera-relative depth and strength.
uniform float4 uWave;
uniform float uColour;
uniform float uParticleTravel;
"""
    private const val ROUTE: String = """
float2 routeAt(float z) {
    return float2(
        11.0 * sin(uRoutePhase.x + z * 0.025) + 3.5 * sin(uRoutePhase.y + z * 0.061),
        4.0 * sin(uRoutePhase.z + z * 0.021)
    ) * uRoute.x;
}

float3 inRoute(float3 p) {
    return float3(p.xy - routeAt(p.z), p.z);
}
"""
    private const val GEOMETRY: String = UNIFORMS + ROUTE + """
float waveAt(float z) {
    float a = max(0.0, 1.0 - abs(z - uWave.x) / 4.5);
    float b = max(0.0, 1.0 - abs(z - uWave.z) / 4.5);
    return min(1.2, a * a * uWave.y + b * b * uWave.w);
}
// One fold step: an operation code in s.x and three numbers. No trigonometry: rotations arrive
// as cosine and sine pairs and plane folds as unit normals, both prepared on the host.
float4 fold(float4 qs, float4 s) {
    float3 q = qs.xyz;
    float scale = qs.w;
    float op = s.x;
    if (op < 0.5) return qs;
    if (op < 1.5) {
        q = abs(q) - s.yzw;
    } else if (op < 2.5) {
        q = clamp(q, -s.y, s.y) * 2.0 - q;
    } else if (op < 3.5) {
        float r2 = dot(q, q);
        float f = r2 < s.y ? s.z / s.y : (r2 < s.z ? s.z / r2 : 1.0);
        q *= f; scale *= f;
    } else if (op < 4.5) {
        q -= 2.0 * min(0.0, dot(q, s.yzw)) * s.yzw;
    } else if (op < 5.5) {
        q = q * s.y - float3(s.z, s.w, 0.5 * (s.z + s.w)); scale *= abs(s.y);
    } else if (op < 6.5) {
        q = abs(q);
        if (q.x < q.y) q.xy = q.yx;
        if (q.x < q.z) q.xz = q.zx;
        if (q.y < q.z) q.yz = q.zy;
        q = q * s.y - float3(s.y - 1.0);
        if (q.z < -0.5 * (s.y - 1.0)) q.z += s.y - 1.0;
        scale *= s.y;
    } else if (op < 7.5) {
        q.xy = float2(q.x * s.y - q.y * s.z, q.x * s.z + q.y * s.y);
    } else {
        q.yz = float2(q.y * s.y - q.z * s.z, q.y * s.z + q.z * s.y);
    }
    return float4(q, scale);
}
// Distance and fold trace of one district's recipe, in district-local coordinates: the district
// starts at z = 0 and is span units long. The trace is how close the point came to a fold axis.
float2 recipeTrap(float3 p, int home, float span, float wave) {
    float4 steps[8];
    float4 tail = uRecipeTail[0];
    for (int i = 0; i < 8; i++) steps[i] = uRecipe[i];
    for (int d = 1; d < 6; d++) {
        if (d == home) {
            tail = uRecipeTail[d];
            for (int i = 0; i < 8; i++) steps[i] = uRecipe[d * 8 + i];
        }
    }
    float3 q = p;
    if (tail.z > 0.5) q = mod(q + tail.w, tail.w * 2.0) - tail.w;
    else q.z -= span * 0.5;
    q /= tail.w;
    float4 qs = float4(q, 1.0 / tail.w);
    float trap = 10.0;
    for (int i = 0; i < 8; i++) {
        if (float(i) >= uShape.x) break;
        qs = fold(qs, steps[i]);
        trap = min(trap, length(qs.xy));
    }
    // An impact front breathes the primitive; safetyOf covers the slope that adds along z.
    float size = tail.y * (1.0 + 0.2 * wave * uResponse.x);
    float d;
    if (tail.x < 0.5) d = sdBox(qs.xyz, float3(size));
    else if (tail.x < 1.5) d = length(qs.xyz) - size;
    else { float3 a = abs(qs.xyz); d = min(max(a.x, a.y), min(max(a.y, a.z), max(a.z, a.x))) - size; }
    return float2(d / qs.w, trap);
}
// The share of a distance estimate a ray may step, for the recipe in a slot, or the most cautious of all six for the gate.
float safetyOf(float slot) {
    // A gate stands on a district boundary, with a recipe on each side, so it steps as carefully
    // as the most cautious recipe in the world. Slots 0 to 5 read their own.
    float trust = min(min(min(uDistricts[0].z, uDistricts[1].z), min(uDistricts[2].z, uDistricts[3].z)),
        min(uDistricts[4].z, uDistricts[5].z));
    for (int index = 0; index < 6; index++) if (float(index) == slot) trust = uDistricts[index].z;
    // The wave breathes the primitive by 0.2 of its size per unit of front, divided by the folds'
    // accumulated scale. 0.2 covers a Menger-folded recipe (about 0.04 after the folds); the
    // kaleidoscope, the folded cavern and a sculpture without a Menger step can run two to three
    // times over it, which the phone run watches for as flicker on drops.
    float slope = 0.2 * (2.0 / 4.5) * (uWave.y + uWave.w) * uResponse.x;
    return trust / ((1.0 + 0.5 * uRoute.x) * (1.0 + slope));
}
float spanOf(float slot) {
    float span = 80.0;
    for (int index = 0; index < 6; index++) if (float(index) == slot) span = uDistricts[index].y;
    return span;
}
float doorway(float3 p) {
    float frame = sdBox(p, float3(2.62, 2.92, 0.25));
    float opening = sdBox(p, float3(2.37, 2.67, 0.8));
    return max(frame, -opening);
}

// Distance, district slot, layout seed, district origin. March steps stop at district
// boundaries, so a ray cannot skip the next recipe.
float4 scene(float3 point) {
    float3 p = inRoute(point);
    float4 home = uDistricts[0];
    int slot = 0;
    float closest = 10000.0;
    for (int index = 0; index < 6; index++) {
        float4 district = uDistricts[index];
        float slab = max(district.x - p.z, p.z - district.x - district.y);
        if (slab < closest) { closest = slab; home = district; slot = index; }
    }
    float slab = max(home.x - p.z, p.z - home.x - home.y);
    float body = recipeTrap(float3(p.xy, p.z - home.x), slot, home.y, waveAt(point.z)).x;
    // A safety bore shared by every recipe. Audio deformation can never engulf the eye.
    float distance = max(max(body, 1.15 - length(p.xy)), slab);
    float4 nearest = float4(distance, float(slot), home.w, home.x);
    float4 gateHome = home;
    float gateZ = abs(p.z - home.x);
    for (int index = 0; index < 6; index++) {
        float nextZ = abs(p.z - uDistricts[index].x);
        if (nextZ < gateZ) { gateHome = uDistricts[index]; gateZ = nextZ; }
    }
    float gate = doorway(float3(p.xy, p.z - gateHome.x));
    if (gate < nearest.x) nearest = float4(gate, 6.0, gateHome.w, gateHome.x);
    return nearest;
}
"""
    private const val LIGHTING: String = """
float3 tintFor(float seed) {
    return paletteCycled(seed * 0.61 + uColour);
}

float3 skyFor(float3 ray) {
    float horizon = exp(-abs(ray.y + 0.02) * 5.0);
    float3 colour = palette(0.04) * 0.07 + paletteCycled(0.6 + uColour) * horizon * 0.13;
    float2 sphere = float2(atan(ray.z, ray.x), ray.y) * 140.0;
    float2 cell = floor(sphere);
    float star = smoothstep(0.993, 1.0, hash21(cell)) *
        (1.0 - smoothstep(0.05, 0.3, length(fract(sphere) - 0.5)));
    return colour + palette(0.9) * star * (0.15 + 0.5 * uSound.z);
}

float3 surface(float3 point, float3 normal, float3 ray, float distance, float4 material) {
    float3 p = inRoute(point);
    p.z -= material.w;
    float slot = material.y;
    float seed = material.z;
    float pixel = max(0.006, distance * 2.0 / uResolution.y);
    float facing = max(dot(normal, -ray), 0.0);
    float key = max(dot(normal, normalize(float3(-0.45, 0.8, -0.35))), 0.0);
    float cavityA = clamp(scene(point + normal * 0.16).x / 0.16, 0.0, 1.0);
    float cavityB = clamp(scene(point + normal * 0.55).x / 0.55, 0.0, 1.0);
    float occlusion = 0.3 + 0.7 * (cavityA * 0.6 + cavityB * 0.4);
    float3 tint = tintFor(seed);
    float3 stone = mix(float3(0.22, 0.24, 0.28), tint, 0.45);
    float3 colour = stone * (0.14 + 0.65 * key + 0.38 * facing) * occlusion;
    float musical = uResponse.y;
    float light = 0.12 + musical * (0.45 * uEnergy + 0.35 * uSound.w);
    float3 accent = paletteCycled(seed * 0.61 + uColour + 0.2);
    float emission;
    if (slot > 5.5) {
        // Gate accents accompany the travelling impact front.
        emission = 0.35 + light + musical * 0.5 * uHits.x;
    } else {
        // The fold chain remembers how close the point came to a fold axis; those seams glow,
        // on any shape, without a light pattern written by hand for each shape.
        float trap = recipeTrap(p, int(slot), spanOf(slot), waveAt(point.z)).y;
        float seam = 1.0 - smoothstep(0.02, 0.1 + pixel * 4.0, trap);
        float channel = band(fract(floor(p.z / 6.0) * 0.173 + seed));
        emission = seam * (light + musical * (channel * 0.55 + uHits.x * 0.4)) * uResponse.w;
        colour += accent * pow(1.0 - facing, 3.0) * 0.16 * occlusion;
    }
    // Bright detail retains its hue. Broad surfaces never become a single additive white glow.
    emission += waveAt(point.z) * (0.45 + 0.55 * uSound.x) * uResponse.x;
    colour += accent * emission * uResponse.z * (0.45 + 0.55 * occlusion);
    return colour;
}

float3 worldParticles(float3 eye, float3 ray, float limit) {
    float3 light = float3(0.0);
    if (uFinish.y <= 0.0) return light;
    for (int index = 0; index < 16; index++) {
        float id = float(index);
        float z = mod(id * 6.0 - uParticleTravel + 96.0, 96.0);
        float2 lane = routeAt(z);
        float3 point = float3(lane + float2(sin(id * 2.4) * 2.0, cos(id * 1.7) * 1.7), z);
        float along = dot(point - eye, ray);
        if (along <= 0.0 || along >= limit) continue;
        float streak = 0.06 + 1.5 * uDynamics.y + 0.6 * uDynamics.z;
        float3 nearest = eye + ray * along - point;
        nearest.z = max(abs(nearest.z) - streak, 0.0);
        float miss = length(nearest);
        float radius = 0.014 + 0.018 * uSound.z;
        float mote = exp(-miss * miss / (radius * radius));
        float depthFade = smoothstep(0.3, 2.0, z) * (1.0 - smoothstep(55.0, 72.0, z));
        light += paletteCycled(id * 0.13 + uColour) * mote * depthFade *
            (0.08 + 0.6 * uSound.w + 0.25 * uHits.z) * uFinish.y;
    }
    return light;
}
"""
    private const val CAMERA: String = """
float3 cameraEye() { return uCamera[0]; }
float3 cameraRay(float2 position) {
    float2 uv = view(position);
    return normalize(uCamera[1] * uRoute.z + uCamera[2] * uv.x + uCamera[3] * uv.y);
}
"""
    private const val PACKET: String = """
float decodeDistance(float4 packet) {
    return (packet.r + packet.g / 256.0) * 128.0;
}
float4 encodeDistance(float distance, float hit) {
    float scaled = clamp(distance / 128.0, 0.0, 1.0) * 256.0;
    float high = floor(scaled);
    return float4(high / 256.0, scaled - high, hit, 1.0);
}
"""
    private const val DEPTH_PACKET: String = """
float decodeDepth(float4 packet) {
    float3 bytes = floor(packet.rgb * 255.0 + 0.5);
    bytes.r = mod(bytes.r, 128.0);
    return dot(bytes, float3(65025.0, 255.0, 1.0)) * (128.0 / 8258175.0);
}
float depthHit(float4 packet) { return step(127.5 / 255.0, packet.r); }
float4 encodeDepth(float distance, float hit) {
    float digits = floor(clamp(distance / 128.0, 0.0, 1.0) * 8258175.0);
    float high = floor(digits / 65025.0);
    digits -= high * 65025.0;
    float mid = floor(digits / 255.0);
    return float4((high + hit * 128.0) / 255.0, mid / 255.0, (digits - mid * 255.0) / 255.0, 1.0);
}
"""
    // One coarse search at quarter resolution. It reads the field through the distance child, so
    // this program holds no geometry and a single 112-step loop compiles on Android.
    private const val COARSE: String = """
uniform shader uScene;
float4 main(float2 position) {
    float3 eye = cameraEye();
    float3 ray = cameraRay(position);
    float distance = 0.0;
    float hit = 0.0;
    for (int step = 0; step < 112; step++) {
        if (hit > 0.5 || distance > uRoute.w || float(step) >= uFinish.w) break;
        float4 field = uScene.eval(float2(floor(position.y) * ceil(uResolution.x) + floor(position.x), distance));
        // Stop at a fraction of the pixel's footprint. Searching for subpixel fractal holes makes
        // low-resolution menu tiles sparkle even though their visible surfaces should be solid.
        float epsilon = max(0.0015, distance * 2.0 / (uResolution.y * uRoute.z));
        if (field.x < epsilon) { hit = 1.0; break; }
        float border = 10000.0;
        float z = eye.z + ray.z * distance;
        for (int index = 0; index < 6; index++) border = min(border, abs(z - uDistricts[index].x));
        // Cross an empty seam by only a thousandth of a world unit, then evaluate its new world.
        float stepLimit = (border + 0.001) / max(abs(ray.z), 0.0001);
        distance += min(max(field.x - epsilon, 0.001) * field.y, stepLimit);
        if (distance > uRoute.w) break;
    }
    return encodeDistance(distance, hit);
}
"""
    private const val REFINE: String = """
uniform shader uDepth;
uniform float uDepthScale;

uniform shader uScene;
float4 main(float2 position) {
    float3 eye = cameraEye();
    float3 ray = cameraRay(position);
    float4 previous = uDepth.eval(floor(position * uDepthScale) + 0.5);
    float distance = decodeDepth(previous);
    if (uDepthScale < 0.9) {
        // Strongly opening/twisting cavities can put a thin foreground lip beside a grid ray.
        // Start behind the nearest neighbouring bound rather than inheriting a deep hole.
        float2 grid = floor(position * uDepthScale) + 0.5;
        float2 last = ceil(uResolution * uDepthScale) - 0.5;
        for (int x = -1; x <= 1; x++) for (int y = -1; y <= 1; y++) {
            float2 sampleAt = clamp(grid + float2(float(x), float(y)), float2(0.5), last);
            distance = min(distance, decodeDepth(uDepth.eval(sampleAt)));
        }
    }
    // Back away by the coarse cone's radius before resolving thin native-pixel structures.
    float margin = max(0.004, distance * 2.0 / (uResolution.y * uDepthScale * uRoute.z));
    if (uDepthScale < 0.9) distance = max(0.0, distance - margin);
    float hit = depthHit(previous);
    float4 material = float4(180.0, 0.0, 0.0, 0.0);
    for (int step = 0; step < 112; step++) {
        if (hit > 0.5 || distance > uRoute.w || float(step) >= uFinish.w) break;
        material = uScene.eval(float2(floor(position.y)*ceil(uResolution.x)+floor(position.x), distance));
        // Stop at a fraction of the pixel's footprint. Searching for subpixel fractal holes makes
        // low-resolution menu tiles sparkle even though their visible surfaces should be solid.
        float epsilon = max(0.0015, distance / (uResolution.y * uRoute.z));
        if (material.x < epsilon) { hit = 1.0; break; }
        // The small safety factor covers the bent coordinate system and the fractal estimator.
        float border = 10000.0;
        float z = eye.z + ray.z * distance;
        for (int index = 0; index < 6; index++) border = min(border, abs(z - uDistricts[index].x));
        // Cross an empty seam by only a thousandth of a world unit, then evaluate its new world.
        float stepLimit = (border + 0.001) / max(abs(ray.z), 0.0001);
        distance += min(material.x * material.y, stepLimit);
        if (distance > uRoute.w) break;
    }
    return encodeDepth(distance, hit);
}
"""
    private const val NORMALS: String = """
// A numerical gradient of the whole scene serves every recipe. It includes the route bend, the
// bore, the slabs and the doorway, so no per-shape normal and no Jacobian are needed.
float3 sceneNormal(float3 point, float epsilon) {
    float2 e = float2(1.0, -1.0) * epsilon;
    float3 gradient = e.xyy * scene(point + e.xyy).x + e.yyx * scene(point + e.yyx).x +
        e.yxy * scene(point + e.yxy).x + e.xxx * scene(point + e.xxx).x;
    return gradient / max(length(gradient), 0.000001);
}

// Depth already established a hit. Identify its district and gate without repeating the folds.
float4 surfaceMaterial(float3 point, float distance) {
    float3 p = inRoute(point);
    float4 home = uDistricts[0], gateHome = uDistricts[0];
    float slot = 0.0;
    float closest = 10000.0, gateZ = 10000.0;
    for (int index = 0; index < 6; index++) {
        float4 district = uDistricts[index];
        float slab = max(district.x - p.z, p.z - district.x - district.y);
        if (slab < closest) { closest = slab; home = district; slot = float(index); }
        float z = abs(p.z - district.x);
        if (z < gateZ) { gateZ = z; gateHome = district; }
    }
    float gate = doorway(float3(p.xy, p.z - gateHome.x));
    float pixel = max(0.002, distance * 1.3 / (uResolution.y * uRoute.z));
    if (abs(gate) <= pixel) return float4(gate, 6.0, gateHome.w, gateHome.x);
    return float4(0.0, slot, home.w, home.x);
}
"""
    val COARSE_SOURCE: String = HEADER + UNIFORMS + ROUTE + CAMERA + PACKET + COARSE
    val PACK_SOURCE: String = PACKET + DEPTH_PACKET + """
uniform shader uMarch;
half4 main(float2 position) {
    // The coarse hit is only a starting bound; every native pixel still resolves its own surface.
    return half4(encodeDepth(decodeDistance(uMarch.eval(position)), 0.0));
}
"""
    // The distance child. Its coordinates are (linear pixel index, ray distance), not a picture.
    val DISTANCE_SOURCE: String = HEADER + GEOMETRY + CAMERA + """
float4 main(float2 query) {
    float stride = ceil(uResolution.x);
    float2 position = float2(mod(query.x, stride) + 0.5, floor(query.x / stride) + 0.5);
    float3 eye = cameraEye();
    float3 point = eye + cameraRay(position) * query.y;
    float4 material = scene(point);
    return float4(material.x, safetyOf(material.y), 0.0, 1.0);
}
"""
    val REFINE_SOURCE: String = HEADER + UNIFORMS + ROUTE + CAMERA + DEPTH_PACKET + REFINE
    // Evaluates one slot's recipe on a grid of points and packs the distance, for the oracle test.
    val PROBE_SOURCE: String = HEADER + GEOMETRY + DEPTH_PACKET + """
uniform float3 uProbeOrigin;
uniform float2 uProbeStep;
uniform float uProbeSlot;
uniform float uProbeSpan;
half4 main(float2 position) {
    float3 point = uProbeOrigin + float3(floor(position.x) * uProbeStep.x, floor(position.y) * uProbeStep.y, 0.0);
    float distance = recipeTrap(point, int(uProbeSlot), uProbeSpan, 0.0).x;
    return half4(encodeDepth(clamp(distance + 64.0, 0.0, 128.0), 0.0));
}
"""
    val SOURCE: String = GEOMETRY + LIGHTING + CAMERA + DEPTH_PACKET +
        "uniform shader uDepth;\n" + NORMALS + """
half4 main(float2 position) {
    float3 eye = cameraEye();
    float3 ray = cameraRay(position);
    float4 packet = uDepth.eval(position);
    float distance = decodeDepth(packet);
    float hit = depthHit(packet);
    float3 sky = skyFor(ray);
    float3 colour = sky;
    if (hit > 0.5) {
        float3 at = eye + ray * distance;
        float4 material = surfaceMaterial(at, distance);
        float3 normal = sceneNormal(at, max(0.002, distance * 0.9 / (uResolution.y * uRoute.z)));
        colour = surface(at, normal, ray, distance, material);
        float fog = 0.004 + uFinish.x * 0.022;
        colour = fogged(colour, sky, distance, fog);
        colour = mix(colour, sky, smoothstep(uRoute.w * 0.82, uRoute.w, distance));
    }
    colour += worldParticles(eye, ray, hit > 0.5 ? distance : uRoute.w);
    float luminance = dot(colour, float3(0.2126, 0.7152, 0.0722));
    colour = max(mix(float3(luminance), colour, uFinish.z), float3(0.0));
    return half4(toneMap(colour), 1.0);
}
"""
}
