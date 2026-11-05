#version 150
#moj_import <minecraft:fog.glsl>
in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV2;
uniform sampler2D Sampler2;
uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform int FogShape;
uniform float Movement;
out float vertexDistance;
out vec4 vertexColor;
void main() {
    vec3 position = vec3(mix(Position.x, UV0.x, Movement), Position.y, mix(Position.z, UV0.y, Movement));
    gl_Position = ProjMat * ModelViewMat * vec4(position, 1.0);
    vertexDistance = fog_distance(position, FogShape);
    vertexColor = Color * texelFetch(Sampler2, UV2 / 16, 0);
}
