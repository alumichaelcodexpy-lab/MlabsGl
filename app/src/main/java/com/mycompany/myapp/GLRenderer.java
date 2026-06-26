package com.mycompany.myapp;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.opengl.GLES30;
import android.opengl.GLSurfaceView;
import android.opengl.GLUtils;
import android.opengl.Matrix;
import android.util.Log;

import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.List;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class GLRenderer implements GLSurfaceView.Renderer {

    public interface StatusListener {
        void onStatus(String message);
    }

    private final Context context;
    private StatusListener statusListener;

    // Main program (walls, character, space)
    private int program;
    private int uMVP, uM, uTex, uLightDir, uViewPos, uRoughness, uMetallic, uJointMatrices;
    private int aPosition, aNormal, aTexCoord, aBoneIds, aBoneWeights;

    // Floor program (with reflection)
    private int programFloor;
    private int uMVP_floor, uM_floor, uTex_floor, uLightDir_floor, uViewPos_floor;
    private int uRoughness_floor, uMetallic_floor, uReflectionTex_floor, uReflectionMVP_floor;
    private int aPosition_floor, aNormal_floor, aTexCoord_floor;

    // Character models
    private GltfModel standingModel, walkingModel, runningModel, characterModel;
    private float[][] standingWorld, walkingWorld, runningWorld, characterWorld;

    private GltfModel floorModel, wallModel;
    private float[][] floorWorld, wallWorld;

    private GltfModel spaceModel;
    private float[][] spaceWorld;
    private float spaceScale = 20.0f;

    // Collision data
    private List<float[]> floorTriangles = new ArrayList<>();
    private List<float[]> wallTriangles = new ArrayList<>();
    private boolean floorDirty = true, wallDirty = true;
    private static final float GRAVITY = -3.8f;
    private static final float PLAYER_RADIUS = 0.25f;

    private final float[] projection = new float[16];
    private final float[] view = new float[16];
    private final float[] temp = new float[16];
    private final float[] mvp = new float[16];
    private final float[] lightDir = new float[] { -0.4f, 0.8f, 0.6f };
    private final float[] identityPalette = new float[32 * 16];
    private final float[] jointPalette = new float[32 * 16];

    // Camera
    private float cameraYaw = 0f, cameraPitch = 0.785f, cameraDistance = 14.14f;
    private float fpYaw = 3.0203414f, fpPitch = -0.25837594f;
    private float[] mCameraPos = new float[3];

    // Character
    private float characterX = 0f, characterY = 0f, characterZ = 0f;
    private float mcharacterX = 0f, mcharacterY = 0.001f, mcharacterZ = 0f;
    private float characterRotationY = 0f, characterScale = 1f;

    private float floorY = 5.5f, wallY = 2.8f, wallScale = 1.0f;
    private float targetX = 0f, targetY = 1.2f, targetZ = 0f;
    private float viewPanX = -0.34499988f, viewPanY = 0.44500032f;

    private long startTimeNs = 0L, lastFrameTimeNs = 0L;
    private float moveDirX = 0f, moveDirY = 0f;
    public float moveSpeed = 0.5f;
    private String activeModelName = "standing";
    private float currentMoveMag = 0f;
    private static final float ROTATION_SPEED = 540.0f;
    private static final float TURN_PAUSE_THRESHOLD = 5.0f;

    private boolean firstPersonMode = true;
    private static final float EYE_HEIGHT = 1.6f;

    // ---------- Reflection FBO ----------
    private int reflectionFbo = -1;
    private int reflectionTex = -1;
    private int reflectionRbo = -1;          // track renderbuffer to delete it
    private int reflectionWidth = 0, reflectionHeight = 0;
    private final float[] reflectionView = new float[16];
    private final float[] reflectionMVP = new float[16];

    // supersample scale for reflection FBO (2.0 = 2x width & 2x height)
    private final float reflectionScale = 2.0f;

    // track screen size for viewport restore
    private int screenWidth = 0, screenHeight = 0;

    // fallback texture
    private int fallbackTextureId = -1;

    public GLRenderer(Context context) {
        this.context = context;
        fillIdentityPalette(identityPalette);
        fillIdentityPalette(jointPalette);
    }

    public void setStatusListener(StatusListener listener) { this.statusListener = listener; }
    public void setMoveDirection(float dx, float dy) { moveDirX = dx; moveDirY = dy; }

    public void addOrbitDelta(float dx, float dy) {
        if (firstPersonMode) {
            fpYaw -= dx * 0.005f;
            fpPitch -= dy * 0.005f;
            if (fpPitch > 1.4f) fpPitch = 1.4f;
            if (fpPitch < -1.4f) fpPitch = -1.4f;
        } else {
            cameraYaw -= dx * 0.005f;
            cameraPitch += dy * 0.005f;
            if (cameraPitch > 1.4f) cameraPitch = 1.4f;
            if (cameraPitch < 0.2f) cameraPitch = 0.2f;
        }
    }

    public void addZoomDelta(float dz) {
        if (firstPersonMode) return;
        cameraDistance += dz * 0.02f;
        if (cameraDistance < 2f) cameraDistance = 2f;
        if (cameraDistance > 40f) cameraDistance = 40f;
    }

    public void panCameraBy(float dx, float dy) {
        if (firstPersonMode) return;
        viewPanX += dx;
        viewPanY += dy;
    }

    public float getCameraDistance() { return cameraDistance; }
    public void setCameraDistance(float d) { if (d < 2) d = 2; if (d > 40) d = 40; cameraDistance = d; }

    public void setCharacterPosition(float x, float y, float z) {
        characterX = x; characterY = y; characterZ = z; syncTargetToCharacter();
    }
    public void moveCharacterBy(float dx, float dy, float dz) {
        characterX += dx; characterY += dy; characterZ += dz; syncTargetToCharacter();
    }
    public void addCharacterRotation(float deltaDegrees) {
        characterRotationY += deltaDegrees;
        if (characterRotationY >= 360f) characterRotationY -= 360f;
        if (characterRotationY <= -360f) characterRotationY += 360f;
    }

    public void setFloorY(float y) { floorY = y; floorDirty = true; }
    public void moveFloorBy(float dy) { floorY += dy; floorDirty = true; }
    public void setWallY(float y) { wallY = y; wallDirty = true; }
    public void moveWallBy(float dy) { wallY += dy; wallDirty = true; }
    public void setWallScale(float scale) { wallScale = scale; wallDirty = true; }
    public void moveWallScale(float delta) { wallScale += delta; wallDirty = true; }
    public void setSpaceScale(float scale) { spaceScale = scale; }

    public void togglePerspective() {
        firstPersonMode = !firstPersonMode;
        if (firstPersonMode) {
            fpYaw = 3.0203414f;
            fpPitch = -0.25837594f;
        }
    }

    private void syncTargetToCharacter() {
        targetX = characterX; targetY = characterY + 1.2f; targetZ = characterZ;
    }

    private String buildDebugText() {
        int triCount = wallModel != null ? wallTriangles.size() : 0;
        float floorFit = floorModel != null ? floorModel.fitScale : 0;
        float usedScale = floorFit * 3f * wallScale;
        return "cameraYaw=" + cameraYaw + "\n"
            + "cameraPitch=" + cameraPitch + "\n"
            + "cameraDistance=" + cameraDistance + "\n"
            + "characterX=" + characterX + "\n"
            + "characterY=" + characterY + "\n"
            + "characterZ=" + characterZ + "\n"
            + "characterRotationY=" + characterRotationY + "°\n"
            + "moveDirX=" + moveDirX + "\n"
            + "moveDirY=" + moveDirY + "\n"
            + "moveMag=" + currentMoveMag + "\n"
            + "model=" + activeModelName + "\n"
            + "floorY=" + floorY + " fit=" + floorFit + "\n"
            + "wallY=" + wallY + " usedScale=" + usedScale + "\n"
            + "wallTriangles=" + triCount
            + "\nspaceScale=" + spaceScale
            + "\nfirstPerson=" + firstPersonMode
            + "\nfpYaw=" + fpYaw
            + "\nfpPitch=" + fpPitch;
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        GLES30.glClearColor(0.05f, 0.05f, 0.08f, 1f);
        GLES30.glEnable(GLES30.GL_DEPTH_TEST);
        // Enable back-face culling to reduce overdraw (models should have consistent winding).
        GLES30.glEnable(GLES30.GL_CULL_FACE);
        GLES30.glCullFace(GLES30.GL_BACK);

        // ---------- Main shader (walls, character, space) ----------
        String vertexShader =
            "#version 300 es\n" +
            "precision highp float;\n" +
            "#define MAX_JOINTS 32\n" +
            "uniform mat4 uMVP;\n" +
            "uniform mat4 uM;\n" +
            "uniform mat4 uJointMatrices[MAX_JOINTS];\n" +
            "in vec3 aPosition;\n" +
            "in vec3 aNormal;\n" +
            "in vec2 aTexCoord;\n" +
            "in vec4 aBoneIds;\n" +
            "in vec4 aBoneWeights;\n" +
            "out vec2 vTexCoord;\n" +
            "out vec3 vNormal;\n" +
            "out vec3 vWorldPos;\n" +
            "void main() {\n" +
            "    int j0 = int(floor(aBoneIds.x + 0.5));\n" +
            "    int j1 = int(floor(aBoneIds.y + 0.5));\n" +
            "    int j2 = int(floor(aBoneIds.z + 0.5));\n" +
            "    int j3 = int(floor(aBoneIds.w + 0.5));\n" +
            "    mat4 skin =\n" +
            "          aBoneWeights.x * uJointMatrices[j0]\n" +
            "        + aBoneWeights.y * uJointMatrices[j1]\n" +
            "        + aBoneWeights.z * uJointMatrices[j2]\n" +
            "        + aBoneWeights.w * uJointMatrices[j3];\n" +
            "    vec4 skinnedPos = skin * vec4(aPosition, 1.0);\n" +
            "    vec4 worldPos = uM * skinnedPos;\n" +
            "    mat3 normalMat = transpose(inverse(mat3(uM * skin)));\n" +
            "    vec3 skinnedNormal = normalize(normalMat * aNormal);\n" +
            "    vTexCoord = aTexCoord;\n" +
            "    vNormal = skinnedNormal;\n" +
            "    vWorldPos = worldPos.xyz;\n" +
            "    gl_Position = uMVP * worldPos;\n" +
            "}";

        String fragmentShader =
            "#version 300 es\n" +
            "precision mediump float;\n" +
            "uniform sampler2D uTex;\n" +
            "uniform vec3 uLightDir;\n" +
            "uniform vec3 uViewPos;\n" +
            "uniform float uRoughness;\n" +
            "uniform float uMetallic;\n" +
            "in vec2 vTexCoord;\n" +
            "in vec3 vNormal;\n" +
            "in vec3 vWorldPos;\n" +
            "out vec4 fragColor;\n" +
            "void main() {\n" +
            "    vec3 n = normalize(vNormal);\n" +
            "    vec3 light = normalize(-uLightDir);\n" +
            "    vec3 view = normalize(uViewPos - vWorldPos);\n" +
            "    vec3 halfVec = normalize(light + view);\n" +
            "    float diff = max(dot(n, light), 0.0);\n" +
            "    float roughness = clamp(uRoughness, 0.001, 1.0);\n" +
            "    float shininess = 100.0 / (roughness + 0.001);\n" +
            "    float spec = pow(max(dot(n, halfVec), 0.0), shininess);\n" +
            "    float F0 = 0.04;\n" +
            "    float fresnel = F0 + (1.0 - F0) * pow(1.0 - max(dot(n, view), 0.0), 5.0);\n" +
            "    float specular = spec * fresnel;\n" +
            "    vec4 texColor = texture(uTex, vTexCoord);\n" +
            "    vec3 diffuseColor = texColor.rgb;\n" +
            "    float ambient = 0.1;\n" +
            "    vec3 color = (ambient + diff) * diffuseColor + specular * vec3(1.0);\n" +
            "    fragColor = vec4(color, texColor.a);\n" +
            "}";

        program = ShaderUtil.createProgram(vertexShader, fragmentShader);

        if (program == 0) {
            Log.e("GLRenderer", "Main program failed to compile/link - rendering disabled for main pass.");
        } else {
            uMVP = GLES30.glGetUniformLocation(program, "uMVP");
            uM = GLES30.glGetUniformLocation(program, "uM");
            uTex = GLES30.glGetUniformLocation(program, "uTex");
            uLightDir = GLES30.glGetUniformLocation(program, "uLightDir");
            uViewPos = GLES30.glGetUniformLocation(program, "uViewPos");
            uRoughness = GLES30.glGetUniformLocation(program, "uRoughness");
            uMetallic = GLES30.glGetUniformLocation(program, "uMetallic");
            uJointMatrices = GLES30.glGetUniformLocation(program, "uJointMatrices[0]");
            aPosition = GLES30.glGetAttribLocation(program, "aPosition");
            aNormal = GLES30.glGetAttribLocation(program, "aNormal");
            aTexCoord = GLES30.glGetAttribLocation(program, "aTexCoord");
            aBoneIds = GLES30.glGetAttribLocation(program, "aBoneIds");
            aBoneWeights = GLES30.glGetAttribLocation(program, "aBoneWeights");
        }

        // ---------- Floor shader with reflection (brightened, strong) ----------
        String floorVertexShader =
            "#version 300 es\n" +
            "precision highp float;\n" +
            "uniform mat4 uMVP;\n" +
            "uniform mat4 uM;\n" +
            "uniform mat4 uReflectionMVP;\n" +
            "in vec3 aPosition;\n" +
            "in vec3 aNormal;\n" +
            "in vec2 aTexCoord;\n" +
            "out vec2 vTexCoord;\n" +
            "out vec3 vNormal;\n" +
            "out vec3 vWorldPos;\n" +
            "out vec4 vReflectCoord;\n" +
            "void main() {\n" +
            "    vec4 worldPos = uM * vec4(aPosition, 1.0);\n" +
            "    vWorldPos = worldPos.xyz;\n" +
            "    mat3 normalMat = transpose(inverse(mat3(uM)));\n" +
            "    vNormal = normalize(normalMat * aNormal);\n" +
            "    vTexCoord = aTexCoord;\n" +
            "    vReflectCoord = uReflectionMVP * worldPos;\n" +
            "    gl_Position = uMVP * worldPos;\n" +
            "}";

        String floorFragmentShader =
            "#version 300 es\n" +
            "precision highp float;\n" +
            "uniform sampler2D uTex;\n" +
            "uniform sampler2D uReflectionTex;\n" +
            "uniform vec3 uLightDir;\n" +
            "uniform vec3 uViewPos;\n" +
            "uniform float uRoughness;\n" +
            "uniform float uMetallic;\n" +
            "in vec2 vTexCoord;\n" +
            "in vec3 vNormal;\n" +
            "in vec3 vWorldPos;\n" +
            "in vec4 vReflectCoord;\n" +
            "out vec4 fragColor;\n" +
            "void main() {\n" +
            "    vec3 n = normalize(vNormal);\n" +
            "    vec3 light = normalize(-uLightDir);\n" +
            "    vec3 view = normalize(uViewPos - vWorldPos);\n" +
            "    vec3 halfVec = normalize(light + view);\n" +
            "    float diff = max(dot(n, light), 0.0);\n" +
            "    float roughness = clamp(uRoughness, 0.001, 1.0);\n" +
            "    float shininess = 100.0 / (roughness + 0.001);\n