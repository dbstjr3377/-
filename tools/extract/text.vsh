#version 330
#extension GL_ARB_separate_shader_objects : require

// 띵호와 주방장 리소스팩: 신호 색(파랑 채널 상위 4비트 = 0xA)이 붙은 GUI 글리프를
// 원본 스테이지(550x413) 좌표로 옮겨 화면 세로에 꽉 차게 그린다. 그 외 글자는 바닐라와 같다.

#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
#include <minecraft:fog.glsl>
#include <minecraft:sample_lightmap.glsl>
#endif

#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
layout(location = 2) in vec2 UV0;
#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
layout(location = 3) in ivec2 UV2;
#endif

#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
uniform sampler2D Sampler2;
layout(location = 0) out float sphericalVertexDistance;
layout(location = 1) out float cylindricalVertexDistance;
#endif

layout(location = 2) out vec4 vertexColor;
layout(location = 3) out vec2 texCoord0;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
    sphericalVertexDistance = fog_spherical_distance(Position);
    cylindricalVertexDistance = fog_cylindrical_distance(Position);
    vertexColor = Color * sample_lightmap(Sampler2, UV2);
#else
    vertexColor = Color;
#endif
    texCoord0 = UV0;

#ifdef IS_GUI
    ivec4 c = ivec4(round(Color * 255.0));
    if (c.a >= 250 && (c.b & 0xF0) == 0xA0) {
        // 글리프 원점(스테이지 px, -256 바이어스)
        float sx = float(c.r | (((c.b >> 2) & 3) << 8)) - 256.0;
        float sy = float(c.g | ((c.b & 3) << 8)) - 256.0;
        vec4 gp = ModelViewMat * vec4(Position, 1.0);
        float guiW = abs(2.0 / ProjMat[0][0]);
        float guiH = abs(2.0 / ProjMat[1][1]);
        // 타이틀: translate(guiW/2, guiH/2) -> scale 4 -> drawString(-width/2, -10). 글 폭은 0 으로 맞춰 보냄
        vec2 p0 = vec2(floor(guiW * 0.5 + 0.01), floor(guiH * 0.5 + 0.01) - 40.0);
        vec2 st = vec2(sx, sy) + (gp.xy - p0) * 0.25;
        float ndcY = 1.0 - st.y * (2.0 / 413.0);
        float ndcX = (st.x - 275.0) * (2.0 / 413.0) * (guiH / guiW);
        gl_Position = vec4(ndcX, ndcY, gl_Position.z, 1.0);
        vertexColor = vec4(1.0);
    }
#endif
}
