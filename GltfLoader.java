package com.mycompany.myapp; // Declares the package for this loader class.

import android.content.Context; // Gives access to app assets and file loading.
import android.graphics.Bitmap; // Represents image data used for textures.
import android.graphics.BitmapFactory; // Decodes image data into Bitmap objects.
import android.util.Base64; // Decodes base64-encoded embedded buffer and image data.

import org.json.JSONArray; // Handles JSON arrays in glTF files.
import org.json.JSONObject; // Handles JSON objects in glTF files.

import java.io.ByteArrayOutputStream; // Collects bytes into memory while reading files.
import java.io.InputStream; // Reads asset files and external binary data as streams.
import java.util.ArrayList; // Imported in the original file, though unused here.
import java.util.HashMap; // Imported in the original file, though unused here.
import java.util.LinkedHashMap; // Imported in the original file, though unused here.
import java.util.Map; // Imported in the original file, though unused here.

public class GltfLoader { // Main loader that reads glTF JSON and converts it into GltfModel data.

    private static final int GL_UNSIGNED_BYTE = 5121; // glTF/OpenGL constant for 8-bit unsigned integers.
    private static final int GL_UNSIGNED_SHORT = 5123; // glTF/OpenGL constant for 16-bit unsigned integers.
    private static final int GL_UNSIGNED_INT = 5125; // glTF/OpenGL constant for 32-bit unsigned integers.
    private static final int GL_FLOAT = 5126; // glTF/OpenGL constant for 32-bit floating-point values.

    private static class LoadedPrimitive { // Temporary container used while parsing one mesh primitive.
        float[] positions; // Vertex positions read from the POSITION accessor.
        float[] normals; // Vertex normals read from the NORMAL accessor.
        float[] uvs; // Texture coordinates read from the TEXCOORD_0 accessor.
        float[] jointIds; // Joint indices for skinned vertices.
        float[] jointWeights; // Joint weights for skinned vertices.

        short[] indices; // Index list when using indexed rendering with short indices.
        int indexCount; // Number of indices in the index buffer.

        int vertexCount; // Number of vertices when using draw arrays.
        boolean useDrawArrays; // True when indices were expanded into raw vertices.

        Bitmap textureBitmap; // Texture image associated with this primitive.
        boolean skinned; // True when the primitive has joint data.
    }

    public static GltfModel load(Context context, String assetName) { // Loads a glTF file from assets into a GltfModel.
        try {
            String json = readAsset(context, assetName); // Read the .gltf JSON text from the asset.
            JSONObject gltf = new JSONObject(json); // Parse the JSON string into a JSONObject.

            GltfModel model = new GltfModel(); // Create the destination model container.

            LoadedPrimitive[][] loadedMeshes = parseMeshes(context, gltf, assetName); // Parse all meshes and primitives.
            model.fitScale = computeFitScale(loadedMeshes); // Compute a scale that fits the model in view.

            float[] center = computeCenter(loadedMeshes); // Compute the geometric center of the model.
            model.centerX = center[0]; // Store center X.
            model.centerY = center[1]; // Store center Y.
            model.centerZ = center[2]; // Store center Z.

            model.meshes = new GltfModel.GltfMesh[loadedMeshes.length]; // Allocate final mesh array.

            int totalVertices = 0; // Count total vertices for status text.
            int totalIndices = 0; // Count total indices for status text.

            for (int m = 0; m < loadedMeshes.length; m++) { // Loop over each parsed mesh.
                LoadedPrimitive[] meshPrims = loadedMeshes[m]; // Get the primitive list for this mesh.
                GltfModel.GltfMesh mesh = new GltfModel.GltfMesh(); // Create the final mesh wrapper.
                mesh.primitives = new GltfModel.GltfPrimitive[meshPrims.length]; // Allocate primitive array.

                for (int p = 0; p < meshPrims.length; p++) { // Loop over each primitive in the mesh.
                    LoadedPrimitive lp = meshPrims[p]; // Temporary parsed primitive.
                    GltfModel.GltfPrimitive prim = new GltfModel.GltfPrimitive(); // Final primitive object.

                    prim.positionBuffer = BufferUtil.toFloatBuffer(lp.positions); // Convert positions to FloatBuffer.
                    prim.normalBuffer = BufferUtil.toFloatBuffer(lp.normals); // Convert normals to FloatBuffer.
                    prim.uvBuffer = BufferUtil.toFloatBuffer(lp.uvs); // Convert UVs to FloatBuffer.
                    prim.textureBitmap = lp.textureBitmap; // Attach the loaded texture bitmap.
                    prim.skinned = lp.skinned; // Store whether this primitive uses skinning.

                    prim.useDrawArrays = lp.useDrawArrays; // Store draw mode.
                    prim.vertexCount = lp.vertexCount; // Store vertex count for draw arrays.

                    if (!lp.useDrawArrays && lp.indices != null) { // If indexed drawing is being used...
                        prim.indexBuffer = BufferUtil.toShortBuffer(lp.indices); // Convert short indices to ShortBuffer.
                        prim.indexCount = lp.indexCount; // Store number of indices.
                        prim.indexComponentType = GL_UNSIGNED_SHORT; // The renderer expects unsigned short indices here.
                    } else { // Otherwise clear indexed fields.
                        prim.indexBuffer = null; // No index buffer.
                        prim.indexCount = 0; // No indices.
                        prim.indexComponentType = 0; // No index type.
                    }

                    if (lp.jointIds != null) { // If joint IDs were loaded...
                        prim.jointIdsBuffer = BufferUtil.toFloatBuffer(lp.jointIds); // Convert joint IDs to FloatBuffer.
                    }
                    if (lp.jointWeights != null) { // If joint weights were loaded...
                        prim.jointWeightsBuffer = BufferUtil.toFloatBuffer(lp.jointWeights); // Convert joint weights to FloatBuffer.
                    }

                    mesh.primitives[p] = prim; // Store the final primitive in the mesh.

                    totalVertices += lp.positions.length / 3; // Each position has 3 floats.
                    totalIndices += lp.useDrawArrays ? lp.vertexCount : lp.indexCount; // Count rendered elements.
                }

                model.meshes[m] = mesh; // Store the mesh in the model.
            }

            model.nodes = parseNodes(context, gltf); // Parse nodes and their transforms.
            model.skins = parseSkins(context, gltf); // Parse skin definitions.
            model.sceneRoots = parseSceneRoots(gltf, model.nodes); // Find scene root nodes.
            model.animations = parseAnimations(context, gltf); // Parse animations if present.

            int skinCount = model.skins == null ? 0 : model.skins.length; // Count skins safely.
            int animCount = model.animations == null ? 0 : model.animations.length; // Count animations safely.

            model.statusText = // Build a status report for the UI.
                "PATH: assets/" + assetName + "\n" + // Show asset path.
                "FILE FOUND\n" + // Confirm the file was found.
                "GLTF LOADED\n" + // Confirm the glTF parsed successfully.
                "MESHES: " + model.meshes.length + "\n" + // Show mesh count.
                "VERTICES: " + totalVertices + "\n" + // Show vertex count.
                "INDICES: " + totalIndices + "\n" + // Show index count.
                "SKINS: " + skinCount + "\n" + // Show skin count.
                "ANIMATIONS: " + animCount + "\n" + // Show animation count.
                "TEXTURE: " + (findFirstTexture(model) != null ? "RENDERED" : "FALLBACK CHECKER"); // Report texture status.

            return model; // Return the completed model.
        } catch (Exception e) { // Any failure falls back to a simple cube.
            e.printStackTrace(); // Print the exception for debugging.
            return createFallback(assetName, e); // Return fallback model instead of crashing.
        }
    }

