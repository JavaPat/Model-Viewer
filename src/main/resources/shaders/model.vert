#version 330 core

// ── Vertex inputs ──────────────────────────────────────────────────────────
layout(location = 0) in vec3 aPosition;  // world-space position (tile units, centered)
layout(location = 1) in vec3 aColor;     // per-vertex RGB colour (from HSL face colour)
layout(location = 2) in vec3 aNormal;    // smooth vertex normal
layout(location = 3) in vec2 aUV;        // texture coordinates

// ── Uniforms ───────────────────────────────────────────────────────────────
uniform mat4 uMVP;        // pre-multiplied model-view-projection matrix
uniform mat4 uModel;      // model rotation matrix for normals / model-space transforms
uniform int  uRenderMode; // 0=colour+lighting  1=wireframe  2=flat colour

// DEBUG: set to 1 to bypass MVP and render raw positions (tests vertex attributes).
// This is only meaningful if uploaded positions are already within clip space.
// If the model appears with bypassMvp=1 but not with bypassMvp=0 → MVP is broken.
// If nothing appears with bypassMvp=1                             → attributes broken.
uniform int  uBypassMvp;  // 0=normal (MVP applied), 1=bypass (raw positions)

// ── Outputs to fragment shader ─────────────────────────────────────────────
out vec3 vColor;
out vec3 vNormal;
out vec2 vUV;

void main() {
    vColor  = aColor;
    vNormal = normalize(mat3(uModel) * aNormal);
    vUV     = aUV;

    if (uBypassMvp != 0) {
        // Bypass MVP — render raw positions as NDC.
        gl_Position = vec4(aPosition, 1.0);
    } else {
        gl_Position = uMVP * vec4(aPosition, 1.0);
    }
}
