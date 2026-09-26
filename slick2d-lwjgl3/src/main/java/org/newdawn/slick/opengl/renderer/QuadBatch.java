package org.newdawn.slick.opengl.renderer;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.newdawn.slick.Color;
import org.newdawn.slick.opengl.TextureImpl;

import java.nio.FloatBuffer;

/**
 * Collects textured quads and untextured line segments and submits them with one
 * client-array draw instead of one glBegin/glEnd per primitive.
 * <p>
 * Textured quads may use up to {@link #MAX_TEXTURES} textures in one draw, with optional
 * per-vertex team-colouring that matches the original {@code TeamColorShader} fragment
 * programs. Colour changes do not end a batch. Anything else that changes GL state or
 * reads/writes pixels must call {@link #flush()} first.
 */
public final class QuadBatch {
    public static final int TEAM_NONE = 0;
    public static final int TEAM_PURE_GREEN = 1;
    public static final int TEAM_HUE_ADD = 2;
    public static final int TEAM_HUE_SHIFT = 3;

    private static final int FLOATS_PER_VERTEX = 13;
    private static final int STRIDE_BYTES = FLOATS_PER_VERTEX * 4;
    private static final int MAX_VERTICES = 4096 * 4;
    private static final int MAX_TEXTURES = 8;
    private static final int NO_TEXTURE = 0;

    private static final float[] vertices = new float[MAX_VERTICES * FLOATS_PER_VERTEX];
    private static final FloatBuffer buffer = BufferUtils.createFloatBuffer(MAX_VERTICES * FLOATS_PER_VERTEX);
    private static final int[] textures = new int[MAX_TEXTURES];

    private static int vertexCount;
    private static int mode = -1;
    private static int textureCount;
    private static int pendingTexSlot;
    private static Thread owner;
    private static boolean clientArraysEnabled;
    private static boolean texCoordArrayEnabled;
    private static boolean attribsEnabled;
    private static boolean shaderBound;

    private static int programId;
    private static int attrPos;
    private static int attrUv;
    private static int attrColor;
    private static int attrTexIndex;
    private static int attrTeamMode;
    private static int attrTeamColor;
    private static final int[] uniTex = new int[MAX_TEXTURES];
    private static boolean shaderReady;
    private static boolean shaderFailed;
    private static boolean shaderChecked;

    private QuadBatch() {
    }

    public static boolean ensureBatchShader() {
        ensureShader();
        return shaderReady;
    }

    public static void addTexturedQuad(
            int texture,
            float leftU, float topV, float rightU, float bottomV,
            float topLeftX, float topLeftY,
            float bottomLeftX, float bottomLeftY,
            float bottomRightX, float bottomRightY,
            float topRightX, float topRightY,
            float r, float g, float b, float a) {
        addTexturedQuad(
                texture,
                leftU, topV, rightU, bottomV,
                topLeftX, topLeftY,
                bottomLeftX, bottomLeftY,
                bottomRightX, bottomRightY,
                topRightX, topRightY,
                r, g, b, a,
                TEAM_NONE, 0f, 0f, 0f);
    }

    public static void addTexturedQuad(
            int texture,
            float leftU, float topV, float rightU, float bottomV,
            float topLeftX, float topLeftY,
            float bottomLeftX, float bottomLeftY,
            float bottomRightX, float bottomRightY,
            float topRightX, float topRightY,
            float r, float g, float b, float a,
            int teamMode,
            float teamR, float teamG, float teamB) {
        prepareTextured(texture, 4);
        a *= alphaScale();
        float texIndex = pendingTexSlot;
        float modeValue = teamMode;
        put(topLeftX, topLeftY, leftU, topV, r, g, b, a, texIndex, modeValue, teamR, teamG, teamB);
        put(bottomLeftX, bottomLeftY, leftU, bottomV, r, g, b, a, texIndex, modeValue, teamR, teamG, teamB);
        put(bottomRightX, bottomRightY, rightU, bottomV, r, g, b, a, texIndex, modeValue, teamR, teamG, teamB);
        put(topRightX, topRightY, rightU, topV, r, g, b, a, texIndex, modeValue, teamR, teamG, teamB);
    }

    /** Adds a closed polygon outline; {@code points} holds {@code count} x/y pairs. */
    public static void addLineLoop(float[] points, int count, float r, float g, float b, float a) {
        if (count < 2) {
            return;
        }
        a *= alphaScale();
        int segments = Math.min(count, MAX_VERTICES / 2);
        prepareLines(segments * 2);
        for (int i = 0; i < segments; i++) {
            int next = (i + 1) % count;
            put(points[i * 2], points[i * 2 + 1], 0f, 0f, r, g, b, a, 0f, 0f, 0f, 0f, 0f);
            put(points[next * 2], points[next * 2 + 1], 0f, 0f, r, g, b, a, 0f, 0f, 0f, 0f, 0f);
        }
    }