    private static String readAsset(Context context, String fileName) throws Exception { // Reads a text asset into a String.
        InputStream is = context.getAssets().open(fileName); // Open the asset stream.
        ByteArrayOutputStream bos = new ByteArrayOutputStream(); // Buffer the full file in memory.
        byte[] buffer = new byte[4096]; // Temporary byte buffer for reading chunks.
        int len; // Number of bytes read in each chunk.
        while ((len = is.read(buffer)) != -1) { // Read until end of file.
            bos.write(buffer, 0, len); // Append the bytes to the output buffer.
        }
        is.close(); // Close the asset stream.
        return bos.toString("UTF-8"); // Convert the bytes into a UTF-8 string.
    }

    private static LoadedPrimitive[][] parseMeshes(Context context, JSONObject gltf, String assetName) throws Exception { // Reads glTF mesh list.
        if (!gltf.has("meshes")) { // If there are no meshes...
            return new LoadedPrimitive[0][]; // Return an empty 2D array.
        }

        JSONArray meshes = gltf.getJSONArray("meshes"); // Read the meshes array from JSON.
        LoadedPrimitive[][] out = new LoadedPrimitive[meshes.length()][]; // Allocate output array.

        for (int m = 0; m < meshes.length(); m++) { // Loop over every mesh.
            JSONObject meshObj = meshes.getJSONObject(m); // Read the current mesh object.
            JSONArray primitives = meshObj.getJSONArray("primitives"); // Read the primitive list.
            LoadedPrimitive[] meshPrims = new LoadedPrimitive[primitives.length()]; // Allocate primitive storage.

            for (int p = 0; p < primitives.length(); p++) { // Loop over each primitive.
                meshPrims[p] = parsePrimitive(context, gltf, primitives.getJSONObject(p), assetName); // Parse one primitive.
            }

            out[m] = meshPrims; // Store the parsed primitive array.
        }

        return out; // Return the full mesh primitive list.
    }

    private static LoadedPrimitive parsePrimitive(Context context, JSONObject gltf, JSONObject prim, String assetName) throws Exception { // Parses one glTF primitive.
        LoadedPrimitive out = new LoadedPrimitive(); // Create a new temporary primitive container.

        JSONObject attrs = prim.getJSONObject("attributes"); // Read the primitive attribute map.
        int positionAccessor = attrs.getInt("POSITION"); // POSITION accessor is required.
        int normalAccessor = attrs.has("NORMAL") ? attrs.getInt("NORMAL") : -1; // NORMAL is optional.
        int uvAccessor = attrs.has("TEXCOORD_0") ? attrs.getInt("TEXCOORD_0") : -1; // TEXCOORD_0 is optional.
        int jointsAccessor = attrs.has("JOINTS_0") ? attrs.getInt("JOINTS_0") : -1; // JOINTS_0 is optional.
        int weightsAccessor = attrs.has("WEIGHTS_0") ? attrs.getInt("WEIGHTS_0") : -1; // WEIGHTS_0 is optional.

        out.positions = readAccessorAsFloats(context, gltf, positionAccessor, false); // Read vertex positions.
        out.normals = normalAccessor >= 0
            ? readAccessorAsFloats(context, gltf, normalAccessor, false) // Read normals if present.
            : makeFlatNormals(out.positions.length / 3); // Otherwise create flat forward normals.
        out.uvs = uvAccessor >= 0
            ? readAccessorAsFloats(context, gltf, uvAccessor, false) // Read UVs if present.
            : makeZeroUVs(out.positions.length / 3); // Otherwise create default UVs.

        if (jointsAccessor >= 0) { // If joint indices exist...
            out.jointIds = readAccessorAsFloats(context, gltf, jointsAccessor, false); // Read them as floats for the shader.
            out.skinned = true; // Mark primitive as skinned.
        }

        if (weightsAccessor >= 0) { // If joint weights exist...
            out.jointWeights = readAccessorAsFloats(context, gltf, weightsAccessor, true); // Read normalized weights.
            out.skinned = true; // Mark primitive as skinned.
        }

        if (prim.has("indices")) { // If the primitive uses indexed geometry...
            int indexAccessor = prim.getInt("indices"); // Read the index accessor index.
            int[] indices = readIndexAccessorAsInt(context, gltf, indexAccessor); // Decode the index data.
            int componentType = readIndexComponentType(gltf, indexAccessor); // Read the component type of the indices.
            int maxIndex = maxIndex(indices); // Find the largest index value.

            if (componentType == GL_UNSIGNED_INT || maxIndex > 65535) { // If 32-bit indices are used or values are too big for shorts...
                expandToDrawArrays(out, indices); // Expand indexed geometry into draw-arrays data.
            } else { // Otherwise we can keep compact short indices.
                out.indices = toShortIndices(indices); // Convert int indices to short indices.
                out.indexCount = out.indices.length; // Store number of indices.
                out.useDrawArrays = false; // Use indexed drawing.
                out.vertexCount = 0; // Vertex count is not needed in indexed mode.
            }
        } else { // If the primitive has no indices...
            out.useDrawArrays = true; // Render with glDrawArrays.
            out.vertexCount = out.positions.length / 3; // Vertex count is number of position triplets.
            out.indices = null; // No index buffer.
            out.indexCount = 0; // No index count.
        }

        out.textureBitmap = readTextureForPrimitive(context, gltf, prim, assetName); // Load a texture for this primitive.
        return out; // Return the parsed primitive.
    }

