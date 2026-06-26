package com.mycompany.myapp;

import android.graphics.Bitmap;

public class GltfModel {

    public GltfMesh[] meshes = new GltfMesh[0];
    public GltfNode[] nodes = new GltfNode[0];
    public int[] sceneRoots = new int[0];
    public GltfSkin[] skins = new GltfSkin[0];
    public GltfAnimation[] animations = new GltfAnimation[0];

    public float fitScale = 1f;
    public float centerX = 0f;
    public float centerY = 0f;
    public float centerZ = 0f;

    public String statusText = "";

    /* --------------------------------------------------------
     *  MESH & PRIMITIVE (extended for PBR and normal mapping)
     * -------------------------------------------------------- */
    public static class GltfMesh {
        public GltfPrimitive[] primitives = new GltfPrimitive[0];
    }

    public static class GltfPrimitive {
        // Vertex buffers
        public java.nio.FloatBuffer positionBuffer;
        public java.nio.FloatBuffer normalBuffer;
        public java.nio.FloatBuffer uvBuffer;
        public java.nio.FloatBuffer tangentBuffer;          // 4 components: xyz + handedness

        public java.nio.FloatBuffer jointIdsBuffer;
        public java.nio.FloatBuffer jointWeightsBuffer;

        // Index buffer
        public java.nio.ShortBuffer indexBuffer;
        public int indexCount;
        public int indexComponentType;

        // Draw mode
        public int vertexCount;
        public boolean useDrawArrays = false;

        // Base colour texture (still used as fallback)
        public Bitmap textureBitmap;
        public int textureId = -1;

        // PBR material properties
        public float[] baseColorFactor = { 1f, 1f, 1f, 1f };
        public float metallicFactor = 1f;
        public float roughnessFactor = 1f;

        // PBR textures
        public Bitmap metallicRoughnessBitmap;   // G = roughness, B = metallic (glTF spec)
        public int metallicRoughnessTexId = -1;

        public Bitmap normalBitmap;              // Normal map
        public int normalTexId = -1;

        // Skinning
        public boolean skinned = false;
    }

    /* --------------------------------------------------------
     *  NODE
     * -------------------------------------------------------- */
    public static class GltfNode {
        public int mesh = -1;
        public int skin = -1;
        public int[] children = new int[0];

        public float[] translation = { 0f, 0f, 0f };
        public float[] rotation = { 0f, 0f, 0f, 1f };
        public float[] scale = { 1f, 1f, 1f };
        public float[] matrix = null;

        public float[] currentTranslation = { 0f, 0f, 0f };
        public float[] currentRotation = { 0f, 0f, 0f, 1f };
        public float[] currentScale = { 1f, 1f, 1f };

        public void resetAnimatedValues() {
            currentTranslation[0] = translation[0];
            currentTranslation[1] = translation[1];
            currentTranslation[2] = translation[2];
            currentRotation[0] = rotation[0];
            currentRotation[1] = rotation[1];
            currentRotation[2] = rotation[2];
            currentRotation[3] = rotation[3];
            currentScale[0] = scale[0];
            currentScale[1] = scale[1];
            currentScale[2] = scale[2];
        }
    }

    /* --------------------------------------------------------
     *  SKIN
     * -------------------------------------------------------- */
    public static class GltfSkin {
        public int[] joints = new int[0];
        public int skeleton = -1;
        public float[] inverseBindMatrices = new float[0];
    }

    /* --------------------------------------------------------
     *  ANIMATION
     * -------------------------------------------------------- */
    public static class GltfAnimation {
        public GltfSampler[] samplers = new GltfSampler[0];
        public GltfChannel[] channels = new GltfChannel[0];
        public float duration = 0f;
    }

    public static class GltfSampler {
        public float[] input = new float[0];       // keyframe times
        public float[] output = new float[0];      // keyframe values
        public String interpolation = "LINEAR";    // "LINEAR", "STEP", etc.
    }

    public static class GltfChannel {
        public int sampler = 0;
        public int targetNode = -1;
        public String path = "";                   // "translation", "rotation", "scale"
    }

