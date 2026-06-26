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
            "precision highp float;\n" + // increased precision for better sampling / fresnel math
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
            "    float shininess = 100.0 / (roughness + 0.001);\n" +
            "    float spec = pow(max(dot(n, halfVec), 0.0), shininess);\n" +
            "    float F0 = 0.04;\n" +
            "    float fresnel = F0 + (1.0 - F0) * pow(1.0 - max(dot(n, view), 0.0), 5.0);\n" +
            "    float specular = spec * fresnel;\n" +
            "    vec4 texColor = texture(uTex, vTexCoord);\n" +
            "    vec3 diffuseColor = texColor.rgb;\n" +
            "    float ambient = 0.3;\n" +
            "    float diffBoost = 1.2;\n" +
            "    vec3 color = (ambient + diff * diffBoost) * diffuseColor;\n" +
            "    vec4 r = vReflectCoord;\n" +
            "    r /= r.w;\n" +
            "    vec2 reflectUV = r.xy * 0.5 + 0.5;\n" +
            "    reflectUV = clamp(reflectUV, 0.001, 0.999);\n" +
            "    vec4 reflectColor = texture(uReflectionTex, reflectUV);\n" +
            "    // stronger, slightly compressed fresnel for punchier reflections\n" +
            "    float reflectFactor = pow(fresnel, 0.8) * (1.0 - roughness) * 4.0;\n" +
            "    color = mix(color, reflectColor.rgb * 2.5, clamp(reflectFactor, 0.0, 1.0));\n" +
            "    color += specular * vec3(1.0);\n" +
            "    fragColor = vec4(color, texColor.a);\n" +
            "}";

        programFloor = ShaderUtil.createProgram(floorVertexShader, floorFragmentShader);

        if (programFloor == 0) {
            Log.e("GLRenderer", "Floor program failed to compile/link - floor pass disabled.");
        } else {
            uMVP_floor = GLES30.glGetUniformLocation(programFloor, "uMVP");
            uM_floor = GLES30.glGetUniformLocation(programFloor, "uM");
            uTex_floor = GLES30.glGetUniformLocation(programFloor, "uTex");
            uLightDir_floor = GLES30.glGetUniformLocation(programFloor, "uLightDir");
            uViewPos_floor = GLES30.glGetUniformLocation(programFloor, "uViewPos");
            uRoughness_floor = GLES30.glGetUniformLocation(programFloor, "uRoughness");
            uMetallic_floor = GLES30.glGetUniformLocation(programFloor, "uMetallic");
            uReflectionTex_floor = GLES30.glGetUniformLocation(programFloor, "uReflectionTex");
            uReflectionMVP_floor = GLES30.glGetUniformLocation(programFloor, "uReflectionMVP");
            aPosition_floor = GLES30.glGetAttribLocation(programFloor, "aPosition");
            aNormal_floor = GLES30.glGetAttribLocation(programFloor, "aNormal");
            aTexCoord_floor = GLES30.glGetAttribLocation(programFloor, "aTexCoord");
        }

        // Load models (these can be heavy; consider background load in future)
        standingModel = GltfLoader.load(context, "standing.gltf");
        walkingModel = GltfLoader.load(context, chooseAsset("walking.gltf", "walking.GLTF"));
        runningModel = GltfLoader.load(context, chooseAsset("running.gltf", "running.GLTF"));

        overrideModelTexture(standingModel, "ptex.png");
        overrideModelTexture(walkingModel, "ptex.png");
        overrideModelTexture(runningModel, "ptex.png");

        floorModel = GltfLoader.load(context, chooseAsset("floor.gltf", "floor.GLTF"));
        overrideModelTexture(floorModel, "ftex.png");

        wallModel = GltfLoader.load(context, chooseAsset("wall.gltf", "wall.GLTF"));
        overrideModelTexture(wallModel, "ftex.png");

        spaceModel = GltfLoader.load(context, chooseAsset("space_orbit_sphere.gltf", "space_orbit_sphere.gltf"));
        overrideModelTexture(spaceModel, "space.png");
        spaceWorld = (spaceModel != null) ? createWorldArray(spaceModel) : new float[0][0];

        // Set roughness/metallic
        setModelRoughnessAndMetallic(floorModel, 0.1f, 0.0f);
        setModelRoughnessAndMetallic(wallModel, 0.5f, 0.0f);
        setModelRoughnessAndMetallic(standingModel, 0.5f, 0.0f);
        setModelRoughnessAndMetallic(walkingModel, 0.5f, 0.0f);
        setModelRoughnessAndMetallic(runningModel, 0.5f, 0.0f);

        standingWorld = createWorldArray(standingModel);
        walkingWorld = createWorldArray(walkingModel);
        runningWorld = createWorldArray(runningModel);
        floorWorld = createWorldArray(floorModel);
        wallWorld = createWorldArray(wallModel);

        characterModel = standingModel;
        characterWorld = standingWorld;
        activeModelName = "standing";

        if (standingModel != null) {
            characterX = standingModel.centerX + 0.3f;
            characterY = 1.0f;
            characterZ = standingModel.centerZ + 0.3f;
            moveCharacterBy(mcharacterX, mcharacterY, mcharacterZ);
        }

        float initDx = 0f, initDy = 10.0f, initDz = 10.0f;
        cameraDistance = (float) Math.sqrt(initDx*initDx + initDy*initDy + initDz*initDz);
        cameraYaw = (float) Math.atan2(initDx, initDz);
        cameraPitch = (float) Math.asin(initDy / cameraDistance);

        startTimeNs = System.nanoTime();
        lastFrameTimeNs = startTimeNs;
        floorDirty = wallDirty = true;
    }

    private void setModelRoughnessAndMetallic(GltfModel model, float roughness, float metallic) {
        if (model == null || model.meshes == null) return;
        for (GltfModel.GltfMesh mesh : model.meshes) {
            if (mesh != null && mesh.primitives != null) {
                for (GltfModel.GltfPrimitive prim : mesh.primitives) {
                    if (prim != null) {
                        prim.roughnessFactor = roughness;
                        prim.metallicFactor = metallic;
                    }
                }
            }
        }
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        screenWidth = width;
        screenHeight = height;
        GLES30.glViewport(0, 0, width, height);
        float ratio = (float) width / (float) height;
        Matrix.perspectiveM(projection, 0, 45f, ratio, 0.5f, 200f);

        int desiredW = Math.max(1, (int)(width * reflectionScale));
        int desiredH = Math.max(1, (int)(height * reflectionScale));
        if (reflectionFbo == -1 || reflectionWidth != desiredW || reflectionHeight != desiredH) {
            initReflectionFBO(width, height);
        }
    }

    private void initReflectionFBO(int width, int height) {
        // Delete previous attachments if present
        if (reflectionFbo != -1) {
            int[] ids = {reflectionFbo};
            GLES30.glDeleteFramebuffers(1, ids, 0);
            reflectionFbo = -1;
        }
        if (reflectionTex != -1) {
            int[] ids = {reflectionTex};
            GLES30.glDeleteTextures(1, ids, 0);
            reflectionTex = -1;
        }
        if (reflectionRbo != -1) {
            int[] rboIds = {reflectionRbo};
            GLES30.glDeleteRenderbuffers(1, rboIds, 0);
            reflectionRbo = -1;
        }

        reflectionWidth = Math.max(1, (int) (width * reflectionScale));
        reflectionHeight = Math.max(1, (int) (height * reflectionScale));

        int[] texIds = new int[1];
        GLES30.glGenTextures(1, texIds, 0);
        reflectionTex = texIds[0];
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, reflectionTex);
        // use RGBA so sampling is consistent and we can generate mipmaps
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, reflectionWidth, reflectionHeight, 0,
                            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null);
        // enable trilinear filtering (requires mipmaps)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);

        int[] fboIds = new int[1];
        GLES30.glGenFramebuffers(1, fboIds, 0);
        reflectionFbo = fboIds[0];
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, reflectionFbo);
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                                      GLES30.GL_TEXTURE_2D, reflectionTex, 0);

        int[] rboIds = new int[1];
        GLES30.glGenRenderbuffers(1, rboIds, 0);
        int rbo = rboIds[0];
        GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, rbo);
        GLES30.glRenderbufferStorage(GLES30.GL_RENDERBUFFER, GLES30.GL_DEPTH_COMPONENT16, reflectionWidth, reflectionHeight);
        GLES30.glFramebufferRenderbuffer(GLES30.GL_FRAMEBUFFER, GLES30.GL_DEPTH_ATTACHMENT,
                                         GLES30.GL_RENDERBUFFER, rbo);
        reflectionRbo = rbo;

        int status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER);
        if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) {
            Log.e("GLRenderer", "Reflection FBO incomplete: " + status);
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        GLES30.glClearColor(0.05f, 0.05f, 0.08f, 1f);

        long now = System.nanoTime();
        float dt = (now - lastFrameTimeNs) / 1_000_000_000.0f;
        if (dt > 0.1f) dt = 0.1f;
        lastFrameTimeNs = now;

        currentMoveMag = (float) Math.sqrt(moveDirX * moveDirX + moveDirY * moveDirY);

        if (currentMoveMag < 0.15f) {
            if (characterModel != standingModel) {
                characterModel = standingModel; characterWorld = standingWorld;
                activeModelName = "standing";
            }
        } else if (currentMoveMag < 0.7f) {
            if (characterModel != walkingModel) {
                characterModel = walkingModel; characterWorld = walkingWorld;
                activeModelName = "walking";
            }
        } else {
            if (characterModel != runningModel) {
                characterModel = runningModel; characterWorld = runningWorld;
                activeModelName = "running";
            }
        }

        float camYaw = firstPersonMode ? fpYaw : cameraYaw;
        float camForwardX = (float) Math.sin(camYaw);
        float camForwardZ = (float) Math.cos(camYaw);
        float camRightX = (float) Math.cos(camYaw);
        float camRightZ = (float) -Math.sin(camYaw);

        float worldDx, worldDz;
        if (firstPersonMode) {
            worldDx = -moveDirX * camRightX - moveDirY * camForwardX;
            worldDz = -moveDirX * camRightZ - moveDirY * camForwardZ;
        } else {
            worldDx = moveDirX * camRightX + moveDirY * camForwardX;
            worldDz = moveDirX * camRightZ + moveDirY * camForwardZ;
        }

        boolean isMoving = (Math.abs(worldDx) > 0.001f || Math.abs(worldDz) > 0.001f);
        boolean applyMovement = isMoving;

        if (!firstPersonMode && isMoving) {
            float worldAngleRad = (float) Math.atan2(worldDx, worldDz);
            float worldAngleDeg = (float) Math.toDegrees(worldAngleRad);
            float targetRotationY = (worldAngleDeg / 2f + 360f) % 360f;

            float diff = targetRotationY - characterRotationY;
            if (diff > 180f) diff -= 360f;
            if (diff < -180f) diff += 360f;

            float maxStep = ROTATION_SPEED * dt;
            if (Math.abs(diff) <= maxStep) {
                characterRotationY = targetRotationY;
            } else {
                characterRotationY += Math.signum(diff) * maxStep;
            }
            if (characterRotationY >= 360f) characterRotationY -= 360f;
            if (characterRotationY < 0f) characterRotationY += 360f;

            float remainingDiff = targetRotationY - characterRotationY;
            if (remainingDiff > 180f) remainingDiff -= 360f;
            if (remainingDiff < -180f) remainingDiff += 360f;
            if (Math.abs(remainingDiff) > TURN_PAUSE_THRESHOLD) {
                applyMovement = false;
            }
        }

        if (wallModel != null && (wallDirty || wallTriangles.isEmpty())) {
            updateWallCollision();
            wallDirty = false;
        }

        if (applyMovement && isMoving && wallModel != null && !wallTriangles.isEmpty()) {
            float moveDist = moveSpeed * dt;
            float moveX = worldDx * moveDist;
            float moveZ = worldDz * moveDist;

            float[] dir = {moveX, 0f, moveZ};
            float len = (float) Math.sqrt(moveX*moveX + moveZ*moveZ);
            if (len > 0.001f) {
                float[] ndir = {moveX/len, 0f, moveZ/len};
                float startY = characterY + 0.1f;
                float endY = characterY + 3.0f;
                float stepY = 0.4f;
                boolean hit = false;
                for (float y = startY; y <= endY; y += stepY) {
                    float[] origin = {characterX, y, characterZ};
                    float t = raycastWall(origin, ndir, len);
                    if (t >= 0f && t <= len) {
                        hit = true;
                        break;
                    }
                }
                if (hit) applyMovement = false;
            }
        }

        if (applyMovement && isMoving) {
            float moveDist = moveSpeed * dt;
            float newX = characterX + worldDx * moveDist;
            float newZ = characterZ + worldDz * moveDist;

            if (sphereCollidesWithWall(newX, newZ, PLAYER_RADIUS)) {
                if (!sphereCollidesWithWall(newX, characterZ, PLAYER_RADIUS)) {
                    characterX = newX;
                } else if (!sphereCollidesWithWall(characterX, newZ, PLAYER_RADIUS)) {
                    characterZ = newZ;
                }
            } else {
                characterX = newX;
                characterZ = newZ;
            }
        }

        characterY += GRAVITY * dt;
        if (floorDirty) {
            updateFloorCollision();
            floorDirty = false;
        }
        float floorHeight = getFloorHeight(characterX, characterZ);
        if (floorHeight != Float.NEGATIVE_INFINITY && characterY < floorHeight) {
            characterY = floorHeight;
        }

        resolveWallPenetration();
        syncTargetToCharacter();

        float timeSec = (System.nanoTime() - startTimeNs) / 1_000_000_000.0f;
        if (characterModel != null) characterModel.updateAnimations(timeSec);
        if (floorModel != null) floorModel.updateAnimations(timeSec);
        if (wallModel != null) wallModel.updateAnimations(timeSec);
        if (spaceModel != null) spaceModel.updateAnimations(timeSec);

        updateViewMatrix();

        // ---------- Render reflection into FBO (always if available) ----------
        if (reflectionFbo != -1 && program != 0) {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, reflectionFbo);
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT | GLES30.GL_DEPTH_BUFFER_BIT);
            GLES30.glViewport(0, 0, reflectionWidth, reflectionHeight);

            float reflectY = floorY;
            float eyeX = mCameraPos[0], eyeY = mCameraPos[1], eyeZ = mCameraPos[2];
            float reflectedEyeY = 2f * reflectY - eyeY;

            // determine look point based on current camera mode
            float lookX, lookY, lookZ;
            if (firstPersonMode) {
                // compute look direction from fpYaw/fpPitch (same logic as updateViewMatrix)
                float cosP = (float) Math.cos(fpPitch);
                float sinP = (float) Math.sin(fpPitch);
                float cosY = (float) Math.cos(fpYaw);
                float sinY = (float) Math.sin(fpYaw);
                float lx = sinY * cosP;
                float ly = sinP;
                float lz = cosY * cosP;
                // use same look distance as updateViewMatrix (10f)
                lookX = eyeX + lx * 10f;
                lookY = eyeY + ly * 10f;
                lookZ = eyeZ + lz * 10f;
            } else {
                lookX = targetX + viewPanX;
                lookY = targetY + viewPanY;
                lookZ = targetZ;
            }

            float reflectedLookY = 2f * reflectY - lookY;

            // avoid exact coplanar eye/look positions (near-plane clipping) by nudging slightly
            final float EPS = 0.02f;
            if (Math.abs(reflectedEyeY - reflectY) < 0.001f) reflectedEyeY += EPS;
            if (Math.abs(reflectedLookY - reflectY) < 0.001f) reflectedLookY += EPS;

            // mirrored up vector for reflection, and flip front-face winding so culling still works
            Matrix.setLookAtM(reflectionView, 0,
                              eyeX, reflectedEyeY, eyeZ,
                              lookX, reflectedLookY, lookZ,
                              0f, -1f, 0f);

            Matrix.multiplyMM(temp, 0, projection, 0, reflectionView, 0);
            System.arraycopy(temp, 0, reflectionMVP, 0, 16);

            // set view position to the reflected eye so lighting/specular in reflection is correct
            if (program != 0 && uViewPos >= 0) {
                GLES30.glUseProgram(program);
                GLES30.glUniform3f(uViewPos, eyeX, reflectedEyeY, eyeZ);
            }

            // Flip front-face winding to CW for the mirrored render so back-face culling behaves correctly
            GLES30.glFrontFace(GLES30.GL_CW);

            GLES30.glUseProgram(program);
            renderWallsReflected();
            renderCharacterReflected();

            // Restore front-face winding back to CCW
            GLES30.glFrontFace(GLES30.GL_CCW);

            // generate mipmaps for the reflection texture to improve sampling quality
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, reflectionTex);
            GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D);

            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
            // restore viewport to screen size
            GLES30.glViewport(0, 0, screenWidth, screenHeight);
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT | GLES30.GL_DEPTH_BUFFER_BIT);
        } else {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
            GLES30.glViewport(0, 0, screenWidth, screenHeight);
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT | GLES30.GL_DEPTH_BUFFER_BIT);
        }

        // ---------- Render main scene ----------
        if (program != 0) {
            GLES30.glUseProgram(program);
            if (uViewPos >= 0) GLES30.glUniform3f(uViewPos, mCameraPos[0], mCameraPos[1], mCameraPos[2]);
            renderWalls();
            renderCharacter();
            renderSpace();
        }

        // floor uses programFloor
        renderFloorReflected();

        if (statusListener != null) {
            statusListener.onStatus(buildDebugText());
        }
    }

    // ------------------ Reflected rendering helpers ------------------
    private void renderWallsReflected() {
        if (wallModel == null || floorModel == null) return;
        float[] root = new float[16];
        Matrix.setIdentityM(root, 0);
        float scaleVal = floorModel.fitScale * 3f * wallScale;
        Matrix.translateM(root, 0, -wallModel.centerX, wallY - wallModel.centerY, -wallModel.centerZ);
        Matrix.scaleM(root, 0, scaleVal, scaleVal, scaleVal);
        buildWorldRecursive(wallModel, wallWorld, root);
        renderModelReflected(wallModel, wallWorld);
    }

    private void renderCharacterReflected() {
        if (characterModel == null) return;
        float[] root = new float[16];
        Matrix.setIdentityM(root, 0);
        float centerX = standingModel != null ? standingModel.centerX : 0f;
        float centerY = standingModel != null ? standingModel.centerY : 0f;
        float centerZ = standingModel != null ? standingModel.centerZ : 0f;
        float fitScale = standingModel != null ? standingModel.fitScale : 1f;
        Matrix.translateM(root, 0, characterX - centerX, characterY - centerY, characterZ - centerZ);
        Matrix.rotateM(root, 0, characterRotationY, 0f, 1f, 0f);
        Matrix.scaleM(root, 0, fitScale * characterScale, fitScale * characterScale, fitScale * characterScale);
        buildWorldRecursive(characterModel, characterWorld, root);
        renderModelReflected(characterModel, characterWorld);
    }

    private void renderModelReflected(GltfModel model, float[][] world) {
        if (model == null || model.nodes == null || world == null) return;
        boolean[] childFlags = new boolean[model.nodes.length];
        for (int i = 0; i < model.nodes.length; i++) {
            GltfModel.GltfNode n = model.nodes[i];
            if (n == null || n.children == null) continue;
            for (int c = 0; c < n.children.length; c++) {
                int child = n.children[c];
                if (child >= 0 && child < childFlags.length) childFlags[child] = true;
            }
        }
        if (model.sceneRoots != null && model.sceneRoots.length > 0) {
            for (int i = 0; i < model.sceneRoots.length; i++)
                renderNodeReflected(model, world, model.sceneRoots[i]);
        } else {
            for (int i = 0; i < model.nodes.length; i++)
                if (!childFlags[i]) renderNodeReflected(model, world, i);
        }
    }

    private void renderNodeReflected(GltfModel model, float[][] world, int nodeIndex) {
        if (model == null || model.nodes == null || world == null) return;
        if (nodeIndex < 0 || nodeIndex >= model.nodes.length) return;
        GltfModel.GltfNode node = model.nodes[nodeIndex];
        if (node == null) return;
        float[] modelMatrix = world[nodeIndex];
        if (modelMatrix == null) return;
        if (node.mesh >= 0 && model.meshes != null && node.mesh < model.meshes.length) {
            GltfModel.GltfMesh mesh = model.meshes[node.mesh];
            if (mesh != null && mesh.primitives != null)
                for (int i = 0; i < mesh.primitives.length; i++)
                    drawPrimitiveReflected(model, mesh.primitives[i], modelMatrix, node.skin, world);
        }
        if (node.children != null)
            for (int i = 0; i < node.children.length; i++)
                renderNodeReflected(model, world, node.children[i]);
    }

    private void drawPrimitiveReflected(GltfModel model, GltfModel.GltfPrimitive primitive, float[] modelMatrix, int skinIndex, float[][] modelWorld) {
        if (primitive == null) return;
        if (primitive.useDrawArrays) { if (primitive.vertexCount <= 0) return; }
        else { if (primitive.indexCount <= 0) return; }
        ensureTexture(primitive);
        if (skinIndex >= 0 && model.skins != null && skinIndex < model.skins.length)
            buildJointPalette(model, skinIndex, modelMatrix, jointPalette, modelWorld);
        else fillIdentityPalette(jointPalette);
        if (uJointMatrices >= 0) GLES30.glUniformMatrix4fv(uJointMatrices, 32, false, jointPalette, 0);
        Matrix.multiplyMM(temp, 0, reflectionView, 0, modelMatrix, 0);
        Matrix.multiplyMM(mvp, 0, projection, 0, temp, 0);
        if (uMVP >= 0) GLES30.glUniformMatrix4fv(uMVP, 1, false, mvp, 0);
        if (uM >= 0) GLES30.glUniformMatrix4fv(uM, 1, false, modelMatrix, 0);
        if (uLightDir >= 0) GLES30.glUniform3f(uLightDir, lightDir[0], lightDir[1], lightDir[2]);
        if (uRoughness >= 0) GLES30.glUniform1f(uRoughness, primitive.roughnessFactor);
        if (uMetallic >= 0) GLES30.glUniform1f(uMetallic, primitive.metallicFactor);

        if (aPosition >= 0) {
            GLES30.glEnableVertexAttribArray(aPosition);
            GLES30.glVertexAttribPointer(aPosition, 3, GLES30.GL_FLOAT, false, 3*4, primitive.positionBuffer);
        }
        if (aNormal >= 0) {
            GLES30.glEnableVertexAttribArray(aNormal);
            GLES30.glVertexAttribPointer(aNormal, 3, GLES30.GL_FLOAT, false, 3*4, primitive.normalBuffer);
        }
        if (aTexCoord >= 0) {
            GLES30.glEnableVertexAttribArray(aTexCoord);
            GLES30.glVertexAttribPointer(aTexCoord, 2, GLES30.GL_FLOAT, false, 2*4, primitive.uvBuffer);
        }

        if (primitive.skinned && primitive.jointIdsBuffer != null && primitive.jointWeightsBuffer != null && aBoneIds >= 0 && aBoneWeights >= 0) {
            GLES30.glEnableVertexAttribArray(aBoneIds);
            GLES30.glEnableVertexAttribArray(aBoneWeights);
            GLES30.glVertexAttribPointer(aBoneIds, 4, GLES30.GL_FLOAT, false, 4*4, primitive.jointIdsBuffer);
            GLES30.glVertexAttribPointer(aBoneWeights, 4, GLES30.GL_FLOAT, false, 4*4, primitive.jointWeightsBuffer);
        }

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        int texToBind = primitive.textureId > 0 ? primitive.textureId : getFallbackTexture();
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texToBind);
        if (uTex >= 0) GLES30.glUniform1i(uTex, 0);
        if (primitive.useDrawArrays)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, primitive.vertexCount);
        else
            GLES30.glDrawElements(GLES30.GL_TRIANGLES, primitive.indexCount, primitive.indexComponentType, primitive.indexBuffer);

        if (aPosition >= 0) GLES30.glDisableVertexAttribArray(aPosition);
        if (aNormal >= 0) GLES30.glDisableVertexAttribArray(aNormal);
        if (aTexCoord >= 0) GLES30.glDisableVertexAttribArray(aTexCoord);
        if (primitive.skinned && primitive.jointIdsBuffer != null && primitive.jointWeightsBuffer != null && aBoneIds >= 0 && aBoneWeights >= 0) {
            GLES30.glDisableVertexAttribArray(aBoneIds);
            GLES30.glDisableVertexAttribArray(aBoneWeights);
        }
    }

    // ------------------ Floor with reflection ------------------
    private void renderFloorReflected() {
        if (floorModel == null) return;
        if (programFloor == 0) return; // disabled
        GLES30.glUseProgram(programFloor);

        float[] root = new float[16];
        Matrix.setIdentityM(root, 0);
        Matrix.translateM(root, 0, -floorModel.centerX, floorY - floorModel.centerY, -floorModel.centerZ);
        Matrix.scaleM(root, 0, floorModel.fitScale * 3f, floorModel.fitScale * 3f, floorModel.fitScale * 3f);
        buildWorldRecursive(floorModel, floorWorld, root);

        renderFloorModelReflected();
    }

    private void renderFloorModelReflected() {
        if (floorModel == null || floorModel.nodes == null) return;
        boolean[] childFlags = new boolean[floorModel.nodes.length];
        for (int i = 0; i < floorModel.nodes.length; i++) {
            GltfModel.GltfNode n = floorModel.nodes[i];
            if (n == null || n.children == null) continue;
            for (int c = 0; c < n.children.length; c++) {
                int child = n.children[c];
                if (child >= 0 && child < childFlags.length) childFlags[child] = true;
            }
        }
        if (floorModel.sceneRoots != null && floorModel.sceneRoots.length > 0) {
            for (int i = 0; i < floorModel.sceneRoots.length; i++)
                renderFloorNode(floorModel, floorWorld, floorModel.sceneRoots[i]);
        } else {
            for (int i = 0; i < floorModel.nodes.length; i++)
                if (!childFlags[i]) renderFloorNode(floorModel, floorWorld, i);
        }
    }

    private void renderFloorNode(GltfModel model, float[][] world, int nodeIndex) {
        if (model == null || model.nodes == null || world == null) return;
        if (nodeIndex < 0 || nodeIndex >= model.nodes.length) return;
        GltfModel.GltfNode node = model.nodes[nodeIndex];
        if (node == null) return;
        float[] modelMatrix = world[nodeIndex];
        if (modelMatrix == null) return;
        if (node.mesh >= 0 && model.meshes != null && node.mesh < model.meshes.length) {
            GltfModel.GltfMesh mesh = model.meshes[node.mesh];
            if (mesh != null && mesh.primitives != null)
                for (int i = 0; i < mesh.primitives.length; i++)
                    drawFloorPrimitive(model, mesh.primitives[i], modelMatrix);
        }
        if (node.children != null)
            for (int i = 0; i < node.children.length; i++)
                renderFloorNode(model, world, node.children[i]);
    }

    private void drawFloorPrimitive(GltfModel model, GltfModel.GltfPrimitive primitive, float[] modelMatrix) {
        if (primitive == null) return;
        if (primitive.useDrawArrays) { if (primitive.vertexCount <= 0) return; }
        else { if (primitive.indexCount <= 0) return; }
        ensureTexture(primitive);

        Matrix.multiplyMM(temp, 0, view, 0, modelMatrix, 0);
        Matrix.multiplyMM(mvp, 0, projection, 0, temp, 0);
        if (uMVP_floor >= 0) GLES30.glUniformMatrix4fv(uMVP_floor, 1, false, mvp, 0);
        if (uM_floor >= 0) GLES30.glUniformMatrix4fv(uM_floor, 1, false, modelMatrix, 0);
        if (uLightDir_floor >= 0) GLES30.glUniform3f(uLightDir_floor, lightDir[0], lightDir[1], lightDir[2]);
        if (uRoughness_floor >= 0) GLES30.glUniform1f(uRoughness_floor, primitive.roughnessFactor);
        if (uMetallic_floor >= 0) GLES30.glUniform1f(uMetallic_floor, primitive.metallicFactor);

        // ensure floor shader gets the real camera position (not mirrored)
        if (uViewPos_floor >= 0) GLES30.glUniform3f(uViewPos_floor, mCameraPos[0], mCameraPos[1], mCameraPos[2]);

        if (uReflectionTex_floor >= 0 && reflectionTex > 0) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE1);
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, reflectionTex);
            GLES30.glUniform1i(uReflectionTex_floor, 1);
        }

        if (uReflectionMVP_floor >= 0) GLES30.glUniformMatrix4fv(uReflectionMVP_floor, 1, false, reflectionMVP, 0);

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        int texToBind = primitive.textureId > 0 ? primitive.textureId : getFallbackTexture();
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texToBind);
        if (uTex_floor >= 0) GLES30.glUniform1i(uTex_floor, 0);

        if (aPosition_floor >= 0) {
            GLES30.glEnableVertexAttribArray(aPosition_floor);
            GLES30.glVertexAttribPointer(aPosition_floor, 3, GLES30.GL_FLOAT, false, 3*4, primitive.positionBuffer);
        }
        if (aNormal_floor >= 0) {
            GLES30.glEnableVertexAttribArray(aNormal_floor);
            GLES30.glVertexAttribPointer(aNormal_floor, 3, GLES30.GL_FLOAT, false, 3*4, primitive.normalBuffer);
        }
        if (aTexCoord_floor >= 0) {
            GLES30.glEnableVertexAttribArray(aTexCoord_floor);
            GLES30.glVertexAttribPointer(aTexCoord_floor, 2, GLES30.GL_FLOAT, false, 2*4, primitive.uvBuffer);
        }

        if (primitive.useDrawArrays)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, primitive.vertexCount);
        else
            GLES30.glDrawElements(GLES30.GL_TRIANGLES, primitive.indexCount, primitive.indexComponentType, primitive.indexBuffer);

        if (aPosition_floor >= 0) GLES30.glDisableVertexAttribArray(aPosition_floor);
        if (aNormal_floor >= 0) GLES30.glDisableVertexAttribArray(aNormal_floor);
        if (aTexCoord_floor >= 0) GLES30.glDisableVertexAttribArray(aTexCoord_floor);
    }

    // ------------------ Standard rendering (walls, character, space) ------------------
    private void renderWalls() {
        if (wallModel == null || floorModel == null) return;
        float[] root = new float[16];
        Matrix.setIdentityM(root, 0);
        float scaleVal = floorModel.fitScale * 3f * wallScale;
        Matrix.translateM(root, 0, -wallModel.centerX, wallY - wallModel.centerY, -wallModel.centerZ);
        Matrix.scaleM(root, 0, scaleVal, scaleVal, scaleVal);
        buildWorldRecursive(wallModel, wallWorld, root);
        renderModel(wallModel, wallWorld);
    }

    private void renderCharacter() {
        if (characterModel == null) return;
        if (firstPersonMode) return;
        float[] root = new float[16];
        Matrix.setIdentityM(root, 0);
        float centerX = standingModel != null ? standingModel.centerX : 0f;
        float centerY = standingModel != null ? standingModel.centerY : 0f;
        float centerZ = standingModel != null ? standingModel.centerZ : 0f;
        float fitScale = standingModel != null ? standingModel.fitScale : 1f;
        Matrix.translateM(root, 0, characterX - centerX, characterY - centerY, characterZ - centerZ);
        Matrix.rotateM(root, 0, characterRotationY, 0f, 1f, 0f);
        Matrix.scaleM(root, 0, fitScale * characterScale, fitScale * characterScale, fitScale * characterScale);
        buildWorldRecursive(characterModel, characterWorld, root);
        renderModel(characterModel, characterWorld);
    }

    private void renderSpace() {
        if (spaceModel == null || spaceWorld == null || spaceWorld.length == 0) return;
        float[] root = new float[16];
        Matrix.setIdentityM(root, 0);
        float finalScale = spaceModel.fitScale * spaceScale;
        Matrix.scaleM(root, 0, finalScale, finalScale, finalScale);
        buildWorldRecursive(spaceModel, spaceWorld, root);

        GLES30.glEnable(GLES30.GL_BLEND);
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA);
        GLES30.glDepthMask(false);
        renderModel(spaceModel, spaceWorld);
        GLES30.glDepthMask(true);
        GLES30.glDisable(GLES30.GL_BLEND);
    }

    // ------------------ Scene graph and rendering utilities ------------------
    private void buildWorldRecursive(GltfModel model, float[][] world, float[] rootMatrix) {
        if (model == null || model.nodes == null || world == null) return;
        boolean[] childFlags = new boolean[model.nodes.length];
        for (int i = 0; i < model.nodes.length; i++) {
            GltfModel.GltfNode n = model.nodes[i];
            if (n == null || n.children == null) continue;
            for (int c = 0; c < n.children.length; c++) {
                int child = n.children[c];
                if (child >= 0 && child < childFlags.length) childFlags[child] = true;
            }
        }
        if (model.sceneRoots != null && model.sceneRoots.length > 0) {
            for (int i = 0; i < model.sceneRoots.length; i++)
                buildNodeWorld(model, world, model.sceneRoots[i], rootMatrix);
        } else {
            for (int i = 0; i < model.nodes.length; i++)
                if (!childFlags[i]) buildNodeWorld(model, world, i, rootMatrix);
        }
    }

    private void buildNodeWorld(GltfModel model, float[][] world, int nodeIndex, float[] parentMatrix) {
        if (model == null || model.nodes == null) return;
        if (nodeIndex < 0 || nodeIndex >= model.nodes.length) return;
        GltfModel.GltfNode node = model.nodes[nodeIndex];
        if (node == null) return;
        float[] local = new float[16];
        buildNodeMatrix(node, local);
        if (world[nodeIndex] == null || world[nodeIndex].length != 16)
            world[nodeIndex] = new float[16];
        Matrix.multiplyMM(world[nodeIndex], 0, parentMatrix, 0, local, 0);
        if (node.children != null)
            for (int c = 0; c < node.children.length; c++)
                buildNodeWorld(model, world, node.children[c], world[nodeIndex]);
    }

    private void renderModel(GltfModel model, float[][] world) {
        if (model == null || model.nodes == null || world == null) return;
        boolean[] childFlags = new boolean[model.nodes.length];
        for (int i = 0; i < model.nodes.length; i++) {
            GltfModel.GltfNode n = model.nodes[i];
            if (n == null || n.children == null) continue;
            for (int c = 0; c < n.children.length; c++) {
                int child = n.children[c];
                if (child >= 0 && child < childFlags.length) childFlags[child] = true;
            }
        }
        if (model.sceneRoots != null && model.sceneRoots.length > 0) {
            for (int i = 0; i < model.sceneRoots.length; i++)
                renderNode(model, world, model.sceneRoots[i]);
        } else {
            for (int i = 0; i < model.nodes.length; i++)
                if (!childFlags[i]) renderNode(model, world, i);
        }
    }

    private void renderNode(GltfModel model, float[][] world, int nodeIndex) {
        if (model == null || model.nodes == null || world == null) return;
        if (nodeIndex < 0 || nodeIndex >= model.nodes.length) return;
        GltfModel.GltfNode node = model.nodes[nodeIndex];
        if (node == null) return;
        float[] modelMatrix = world[nodeIndex];
        if (modelMatrix == null) return;
        if (node.mesh >= 0 && model.meshes != null && node.mesh < model.meshes.length) {
            GltfModel.GltfMesh mesh = model.meshes[node.mesh];
            if (mesh != null && mesh.primitives != null)
                for (int i = 0; i < mesh.primitives.length; i++)
                    drawPrimitive(model, mesh.primitives[i], modelMatrix, node.skin, world);
        }
        if (node.children != null)
            for (int i = 0; i < node.children.length; i++)
                renderNode(model, world, node.children[i]);
    }

    private void drawPrimitive(GltfModel model, GltfModel.GltfPrimitive primitive, float[] modelMatrix, int skinIndex, float[][] modelWorld) {
        if (primitive == null) return;
        if (primitive.useDrawArrays) { if (primitive.vertexCount <= 0) return; }
        else { if (primitive.indexCount <= 0) return; }
        ensureTexture(primitive);
        if (skinIndex >= 0 && model.skins != null && skinIndex < model.skins.length)
            buildJointPalette(model, skinIndex, modelMatrix, jointPalette, modelWorld);
        else fillIdentityPalette(jointPalette);
        if (uJointMatrices >= 0) GLES30.glUniformMatrix4fv(uJointMatrices, 32, false, jointPalette, 0);
        Matrix.multiplyMM(temp, 0, view, 0, modelMatrix, 0);
        Matrix.multiplyMM(mvp, 0, projection, 0, temp, 0);
        if (uMVP >= 0) GLES30.glUniformMatrix4fv(uMVP, 1, false, mvp, 0);
        if (uM >= 0) GLES30.glUniformMatrix4fv(uM, 1, false, modelMatrix, 0);
        if (uLightDir >= 0) GLES30.glUniform3f(uLightDir, lightDir[0], lightDir[1], lightDir[2]);
        if (uRoughness >= 0) GLES30.glUniform1f(uRoughness, primitive.roughnessFactor);
        if (uMetallic >= 0) GLES30.glUniform1f(uMetallic, primitive.metallicFactor);

        if (aPosition >= 0) {
            GLES30.glEnableVertexAttribArray(aPosition);
            GLES30.glVertexAttribPointer(aPosition, 3, GLES30.GL_FLOAT, false, 3*4, primitive.positionBuffer);
        }
        if (aNormal >= 0) {
            GLES30.glEnableVertexAttribArray(aNormal);
            GLES30.glVertexAttribPointer(aNormal, 3, GLES30.GL_FLOAT, false, 3*4, primitive.normalBuffer);
        }
        if (aTexCoord >= 0) {
            GLES30.glEnableVertexAttribArray(aTexCoord);
            GLES30.glVertexAttribPointer(aTexCoord, 2, GLES30.GL_FLOAT, false, 2*4, primitive.uvBuffer);
        }
        if (primitive.skinned && primitive.jointIdsBuffer != null && primitive.jointWeightsBuffer != null && aBoneIds >= 0 && aBoneWeights >= 0) {
            GLES30.glEnableVertexAttribArray(aBoneIds);
            GLES30.glEnableVertexAttribArray(aBoneWeights);
            GLES30.glVertexAttribPointer(aBoneIds, 4, GLES30.GL_FLOAT, false, 4*4, primitive.jointIdsBuffer);
            GLES30.glVertexAttribPointer(aBoneWeights, 4, GLES30.GL_FLOAT, false, 4*4, primitive.jointWeightsBuffer);
        }

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        int texToBind = primitive.textureId > 0 ? primitive.textureId : getFallbackTexture();
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texToBind);
        if (uTex >= 0) GLES30.glUniform1i(uTex, 0);
        if (primitive.useDrawArrays)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, primitive.vertexCount);
        else
            GLES30.glDrawElements(GLES30.GL_TRIANGLES, primitive.indexCount, primitive.indexComponentType, primitive.indexBuffer);
        if (aPosition >= 0) GLES30.glDisableVertexAttribArray(aPosition);
        if (aNormal >= 0) GLES30.glDisableVertexAttribArray(aNormal);
        if (aTexCoord >= 0) GLES30.glDisableVertexAttribArray(aTexCoord);
        if (primitive.skinned && primitive.jointIdsBuffer != null && primitive.jointWeightsBuffer != null && aBoneIds >= 0 && aBoneWeights >= 0) {
            GLES30.glDisableVertexAttribArray(aBoneIds);
            GLES30.glDisableVertexAttribArray(aBoneWeights);
        }
    }

    // ------------------ Collision and physics helpers ------------------
    private void updateFloorCollision() {
        if (floorModel == null) return;
        float[] root = new float[16];
        Matrix.setIdentityM(root, 0);
        Matrix.translateM(root, 0, -floorModel.centerX, floorY - floorModel.centerY, -floorModel.centerZ);
        Matrix.scaleM(root, 0, floorModel.fitScale * 3f, floorModel.fitScale * 3f, floorModel.fitScale * 3f);
        buildWorldRecursive(floorModel, floorWorld, root);
        floorTriangles = computeTriangles(floorModel, floorWorld);
    }

    private void updateWallCollision() {
        if (wallModel == null || floorModel == null) return;
        float[] root = new float[16];
        Matrix.setIdentityM(root, 0);
        float scaleVal = floorModel.fitScale * 3f * wallScale;
        Matrix.translateM(root, 0, -wallModel.centerX, wallY - wallModel.centerY, -wallModel.centerZ);
        Matrix.scaleM(root, 0, scaleVal, scaleVal, scaleVal);
        buildWorldRecursive(wallModel, wallWorld, root);
        wallTriangles = computeTriangles(wallModel, wallWorld);
    }

    private List<float[]> computeTriangles(GltfModel model, float[][] world) {
        List<float[]> tris = new ArrayList<>();
        if (model == null || model.meshes == null) return tris;
        for (int m = 0; m < model.meshes.length; m++) {
            GltfModel.GltfMesh mesh = model.meshes[m];
            if (mesh == null || mesh.primitives == null) continue;
            for (int p = 0; p < mesh.primitives.length; p++) {
                GltfModel.GltfPrimitive prim = mesh.primitives[p];
                if (prim == null) continue;
                FloatBuffer posBuf = prim.positionBuffer;
                if (posBuf == null) continue;
                posBuf.position(0);
                float[] positions = new float[posBuf.remaining()];
                posBuf.get(positions);
                posBuf.position(0);

                int[] indices = null;
                ShortBuffer idxBuf = prim.indexBuffer;
                if (!prim.useDrawArrays && idxBuf != null) {
                    idxBuf.position(0);
                    short[] shortIndices = new short[idxBuf.remaining()];
                    idxBuf.get(shortIndices);
                    idxBuf.position(0);
                    indices = new int[shortIndices.length];
                    for (int i = 0; i < shortIndices.length; i++)
                        indices[i] = shortIndices[i] & 0xFFFF;
                }

                float[] transform = null;
                if (model.nodes != null) {
                    for (int n = 0; n < model.nodes.length; n++) {
                        GltfModel.GltfNode node = model.nodes[n];
                        if (node != null && node.mesh == m) {
                            transform = world[n];
                            break;
                        }
                    }
                }
                if (transform == null) {
                    transform = new float[16];
                    Matrix.setIdentityM(transform, 0);
                }

                if (indices != null) {
                    for (int i = 0; i < indices.length; i += 3) {
                        if (i + 2 >= indices.length) break;
                        int i0 = indices[i], i1 = indices[i+1], i2 = indices[i+2];
                        float[] v0 = getVertex(positions, i0, transform);
                        float[] v1 = getVertex(positions, i1, transform);
                        float[] v2 = getVertex(positions, i2, transform);
                        tris.add(new float[]{v0[0],v0[1],v0[2], v1[0],v1[1],v1[2], v2[0],v2[1],v2[2]});
                    }
                } else {
                    for (int i = 0; i < positions.length; i += 9) {
                        if (i + 8 >= positions.length) break;
                        float[] v0 = getVertex(positions, i/3, transform);
                        float[] v1 = getVertex(positions, i/3+1, transform);
                        float[] v2 = getVertex(positions, i/3+2, transform);
                        tris.add(new float[]{v0[0],v0[1],v0[2], v1[0],v1[1],v1[2], v2[0],v2[1],v2[2]});
                    }
                }
            }
        }
        return tris;
    }

    private float[] getVertex(float[] positions, int index, float[] matrix) {
        int base = index * 3;
        float x = positions[base], y = positions[base+1], z = positions[base+2];
        float[] out = new float[4];
        Matrix.multiplyMV(out, 0, matrix, 0, new float[]{x,y,z,1f}, 0);
        return new float[]{out[0], out[1], out[2]};
    }

    private float getFloorHeight(float x, float z) {
        float bestT = Float.POSITIVE_INFINITY;
        float[] origin = {x, 1000f, z}, dir = {0,-1,0};
        for (float[] tri : floorTriangles) {
            float[] v0 = {tri[0],tri[1],tri[2]}, v1 = {tri[3],tri[4],tri[5]}, v2 = {tri[6],tri[7],tri[8]};
            float t = rayTriangleIntersect(origin, dir, v0, v1, v2);
            if (t > 0 && t < bestT) bestT = t;
        }
        if (bestT == Float.POSITIVE_INFINITY) return Float.NEGATIVE_INFINITY;
        return origin[1] + dir[1] * bestT;
    }

    private float raycastWall(float[] origin, float[] dir, float maxDist) {
        float bestT = -1f;
        for (float[] tri : wallTriangles) {
            float[] v0 = {tri[0],tri[1],tri[2]}, v1 = {tri[3],tri[4],tri[5]}, v2 = {tri[6],tri[7],tri[8]};
            float t = rayTriangleIntersect(origin, dir, v0, v1, v2);
            if (t > 0 && t <= maxDist) {
                if (bestT < 0 || t < bestT) bestT = t;
            }
        }
        return bestT;
    }

    private float rayTriangleIntersect(float[] orig, float[] dir,
                                       float[] v0, float[] v1, float[] v2) {
        final float EPS = 1e-6f;
        float[] e1 = {v1[0]-v0[0], v1[1]-v0[1], v1[2]-v0[2]};
        float[] e2 = {v2[0]-v0[0], v2[1]-v0[1], v2[2]-v0[2]};
        float[] pvec = cross(dir, e2);
        float det = dot(e1, pvec);
        if (Math.abs(det) < EPS) return -1;
        float invDet = 1.0f / det;
        float[] tvec = {orig[0]-v0[0], orig[1]-v0[1], orig[2]-v0[2]};
        float u = dot(tvec, pvec) * invDet;
        if (u < 0 || u > 1) return -1;
        float[] qvec = cross(tvec, e1);
        float v = dot(dir, qvec) * invDet;
        if (v < 0 || u+v > 1) return -1;
        float t = dot(e2, qvec) * invDet;
        return t > EPS ? t : -1;
    }

    private float dot(float[] a, float[] b) { return a[0]*b[0] + a[1]*b[1] + a[2]*b[2]; }
    private float[] cross(float[] a, float[] b) {
        return new float[]{ a[1]*b[2] - a[2]*b[1], a[2]*b[0] - a[0]*b[2], a[0]*b[1] - a[1]*b[0] };
    }

    private float[] closestPointOnTriangle2D(float px, float pz, float[] tri) {
        float ax = tri[0], az = tri[2];
        float bx = tri[3], bz = tri[5];
        float cx = tri[6], cz = tri[8];

        float v0x = bx - ax, v0z = bz - az;
        float v1x = cx - ax, v1z = cz - az;
        float v2x = px - ax, v2z = pz - az;

        float d00 = v0x*v0x + v0z*v0z;
        float d01 = v0x*v1x + v0z*v1z;
        float d11 = v1x*v1x + v1z*v1z;
        float d20 = v2x*v0x + v2z*v0z;
        float d21 = v2x*v1x + v2z*v1z;
        float denom = d00*d11 - d01*d01;

        // protect against degenerate / nearly collinear triangles
        if (Math.abs(denom) < 1e-8f) {
            // fallback: use closest point on edges
            float bestDistSq = Float.MAX_VALUE;
            float bestX = ax, bestZ = az;
            float[] e = closestPointOnSegment(px, pz, ax, az, bx, bz);
            float dx = px - e[0], dz = pz - e[1];
            float d = dx*dx + dz*dz;
            if (d < bestDistSq) { bestDistSq = d; bestX = e[0]; bestZ = e[1]; }
            e = closestPointOnSegment(px, pz, bx, bz, cx, cz);
            dx = px - e[0]; dz = pz - e[1];
            d = dx*dx + dz*dz;
            if (d < bestDistSq) { bestDistSq = d; bestX = e[0]; bestZ = e[1]; }
            e = closestPointOnSegment(px, pz, cx, cz, ax, az);
            dx = px - e[0]; dz = pz - e[1];
            d = dx*dx + dz*dz;
            if (d < bestDistSq) { bestDistSq = d; bestX = e[0]; bestZ = e[1]; }
            return new float[]{bestX, bestZ};
        }

        float u = (d11*d20 - d01*d21) / denom;
        float v = (d00*d21 - d01*d20) / denom;
        float w = 1 - u - v;

        float closestX, closestZ;
        if (u >= 0 && v >= 0 && w >= 0) {
            closestX = ax + u*v0x + v*v1x;
            closestZ = az + u*v0z + v*v1z;
        } else {
            float bestDistSq = Float.MAX_VALUE;
            float bestX = 0, bestZ = 0;
            float[] e = closestPointOnSegment(px, pz, ax, az, bx, bz);
            float dx = px - e[0], dz = pz - e[1];
            float d = dx*dx + dz*dz;
            if (d < bestDistSq) { bestDistSq = d; bestX = e[0]; bestZ = e[1]; }
            e = closestPointOnSegment(px, pz, bx, bz, cx, cz);
            dx = px - e[0]; dz = pz - e[1];
            d = dx*dx + dz*dz;
            if (d < bestDistSq) { bestDistSq = d; bestX = e[0]; bestZ = e[1]; }
            e = closestPointOnSegment(px, pz, cx, cz, ax, az);
            dx = px - e[0]; dz = pz - e[1];
            d = dx*dx + dz*dz;
            if (d < bestDistSq) { bestDistSq = d; bestX = e[0]; bestZ = e[1]; }
            closestX = bestX;
            closestZ = bestZ;
        }
        return new float[]{closestX, closestZ};
    }

    private float[] closestPointOnSegment(float px, float pz, float ax, float az, float bx, float bz) {
        float dx = bx - ax, dz = bz - az;
        float len2 = dx*dx + dz*dz;
        if (len2 < 1e-8f) return new float[]{ax, az};
        float t = ((px - ax)*dx + (pz - az)*dz) / len2;
        t = Math.max(0, Math.min(1, t));
        return new float[]{ax + t*dx, az + t*dz};
    }

    private boolean sphereCollidesWithWall(float cx, float cz, float radius) {
        if (wallTriangles.isEmpty()) return false;
        for (float[] tri : wallTriangles) {
            float[] closest = closestPointOnTriangle2D(cx, cz, tri);
            float dx = cx - closest[0];
            float dz = cz - closest[1];
            float distSq = dx*dx + dz*dz;
            if (distSq < radius*radius) {
                return true;
            }
        }
        return false;
    }

    private void resolveWallPenetration() {
        if (wallTriangles.isEmpty()) return;
        float pushX = 0f, pushZ = 0f;
        boolean overlapped = false;
        for (float[] tri : wallTriangles) {
            float[] closest = closestPointOnTriangle2D(characterX, characterZ, tri);
            float dx = characterX - closest[0];
            float dz = characterZ - closest[1];
            float distSq = dx*dx + dz*dz;
            if (distSq < PLAYER_RADIUS * PLAYER_RADIUS) {
                overlapped = true;
                float dist = (float) Math.sqrt(distSq);
                if (dist < 0.0001f) {
                    pushX += 0.01f;
                    continue;
                }
                float overlap = PLAYER_RADIUS - dist;
                pushX += (dx / dist) * overlap;
                pushZ += (dz / dist) * overlap;
            }
        }
        if (overlapped) {
            characterX += pushX;
            characterZ += pushZ;
        }
    }

    // ------------------ View and camera ------------------
    private void updateViewMatrix() {
        if (firstPersonMode) {
            float rad = (float) Math.toRadians(characterRotationY);
            float forwardX = (float) Math.sin(rad);
            float forwardZ = (float) Math.cos(rad);
            float offset = 0.3f;
            float eyeX = characterX + forwardX * offset;
            float eyeY = characterY + EYE_HEIGHT;
            float eyeZ = characterZ + forwardZ * offset;

            float cosP = (float) Math.cos(fpPitch);
            float sinP = (float) Math.sin(fpPitch);
            float cosY = (float) Math.cos(fpYaw);
            float sinY = (float) Math.sin(fpYaw);
            float lookX = sinY * cosP;
            float lookY = sinP;
            float lookZ = cosY * cosP;

            float targetX = eyeX + lookX * 10f;
            float targetY = eyeY + lookY * 10f;
            float targetZ = eyeZ + lookZ * 10f;

            Matrix.setLookAtM(view, 0, eyeX, eyeY, eyeZ, targetX, targetY, targetZ, 0f, 1f, 0f);
            mCameraPos[0] = eyeX; mCameraPos[1] = eyeY; mCameraPos[2] = eyeZ;
        } else {
            float lookX = targetX + viewPanX;
            float lookY = targetY + viewPanY;
            float lookZ = targetZ;
            float camX = lookX + cameraDistance * (float)(Math.cos(cameraPitch) * Math.sin(cameraYaw));
            float camY = lookY + cameraDistance * (float)(Math.sin(cameraPitch));
            float camZ = lookZ + cameraDistance * (float)(Math.cos(cameraPitch) * Math.cos(cameraYaw));
            Matrix.setLookAtM(view, 0, camX, camY, camZ, lookX, lookY, lookZ, 0f, 1f, 0f);
            mCameraPos[0] = camX; mCameraPos[1] = camY; mCameraPos[2] = camZ;
        }
    }

    // ------------------ Skinning and matrix helpers ------------------
    private void buildJointPalette(GltfModel model, int skinIndex, float[] meshWorld, float[] outPalette, float[][] modelWorld) {
        fillIdentityPalette(outPalette);
        if (model.skins == null || skinIndex < 0 || skinIndex >= model.skins.length) return;
        GltfModel.GltfSkin skin = model.skins[skinIndex];
        if (skin == null || skin.joints == null) return;
        float[] meshInv = new float[16];
        if (!Matrix.invertM(meshInv, 0, meshWorld, 0)) Matrix.setIdentityM(meshInv, 0);
        int jointCount = Math.min(skin.joints.length, 32);
        for (int i = 0; i < jointCount; i++) {
            int jointNodeIndex = skin.joints[i];
            if (jointNodeIndex < 0 || jointNodeIndex >= worldLength(model)) continue;
            float[] jointWorld = (modelWorld != null && jointNodeIndex < modelWorld.length) ? modelWorld[jointNodeIndex] : null;
            if (jointWorld == null) continue;
            float[] invBind = readMat4(skin.inverseBindMatrices, i);
            float[] tmp = new float[16], finalMat = new float[16];
            Matrix.multiplyMM(tmp, 0, jointWorld, 0, invBind, 0);
            Matrix.multiplyMM(finalMat, 0, meshInv, 0, tmp, 0);
            System.arraycopy(finalMat, 0, outPalette, i * 16, 16);
        }
    }

    private int worldLength(GltfModel model) { return model == null || model.nodes == null ? 0 : model.nodes.length; }
    private float[] readMat4(float[] source, int index) {
        float[] out = new float[16];
        int base = index * 16;
        if (source == null || base + 15 >= source.length) { Matrix.setIdentityM(out, 0); return out; }
        System.arraycopy(source, base, out, 0, 16);
        return out;
    }
    private void fillIdentityPalette(float[] out) {
        for (int i = 0; i < 32; i++) {
            int base = i * 16;
            for (int j = 0; j < 16; j++) out[base+j] = 0f;
            out[base+0] = 1f; out[base+5] = 1f; out[base+10] = 1f; out[base+15] = 1f;
        }
    }
    private void buildNodeMatrix(GltfModel.GltfNode node, float[] out) {
        if (node.matrix != null) { System.arraycopy(node.matrix, 0, out, 0, 16); return; }
        float[] t = new float[16], r = new float[16], s = new float[16], tr = new float[16];
        Matrix.setIdentityM(t, 0); Matrix.setIdentityM(r, 0); Matrix.setIdentityM(s, 0);
        Matrix.translateM(t, 0, node.currentTranslation[0], node.currentTranslation[1], node.currentTranslation[2]);
        quatToMatrix(node.currentRotation, r);
        Matrix.scaleM(s, 0, node.currentScale[0], node.currentScale[1], node.currentScale[2]);
        Matrix.multiplyMM(tr, 0, t, 0, r, 0);
        Matrix.multiplyMM(out, 0, tr, 0, s, 0);
    }
    private void quatToMatrix(float[] q, float[] m) {
        float x=q[0],y=q[1],z=q[2],w=q[3];
        float xx=x*x,yy=y*y,zz=z*z,xy=x*y,xz=x*z,yz=y*z,wx=w*x,wy=w*y,wz=w*z;
        m[0]=1-2*(yy+zz); m[1]=2*(xy+wz); m[2]=2*(xz-wy); m[3]=0;
        m[4]=2*(xy-wz); m[5]=1-2*(xx+zz); m[6]=2*(yz+wx); m[7]=0;
        m[8]=2*(xz+wy); m[9]=2*(yz-wx); m[10]=1-2*(xx+yy); m[11]=0;
        m[12]=0; m[13]=0; m[14]=0; m[15]=1;
    }

    // ------------------ Texture helpers ------------------
    private void ensureTexture(GltfModel.GltfPrimitive primitive) {
        if (primitive.textureId > 0) return;
        Bitmap bmp = primitive.textureBitmap;
        if (bmp == null) bmp = TextureUtil.makeCheckerBitmap();
        primitive.textureId = loadTexture(bmp);
        if (primitive.textureBitmap != null) { primitive.textureBitmap.recycle(); primitive.textureBitmap = null; }
    }
    private int loadTexture(Bitmap bitmap) {
        int[] ids = new int[1];
        GLES30.glGenTextures(1, ids, 0);
        int texId = ids[0];
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texId);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_REPEAT);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_REPEAT);
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0);
        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D);
        return texId;
    }
    private void overrideModelTexture(GltfModel model, String assetName) {
        if (model == null) return;
        try {
            java.io.InputStream is = context.getAssets().open(assetName);
            Bitmap bmp = BitmapFactory.decodeStream(is);
            is.close();
            if (bmp == null) return;
            if (model.meshes != null) {
                for (GltfModel.GltfMesh mesh : model.meshes) {
                    if (mesh != null && mesh.primitives != null) {
                        for (GltfModel.GltfPrimitive prim : mesh.primitives) {
                            if (prim != null) {
                                Bitmap copy = bmp.copy(bmp.getConfig(), false);
                                prim.textureBitmap = copy;
                                prim.textureId = -1;
                            }
                        }
                    }
                }
            }
            bmp.recycle();
        } catch (Exception ignored) {}
    }

    private String chooseAsset(String primary, String fallback) {
        try { context.getAssets().open(primary).close(); return primary; } catch (Exception ignored) {}
        try { context.getAssets().open(fallback).close(); return fallback; } catch (Exception ignored) {}
        return primary;
    }

    private float[][] createWorldArray(GltfModel model) {
        if (model == null || model.nodes == null) return new float[0][0];
        float[][] world = new float[model.nodes.length][16];
        for (int i = 0; i < world.length; i++) Matrix.setIdentityM(world[i], 0);
        return world;
    }

    private int getFallbackTexture() {
        if (fallbackTextureId > 0) return fallbackTextureId;
        Bitmap bmp = TextureUtil.makeCheckerBitmap();
        fallbackTextureId = loadTexture(bmp);
        bmp.recycle();
        return fallbackTextureId;
    }

    // ------------------ Cleanup ------------------
    // Call this from your Activity.onPause/onDestroy to free GL resources (when GL context valid).
    public void dispose() {
        if (reflectionFbo != -1) {
            int[] ids = {reflectionFbo};
            GLES30.glDeleteFramebuffers(1, ids, 0);
            reflectionFbo = -1;
        }
        if (reflectionTex != -1) {
            int[] ids = {reflectionTex};
            GLES30.glDeleteTextures(1, ids, 0);
            reflectionTex = -1;
        }
        if (reflectionRbo != -1) {
            int[] ids = {reflectionRbo};
            GLES30.glDeleteRenderbuffers(1, ids, 0);
            reflectionRbo = -1;
        }
        if (fallbackTextureId > 0) {
            int[] ids = {fallbackTextureId};
            GLES30.glDeleteTextures(1, ids, 0);
            fallbackTextureId = -1;
        }
        if (program != 0) {
            GLES30.glDeleteProgram(program);
            program = 0;
        }
        if (programFloor != 0) {
            GLES30.glDeleteProgram(programFloor);
            programFloor = 0;
        }
        // Note: individual primitive.textureId uploaded during ensureTexture are not tracked here.
        // For a full cleanup, track and delete those texture IDs as well when disposing.
    }
}
