/*
 * Copyright 2015 Howard Hughes Medical Institute.
 * All rights reserved.
 * Use is subject to Janelia Farm Research Campus Software Copyright 1.1
 * license terms ( http://license.janelia.org/license/jfrc_copyright_1_1.html ).
 */
package org.janelia.it.jacs.shared.ffmpeg;

import org.bytedeco.javacpp.BytePointer;

import java.util.ArrayList;

import static org.bytedeco.javacpp.avutil.*;

/**
 * @author bostadm@janelia.hhmi.org
 */
public class Frame {
    public boolean keyFrame;
    //public AVFrame image;
    //public Object opaque;
    public AVFrame picture = null, picture_rgb = null;
    public ArrayList<byte[]> imageBytes = new ArrayList<byte[]>();

    public void release() throws Exception {
        // Free the RGB image
        if (picture_rgb != null) {
            av_frame_free(picture_rgb);
            picture_rgb = null;
        }

        // Free the native format picture frame
        if (picture != null) {
        	av_frame_free(picture);
            picture = null;
        }

        //image = null;
        //opaque = null;
        imageBytes.clear();
    }

}

