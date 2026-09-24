package io.github.yuroyami.kiteplayer.audioviz.viz.shader

/** One native-resolution analytic pass. No height marching, bake, readback or depth refinement. */
internal object NeonLoFiSky {
    val SOURCE = """
uniform float3 nLow;
uniform float3 nMid;
uniform float3 nHigh;
uniform float3 nCap;
uniform float3 nInk;
uniform float3 nNear;
uniform float3 nFar;
uniform float4 nCamera; // bank cos/sin, height, distance
uniform float4 nLife; // local audible phase, layout, air, slow level
uniform float4 nStyle; // neon, ribbons, mountain amount, coast weight
uniform float3 nSun;
uniform float nLens;
uniform float nTexture;
uniform float4 nRegions;
uniform float4 nGaps0; // the sun's gaps, bass at the bottom
uniform float4 nGaps1;
uniform float4 nPulse; // kick, wet road, lift-off, horizon
uniform shader nRidges;
// Light is scaled on its brightest channel above the knee, so a bright colour keeps its hue.
float3 nDisplay(float3 linear) {
    float3 t = max(float3(0), linear * uExposure);
    float peak = max(t.r, max(t.g, t.b));
    float rolled = 0.75 + 0.25 * (1.0 - exp(-(peak - 0.75) / 0.25));
    t *= peak > 0.75 ? rolled / peak : 1.0;
    t = min(t, float3(1.0));
    return mix(12.92 * t, 1.055 * pow(max(t,float3(0.00001)),float3(1.0/2.4)) - 0.055, step(float3(0.0031308),t));
}
float nRoad(float z) {
    return 2.2 * sin(z * 0.009 + nLife.y * 4.0) + 0.7 * sin(z * 0.023 + nLife.y * 9.0);
}
float nSlope(float z) {
    return 0.0198 * cos(z * 0.009 + nLife.y * 4.0) + 0.0161 * cos(z * 0.023 + nLife.y * 9.0);
}
float4 nOneHot(float k) {
    return float4(1.0) - step(float4(0.5), abs(float4(0.0, 1.0, 2.0, 3.0) - k));
}
float3 nSky(float2 p) {
    // The only gradient in the scene: a rose glow at the horizon under an indigo sky.
    float up = clamp(-p.y / 0.5, 0.0, 1.0);
    float3 col = mix(nMid, nLow, smoothstep(0.0, 0.75, up));
    // The sun, gold at the top and coral at the bottom, pulsing with the kick.
    float r = nSun.z * (1.0 + 0.03 * nPulse.x);
    float d = length(p - nSun.xy);
    float edge = (1.1 + 5.0 * nPulse.y) / uResolution.y;
    float disc = 1.0 - smoothstep(r - edge, r + edge, d);
    float y = (p.y - nSun.y) / r;
    // Eight gaps across its lower half, bass at the bottom, each as wide as its band is loud. A
    // silence leaves thin, even gaps; a lift-off closes them and the sun stands whole.
    float pixel = 1.0 / (uResolution.y * r);
    float cut = 1.0;
    for (int i = 0; i < 8; i++) {
        float k = float(i);
        float level = k < 4.0 ? dot(nGaps0, nOneHot(k)) : dot(nGaps1, nOneHot(k - 4.0));
        float centre = 0.9 - k * 0.12;
        float gap = (0.012 + 0.05 * level) * (1.0 - nPulse.z);
        cut *= smoothstep(gap - pixel, gap + pixel, abs(y - centre));
    }
    disc *= cut;
    float3 sun = mix(nHigh, nCap, clamp(y * 0.5 + 0.5, 0.0, 1.0)) * (1.5 + 0.35 * nPulse.x);
    // A thin halo, which a wet night hazes out.
    float halo = exp(-max(d - r, 0.0) / (0.015 + 0.05 * nPulse.y)) * (0.18 + 0.3 * nPulse.y);
    col = mix(col, sun, disc) + mix(nHigh, nCap, 0.5) * halo * (1.0 - disc);
    // Four long quiet ribbons, bent by separate measured upper bands.
    if (p.y < -0.20) for (int i=0; i<4; i++) {
        float fi = float(i);
        float measured = band(0.52 + fi * 0.13);
        float ribbonY = -0.30 - fi*0.043 + sin(p.x*(1.5+fi*0.35+nTexture*0.25)+fi*1.8) * (0.02 + measured*0.035 + nLife.z*0.015);
        float line = exp(-abs(p.y-ribbonY) / (0.0015 + 1.0 / uResolution.y));
        col += mix(nFar, nHigh, fi/3.0) * line * nStyle.y * (0.03+measured*0.1);
    }
    return col;
}
half4 main(float2 position) {
    float2 q = (position - float2(uResolution.x*0.5,uResolution.y*nPulse.w)) / uResolution.y;
    float2 p = float2(q.x*nCamera.x+q.y*nCamera.y,-q.x*nCamera.y+q.y*nCamera.x);
    float aa = 1.0/uResolution.y;
    float3 col = p.y < aa*2.0 ? nSky(p) : float3(0.0);
    // Black hills with neon crests. Each pixel reads two host-prepared contour samples, never the
    // 360-row history; in the pass they are the heard song's spectrogram.
    float aspect = uResolution.x/uResolution.y;
    float openingWidth = clamp(nSun.z/aspect*2.0,0.10,0.65);
    float across = clamp((abs(p.x)/(aspect*0.5)-openingWidth)/(1.0-openingWidth),0.0,1.0);
    float az = 0.5 + sign(p.x)*across*0.5;
    if (p.y < aa*2.0) for (int i=1; i>=0; i--) {
        float h = nRidges.eval(float2(az*255.0+0.5,float(i)+0.5)).r;
        float opening = smoothstep(0.03,0.28,abs(az-0.5)*2.0);
        float ridge = -(sqrt(h) * (i==0 ? 0.30 : 0.20) * nStyle.z + opening*0.008) * (1.0+nRegions.y*0.45);
        float fill = smoothstep(ridge-aa,ridge+aa,p.y);
        float line = (1.0-smoothstep(aa*0.45,aa*1.65,abs(p.y-ridge))) * (0.2 + 0.5*h) * nStyle.x;
        col = mix(col,nInk,fill);
        col += nFar * line * (i==0 ? 1.0 : 0.6);
    }
    if (p.y > 0.0) {
        float z = min(1500.0,nCamera.z*0.714074/max(p.y,0.0001));
        float worldZ = nCamera.w + z;
        float worldX = p.x*z/nLens + nRoad(nCamera.w) + nSlope(nCamera.w)*z;
        float localX = worldX - nRoad(worldZ);
        float footprint = max(z/uResolution.y/nLens, 0.003);
        float footprintZ = max(z*z/(nCamera.z*0.714074*uResolution.y),0.01);
        float gx = abs(fract(worldX/4.0+0.5)-0.5)*4.0;
        float gz = abs(fract(worldZ/4.0+0.5)-0.5)*4.0;
        float gridX = (1.0-smoothstep(0.015,0.015+footprint,gx))*(1.0-smoothstep(1.0,2.2,footprint));
        float gridZ = (1.0-smoothstep(0.022,0.022+footprintZ,gz))*(1.0-smoothstep(1.0,2.2,footprintZ));
        float road = 1.0-smoothstep(3.25-footprint,3.25+footprint,abs(localX));
        float edge = 1.0-smoothstep(0.028,0.028+footprint,abs(abs(localX)-3.25));
        // One white dash a lane length, which the road passes once a beat.
        float center = (1.0-smoothstep(0.025,0.025+footprint,abs(localX))) * step(0.55,fract(worldZ/7.0));
        float fade = 1.0-smoothstep(45.0,120.0,z);
        gridX *= min(1.0,0.12/footprint);
        gridZ *= min(1.0,0.12/footprintZ);
        float3 ground = nInk;
        ground += nFar * max(gridX,gridZ) * 0.14 * (1.0-road) * fade * nStyle.x;
        ground = mix(ground,nInk*0.5,road);
        // Neon road edges that pulse with the kick, and white dashes.
        ground += nNear*edge*fade*nStyle.x*(0.9+0.9*nPulse.x);
        ground += float3(0.85)*center*fade;
        // A wet road carries the sun's reflection down toward the viewer, broken by ripples.
        if (nPulse.y > 0.001) {
            float glint = exp(-abs(p.x-nSun.x)/(0.015+0.25*p.y)) * (0.55+0.45*sin(worldZ*1.7+nLife.x*0.5));
            ground += mix(nHigh,nCap,0.45) * glint * nPulse.y * road * 0.8 * fade;
        }
        float water = smoothstep(3.6,7.6,localX)*nStyle.w;
        if (water > 0.001) {
            float2 mirror = float2(p.x,-p.y*0.62);
            mirror.x += sin(worldZ*0.65+worldX*2.0+nLife.x*0.4)*0.002*(0.2+nLife.z);
            float3 sea = nSky(mirror)*0.4;
            // The sun's light lies on the sea in stripes, each as long as the waveform reaches:
            // the heard sound, laid on the water out from the shore.
            float row = floor(worldZ/2.5);
            float reach = abs(nRidges.eval(float2(fract(row*0.0371)*255.0+0.5,2.5)).r - 0.5) * 2.0;
            float offshore = localX - 3.6;
            float stripe = (1.0-smoothstep(reach*30.0-footprint,reach*30.0+footprint,offshore)) *
                smoothstep(0.1,0.1+footprintZ/2.5,fract(worldZ/2.5)) * (1.0-smoothstep(0.55,0.55+footprintZ/2.5,fract(worldZ/2.5)));
            sea += mix(nHigh,nCap,0.5) * stripe * 0.9 * fade;
            ground = mix(ground,sea,water*0.9);
        }
        col = mix(col,ground,smoothstep(0.0,aa*2.0,p.y));
    }
    return half4(nDisplay(col),1.0);
}
""".trimIndent()
}
