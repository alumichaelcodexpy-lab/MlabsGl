package com.mycompany.myapp; // Declares the package this class belongs to, helping organize the project.

/* Imports ByteBuffer, which is a low-level byte container used for direct memory buffers. */
import java.nio.ByteBuffer;
/* Imports ByteOrder, which lets us choose the platform's native byte ordering. */
import java.nio.ByteOrder;
/* Imports FloatBuffer, which stores floating-point data for OpenGL attributes. */
import java.nio.FloatBuffer;
/* Imports ShortBuffer, which stores short integer data such as mesh indices. */
import java.nio.ShortBuffer;

/* Utility class for converting normal Java arrays into NIO buffers that OpenGL can use. */
public class BufferUtil {

    /* Converts a float array into a direct FloatBuffer for OpenGL consumption. */
    public static FloatBuffer toFloatBuffer(float[] data) {
        /* Allocates direct native memory sized for all floats, 4 bytes each. */
        ByteBuffer bb = ByteBuffer.allocateDirect(data.length * 4);
        /* Sets the buffer to the device's native byte order for correct reading. */
        bb.order(ByteOrder.nativeOrder());
        /* Views the ByteBuffer as a FloatBuffer so we can put float values into it. */
        FloatBuffer fb = bb.asFloatBuffer();
        /* Copies the float array into the buffer. */
        fb.put(data);
        /* Resets position back to the start so OpenGL reads from the beginning. */
        fb.position(0);
        /* Returns the ready-to-use FloatBuffer. */
        return fb;
    }

    /* Converts a short array into a direct ShortBuffer for index data. */
    public static ShortBuffer toShortBuffer(short[] data) {
        /* Allocates direct native memory sized for all shorts, 2 bytes each. */
        ByteBuffer bb = ByteBuffer.allocateDirect(data.length * 2);
        /* Sets the byte order to the native order for safe cross-device use. */
        bb.order(ByteOrder.nativeOrder());
        /* Converts the ByteBuffer into a ShortBuffer view. */
        ShortBuffer sb = bb.asShortBuffer();
        /* Copies the short array into the buffer. */
        sb.put(data);
        /* Moves the read pointer back to the start. */
        sb.position(0);
        /* Returns the ready-to-use ShortBuffer. */
        return sb;
    }
}