    /**
     * Submits pending geometry and turns off client arrays so immediate-mode Slick draws are safe.
     */
    public static void flush() {
        submit();
        disableClientArrays();
        unbindShader();
        TextureImpl.unbind();
        Color.setRebindRequired();
    }

    private static void submit() {
        if (vertexCount == 0) {
            return;
        }
        if (owner != Thread.currentThread()) {
            return;
        }
        int count = vertexCount;
        vertexCount = 0;
        int primitive = mode;
        int usedTextures = textureCount;
        textureCount = 0;

        buffer.clear();
        buffer.put(vertices, 0, count * FLOATS_PER_VERTEX);
        buffer.flip();

        if (primitive == GL11.GL_QUADS && ensureBatchShader()) {
            submitTexturedQuads(count, usedTextures);
            return;
        }
        submitFixedFunction(count, primitive, usedTextures);
    }

    private static void submitTexturedQuads(int count, int usedTextures) {
        if (!shaderBound) {
            disableClientArrays();
            GL20.glUseProgram(programId);
            shaderBound = true;
        }
        bindTextures(usedTextures);
        if (!attribsEnabled) {
            GL20.glEnableVertexAttribArray(attrPos);
            GL20.glEnableVertexAttribArray(attrUv);
            GL20.glEnableVertexAttribArray(attrColor);
            GL20.glEnableVertexAttribArray(attrTexIndex);
            GL20.glEnableVertexAttribArray(attrTeamMode);
            GL20.glEnableVertexAttribArray(attrTeamColor);
            attribsEnabled = true;
        }
        buffer.position(0);
        GL20.glVertexAttribPointer(attrPos, 2, GL11.GL_FLOAT, false, STRIDE_BYTES, buffer);
        buffer.position(2);
        GL20.glVertexAttribPointer(attrUv, 2, GL11.GL_FLOAT, false, STRIDE_BYTES, buffer);
        buffer.position(4);
        GL20.glVertexAttribPointer(attrColor, 4, GL11.GL_FLOAT, false, STRIDE_BYTES, buffer);
        buffer.position(8);
        GL20.glVertexAttribPointer(attrTexIndex, 1, GL11.GL_FLOAT, false, STRIDE_BYTES, buffer);
        buffer.position(9);
        GL20.glVertexAttribPointer(attrTeamMode, 1, GL11.GL_FLOAT, false, STRIDE_BYTES, buffer);
        buffer.position(10);
        GL20.glVertexAttribPointer(attrTeamColor, 3, GL11.GL_FLOAT, false, STRIDE_BYTES, buffer);
        GL11.glDrawArrays(GL11.GL_QUADS, 0, count);
    }

