/*
 * Copyright 2015 Howard Hughes Medical Institute.
 * All rights reserved.
 * Use is subject to Janelia Farm Research Campus Software Copyright 1.1
 * license terms ( http://license.janelia.org/license/jfrc_copyright_1_1.html ).
 */
package org.janelia.it.h5j.fiji.adapter;

import ij.CompositeImage;
import ij.IJ;
import ij.ImagePlus;
import ij.gui.NewImage;
import ij.io.FileInfo;
import ij.macro.Interpreter;
import ij.measure.Calibration;
import ij.process.ByteProcessor;
import org.janelia.it.jacs.shared.ffmpeg.*;
import java.io.File;

/**
 * This will pull H5j data into Fiji's required internal format.
 *
 * @author fosterl
 */
public class FijiAdapter {

	public ImagePlus getImagePlus(File inputFile) throws Exception {
		Calibration calibration = createCalibration();

		// Need here, to pull the values IN from the input file, per H5J
		// read mechanism.
		H5JLoader loader = new H5JLoader(inputFile.getAbsolutePath());
		org.janelia.it.jacs.shared.ffmpeg.ImageStack h5jImageStack
				= loader.extractAllChannels();

		FileInfo fileInfo = createFileInfo(inputFile, h5jImageStack);
		ImagePlus rtnVal = IJ.createImage(inputFile.getName(), "RGB black", fileInfo.width, fileInfo.height, fileInfo.nImages);

		// Iterate over all frames.
		for (int i = 0; i < fileInfo.nImages; i++) {
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

	public ImagePlus getMultiChannelImagePlus(File inputFile) throws Exception {
		// Need here, to pull the values IN from the input file, per H5J
		// read mechanism.
		H5JLoader loader = new H5JLoader(inputFile.getAbsolutePath());
		FileInfo fileInfo = null;
		ImagePlus rtnVal = null;

		// Iterate over all channels.
		int channelNum = 1;  // Output channels are 1-based.
		int channelCount = loader.channelNames().size();
		double max[] = new double[channelCount]; // track maximum intensity in each channel for display calibration
		for (String channelName : loader.channelNames()) {
            max[channelNum - 1] = -Double.MAX_VALUE;
			org.janelia.it.jacs.shared.ffmpeg.ImageStack h5jImageStack = loader.extract(channelName);
			// Scoop whole-product data from the first channel.
			if (rtnVal == null || fileInfo == null) {
				fileInfo = createFileInfo(inputFile, h5jImageStack);

				int bytesPerPixel = h5jImageStack.getBytesPerPixel() / channelCount;  // Adjusting
				if (bytesPerPixel != 1) {
					throw new Exception("Unexpected value for bytes-per-pixel: " + bytesPerPixel + ", only value of 1 acceptable.");
				}
				rtnVal = NewImage.createImage(
						inputFile.getName(),              //Name
						fileInfo.width,                   //Width
						fileInfo.height,                  //Height
						channelCount * fileInfo.nImages,  //nSlices
						8 * bytesPerPixel,                //BitDepth
                        NewImage.FILL_BLACK               //Options
                );

				rtnVal = new CompositeImage(rtnVal, CompositeImage.COMPOSITE);
                rtnVal.setDimensions(channelCount, fileInfo.nImages, 1);
                IJ.log("Setting dimensions: channelCount=" + channelCount + ", n-Images=" + fileInfo.nImages);
				rtnVal.setOpenAsHyperStack(true);
                if (!Interpreter.isBatchMode()) {
                    IJ.showStatus("Loading volume...");
                }
            }
            rtnVal.setC(channelNum);

			// Iterate over all frames in the input.
			for (int i = 0; i < fileInfo.nImages; i++) {
                if (!Interpreter.isBatchMode()) {
                    IJ.showProgress(channelNum * fileInfo.nImages + i, channelCount * fileInfo.nImages);
                }
                Frame frame = h5jImageStack.frame(i);

				byte[] nextBytes = frame.imageBytes.get(0);
				
				rtnVal.setZ(i + 1);
                ByteProcessor cp = new ByteProcessor(fileInfo.width, fileInfo.height);
                cp.setPixels(nextBytes);
                rtnVal.setProcessor(cp);

                cp.resetMinAndMax();
                if (cp.getMax() > max[channelNum - 1]) {
                    max[channelNum - 1] = cp.getMax();
                }
			}
			channelNum++;
		}
        IJ.log("Width=" + fileInfo.width + ", height=" + fileInfo.height + ", nChannels=" + channelCount + ", nSlices=" + fileInfo.nImages);

        if (!Interpreter.isBatchMode()) {
            IJ.showStatus("Volume load complete.");
        }
        if (!Interpreter.isBatchMode()) {
            IJ.showProgress(1.0);
        }
        
        if (rtnVal != null) {
            final Calibration calibration = new Calibration(rtnVal);
            rtnVal.setCalibration(calibration);
            calibration.fps = 20;
			// Adjust display range for each channel
			for (int c = 0; c < rtnVal.getNChannels(); ++c) {
				rtnVal.setC(c + 1);
				if (max[c] > 0) {
					rtnVal.setDisplayRange(0, max[c]);
					continue;
				}
				// I guess measuring max failed.
				if (rtnVal.getBitDepth() > 8) {
					rtnVal.setDisplayRange(0, 4095);
				} else {
					rtnVal.setDisplayRange(0, 255);
				}
			}
			rtnVal.setC(1);
			rtnVal.setZ(1);
		}
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
		calibration.pixelDepth = 8;
		calibration.pixelHeight = 1;
		calibration.pixelWidth = 1;
		return calibration;
	}

	/**
	 * Creating a file info, based on characteristics of the H5J input file.
	 *
	 * @param inputFile what to read from.
	 * @param h5jImageStack for dimensions.
	 * @return full config for file info.
	 */
	private FileInfo createFileInfo(File inputFile, org.janelia.it.jacs.shared.ffmpeg.ImageStack h5jImageStack) {
		FileInfo rtnVal = new FileInfo();
		rtnVal.fileName = inputFile.getName();
        rtnVal.directory = inputFile.getParentFile().getAbsolutePath();
		rtnVal.gapBetweenImages = 0;
		rtnVal.whiteIsZero = false;
		rtnVal.width = h5jImageStack.width();
		rtnVal.height = h5jImageStack.height();
		rtnVal.nImages = h5jImageStack.getNumFrames();
        IJ.log("FileInfo: n-images=" + rtnVal.nImages);
		rtnVal.intelByteOrder = true;
		rtnVal.offset = 0;
		rtnVal.longOffset = rtnVal.offset;
        rtnVal.pixelDepth = 8;
		return rtnVal;
	}

}
