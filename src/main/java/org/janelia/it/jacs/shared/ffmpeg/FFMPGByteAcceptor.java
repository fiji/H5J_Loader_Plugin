/*
 * Copyright 2010 Howard Hughes Medical Institute.
 * All rights reserved.
 * Use is subject to Janelia Farm Research Campus Software Copyright 1.1
 * license terms ( http://license.janelia.org/license/jfrc_copyright_1_1.html ).
 */
package org.janelia.it.jacs.shared.ffmpeg;

import org.bytedeco.javacpp.BytePointer;

/**
 * Implement this to accept bytes coming out of FFMpegLoader
 * @author fosterl
 */
public interface FFMPGByteAcceptor {
    void accept(BytePointer data, int linesize, int width, int height);
    void accept(byte[] data, int linesize, int width, int height);
    void setFrameNum(int frameNum);
    void setPixelBytes(int pixelBytes);
}
