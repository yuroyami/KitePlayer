package io.github.yuroyami.kiteplayer.audioviz.viz.shader

/** One native-resolution analytic pass. No height marching, bake, readback or depth refinement. */
internal object NeonLoFiSky {
    val SOURCE = """
uniform float3 nLow;
uniform float3 nMid;
uniform float3 nHigh;
uniform float3 nCap;
uniform float3 nInk;
uniform float4 nCamera; // bank cos/sin, height, distance
uniform float4 nLife; // local audible phase, layout, air, slow level
uniform float4 nStyle; // neon, ribbons, mountain amount, coast weight
uniform float3 nSun;
uniform float nLens;
uniform float nTexture;
uniform float4 nRegions;
uniform shader nRidges;
float3 nDisplay(float3 linear) {
    float3 t = max(float3(0), linear * uExposure);
    t = t / (1.0 + t);
    return mix(12.92 * t, 1.055 * pow(max(t,float3(0.00001)),float3(1.0/2.4)) - 0.055, step(float3(0.0031308),t));
}
float nRoad(float z) {
    return 2.2 * sin(z * 0.009 + nLife.y * 4.0) + 0.7 * sin(z * 0.023 + nLife.y * 9.0);
}
float nSlope(float z) {
    return 0.0198 * cos(z * 0.009 + nLife.y * 4.0) + 0.0161 * cos(z * 0.023 + nLife.y * 9.0);
}
float3 nSky(float2 p) {
    float horizon = exp(-abs(p.y) * 3.5);
    float3 col = nInk * 0.7 + nLow * (0.08 + 0.45 * horizon) + nMid * (1.30 * horizon);
    float d = length(p - nSun.xy);
    float edge = 1.1 / uResolution.y;
    float disc = 1.0 - smoothstep(nSun.z-edge, nSun.z+edge, d);
    float y = (p.y - nSun.y) / nSun.z;
    float phase = (y + 0.20) * 6.65 + nLife.x * 0.018;
    float gap = mix(0.1, 0.40, clamp((y+0.2)/1.2,0.0,1.0));
    float stripeAA = min(0.24, edge / nSun.z * 7.0);
    float stripes = smoothstep(gap-stripeAA, gap+stripeAA, fract(phase));
    disc *= y < -0.20 ? 1.0 : stripes;
    float halo = exp(-max(d-nSun.z,0.0) / (0.018 + nLife.w * 0.007)) * (1.0-disc) * 0.22;
    col += mix(nHigh, nMid, clamp(y*0.5+0.5,0.0,1.0)) * (disc * 2.5 + halo * nStyle.x);
    // Four long quiet ribbons, bent by separate measured upper bands.
    if (p.y < -0.20) for (int i=0; i<4; i++) {
        float fi = float(i);
        float measured = band(0.52 + fi * 0.13);
        float ribbonY = -0.30 - fi*0.043 + sin(p.x*(1.5+fi*0.35+nTexture*0.25)+fi*1.8) * (0.02 + measured*0.035 + nLife.z*0.015);
        float line = exp(-abs(p.y-ribbonY) / (0.0015 + edge));
        col += mix(nLow,nHigh,fi/3.0) * line * nStyle.y * (0.04+measured*0.12);
    }
    return col;
}
half4 main(float2 position) {
    float2 q = (position - float2(uResolution.x*0.5,uResolution.y*0.48)) / uResolution.y;
    float2 p = float2(q.x*nCamera.x+q.y*nCamera.y,-q.x*nCamera.y+q.y*nCamera.x);
    float aa = 1.0/uResolution.y;
    float3 col = p.y < aa*2.0 ? nSky(p) : float3(0.0);
    // Each pixel reads two host-prepared contour samples, never the 360-row history.
    float aspect = uResolution.x/uResolution.y;
    float openingWidth = clamp(nSun.z/aspect*2.0,0.10,0.65);
    float across = clamp((abs(p.x)/(aspect*0.5)-openingWidth)/(1.0-openingWidth),0.0,1.0);
    float az = 0.5 + sign(p.x)*across*0.5;
    if (p.y < aa*2.0) for (int i=1; i>=0; i--) {
        float h = nRidges.eval(float2(az*255.0+0.5,float(i)+0.5)).r;
        float opening = smoothstep(0.03,0.28,abs(az-0.5)*2.0);
        float ridge = -(sqrt(h) * (i==0 ? 0.30 : 0.20) * nStyle.z + opening*0.008) * (1.0+nRegions.y*0.45);
        float fill = smoothstep(ridge-aa,ridge+aa,p.y);
        float3 ink = nLow*(i==0 ? 0.035 : 0.075) + nInk*0.9;
        float line = (1.0-smoothstep(aa*0.45,aa*1.65,abs(p.y-ridge))) * (0.15 + 0.35*h) * nStyle.x;
        col = mix(col,ink,fill);
        col += mix(nMid,nHigh,float(i)) * line;
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
        float center = (1.0-smoothstep(0.025,0.025+footprint,abs(localX))) * step(0.55,fract(worldZ/7.0));
        float fade = 1.0-smoothstep(45.0,120.0,z);
        gridX *= min(1.0,0.12/footprint);
        gridZ *= min(1.0,0.12/footprintZ);
        float3 ground = nInk*0.8+nLow*0.028;
        ground += nMid * max(gridX,gridZ) * 0.30 * (1.0-road) * fade * nStyle.x;
        ground = mix(ground,nInk*0.6+nLow*0.016,road);
        ground += mix(nMid,nHigh,0.65)*(edge*0.75+center*0.23)*fade*nStyle.x;
        float water = smoothstep(3.6,7.6,localX)*nStyle.w;
        if (water > 0.001) {
        float2 mirror = float2(p.x,-p.y*0.62);
        mirror.x += sin(worldZ*0.65+worldX*2.0+nLife.x*0.4)*0.002*(0.2+nLife.z);
        float ripple = 0.56+0.15*sin(worldZ*2.3+worldX*0.8+nLife.x*0.22);
        float3 reflection = nSky(mirror)*ripple*0.5+nLow*0.035;
        ground = mix(ground,reflection,water*0.85);
        }
        col = mix(col,ground,smoothstep(0.0,aa*2.0,p.y));
    }
    return half4(nDisplay(col),1.0);
}
""".trimIndent()
}
