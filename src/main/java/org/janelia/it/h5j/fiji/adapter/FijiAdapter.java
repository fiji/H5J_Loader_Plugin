/*
 * Copyright 2010 Howard Hughes Medical Institute.
 * All rights reserved.
 * Use is subject to Janelia Farm Research Campus Software Copyright 1.1
 * license terms ( http://license.janelia.org/license/jfrc_copyright_1_1.html ).
 */

package org.janelia.it.h5j.fiji.adapter;

import ij.IJ;
import ij.ImagePlus;
import ij.io.FileInfo;
import ij.measure.Calibration;
import org.janelia.it.jacs.shared.ffmpeg.*;
import java.io.File;

/**
 * This will pull H5j data into Fiji's required internal format.
 *
 * @author fosterl
 */
public class FijiAdapter {
    public ImagePlus getImagePlus( File inputFile ) throws Exception {
        FileInfo fileInfo = createFileInfo( inputFile );
        Calibration calibration = createCalibration();
        
        // Need here, to pull the values IN from the input file, per H5J
        // read mechanism.
        H5JLoader loader = new H5JLoader(inputFile.getAbsolutePath());
        org.janelia.it.jacs.shared.ffmpeg.ImageStack h5jImageStack =
                loader.extractAllChannels();
        
        int width = h5jImageStack.width();
        int height = h5jImageStack.height();
        int depth = h5jImageStack.getNumFrames();                
        
        ImagePlus rtnVal = IJ.createImage(inputFile.getName(), "RGB black", width, height, depth);        
        
        // Iterate over all frames.
        for (int i = 0; i < depth; i++) {
            Frame frame = h5jImageStack.frame(i);
            int channelCount = frame.imageBytes.size();
            
            int[] packagedInts = new int[ frame.imageBytes.get(0).length ];
            int channelNum = 0;
            for (byte[] nextBytes: frame.imageBytes) {
                int shifter = (channelCount - channelNum - 1) * 8;
                for (int j = 0; j < nextBytes.length; j++) {
                    int nextInt = intValue(nextBytes, j) << shifter;
                    packagedInts[ j ] += nextInt;
                }
                channelNum ++;
            }

            rtnVal.setSlice(i);
            rtnVal.getChannelProcessor().setPixels(packagedInts);
            
        }
        rtnVal.setFileInfo(fileInfo);
        rtnVal.setCalibration(calibration);
        return rtnVal;
    }

    protected int intValue(byte[] packagedBytes, int j) {
        return packagedBytes[j] < 0 ? 256 + packagedBytes[j] : packagedBytes[j];
    }
    
    private Calibration createCalibration() {
        Calibration calibration = new Calibration();
        calibration.xOrigin = 0;
        calibration.yOrigin = 0;
        calibration.zOrigin = 0;
        calibration.loop = false;
        calibration.fps = 20;

        // Here are assumed values.  If this information is available,
        // settings should be adjusted here.
        calibration.pixelDepth = 1;
        calibration.pixelHeight = 1;
        calibration.pixelWidth = 1;
        return calibration;
    }
    
    /**
     * Creating a file info, based on characteristics of the H5J input file.
     * 
     * @param inputFile what to read from.
     * @return full config for file info.
     */
    private FileInfo createFileInfo(File inputFile) {
        FileInfo rtnVal = new FileInfo();
        rtnVal.fileName = inputFile.getName();
        rtnVal.gapBetweenImages = 0;
        rtnVal.whiteIsZero = false;
        rtnVal.width = 0;
        rtnVal.height = 0;
        rtnVal.nImages = 0;
        rtnVal.intelByteOrder = true;
        rtnVal.offset = 0;
        rtnVal.longOffset = rtnVal.offset;
        return rtnVal;
    }    
    
}