    private static void expandToDrawArrays(LoadedPrimitive out, int[] indices) { // Expands indexed geometry into raw vertex arrays.
        int count = indices.length; // One output vertex per index.

        float[] positions = new float[count * 3]; // Expanded positions.
        float[] normals = out.normals != null ? new float[count * 3] : null; // Expanded normals, if present.
        float[] uvs = out.uvs != null ? new float[count * 2] : null; // Expanded UVs, if present.
        float[] jointIds = out.jointIds != null ? new float[count * 4] : null; // Expanded joint IDs, if present.
        float[] jointWeights = out.jointWeights != null ? new float[count * 4] : null; // Expanded joint weights, if present.

        for (int i = 0; i < count; i++) { // Walk each index and copy source vertex data.
            int src = indices[i]; // Source vertex index.

            copyVec3(out.positions, src, positions, i); // Copy position triplet.

            if (normals != null) { // Copy normals if the array exists.
                copyVec3(out.normals, src, normals, i); // Copy normal triplet.
            }
            if (uvs != null) { // Copy UVs if the array exists.
                copyVec2(out.uvs, src, uvs, i); // Copy UV pair.
            }
            if (jointIds != null) { // Copy joint IDs if the array exists.
                copyVec4(out.jointIds, src, jointIds, i); // Copy joint ID quartet.
            }
            if (jointWeights != null) { // Copy joint weights if the array exists.
                copyVec4(out.jointWeights, src, jointWeights, i); // Copy joint weight quartet.
            }
        }

        out.positions = positions; // Replace old positions with expanded positions.
        out.normals = normals != null ? normals : makeFlatNormals(count); // Ensure normals exist.
        out.uvs = uvs != null ? uvs : makeZeroUVs(count); // Ensure UVs exist.
        out.jointIds = jointIds; // Keep expanded joint IDs if present.
        out.jointWeights = jointWeights; // Keep expanded joint weights if present.

        out.indices = null; // No index buffer in draw-arrays mode.
        out.indexCount = 0; // No index count in draw-arrays mode.
        out.useDrawArrays = true; // Mark primitive as draw-arrays.
        out.vertexCount = count; // Store the new vertex count.
    }

    private static void copyVec2(float[] src, int srcIndex, float[] dst, int dstIndex) { // Copies a 2-component vector.
        int s = srcIndex * 2; // Source offset.
        int d = dstIndex * 2; // Destination offset.
        if (src == null || s + 1 >= src.length || d + 1 >= dst.length) return; // Guard against invalid ranges.
        dst[d] = src[s]; // Copy first component.
        dst[d + 1] = src[s + 1]; // Copy second component.
    }

    private static void copyVec3(float[] src, int srcIndex, float[] dst, int dstIndex) { // Copies a 3-component vector.
        int s = srcIndex * 3; // Source offset.
        int d = dstIndex * 3; // Destination offset.
        if (src == null || s + 2 >= src.length || d + 2 >= dst.length) return; // Guard against invalid ranges.
        dst[d] = src[s]; // Copy x.
        dst[d + 1] = src[s + 1]; // Copy y.
        dst[d + 2] = src[s + 2]; // Copy z.
    }

    private static void copyVec4(float[] src, int srcIndex, float[] dst, int dstIndex) { // Copies a 4-component vector.
        int s = srcIndex * 4; // Source offset.
        int d = dstIndex * 4; // Destination offset.
        if (src == null || s + 3 >= src.length || d + 3 >= dst.length) return; // Guard against invalid ranges.
        dst[d] = src[s]; // Copy first component.
        dst[d + 1] = src[s + 1]; // Copy second component.
        dst[d + 2] = src[s + 2]; // Copy third component.
        dst[d + 3] = src[s + 3]; // Copy fourth component.
    }

    private static int maxIndex(int[] indices) { // Finds the largest index value in an index list.
        int max = 0; // Start from zero.
        for (int i = 0; i < indices.length; i++) { // Scan all indices.
            if (indices[i] > max) max = indices[i]; // Keep the maximum value.
        }
        return max; // Return the maximum index.
    }

    private static int readIndexComponentType(JSONObject gltf, int accessorIndex) throws Exception { // Reads the component type of an index accessor.
        JSONArray accessors = gltf.getJSONArray("accessors"); // Access the accessors array.
        JSONObject accessor = accessors.getJSONObject(accessorIndex); // Get the specific accessor.
        return accessor.getInt("componentType"); // Return its component type.
    }

    private static Bitmap readTextureForPrimitive(Context context, JSONObject gltf, JSONObject prim, String assetName) { // Tries to find a texture for this primitive.
        try {
            int imageIndex = -1; // No image selected yet.

            if (prim.has("material")) { // If a material is attached...
                int materialIndex = prim.getInt("material"); // Read the material index.
                imageIndex = findBaseColorImageIndex(gltf, materialIndex); // Try to find the base color texture.
            }

            if (imageIndex < 0 && gltf.has("images")) { // If no material texture was found, fall back to first image.
                JSONArray images = gltf.getJSONArray("images"); // Read images array.
                if (images.length() > 0) { // If at least one image exists...
                    imageIndex = 0; // Use the first image.
                }
            }

            if (imageIndex >= 0) { // If we have a valid image index...
                return readImageByIndex(context, gltf, imageIndex); // Load that image.
            }

            String pngName = assetName.replace(".gltf", ".png").replace(".GLTF", ".png"); // Guess a sibling PNG file.
            try {
                InputStream is = context.getAssets().open(pngName); // Try opening the PNG asset.
                Bitmap bmp = BitmapFactory.decodeStream(is); // Decode it as a bitmap.
                is.close(); // Close the stream.
                if (bmp != null) return bmp; // Return if decoding succeeded.
            } catch (Exception ignored) { // Ignore missing PNGs or decode failures.
            }
        } catch (Exception ignored) { // Ignore any parsing issues and fall back to null.
        }

        return null; // No texture found.
    }

