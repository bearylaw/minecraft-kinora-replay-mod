#version 330

// Copies the world's depth buffer into a float colour texture, which (unlike a depth texture) can
// be read back on every backend. Used for depth of field in offline renders.

uniform sampler2D InSampler;

in vec2 texCoord;

out vec4 fragColor;

void main() {
    fragColor = vec4(texture(InSampler, texCoord).r, 0.0, 0.0, 1.0);
}