    private static void submitFixedFunction(int count, int primitive, int usedTextures) {
        unbindShader();
        boolean textured = primitive != GL11.GL_LINES && usedTextures > 0 && textures[0] != NO_TEXTURE;
        if (textured) {
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, textures[0]);
        } else {
            GL11.glDisable(GL11.GL_TEXTURE_2D);
        }
        if (!clientArraysEnabled) {
            GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);
            GL11.glEnableClientState(GL11.GL_COLOR_ARRAY);
            clientArraysEnabled = true;
        }
        buffer.position(0);
        GL11.glVertexPointer(2, GL11.GL_FLOAT, STRIDE_BYTES, buffer);
        buffer.position(4);
        GL11.glColorPointer(4, GL11.GL_FLOAT, STRIDE_BYTES, buffer);
        if (textured) {
            if (!texCoordArrayEnabled) {
                GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
                texCoordArrayEnabled = true;
            }
            buffer.position(2);
            GL11.glTexCoordPointer(2, GL11.GL_FLOAT, STRIDE_BYTES, buffer);
        } else if (texCoordArrayEnabled) {
            GL11.glDisableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
            texCoordArrayEnabled = false;
        }
        GL11.glDrawArrays(primitive, 0, count);
    }

    private static void bindTextures(int usedTextures) {
        for (int i = 0; i < usedTextures; i++) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + i);
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, textures[i]);
            GL20.glUniform1i(uniTex[i], i);
        }
        for (int i = usedTextures; i < MAX_TEXTURES; i++) {
            GL20.glUniform1i(uniTex[i], 0);
        }
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
    }

    private static void prepareTextured(int texture, int verts) {
        Thread current = Thread.currentThread();
        if (vertexCount > 0 && (mode != GL11.GL_QUADS || owner != current || vertexCount + verts > MAX_VERTICES)) {
            submit();
        }
        int slot = slotForTexture(texture);
        if (slot < 0) {
            submit();
            slot = slotForTexture(texture);
        }
        owner = current;
        mode = GL11.GL_QUADS;
        pendingTexSlot = slot;
    }

    private static void prepareLines(int verts) {
        Thread current = Thread.currentThread();
        if (vertexCount > 0 && (mode != GL11.GL_LINES || owner != current || vertexCount + verts > MAX_VERTICES)) {
            submit();
        }
        owner = current;
        mode = GL11.GL_LINES;
        textureCount = 0;
    }

    private static int slotForTexture(int texture) {
        for (int i = 0; i < textureCount; i++) {
            if (textures[i] == texture) {
                return i;
            }
        }
        if (textureCount >= MAX_TEXTURES) {
            return -1;
        }
        textures[textureCount] = texture;
        return textureCount++;
    }

    private static void put(
            float x, float y, float u, float v,
            float r, float g, float b, float a,
            float texIndex, float teamMode,
            float teamR, float teamG, float teamB) {
        int i = vertexCount * FLOATS_PER_VERTEX;
        vertices[i] = x;
        vertices[i + 1] = y;
        vertices[i + 2] = u;
        vertices[i + 3] = v;
        vertices[i + 4] = r;
        vertices[i + 5] = g;
        vertices[i + 6] = b;
        vertices[i + 7] = a;
        vertices[i + 8] = texIndex;
        vertices[i + 9] = teamMode;
        vertices[i + 10] = teamR;
        vertices[i + 11] = teamG;
        vertices[i + 12] = teamB;
        vertexCount++;
    }

    private static void disableClientArrays() {
        if (attribsEnabled) {
            GL20.glDisableVertexAttribArray(attrPos);
            GL20.glDisableVertexAttribArray(attrUv);
            GL20.glDisableVertexAttribArray(attrColor);
            GL20.glDisableVertexAttribArray(attrTexIndex);
            GL20.glDisableVertexAttribArray(attrTeamMode);
            GL20.glDisableVertexAttribArray(attrTeamColor);
            attribsEnabled = false;
        }
        if (texCoordArrayEnabled) {
            GL11.glDisableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
            texCoordArrayEnabled = false;
        }
        if (clientArraysEnabled) {
            GL11.glDisableClientState(GL11.GL_COLOR_ARRAY);
            GL11.glDisableClientState(GL11.GL_VERTEX_ARRAY);
            clientArraysEnabled = false;
        }
    }

    private static void unbindShader() {
        if (shaderBound) {
            GL20.glUseProgram(0);
            shaderBound = false;
        }
    }

    private static void ensureShader() {
        if (shaderChecked) {
            return;
        }
        shaderChecked = true;
        int vertex = compileShader(GL20.GL_VERTEX_SHADER, VERTEX_SOURCE);
        int fragment = compileShader(GL20.GL_FRAGMENT_SHADER, FRAGMENT_SOURCE);
        if (vertex == 0 || fragment == 0) {
            shaderFailed = true;
            return;
        }
        programId = GL20.glCreateProgram();
        if (programId == 0) {
            shaderFailed = true;
            return;
        }
        GL20.glAttachShader(programId, vertex);
        GL20.glAttachShader(programId, fragment);
        GL20.glBindAttribLocation(programId, 0, "a_pos");
        GL20.glBindAttribLocation(programId, 1, "a_uv");
        GL20.glBindAttribLocation(programId, 2, "a_color");
        GL20.glBindAttribLocation(programId, 3, "a_texIndex");
        GL20.glBindAttribLocation(programId, 4, "a_teamMode");
        GL20.glBindAttribLocation(programId, 5, "a_teamColor");
        GL20.glLinkProgram(programId);
        if (GL20.glGetProgrami(programId, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            System.err.println("QuadBatch shader link failed: " + GL20.glGetProgramInfoLog(programId));
            shaderFailed = true;
            return;
        }
        attrPos = GL20.glGetAttribLocation(programId, "a_pos");
        attrUv = GL20.glGetAttribLocation(programId, "a_uv");
        attrColor = GL20.glGetAttribLocation(programId, "a_color");
        attrTexIndex = GL20.glGetAttribLocation(programId, "a_texIndex");
        attrTeamMode = GL20.glGetAttribLocation(programId, "a_teamMode");
        attrTeamColor = GL20.glGetAttribLocation(programId, "a_teamColor");
        for (int i = 0; i < MAX_TEXTURES; i++) {
            uniTex[i] = GL20.glGetUniformLocation(programId, "u_tex" + i);
        }
        shaderReady = attrPos >= 0 && attrUv >= 0 && attrColor >= 0
                && attrTexIndex >= 0 && attrTeamMode >= 0 && attrTeamColor >= 0;
        shaderFailed = !shaderReady;
    }

    private static int compileShader(int type, String source) {
        int shader = GL20.glCreateShader(type);
        if (shader == 0) {
            return 0;
        }
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            System.err.println("QuadBatch shader compile failed: " + GL20.glGetShaderInfoLog(shader));
            return 0;
        }
        return shader;
    }

    private static float alphaScale() {
        SGL renderer = Renderer.get();
        return renderer instanceof ImmediateModeOGLRenderer ? ((ImmediateModeOGLRenderer) renderer).alphaScale : 1f;
    }

    private static final String VERTEX_SOURCE =
            "#version 120\n"
                    + "attribute vec2 a_pos;\n"
                    + "attribute vec2 a_uv;\n"
                    + "attribute vec4 a_color;\n"
                    + "attribute float a_texIndex;\n"
                    + "attribute float a_teamMode;\n"
                    + "attribute vec3 a_teamColor;\n"
                    + "varying vec4 v_color;\n"
                    + "varying vec2 v_uv;\n"
                    + "varying float v_texIndex;\n"
                    + "varying float v_teamMode;\n"
                    + "varying vec3 v_teamColor;\n"
                    + "void main() {\n"
                    + "  gl_Position = gl_ProjectionMatrix * gl_ModelViewMatrix * vec4(a_pos, 0.0, 1.0);\n"
                    + "  v_color = a_color;\n"
                    + "  v_uv = a_uv;\n"
                    + "  v_texIndex = a_texIndex;\n"
                    + "  v_teamMode = a_teamMode;\n"
                    + "  v_teamColor = a_teamColor;\n"
                    + "}\n";

    private static final String FRAGMENT_SOURCE =
            "#version 120\n"
                    + "uniform sampler2D u_tex0;\n"
                    + "uniform sampler2D u_tex1;\n"
                    + "uniform sampler2D u_tex2;\n"
                    + "uniform sampler2D u_tex3;\n"
                    + "uniform sampler2D u_tex4;\n"
                    + "uniform sampler2D u_tex5;\n"
                    + "uniform sampler2D u_tex6;\n"
                    + "uniform sampler2D u_tex7;\n"
                    + "varying vec4 v_color;\n"
                    + "varying vec2 v_uv;\n"
                    + "varying float v_texIndex;\n"
                    + "varying float v_teamMode;\n"
                    + "varying vec3 v_teamColor;\n"
                    + "vec4 sampleTex(float idx, vec2 uv) {\n"
                    + "  if (idx < 0.5) return texture2D(u_tex0, uv);\n"
                    + "  if (idx < 1.5) return texture2D(u_tex1, uv);\n"
                    + "  if (idx < 2.5) return texture2D(u_tex2, uv);\n"
                    + "  if (idx < 3.5) return texture2D(u_tex3, uv);\n"
                    + "  if (idx < 4.5) return texture2D(u_tex4, uv);\n"
                    + "  if (idx < 5.5) return texture2D(u_tex5, uv);\n"
                    + "  if (idx < 6.5) return texture2D(u_tex6, uv);\n"
                    + "  return texture2D(u_tex7, uv);\n"
                    + "}\n"
                    + "void main() {\n"
                    + "  vec4 color = sampleTex(v_texIndex, v_uv);\n"
                    + "  float mode = v_teamMode;\n"
                    + "  if (mode > 0.5 && mode < 1.5) {\n"
                    + "    float threshold = 0.04;\n"
                    + "    if (color.g > 0.0 && abs(color.r - color.b) <= threshold) {\n"
                    + "      float lightness = color.r;\n"
                    + "      float greenness = color.g - lightness;\n"
                    + "      color.rgb = lightness + v_teamColor * greenness;\n"
                    + "    }\n"
                    + "  } else if (mode > 1.5 && mode < 2.5) {\n"
                    + "    color.rgb += v_teamColor * 0.15;\n"
                    + "  } else if (mode > 2.5) {\n"
                    + "    float hueness = abs(color.r - color.g);\n"
                    + "    hueness = max(hueness, abs(color.g - color.b));\n"
                    + "    hueness = max(hueness, abs(color.b - color.r));\n"
                    + "    if (hueness > (15.0 / 256.0)) {\n"
                    + "      float lightness = min(min(color.r, color.g), color.b);\n"
                    + "      color.rgb = lightness + v_teamColor * hueness;\n"
                    + "    }\n"
                    + "  }\n"
                    + "  gl_FragColor = color * v_color;\n"
                    + "}\n";
}
