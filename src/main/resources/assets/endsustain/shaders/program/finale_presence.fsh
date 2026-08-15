#version 150

uniform sampler2D DiffuseSampler;
uniform vec2 OutSize;

in vec2 texCoord;
out vec4 fragColor;

void main() {
    vec2 center = vec2(0.5);
    vec2 radial = texCoord - center;
    float edge = smoothstep(0.05, 0.72, length(radial));
    vec2 direction = length(radial) > 0.0001 ? normalize(radial) : vec2(0.0);
    vec2 offset = direction * (1.2 + edge * 1.8) / OutSize;

    vec3 base = texture(DiffuseSampler, texCoord).rgb;
    vec3 blur = texture(DiffuseSampler, texCoord + offset * 1.8).rgb
              + texture(DiffuseSampler, texCoord - offset * 1.8).rgb
              + texture(DiffuseSampler, texCoord + vec2(offset.y, -offset.x)).rgb
              + texture(DiffuseSampler, texCoord - vec2(offset.y, -offset.x)).rgb;
    blur *= 0.25;

    float grayBase = dot(base, vec3(0.299, 0.587, 0.114));
    float grayBlur = dot(blur, vec3(0.299, 0.587, 0.114));
    float glow = max(grayBlur - grayBase, 0.0) * (0.55 + edge * 0.35);
    float gray = mix(grayBase, grayBlur, 0.12 + edge * 0.18) + glow;

    // 紫雨保持紫色，其余世界继续使用黑白散光效果。
    float purpleMask = smoothstep(0.08, 0.32, base.b - base.g)
                     * smoothstep(0.02, 0.20, base.r - base.g)
                     * smoothstep(0.08, 0.38, base.b - base.r);
    vec3 purple = mix(base, blur, 0.08 + edge * 0.10);
    vec3 finalColor = mix(vec3(clamp(gray, 0.0, 1.0)), purple, purpleMask);
    fragColor = vec4(clamp(finalColor, 0.0, 1.0), 1.0);
}