    private static int findBaseColorImageIndex(JSONObject gltf, int materialIndex) throws Exception { // Finds the image index used by a PBR base color texture.
        if (!gltf.has("materials") || !gltf.has("textures") || !gltf.has("images")) { // Need materials, textures, and images to resolve a texture.
            return -1; // Can't resolve texture.
        }

        JSONArray materials = gltf.getJSONArray("materials"); // Read materials array.
        if (materialIndex < 0 || materialIndex >= materials.length()) { // Validate material index.
            return -1; // Invalid material.
        }

        JSONObject mat = materials.getJSONObject(materialIndex); // Get the material object.
        if (!mat.has("pbrMetallicRoughness")) { // If no PBR block exists...
            return -1; // No texture source to extract.
        }

        JSONObject pbr = mat.getJSONObject("pbrMetallicRoughness"); // Read the PBR block.
        if (!pbr.has("baseColorTexture")) { // If no base color texture exists...
            return -1; // No texture source.
        }

        int texIndex = pbr.getJSONObject("baseColorTexture").getInt("index"); // Read the texture index.
        JSONArray textures = gltf.getJSONArray("textures"); // Read textures array.
        if (texIndex < 0 || texIndex >= textures.length()) { // Validate texture index.
            return -1; // Invalid texture reference.
        }

        return textures.getJSONObject(texIndex).getInt("source"); // Return the image source index.
    }