    /* --------------------------------------------------------
     *  ANIMATION UPDATE
     * -------------------------------------------------------- */
    public void updateAnimations(float timeSeconds) {
        if (nodes == null || nodes.length == 0 || animations == null || animations.length == 0)
            return;

        // Reset all nodes to base values before applying animation
        for (GltfNode node : nodes) {
            if (node != null) node.resetAnimatedValues();
        }

        for (GltfAnimation anim : animations) {
            if (anim == null || anim.samplers == null || anim.channels == null) continue;

            float t = timeSeconds;
            if (anim.duration > 0f) {
                t = t % anim.duration;   // loop
            }

            for (GltfChannel ch : anim.channels) {
                if (ch == null || ch.targetNode < 0 || ch.targetNode >= nodes.length) continue;
                if (ch.sampler < 0 || ch.sampler >= anim.samplers.length) continue;

                GltfSampler sam = anim.samplers[ch.sampler];
                if (sam == null || sam.input == null || sam.output == null || sam.input.length == 0)
                    continue;

                float[] value = sample(sam, ch.path, t);
                if (value == null) continue;

                GltfNode node = nodes[ch.targetNode];

                if ("translation".equals(ch.path) && value.length >= 3) {
                    node.currentTranslation[0] = value[0];
                    node.currentTranslation[1] = value[1];
                    node.currentTranslation[2] = value[2];
                } else if ("scale".equals(ch.path) && value.length >= 3) {
                    node.currentScale[0] = value[0];
                    node.currentScale[1] = value[1];
                    node.currentScale[2] = value[2];
                } else if ("rotation".equals(ch.path) && value.length >= 4) {
                    node.currentRotation[0] = value[0];
                    node.currentRotation[1] = value[1];
                    node.currentRotation[2] = value[2];
                    node.currentRotation[3] = value[3];
                }
            }
        }
    }

    private float[] sample(GltfSampler sam, String path, float time) {
        int comp = 3;   // translation / scale
        if ("rotation".equals(path)) comp = 4;

        if (sam.input.length == 1) {
            return extractValue(sam.output, 0, comp);
        }

        // Clamp to ends
        if (time <= sam.input[0]) return extractValue(sam.output, 0, comp);
        int last = sam.input.length - 1;
        if (time >= sam.input[last]) return extractValue(sam.output, last, comp);

        // Find interval
        int left = 0;
        for (int i = 0; i < last; i++) {
            if (time >= sam.input[i] && time <= sam.input[i + 1]) {
                left = i;
                break;
            }
        }

        float t0 = sam.input[left];
        float t1 = sam.input[left + 1];
        float alpha = (time - t0) / (t1 - t0);

        if ("STEP".equals(sam.interpolation)) {
            return extractValue(sam.output, left, comp);
        }

        float[] a = extractValue(sam.output, left, comp);
        float[] b = extractValue(sam.output, left + 1, comp);

        if ("rotation".equals(path)) {
            return slerp(a, b, alpha);
        }

        // Linear interpolation (default for translation/scale)
        float[] out = new float[comp];
        for (int i = 0; i < comp; i++) {
            out[i] = a[i] + (b[i] - a[i]) * alpha;
        }
        return out;
    }

    private float[] extractValue(float[] data, int keyIndex, int comp) {
        int base = keyIndex * comp;
        float[] out = new float[comp];
        for (int i = 0; i < comp; i++) {
            out[i] = data[base + i];
        }
        return out;
    }

    private float[] slerp(float[] q1, float[] q2, float t) {
        float x1 = q1[0], y1 = q1[1], z1 = q1[2], w1 = q1[3];
        float x2 = q2[0], y2 = q2[1], z2 = q2[2], w2 = q2[3];

        float dot = x1 * x2 + y1 * y2 + z1 * z2 + w1 * w2;

        if (dot < 0f) {
            dot = -dot;
            x2 = -x2;
            y2 = -y2;
            z2 = -z2;
            w2 = -w2;
        }

        final float EPS = 0.0001f;
        if (1f - dot < EPS) {
            float[] out = new float[4];
            out[0] = x1 + (x2 - x1) * t;
            out[1] = y1 + (y2 - y1) * t;
            out[2] = z1 + (z2 - z1) * t;
            out[3] = w1 + (w2 - w1) * t;
            normalizeQuat(out);
            return out;
        }

        float theta0 = (float) Math.acos(dot);
        float theta = theta0 * t;

        float sinTheta = (float) Math.sin(theta);
        float sinTheta0 = (float) Math.sin(theta0);

        float s0 = (float) Math.cos(theta) - dot * sinTheta / sinTheta0;
        float s1 = sinTheta / sinTheta0;

        float[] out = new float[4];
        out[0] = s0 * x1 + s1 * x2;
        out[1] = s0 * y1 + s1 * y2;
        out[2] = s0 * z1 + s1 * z2;
        out[3] = s0 * w1 + s1 * w2;
        normalizeQuat(out);
        return out;
    }

    private void normalizeQuat(float[] q) {
        float len = (float) Math.sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3]);
        if (len > 0.000001f) {
            q[0] /= len;
            q[1] /= len;
            q[2] /= len;
            q[3] /= len;
        }
    }
}
