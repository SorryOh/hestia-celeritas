#version 450
// The bindless texture and sampler arrays are unsized.
#extension GL_EXT_nonuniform_qualifier : require
// Hestia build (2026-10-18): the Vulkan version of celeritas-common's blocks/block_layer_opaque.fsh with its fog
// include, for Kora's Vulkan renderer (see block_layer_opaque.vert). The fog is the linear curve ShaderLoaderMixin gives
// the GL shaders; UPSTREAM_FOG keeps celeritas-common's smoothstep, for -Dceleritas.upstreamFog=true. GL's alpha test,
// which the compatibility profile applies after a fragment shader (the 1.8.9 module turns it off for the solid pass
// only), is specialization constant 1 and the push constants' reference.

// GL's alpha test: 0 off, else glAlphaFunc's function minus GL_NEVER plus 1 (1 never ... 8 always).
layout(constant_id = 1) const int GL_ALPHA_FUNC = 0;

// include/fog.glsl
const int FOG_SHAPE_SPHERICAL = 0;
const int FOG_SHAPE_CYLINDRICAL = 1;
const int FOG_SHAPE_PLANAR = 2;

#ifdef UPSTREAM_FOG
#define FOG_CURVE(fogStart, fogEnd, fragDistance) smoothstep(fogStart, fogEnd, fragDistance)
#else
#define FOG_CURVE(fogStart, fogEnd, fragDistance) clamp((fragDistance - fogStart) / (fogEnd - fogStart), 0.0, 1.0)
#endif

vec4 _linearFog(vec4 fragColor, float fragDistance, vec4 fogColor, float fogStart, float fogEnd) {
#ifdef USE_FOG
    if (fragDistance <= fogStart) {
        return fragColor;
    }
    float factor = fragDistance < fogEnd ? FOG_CURVE(fogStart, fogEnd, fragDistance) : 1.0; // alpha value of fog is used as a weight
    vec3 blended = mix(fragColor.rgb, fogColor.rgb, factor * fogColor.a);

    return vec4(blended, fragColor.a); // alpha value of fragment cannot be modified
#else
    return fragColor;
#endif
}

vec4 _exp2Fog(vec4 fragColor, float fragDistance, vec4 fogColor, float fogDensity) {
#ifdef USE_FOG
    float dist = fragDistance * fogDensity;
    float factor = clamp(1.0 / exp2(dist * dist), 0.0, 1.0);
    vec3 blended = mix(fogColor.rgb, fragColor.rgb, factor * fogColor.a);

    return vec4(blended, fragColor.a); // alpha value of fragment cannot be modified
#else
    return fragColor;
#endif
}

vec4 _expFog(vec4 fragColor, float fragDistance, vec4 fogColor, float fogDensity) {
#ifdef USE_FOG
    float dist = fragDistance * fogDensity;
    float factor = clamp(1.0 / exp(dist), 0.0, 1.0);
    vec3 blended = mix(fogColor.rgb, fragColor.rgb, factor * fogColor.a);

    return vec4(blended, fragColor.a); // alpha value of fragment cannot be modified
#else
    return fragColor;
#endif
}

// The GL uniforms (programs.txt lists the same offsets; the block is the vertex shader's).
layout(std140, set = 1, binding = 0) uniform Uniforms {
    layout(offset = 0) mat4 u_ModelViewMatrix;
    layout(offset = 64) mat4 u_ProjectionMatrix;
    layout(offset = 128) vec4 u_FogColor;
    layout(offset = 144) int u_FogShape;
    layout(offset = 148) float u_FogStart;
    layout(offset = 152) float u_FogEnd;
    layout(offset = 156) float u_FogDensity;
};

layout(push_constant) uniform Draw {
    layout(offset = 0) vec3 u_RegionOffset;
    layout(offset = 12) uint u_BlockTexTexture;
    layout(offset = 16) uint u_BlockTexSampler;
    layout(offset = 20) uint u_LightTexTexture;
    layout(offset = 24) uint u_LightTexSampler;
    layout(offset = 28) float glAlphaRef;
};

layout(set = 0, binding = 0) uniform texture2D textures[];
layout(set = 0, binding = 1) uniform sampler samplers[];

// blocks/block_layer_opaque.fsh

layout(location = 0) in vec4 v_Color;
layout(location = 1) in vec4 v_RdhFactor;
layout(location = 2) in vec2 v_QuadCoord;
layout(location = 3) in vec2 v_TexCoord;

layout(location = 4) in float v_MaterialMipBias;
#ifdef USE_FRAGMENT_DISCARD
layout(location = 5) in float v_MaterialAlphaCutoff;
#endif

#if defined(USE_FOG)
layout(location = 6) in float v_FragDistance;
#endif

layout(location = 0) out vec4 fragColor; // The output fragment for the color framebuffer

bool alphaPasses(float alpha) {
    switch (GL_ALPHA_FUNC) {
        case 1: return false;
        case 2: return alpha < glAlphaRef;
        case 3: return alpha == glAlphaRef;
        case 4: return alpha <= glAlphaRef;
        case 5: return alpha > glAlphaRef;
        case 6: return alpha != glAlphaRef;
        case 7: return alpha >= glAlphaRef;
        default: return true;
    }
}

void main() {
    vec4 diffuseColor = texture(sampler2D(textures[u_BlockTexTexture], samplers[u_BlockTexSampler]), v_TexCoord, v_MaterialMipBias);

#ifdef USE_FRAGMENT_DISCARD
    if (diffuseColor.a < v_MaterialAlphaCutoff) {
        discard;
    }
#endif

    vec4 m_color = v_Color;
    // Apply correction factor to make the resulting color very close to what true bilinear interpolation would obtain
    // min(x, y) * (1 - max(x, y)) == min(x, y) - (x * y)
    float correctionWeight = min(v_QuadCoord.x, v_QuadCoord.y)
            - (v_QuadCoord.x * v_QuadCoord.y);
    m_color += v_RdhFactor * correctionWeight;

#ifdef USE_VANILLA_COLOR_FORMAT
    // Apply per-vertex color. AO shade is applied ahead of time on the CPU.
    diffuseColor *= m_color;
#else
    // Apply per-vertex color
    diffuseColor.rgb *= m_color.rgb;

    // Apply ambient occlusion "shade"
    diffuseColor.rgb *= m_color.a;
#endif

#ifdef USE_FOG
    float fragDistance;
    if (u_FogShape == FOG_SHAPE_PLANAR) {
        fragDistance = gl_FragCoord.z / gl_FragCoord.w;
    } else {
        fragDistance = v_FragDistance;
    }
#if defined(USE_FOG_EXP2)
    fragColor = _exp2Fog(diffuseColor, fragDistance, u_FogColor, u_FogDensity);
#elif defined(USE_FOG_EXP)
    fragColor = _expFog(diffuseColor, fragDistance, u_FogColor, u_FogDensity);
#elif defined(USE_FOG_SMOOTH)
    fragColor = _linearFog(diffuseColor, fragDistance, u_FogColor, u_FogStart, u_FogEnd);
#endif

#else // No fog
    fragColor = diffuseColor;
#endif

    if (GL_ALPHA_FUNC != 0 && !alphaPasses(fragColor.a)) {
        discard;
    }
}