    private static Bitmap readImageByIndex(Context context, JSONObject gltf, int imageIndex) throws Exception { // Loads an image from glTF by index.
        JSONArray images = gltf.getJSONArray("images"); // Read the images array.
        if (imageIndex < 0 || imageIndex >= images.length()) { // Validate index.
            return null; // Invalid image index.
        }

        JSONObject image = images.getJSONObject(imageIndex); // Get the image entry.
        if (!image.has("uri")) { // Only URI-based images are handled here.
            return null; // No direct URI to load.
        }

        String uri = image.getString("uri"); // Read image URI.
        if (uri.startsWith("data:")) { // If the image is embedded as a data URI...
            int comma = uri.indexOf(','); // Find the base64 payload separator.
            if (comma < 0) return null; // Reject malformed data URIs.

            String base64 = uri.substring(comma + 1); // Extract base64 payload.
            byte[] bytes = Base64.decode(base64, Base64.DEFAULT); // Decode base64 into bytes.
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length); // Turn bytes into a Bitmap.
        } else { // Otherwise the image is an external asset file.
            InputStream is = context.getAssets().open(uri); // Open the image asset.
            Bitmap bmp = BitmapFactory.decodeStream(is); // Decode the stream into a Bitmap.
            is.close(); // Close the stream.
            return bmp; // Return the decoded image.
        }
    }

    private static float[] readAccessorAsFloats(Context context, JSONObject gltf, int accessorIndex, boolean normalizeIntegers) throws Exception { // Reads a glTF accessor into a float array.
        JSONArray accessors = gltf.getJSONArray("accessors"); // Accessors list.
        JSONArray bufferViews = gltf.getJSONArray("bufferViews"); // Buffer views list.

        JSONObject accessor = accessors.getJSONObject(accessorIndex); // Get the selected accessor.
        JSONObject bufferView = bufferViews.getJSONObject(accessor.getInt("bufferView")); // Find its buffer view.

        int count = accessor.getInt("count"); // Number of elements.
        int comps = numComponents(accessor.getString("type")); // Number of components per element.
        int componentType = accessor.getInt("componentType"); // Component type code.
        boolean normalized = accessor.optBoolean("normalized", false) || normalizeIntegers; // Whether values should be normalized.

        int accessorOffset = accessor.optInt("byteOffset", 0); // Offset within the buffer view.
        int viewOffset = bufferView.optInt("byteOffset", 0); // Offset within the buffer.
        int stride = bufferView.optInt("byteStride", comps * componentSize(componentType)); // Byte stride between elements.

        byte[] buffer = decodeBuffer(context, gltf, bufferView.getInt("buffer")); // Load the referenced binary buffer.
        float[] out = new float[count * comps]; // Create the output float array.

        int p = viewOffset + accessorOffset; // Starting byte position.
        for (int i = 0; i < count; i++) { // Loop through all elements.
            for (int c = 0; c < comps; c++) { // Loop through each component in the element.
                out[i * comps + c] = readComponentAsFloat( // Decode the current component.
                    buffer, // Raw byte buffer.
                    p + c * componentSize(componentType), // Current byte offset.
                    componentType, // Type of the value.
                    normalized // Whether normalization is needed.
                );
            }
            p += stride; // Advance to the next element according to stride.
        }

        return out; // Return the decoded float array.
    }

    private static int[] readIndexAccessorAsInt(Context context, JSONObject gltf, int accessorIndex) throws Exception { // Reads an index accessor into an int array.
        JSONArray accessors = gltf.getJSONArray("accessors"); // Accessors list.
        JSONArray bufferViews = gltf.getJSONArray("bufferViews"); // Buffer views list.

        JSONObject accessor = accessors.getJSONObject(accessorIndex); // Get the selected accessor.
        JSONObject bufferView = bufferViews.getJSONObject(accessor.getInt("bufferView")); // Get its buffer view.

        int count = accessor.getInt("count"); // Number of indices.
        int componentType = accessor.getInt("componentType"); // Index component type.

        int accessorOffset = accessor.optInt("byteOffset", 0); // Offset within the accessor.
        int viewOffset = bufferView.optInt("byteOffset", 0); // Offset within the buffer.
        int stride = bufferView.optInt("byteStride", componentSize(componentType)); // Byte stride for index reading.

        byte[] buffer = decodeBuffer(context, gltf, bufferView.getInt("buffer")); // Load binary buffer.
        int[] out = new int[count]; // Output array for indices.

        int p = viewOffset + accessorOffset; // Starting byte position.

        if (componentType == GL_UNSIGNED_SHORT) { // Read 16-bit unsigned indices.
            for (int i = 0; i < count; i++) { // Loop through all indices.
                int lo = buffer[p] & 0xff; // Low byte.
                int hi = buffer[p + 1] & 0xff; // High byte.
                out[i] = (hi << 8) | lo; // Combine into one unsigned short value.
                p += stride; // Move to next index.
            }
        } else if (componentType == GL_UNSIGNED_BYTE) { // Read 8-bit unsigned indices.
            for (int i = 0; i < count; i++) { // Loop through all indices.
                out[i] = buffer[p] & 0xff; // Read byte as unsigned value.
                p += stride; // Move to next index.
            }
        } else if (componentType == GL_UNSIGNED_INT) { // Read 32-bit unsigned indices.
            for (int i = 0; i < count; i++) { // Loop through all indices.
                long b0 = buffer[p] & 0xffL; // Byte 0.
                long b1 = (buffer[p + 1] & 0xffL) << 8; // Byte 1.
                long b2 = (buffer[p + 2] & 0xffL) << 16; // Byte 2.
                long b3 = (buffer[p + 3] & 0xffL) << 24; // Byte 3.
                out[i] = (int) (b0 | b1 | b2 | b3); // Combine into int.
                p += stride; // Move to next index.
            }
        } else { // Reject any unsupported index component type.
            throw new RuntimeException("Unsupported index component type: " + componentType); // Fail loudly.
        }

        return out; // Return the decoded index array.
    }

    private static short[] toShortIndices(int[] indices) { // Converts int indices into short indices.
        short[] out = new short[indices.length]; // Allocate short array.
        for (int i = 0; i < indices.length; i++) { // Loop through all values.
            out[i] = (short) indices[i]; // Cast each index to short.
        }
        return out; // Return converted indices.
    }

    private static float readComponentAsFloat(byte[] buffer, int offset, int componentType, boolean normalized) { // Reads a single component and returns it as float.
        if (componentType == GL_FLOAT) { // Read 32-bit float values.
            int bits =
                (buffer[offset] & 0xff) |
                ((buffer[offset + 1] & 0xff) << 8) |
                ((buffer[offset + 2] & 0xff) << 16) |
                ((buffer[offset + 3] & 0xff) << 24);
            return Float.intBitsToFloat(bits); // Reinterpret bits as float.
        } else if (componentType == GL_UNSIGNED_BYTE) { // Read unsigned byte values.
            int v = buffer[offset] & 0xff; // Convert byte to unsigned int.
            if (normalized) return v / 255f; // Normalize to 0..1 when required.
            return v; // Return raw numeric value.
        } else if (componentType == GL_UNSIGNED_SHORT) { // Read unsigned short values.
            int v = ((buffer[offset + 1] & 0xff) << 8) | (buffer[offset] & 0xff); // Assemble little-endian short.
            if (normalized) return v / 65535f; // Normalize to 0..1 when required.
            return v; // Return raw numeric value.
        } else if (componentType == GL_UNSIGNED_INT) { // Read unsigned int values.
            long v =
                (buffer[offset] & 0xffL) |
                ((buffer[offset + 1] & 0xffL) << 8) |
                ((buffer[offset + 2] & 0xffL) << 16) |
                ((buffer[offset + 3] & 0xffL) << 24);
            if (normalized) { // Normalize 32-bit unsigned integers if requested.
                return (float) (v / 4294967295.0); // Map to 0..1 range.
            }
            return (float) v; // Return raw numeric value as float.
        }

        return 0f; // Default fallback for unsupported component types.
    }

    private static int componentSize(int componentType) { // Returns the byte size for one component type.
        if (componentType == GL_FLOAT) return 4; // Float is 4 bytes.
        if (componentType == GL_UNSIGNED_INT) return 4; // Unsigned int is 4 bytes.
        if (componentType == GL_UNSIGNED_SHORT) return 2; // Unsigned short is 2 bytes.
        if (componentType == GL_UNSIGNED_BYTE) return 1; // Unsigned byte is 1 byte.
        return 4; // Default to 4 bytes if unknown.
    }

    private static byte[] decodeBuffer(Context context, JSONObject gltf, int bufferIndex) throws Exception { // Loads a glTF buffer either from data URI or asset file.
        JSONArray buffers = gltf.getJSONArray("buffers"); // Read buffer list.
        JSONObject buffer = buffers.getJSONObject(bufferIndex); // Get the selected buffer.
        String uri = buffer.getString("uri"); // Get the buffer URI.

        if (uri.startsWith("data:")) { // If buffer data is embedded directly in the JSON...
            int comma = uri.indexOf(','); // Find the base64 separator.
            if (comma < 0) { // Check for malformed data URI.
                throw new RuntimeException("Bad data URI in buffer " + bufferIndex); // Reject invalid embedded buffer.
            }
            String base64 = uri.substring(comma + 1); // Extract the base64 payload.
            return Base64.decode(base64, Base64.DEFAULT); // Decode payload to raw bytes.
        } else { // Otherwise the buffer is stored as an asset file.
            InputStream is = context.getAssets().open(uri); // Open the external buffer asset.
            ByteArrayOutputStream bos = new ByteArrayOutputStream(); // Collect bytes into memory.
            byte[] tmp = new byte[4096]; // Temporary chunk buffer.
            int len; // Number of bytes read.
            while ((len = is.read(tmp)) != -1) { // Read until the end of the stream.
                bos.write(tmp, 0, len); // Append chunk to output buffer.
            }
            is.close(); // Close the stream.
            return bos.toByteArray(); // Return the loaded bytes.
        }
    }

    private static int numComponents(String type) { // Returns how many components a glTF accessor element has.
        if ("SCALAR".equals(type)) return 1; // One component for scalar.
        if ("VEC2".equals(type)) return 2; // Two components for vec2.
        if ("VEC3".equals(type)) return 3; // Three components for vec3.
        if ("VEC4".equals(type)) return 4; // Four components for vec4.
        if ("MAT4".equals(type)) return 16; // Sixteen components for a 4x4 matrix.
        return 3; // Default to 3 components when type is unknown.
    }

    private static float[] makeZeroUVs(int vertexCount) { // Creates fallback UVs at the center of the texture.
        float[] uvs = new float[vertexCount * 2]; // Allocate UV array.
        for (int i = 0; i < vertexCount; i++) { // Loop through each vertex.
            uvs[i * 2] = 0.5f; // U coordinate.
            uvs[i * 2 + 1] = 0.5f; // V coordinate.
        }
        return uvs; // Return the generated UVs.
    }

    private static float[] makeFlatNormals(int vertexCount) { // Creates fallback normals pointing forward.
        float[] normals = new float[vertexCount * 3]; // Allocate normal array.
        for (int i = 0; i < vertexCount; i++) { // Loop through each vertex.
            normals[i * 3] = 0f; // Normal X.
            normals[i * 3 + 1] = 0f; // Normal Y.
            normals[i * 3 + 2] = 1f; // Normal Z.
        }
        return normals; // Return the generated normals.
    }

    private static void computeBounds(LoadedPrimitive[][] loadedMeshes, float[] outMinMax) { // Computes the overall bounding box of all loaded geometry.
        float minX = Float.MAX_VALUE; // Start with a huge minimum X.
        float minY = Float.MAX_VALUE; // Start with a huge minimum Y.
        float minZ = Float.MAX_VALUE; // Start with a huge minimum Z.
        float maxX = -Float.MAX_VALUE; // Start with a tiny maximum X.
        float maxY = -Float.MAX_VALUE; // Start with a tiny maximum Y.
        float maxZ = -Float.MAX_VALUE; // Start with a tiny maximum Z.

        for (int m = 0; m < loadedMeshes.length; m++) { // Loop through each mesh.
            LoadedPrimitive[] mesh = loadedMeshes[m]; // Get the primitive list for this mesh.
            for (int p = 0; p < mesh.length; p++) { // Loop through each primitive.
                float[] pos = mesh[p].positions; // Read the positions array.
                for (int i = 0; i < pos.length; i += 3) { // Walk through each vertex triplet.
                    float x = pos[i]; // Vertex X.
                    float y = pos[i + 1]; // Vertex Y.
                    float z = pos[i + 2]; // Vertex Z.

                    if (x < minX) minX = x; // Update minimum X.
                    if (y < minY) minY = y; // Update minimum Y.
                    if (z < minZ) minZ = z; // Update minimum Z.
                    if (x > maxX) maxX = x; // Update maximum X.
                    if (y > maxY) maxY = y; // Update maximum Y.
                    if (z > maxZ) maxZ = z; // Update maximum Z.
                }
            }
        }

        if (minX == Float.MAX_VALUE) { // If no geometry was found...
            minX = minY = minZ = -1f; // Use a default cube-ish range.
            maxX = maxY = maxZ = 1f; // Use a default cube-ish range.
        }

        outMinMax[0] = minX; // Store min X.
        outMinMax[1] = minY; // Store min Y.
        outMinMax[2] = minZ; // Store min Z.
        outMinMax[3] = maxX; // Store max X.
        outMinMax[4] = maxY; // Store max Y.
        outMinMax[5] = maxZ; // Store max Z.
    }

    private static float[] computeCenter(LoadedPrimitive[][] loadedMeshes) { // Computes the center of the model bounds.
        float[] b = new float[6]; // Temporary min-max bounds array.
        computeBounds(loadedMeshes, b); // Fill the bounds array.
        return new float[] { // Return the midpoint of the bounds.
            (b[0] + b[3]) * 0.5f, // Center X.
            (b[1] + b[4]) * 0.5f, // Center Y.
            (b[2] + b[5]) * 0.5f // Center Z.
        };
    }

    private static float computeFitScale(LoadedPrimitive[][] loadedMeshes) { // Computes a uniform scale that makes the model fit nicely.
        float[] b = new float[6]; // Temporary bounds array.
        computeBounds(loadedMeshes, b); // Fill it with model bounds.

        float dx = b[3] - b[0]; // Width of the model.
        float dy = b[4] - b[1]; // Height of the model.
        float dz = b[5] - b[2]; // Depth of the model.
        float maxDim = Math.max(dx, Math.max(dy, dz)); // Use the largest dimension.
        if (maxDim < 0.0001f) { // Prevent divide by zero and tiny numbers.
            maxDim = 1f; // Use safe default size.
        }

        return 0.8f / maxDim; // Scale to fit inside a comfortable view volume.
    }

    private static GltfModel.GltfNode[] parseNodes(Context context, JSONObject gltf) throws Exception { // Parses the glTF node hierarchy.
        if (!gltf.has("nodes")) { // If the file has no nodes...
            return new GltfModel.GltfNode[0]; // Return an empty node array.
        }

        JSONArray arr = gltf.getJSONArray("nodes"); // Read nodes array.
        GltfModel.GltfNode[] nodes = new GltfModel.GltfNode[arr.length()]; // Allocate final node array.

        for (int i = 0; i < arr.length(); i++) { // Loop through nodes.
            JSONObject obj = arr.getJSONObject(i); // Read current node object.
            GltfModel.GltfNode n = new GltfModel.GltfNode(); // Create a node.

            if (obj.has("mesh")) { // If the node references a mesh...
                n.mesh = obj.getInt("mesh"); // Store mesh index.
            }
            if (obj.has("skin")) { // If the node references a skin...
                n.skin = obj.getInt("skin"); // Store skin index.
            }
            if (obj.has("translation")) { // If translation exists...
                n.translation = readFloat3(obj.getJSONArray("translation")); // Read translation vector.
            }
            if (obj.has("rotation")) { // If rotation exists...
                n.rotation = readFloat4(obj.getJSONArray("rotation")); // Read rotation quaternion.
            }
            if (obj.has("scale")) { // If scale exists...
                n.scale = readFloat3(obj.getJSONArray("scale")); // Read scale vector.
            }
            if (obj.has("matrix")) { // If a full transform matrix exists...
                n.matrix = readFloat16(obj.getJSONArray("matrix")); // Read the 4x4 matrix.
            }
            if (obj.has("children")) { // If child node references exist...
                n.children = readIntArray(obj.getJSONArray("children")); // Read the child list.
            }

            n.resetAnimatedValues(); // Initialize animated values from static values.
            nodes[i] = n; // Store the node.
        }

        return nodes; // Return all parsed nodes.
    }

    private static GltfModel.GltfSkin[] parseSkins(Context context, JSONObject gltf) throws Exception { // Parses glTF skins and inverse bind matrices.
        if (!gltf.has("skins")) { // If no skins exist...
            return new GltfModel.GltfSkin[0]; // Return an empty skin array.
        }

        JSONArray arr = gltf.getJSONArray("skins"); // Read skins array.
        GltfModel.GltfSkin[] skins = new GltfModel.GltfSkin[arr.length()]; // Allocate final skin array.

        for (int i = 0; i < arr.length(); i++) { // Loop through skins.
            JSONObject obj = arr.getJSONObject(i); // Read current skin object.
            GltfModel.GltfSkin skin = new GltfModel.GltfSkin(); // Create skin holder.

            if (obj.has("joints")) { // If joints are listed...
                skin.joints = readIntArray(obj.getJSONArray("joints")); // Read joint node indices.
            }

            skin.skeleton = obj.optInt("skeleton", -1); // Read skeleton root if present.

            if (obj.has("inverseBindMatrices")) { // If inverse bind matrices are provided...
                skin.inverseBindMatrices = readAccessorAsFloats(context, gltf, obj.getInt("inverseBindMatrices"), false); // Read them as floats.
            } else { // Otherwise create identity inverse bind matrices.
                skin.inverseBindMatrices = new float[skin.joints.length * 16]; // Allocate matrix storage.
                for (int j = 0; j < skin.joints.length; j++) { // Loop through each joint.
                    int base = j * 16; // Matrix base offset.
                    for (int k = 0; k < 16; k++) skin.inverseBindMatrices[base + k] = 0f; // Clear the matrix.
                    skin.inverseBindMatrices[base + 0] = 1f; // Identity matrix element.
                    skin.inverseBindMatrices[base + 5] = 1f; // Identity matrix element.
                    skin.inverseBindMatrices[base + 10] = 1f; // Identity matrix element.
                    skin.inverseBindMatrices[base + 15] = 1f; // Identity matrix element.
                }
            }

            skins[i] = skin; // Store the parsed skin.
        }

        return skins; // Return all skins.
    }

    private static int[] parseSceneRoots(JSONObject gltf, GltfModel.GltfNode[] nodes) throws Exception { // Determines which nodes are scene roots.
        if (gltf.has("scenes")) { // If the glTF defines scenes...
            int sceneIndex = gltf.optInt("scene", 0); // Read active scene index.
            JSONArray scenes = gltf.getJSONArray("scenes"); // Read scene array.
            if (sceneIndex < 0 || sceneIndex >= scenes.length()) { // Validate scene index.
                sceneIndex = 0; // Fall back to first scene.
            }

            JSONObject scene = scenes.getJSONObject(sceneIndex); // Get the selected scene.
            if (scene.has("nodes")) { // If the scene lists root nodes...
                return readIntArray(scene.getJSONArray("nodes")); // Return those nodes.
            }
        }

        if (nodes == null || nodes.length == 0) { // If there are no nodes at all...
            return new int[0]; // Return empty roots list.
        }

        boolean[] child = new boolean[nodes.length]; // Track which nodes are children.
        for (int i = 0; i < nodes.length; i++) { // Walk all nodes.
            if (nodes[i].children != null) { // If the node has children...
                for (int c = 0; c < nodes[i].children.length; c++) { // Loop over children.
                    int ch = nodes[i].children[c]; // Child node index.
                    if (ch >= 0 && ch < child.length) { // Validate child index.
                        child[ch] = true; // Mark as child.
                    }
                }
            }
        }

        int count = 0; // Count root nodes.
        for (int i = 0; i < child.length; i++) { // Scan child markers.
            if (!child[i]) count++; // Count non-child nodes.
        }

        if (count == 0) { // If every node is a child or the graph is weird...
            return new int[] { 0 }; // Return node 0 as a fallback root.
        }

        int[] roots = new int[count]; // Allocate root list.
        int k = 0; // Write position in root list.
        for (int i = 0; i < child.length; i++) { // Walk through all nodes again.
            if (!child[i]) { // If node is not a child...
                roots[k++] = i; // Add it to root list.
            }
        }

        return roots; // Return all root nodes.
    }

    private static GltfModel.GltfAnimation[] parseAnimations(Context context, JSONObject gltf) throws Exception { // Parses animation samplers and channels.
        if (!gltf.has("animations")) { // If there are no animations...
            return new GltfModel.GltfAnimation[0]; // Return empty animation array.
        }

        JSONArray arr = gltf.getJSONArray("animations"); // Read animations array.
        GltfModel.GltfAnimation[] anims = new GltfModel.GltfAnimation[arr.length()]; // Allocate output array.

        for (int a = 0; a < arr.length(); a++) { // Loop through each animation.
            JSONObject animObj = arr.getJSONObject(a); // Read the current animation.
            GltfModel.GltfAnimation anim = new GltfModel.GltfAnimation(); // Create animation holder.

            if (animObj.has("samplers")) { // If samplers exist...
                JSONArray samplers = animObj.getJSONArray("samplers"); // Read sampler array.
                anim.samplers = new GltfModel.GltfSampler[samplers.length()]; // Allocate sampler list.

                for (int s = 0; s < samplers.length(); s++) { // Loop through samplers.
                    JSONObject samObj = samplers.getJSONObject(s); // Read sampler object.
                    GltfModel.GltfSampler sam = new GltfModel.GltfSampler(); // Create sampler holder.

                    sam.input = readAccessorAsFloats(context, gltf, samObj.getInt("input"), false); // Read keyframe times.
                    sam.output = readAccessorAsFloats(context, gltf, samObj.getInt("output"), false); // Read keyframe values.
                    sam.interpolation = samObj.optString("interpolation", "LINEAR"); // Read interpolation mode.

                    anim.samplers[s] = sam; // Store sampler.
                    if (sam.input.length > 0) { // If keyframe times exist...
                        float last = sam.input[sam.input.length - 1]; // Last keyframe time.
                        if (last > anim.duration) anim.duration = last; // Track longest animation duration.
                    }
                }
            }

            if (animObj.has("channels")) { // If channels exist...
                JSONArray channels = animObj.getJSONArray("channels"); // Read channel array.
                anim.channels = new GltfModel.GltfChannel[channels.length()]; // Allocate channel list.

                for (int c = 0; c < channels.length(); c++) { // Loop through channels.
                    JSONObject chObj = channels.getJSONObject(c); // Read channel object.
                    JSONObject target = chObj.getJSONObject("target"); // Read target object.

                    GltfModel.GltfChannel ch = new GltfModel.GltfChannel(); // Create channel holder.
                    ch.sampler = chObj.getInt("sampler"); // Link sampler index.
                    ch.targetNode = target.getInt("node"); // Target node index.
                    ch.path = target.getString("path"); // Target property path.

                    anim.channels[c] = ch; // Store channel.
                }
            }

            anims[a] = anim; // Store animation.
        }

        return anims; // Return parsed animations.
    }

    private static float[] readFloat3(JSONArray arr) throws Exception { // Reads a 3-element float vector.
        return new float[] { // Return the 3 components in order.
            (float) arr.getDouble(0), // X component.
            (float) arr.getDouble(1), // Y component.
            (float) arr.getDouble(2) // Z component.
        };
    }

    private static float[] readFloat4(JSONArray arr) throws Exception { // Reads a 4-element float vector.
        return new float[] { // Return the 4 components in order.
            (float) arr.getDouble(0), // First component.
            (float) arr.getDouble(1), // Second component.
            (float) arr.getDouble(2), // Third component.
            (float) arr.getDouble(3) // Fourth component.
        };
    }

    private static float[] readFloat16(JSONArray arr) throws Exception { // Reads a 4x4 matrix from a JSON array.
        float[] out = new float[16]; // Allocate 16 floats.
        for (int i = 0; i < 16; i++) { // Loop over all matrix entries.
            out[i] = (float) arr.getDouble(i); // Copy each value into the matrix array.
        }
        return out; // Return the matrix.
    }

    private static int[] readIntArray(JSONArray arr) throws Exception { // Reads a JSON integer array into a Java int array.
        int[] out = new int[arr.length()]; // Allocate output array.
        for (int i = 0; i < arr.length(); i++) { // Loop through elements.
            out[i] = arr.getInt(i); // Copy each integer.
        }
        return out; // Return the integer array.
    }

    private static Bitmap findFirstTexture(GltfModel model) { // Finds the first texture bitmap stored in the model.
        if (model.meshes == null) return null; // If there are no meshes, there is no texture.
        for (int m = 0; m < model.meshes.length; m++) { // Loop through meshes.
            GltfModel.GltfMesh mesh = model.meshes[m]; // Get the current mesh.
            if (mesh == null || mesh.primitives == null) continue; // Skip missing entries.
            for (int p = 0; p < mesh.primitives.length; p++) { // Loop through primitives.
                if (mesh.primitives[p] != null && mesh.primitives[p].textureBitmap != null) { // Return first non-null texture.
                    return mesh.primitives[p].textureBitmap; // Found one, return it.
                }
            }
        }
        return null; // No textures available.
    }

    private static GltfModel createFallback(String assetName, Exception e) { // Creates a simple fallback cube when loading fails.
        GltfModel m = new GltfModel(); // Create a blank model.

        float[] positions = new float[] { // Cube positions.
            -1f, -1f,  1f,   1f, -1f,  1f,   1f,  1f,  1f,
            -1f, -1f,  1f,   1f,  1f,  1f,  -1f,  1f,  1f,

            -1f, -1f, -1f,  -1f,  1f, -1f,   1f,  1f, -1f,
            -1f, -1f, -1f,   1f,  1f, -1f,   1f, -1f, -1f,

            -1f,  1f, -1f,  -1f,  1f,  1f,   1f,  1f,  1f,
            -1f,  1f, -1f,   1f,  1f,  1f,   1f,  1f, -1f,

            -1f, -1f, -1f,   1f, -1f, -1f,   1f, -1f,  1f,
            -1f, -1f, -1f,   1f, -1f,  1f,  -1f, -1f,  1f,

            1f, -1f, -1f,   1f,  1f, -1f,   1f,  1f,  1f,
            1f, -1f, -1f,   1f,  1f,  1f,   1f, -1f,  1f,

            -1f, -1f, -1f,  -1f, -1f,  1f,  -1f,  1f,  1f,
            -1f, -1f, -1f,  -1f,  1f,  1f,  -1f,  1f, -1f
        };

        float[] normals = makeFlatNormals(positions.length / 3); // Generate fallback normals.
        float[] uvs = makeZeroUVs(positions.length / 3); // Generate fallback UVs.
        short[] indices = new short[] { // Cube triangle indices.
            0, 1, 2, 0, 2, 3,
            4, 5, 6, 4, 6, 7,
            8, 9, 10, 8, 10, 11,
            12, 13, 14, 12, 14, 15,
            16, 17, 18, 16, 18, 19,
            20, 21, 22, 20, 22, 23
        };

        GltfModel.GltfPrimitive prim = new GltfModel.GltfPrimitive(); // Create a single fallback primitive.
        prim.positionBuffer = BufferUtil.toFloatBuffer(positions); // Upload positions to buffer form.
        prim.normalBuffer = BufferUtil.toFloatBuffer(normals); // Upload normals to buffer form.
        prim.uvBuffer = BufferUtil.toFloatBuffer(uvs); // Upload UVs to buffer form.
        prim.indexBuffer = BufferUtil.toShortBuffer(indices); // Upload indices to buffer form.
        prim.indexCount = indices.length; // Store index count.
        prim.indexComponentType = GL_UNSIGNED_SHORT; // Use unsigned short indices.
        prim.textureBitmap = TextureUtil.makeCheckerBitmap(); // Use checker texture as fallback.
        prim.useDrawArrays = false; // Fallback cube uses indexed drawing.
        prim.vertexCount = 0; // Vertex count is not used here.

        GltfModel.GltfMesh mesh = new GltfModel.GltfMesh(); // Create fallback mesh.
        mesh.primitives = new GltfModel.GltfPrimitive[] { prim }; // Put the primitive in the mesh.

        m.meshes = new GltfModel.GltfMesh[] { mesh }; // Store the mesh in the model.

        GltfModel.GltfNode node = new GltfModel.GltfNode(); // Create a single root node.
        node.mesh = 0; // Attach the only mesh.
        node.resetAnimatedValues(); // Initialize animated values.

        m.nodes = new GltfModel.GltfNode[] { node }; // Store the single node.
        m.sceneRoots = new int[] { 0 }; // Make node 0 the scene root.
        m.skins = new GltfModel.GltfSkin[0]; // No skins in fallback.
        m.animations = new GltfModel.GltfAnimation[0]; // No animations in fallback.
        m.fitScale = 1f; // Use neutral scale.
        m.centerX = 0f; // Center at origin.
        m.centerY = 0f; // Center at origin.
        m.centerZ = 0f; // Center at origin.
        m.statusText = // Build failure status text.
            "PATH: assets/" + assetName + "\n" +
            "LOAD FAILED\n" +
            "ERROR: " + e.getClass().getSimpleName() + "\n" +
            "MESSAGE: " + String.valueOf(e.getMessage()) + "\n" +
            "USING FALLBACK CUBE\n" +
            "TEXTURE: CHECKER";

        return m; // Return the fallback model.
    }
}

