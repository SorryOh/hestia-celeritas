#version 450
// The bindless texture and sampler arrays are unsized.
#extension GL_EXT_nonuniform_qualifier : require
// Hestia build (2026-10-18): the Vulkan version of celeritas-common's blocks/block_layer_opaque.vsh with its includes
// (fog.glsl, chunk_vertex.glsl, chunk_matrices.glsl, chunk_material.glsl), for Kora's Vulkan renderer, which runs
// Celeritas's GL calls on Vulkan and picks this shader for the GL one by its source (assets/celeritas/vulkan/programs.txt;
// README.md, "Vulkan shaders"). The same math; GLSL 450's explicit locations; the uniforms in Kora's uniform block
// (set 1) and push constants; the samplers bindless (set 0, a texture and a sampler slot each). Not ported, as the
// 1.8.9 module uses neither: the legacy GLSL path (GL below 3.2) and the chunk fade-in.

// include/fog.glsl
const int FOG_SHAPE_SPHERICAL = 0;
const int FOG_SHAPE_CYLINDRICAL = 1;
const int FOG_SHAPE_PLANAR = 2;

float getFragDistance(int fogShape, vec3 position) {
    // Use the maximum of the horizontal and vertical distance to get cylindrical fog if fog shape is cylindrical
    if (fogShape == FOG_SHAPE_CYLINDRICAL) {
        return max(length(position.xz), abs(position.y));
    } else {
        return length(position);
    }
}

// The GL uniforms (programs.txt lists the same offsets).
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
    // u_BlockTex and u_LightTex: their textures' and samplers' bindless slots.
    layout(offset = 12) uint u_BlockTexTexture;
    layout(offset = 16) uint u_BlockTexSampler;
    layout(offset = 20) uint u_LightTexTexture;
    layout(offset = 24) uint u_LightTexSampler;
    // GL's alpha test reference (the fragment shader's).
    layout(offset = 28) float glAlphaRef;
};

layout(set = 0, binding = 0) uniform texture2D textures[];
layout(set = 0, binding = 1) uniform sampler samplers[];

// include/chunk_vertex.glsl

// The position of the vertex around the model origin
vec3 _vert_position;

// The block texture coordinate of the vertex
vec2 _vert_tex_diffuse_coord;

// The light texture coordinate of the vertex
ivec2 _vert_tex_light_coord;

// The color of the vertex
vec4 _vert_color;

// The "rdh factor" of the vertex, used when applying AO correction
vec4 _vert_rdh_factor;

// The index of the draw command which this vertex belongs to
uint _draw_id;

// The material bits for the primitive
uint _material_params;

#define REGION_SIZE 256

uvec3 _get_relative_chunk_coord(uint pos) {
    // Packing scheme is defined by LocalSectionIndex
    return uvec3(pos) >> uvec3(5u, 0u, 2u) & uvec3(7u, 3u, 7u);
}

vec3 _get_draw_translation(uint pos) {
    return _get_relative_chunk_coord(pos) * vec3(16.0);
}

#ifdef USE_VERTEX_COMPRESSION
layout(location = 0) in uvec4 a_PosId;
layout(location = 1) in vec4 a_Color;
layout(location = 2) in uvec2 a_TexCoord;
layout(location = 3) in uint a_LightCoord;
layout(location = 4) in vec4 a_RdhFactor;

#if !defined(VERT_POS_SCALE)
#error "VERT_POS_SCALE not defined"
#elif !defined(VERT_POS_OFFSET)
#error "VERT_POS_OFFSET not defined"
#elif !defined(VERT_TEX_SCALE)
#error "VERT_TEX_SCALE not defined"
#endif

void _vert_init() {
    uint packed_draw_params = (a_LightCoord & 0xFFFFu);

    uvec3 position = (a_PosId.xyz << 5u) | ((uvec3(a_PosId.w) >> uvec3(0u, 5u, 10u)) & uvec3(0x1Fu));
    uvec2 tex_coord = (a_TexCoord << 2u) | ((uvec2(packed_draw_params) >> uvec2(4u, 6u)) & uvec2(0x3u));

    _vert_position = (vec3(position) * VERT_POS_SCALE + VERT_POS_OFFSET);
    _vert_tex_diffuse_coord = (vec2(tex_coord) * VERT_TEX_SCALE);
    _vert_color = a_Color;
    _vert_rdh_factor = a_RdhFactor;

    _material_params = (packed_draw_params) & 0xFu;
    _draw_id = (packed_draw_params >> 8) & 0xFFu;
    _vert_tex_light_coord = ivec2((uvec2((a_LightCoord >> 16) & 0xFFFFu) >> uvec2(0, 8)) & uvec2(0xFFu));
}

