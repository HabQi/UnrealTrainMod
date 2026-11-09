#version 150

#moj_import <minecraft:light.glsl>
#moj_import <minecraft:fog.glsl>

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in vec3 Normal;

in vec4 InstModel0;
in vec4 InstModel1;
in vec4 InstModel2;
in vec4 InstModel3;
in vec3 InstNormal0;
in vec3 InstNormal1;
in vec3 InstNormal2;
in ivec2 InstLightUV;

uniform sampler2D Sampler1;
uniform sampler2D Sampler2;
uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform vec3 RenderOrigin;
uniform ivec2 OverlayUV;
uniform int FogShape;
uniform vec3 Light0_Direction;
uniform vec3 Light1_Direction;
uniform int DirectionalLighting;
uniform float SurfaceEmission;

out float vertexDistance;
out vec4 vertexColor;
out vec4 lightMapColor;
out vec4 overlayColor;
out vec2 texCoord0;

void main() {
    mat4 instanceModel = mat4(InstModel0, InstModel1, InstModel2, InstModel3);
    vec4 posedPosition = instanceModel * vec4(Position, 1.0);
    gl_Position = ProjMat * ModelViewMat * posedPosition;
    vertexDistance = fog_distance(posedPosition.xyz + RenderOrigin, FogShape);
    mat3 instanceNormal = mat3(InstNormal0, InstNormal1, InstNormal2);
    vec3 posedNormal = normalize(instanceNormal * Normal);
    vertexColor = DirectionalLighting != 0
        ? minecraft_mix_light(Light0_Direction, Light1_Direction, posedNormal, Color) : Color;
    vertexColor = mix(vertexColor, Color, SurfaceEmission);
    lightMapColor = texelFetch(Sampler2, InstLightUV / 16, 0);
    lightMapColor = mix(lightMapColor, vec4(1.0), SurfaceEmission);
    overlayColor = texelFetch(Sampler1, OverlayUV, 0);
    texCoord0 = UV0;
}
