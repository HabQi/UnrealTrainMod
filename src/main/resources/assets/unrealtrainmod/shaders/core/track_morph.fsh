#version 150
#moj_import <minecraft:fog.glsl>
uniform vec4 ColorModulator;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;
in float vertexDistance;
in vec4 vertexColor;
out vec4 fragColor;
void main() {
    fragColor = linear_fog(vertexColor * ColorModulator, vertexDistance, FogStart, FogEnd, FogColor);
}
