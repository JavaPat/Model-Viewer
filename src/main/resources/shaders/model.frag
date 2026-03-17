#version 330 core

// ── Inputs from vertex shader ──────────────────────────────────────────────
in vec3 vColor;
in vec3 vNormal;
in vec2 vUV;

// ── Uniforms ───────────────────────────────────────────────────────────────
uniform int  uRenderMode;  // 0=colour+lighting  1=wireframe  2=flat colour
uniform vec3 uLightDir;    // normalised world-space directional light direction
uniform vec3 uWireColor;   // wireframe line colour
uniform sampler2D uTexture;    // texture unit 0
uniform int       uHasTexture; // 1 = sample texture, 0 = use vertex colour

// ── Output ─────────────────────────────────────────────────────────────────
out vec4 fragColor;

void main() {
    if (uRenderMode == 1) {
        // Wireframe mode — solid colour override
        fragColor = vec4(uWireColor, 1.0);
        return;
    }

    if (uRenderMode == 2) {
        // Flat colour mode — vertex colour without lighting
        fragColor = vec4(vColor, 1.0);
        return;
    }

    // ── Lit vertex colour (mode 0) ─────────────────────────────────────────
    vec3  normal   = normalize(vNormal);
    vec3  lightDir = normalize(uLightDir);

    // Determine base colour: texture sample blended with vertex colour if textured
    vec3 baseColor = vColor;
    if (uHasTexture != 0) {
        vec4 texSample = texture(uTexture, vUV);
        baseColor = mix(vColor, texSample.rgb, texSample.a);
    }

    // Simple diffuse + ambient lighting
    float diff    = max(dot(normal, lightDir), 0.0);
    float ambient = 0.30;
    float spec    = pow(max(dot(reflect(-lightDir, normal), vec3(0.0, 0.0, 1.0)), 0.0), 16.0) * 0.15;

    float brightness = ambient + diff * 0.65 + spec;
    vec3  litColor   = baseColor * brightness;

    // Subtle rim light from below-behind to give depth perception
    float rimFactor = 1.0 - max(dot(normal, vec3(0.0, 0.0, 1.0)), 0.0);
    litColor += baseColor * rimFactor * 0.05;

    fragColor = vec4(clamp(litColor, 0.0, 1.0), 1.0);
}
