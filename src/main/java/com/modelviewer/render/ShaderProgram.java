package com.modelviewer.render;

import org.lwjgl.opengl.GL20;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.lwjgl.opengl.GL20.*;

/**
 * Compiles and links a GLSL vertex + fragment shader pair into an OpenGL program.
 *
 * Shaders are loaded from the classpath (src/main/resources/shaders/).
 * If compilation fails, the error log is printed and a RuntimeException is thrown.
 */
public final class ShaderProgram {

    private static final Logger log = LoggerFactory.getLogger(ShaderProgram.class);

    private final int programId;

    /** Location cache — populated on first use of each name, never changes after linking. */
    private final Map<String, Integer> locationCache = new HashMap<>();

    /**
     * Loads, compiles, and links shader sources from classpath resources.
     *
     * @param vertexResource   e.g. "/shaders/model.vert"
     * @param fragmentResource e.g. "/shaders/model.frag"
     */
    public ShaderProgram(String vertexResource, String fragmentResource) {
        String vertSrc = loadResource(vertexResource);
        String fragSrc = loadResource(fragmentResource);

        int vertId = compileShader(GL_VERTEX_SHADER,   vertSrc, vertexResource);
        int fragId = compileShader(GL_FRAGMENT_SHADER, fragSrc, fragmentResource);

        programId = glCreateProgram();
        glAttachShader(programId, vertId);
        glAttachShader(programId, fragId);
        glLinkProgram(programId);

        if (glGetProgrami(programId, GL_LINK_STATUS) == GL20.GL_FALSE) {
            String info = glGetProgramInfoLog(programId);
            glDeleteProgram(programId);
            throw new RuntimeException("Shader link failed:\n" + info);
        }

        // Shaders are no longer needed once linked
        glDetachShader(programId, vertId);
        glDetachShader(programId, fragId);
        glDeleteShader(vertId);
        glDeleteShader(fragId);

        // Pre-populate the location cache for all known uniforms so that any
        // "not found" (-1) cases are logged once at startup rather than silently
        // failing every frame.
        preCacheUniforms("uMVP", "uRenderMode", "uLightDir", "uWireColor", "uBypassMvp", "uTexture", "uHasTexture");

        log.debug("Shader program {} linked successfully", programId);
    }

    /** Activates this program for subsequent draw calls. */
    public void use() {
        glUseProgram(programId);
    }

    /** Deactivates any shader program. */
    public static void unbind() {
        glUseProgram(0);
    }

    // ── Uniform setters ───────────────────────────────────────────────────────

    public void setUniform1i(String name, int value) {
        glUniform1i(loc(name), value);
    }

    public void setUniform1f(String name, float value) {
        glUniform1f(loc(name), value);
    }

    public void setUniform3f(String name, float x, float y, float z) {
        glUniform3f(loc(name), x, y, z);
    }

    /**
     * Sets a 4×4 column-major matrix uniform.
     *
     * @param matrix 16-element float array in column-major order
     */
    public void setUniformMatrix4f(String name, float[] matrix) {
        glUniformMatrix4fv(loc(name), false, matrix);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Returns the cached uniform location.
     * Returns -1 (and logs a warning once) if the name is not in the linked program.
     * OpenGL silently ignores uniform calls to location -1, so callers do not need
     * to guard against it — but seeing the warning in the log is important for debugging.
     */
    private int loc(String name) {
        return locationCache.computeIfAbsent(name, n -> {
            int location = glGetUniformLocation(programId, n);
            if (location == -1) {
                log.warn("Shader uniform '{}' not found in program {} (optimised out or misspelled?)",
                        n, programId);
            }
            return location;
        });
    }

    /** Looks up each name at link time so any -1 warnings appear immediately. */
    private void preCacheUniforms(String... names) {
        for (String name : names) {
            loc(name);  // populates the cache and logs any -1 results
        }
    }

    private static int compileShader(int type, String source, String debugName) {
        int id = glCreateShader(type);
        glShaderSource(id, source);
        glCompileShader(id);
        if (glGetShaderi(id, GL_COMPILE_STATUS) == GL20.GL_FALSE) {
            String info = glGetShaderInfoLog(id);
            glDeleteShader(id);
            throw new RuntimeException("Shader compile failed (" + debugName + "):\n" + info);
        }
        return id;
    }

    private static String loadResource(String path) {
        try (InputStream is = ShaderProgram.class.getResourceAsStream(path)) {
            if (is == null) {
                throw new RuntimeException("Shader resource not found: " + path);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load shader: " + path, e);
        }
    }

    /** Frees the GPU program object. Must be called on the OpenGL thread. */
    public void delete() {
        glDeleteProgram(programId);
    }
}