#else

layout(location = 0) in vec3 a_PosId;
layout(location = 1) in vec4 a_Color;
layout(location = 2) in vec2 a_TexCoord;
layout(location = 3) in uint a_LightCoord;
layout(location = 4) in vec4 a_RdhFactor;

void _vert_init() {
    _vert_position = a_PosId;
    _vert_tex_diffuse_coord = a_TexCoord;
    _vert_color = a_Color;
    _vert_rdh_factor = a_RdhFactor;

    uint packed_draw_params = (a_LightCoord & 0xFFFFu);
    // Vertex Material
    _material_params = (packed_draw_params) & 0xFFu;

    // Vertex Mesh ID
    _draw_id  = (packed_draw_params >> 8) & 0xFFu;

    // Vertex Light
    _vert_tex_light_coord = ivec2((uvec2((a_LightCoord >> 16) & 0xFFFFu) >> uvec2(0, 8)) & uvec2(0xFFu));
}
#endif

// include/chunk_material.glsl
const uint MATERIAL_USE_MIP_OFFSET = 0u;
const uint MATERIAL_ALPHA_CUTOFF_OFFSET = 1u;

const float[4] ALPHA_CUTOFF = float[4](0.0, 0.1, 0.5, 1.0);

float _material_mip_bias(uint material) {
    return ((material >> MATERIAL_USE_MIP_OFFSET) & 1u) != 0u ? 0.0 : -4.0;
}

float _material_alpha_cutoff(uint material) {
    return ALPHA_CUTOFF[(material >> MATERIAL_ALPHA_CUTOFF_OFFSET) & 3u];
}

// blocks/block_layer_opaque.vsh

// Avoid z-fighting between the solid and cutout copies of this program
invariant gl_Position;

layout(location = 0) out vec4 v_Color;
layout(location = 1) out vec4 v_RdhFactor;
layout(location = 2) out vec2 v_QuadCoord;
layout(location = 3) out vec2 v_TexCoord;

layout(location = 4) out float v_MaterialMipBias;
#ifdef USE_FRAGMENT_DISCARD
layout(location = 5) out float v_MaterialAlphaCutoff;
#endif

#if defined(USE_FOG)
layout(location = 6) out float v_FragDistance;
#endif

#ifndef CELERITAS_NO_LIGHTMAP
vec4 _sample_lightmap(ivec2 uv) {
    return texture(sampler2D(textures[u_LightTexTexture], samplers[u_LightTexSampler]), clamp(uv / 256.0, vec2(0.5 / 16.0), vec2(15.5 / 16.0)));
}
#endif

void main() {
    _vert_init();

    // Transform the chunk-local vertex position into world model space
    vec3 translation = u_RegionOffset + _get_draw_translation(_draw_id);
    vec3 position = _vert_position + translation;

#if defined(USE_FOG)
    v_FragDistance = getFragDistance(u_FogShape, position);
#endif

    // Transform the vertex position into model-view-projection space. Two matrix-vector products: that's how NVIDIA's GL
    // compiles the GL shader's u_ProjectionMatrix * u_ModelViewMatrix * vec4(position, 1.0), and written so, the
    // positions match GL's to the bit (the matrix product first moved silhouette pixels on distant terrain).
    gl_Position = u_ProjectionMatrix * (u_ModelViewMatrix * vec4(position, 1.0));

    // Add the light color to the vertex color, and pass the texture coordinates to the fragment shader
    v_RdhFactor = _vert_rdh_factor * 2.0;
#ifdef CELERITAS_NO_LIGHTMAP
    v_Color = _vert_color;
#else
    vec4 lightColor = _sample_lightmap(_vert_tex_light_coord);
    v_Color = _vert_color * lightColor;
    v_RdhFactor.rgb *= lightColor.rgb;
#endif

    // GL's gl_VertexID and Vulkan's gl_VertexIndex both count the base vertex in.
    int corner = gl_VertexIndex & 3;
    v_QuadCoord = vec2(corner >= 2 ? 1.0 : 0.0,
            corner == 1 || corner == 2 ? 1.0 : 0.0);
    v_TexCoord = _vert_tex_diffuse_coord;

    v_MaterialMipBias = _material_mip_bias(_material_params);
#ifdef USE_FRAGMENT_DISCARD
    v_MaterialAlphaCutoff = _material_alpha_cutoff(_material_params);
#endif
}
