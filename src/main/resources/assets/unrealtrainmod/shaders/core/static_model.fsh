#version 150

#moj_import <minecraft:fog.glsl>

uniform sampler2D Sampler0;
uniform vec4 ColorModulator;
uniform int AlphaPass;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;

in float vertexDistance;
in vec4 vertexColor;
in vec4 lightMapColor;
in vec4 overlayColor;
in vec2 texCoord0;

out vec4 fragColor;

void main() {
    vec4 color = texture(Sampler0, texCoord0);
    // 0: legacy cutout; 1/2: mixed opaque/blended; 3/4/5: forced opaque/cutout/translucent.
    if (AlphaPass != 3 && color.a < 0.1) discard;
    color *= vertexColor * ColorModulator;
    if (AlphaPass == 1 && color.a < 1.0 - 1e-6) discard;
    if (AlphaPass == 2 && (color.a <= 0.0 || color.a >= 1.0 - 1e-6)) discard;
    if (AlphaPass == 4 && color.a < 0.1) discard;
    if (AlphaPass == 5 && color.a <= 0.0) discard;
    color.rgb = mix(overlayColor.rgb, color.rgb, overlayColor.a);
    color *= lightMapColor;
    if (AlphaPass == 3 || AlphaPass == 4) color.a = 1.0;
    fragColor = linear_fog(color, vertexDistance, FogStart, FogEnd, FogColor);
}
