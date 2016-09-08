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
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * This will pull H5j data into Fiji's required internal format.
 *
 * @author fosterl
 */
public class FijiAdapter {
    public static final int POOL_TIMEOUT_IN_SECONDS = 1200;    
    public static final int STD_THREAD_POOL_SIZE = 8;
    
    private static final boolean LOG_OK = false;

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
        h5jImageStack.release();
		return rtnVal;
	}

	public ImagePlus getMultiChannelImagePlus(File inputFile) throws Exception {
		// Need here, to pull the values IN from the input file, per H5J
		// read mechanism.
		final H5JLoader loader = new H5JLoader(inputFile.getAbsolutePath());
		FileInfo fileInfo = null;
		ImagePlus rtnVal = null;

		// Iterate over all channels.
		int channelNum = 1;  // Output channels are 1-based.
		int channelCount = loader.channelNames().size();
		final double max[] = new double[channelCount]; // track maximum intensity in each channel for display calibration
		for (String channelName : loader.channelNames()) {
            if (!Interpreter.isBatchMode()) {
                IJ.showProgress(channelNum, channelCount);
            }
            max[channelNum - 1] = -Double.MAX_VALUE;
			final org.janelia.it.jacs.shared.ffmpeg.ImageStack h5jImageStack = loader.extract(channelName);
			// Scoop whole-product data from the first channel.
			if (rtnVal == null || fileInfo == null) {
				fileInfo = createFileInfo(inputFile, h5jImageStack);
				if (!Interpreter.isBatchMode()  &&  LOG_OK) {
					IJ.log("Padded width=" + fileInfo.width + ", width padding=" + h5jImageStack.getPaddingRight() + ", padded height=" + fileInfo.height + ", height padding=" + h5jImageStack.getPaddingBottom());
				}

				int bytesPerPixel = h5jImageStack.getBytesPerPixel() / channelCount;  // Adjusting
                // Assume exactly 1, if the value is not given.
                if (bytesPerPixel == 0) {
					if (!Interpreter.isBatchMode()  &&  LOG_OK) {
	                    IJ.log("No bytes-per-pixel value available.  Assuming 1 byte/pixel.");
					}
                    bytesPerPixel = 1;
                }
				if (bytesPerPixel != 1) {
					throw new Exception("Unexpected value for bytes-per-pixel: " + bytesPerPixel + ", only value of 1 acceptable.");
				}
				rtnVal = NewImage.createImage(
						inputFile.getName(),              //Name
						fileInfo.width - h5jImageStack.getPaddingRight(),
                                                          //Width
						fileInfo.height - h5jImageStack.getPaddingBottom(),
                                                          //Height
						channelCount * fileInfo.nImages,  //nSlices
						8 * bytesPerPixel,                //BitDepth
                        NewImage.FILL_BLACK               //Options
                );

				rtnVal = new CompositeImage(rtnVal, CompositeImage.COMPOSITE);
                rtnVal.setDimensions(channelCount, fileInfo.nImages, 1);
				if (!Interpreter.isBatchMode()  &&  LOG_OK) {
					IJ.log("Setting dimensions: channelCount=" + channelCount + ", n-Images=" + fileInfo.nImages);
				}
				rtnVal.setOpenAsHyperStack(true);
            }

            final Map<BPKey, ByteProcessor> byteProcessors =
                    Collections.synchronizedMap(new HashMap<BPKey, ByteProcessor>());
			// Iterate over all frames in the input.
            ExecutorService buildBPPool = Executors.newFixedThreadPool(STD_THREAD_POOL_SIZE);
            final ExecutorService applyBPPool = Executors.newFixedThreadPool(1);
			for (int i = 0; i < fileInfo.nImages; i++) {
                final int finalChannelNum = channelNum;
                final int finalI = i;
                final FileInfo finalFileInfo = fileInfo;
                final ImagePlus finalRtnVal = rtnVal;
                buildBPPool.submit(new Runnable() {
                    public void run() {
                        final BPKey key = addByteProcessor(finalChannelNum, finalI, h5jImageStack, finalFileInfo, max, byteProcessors);                        
                        applyBPPool.submit(new Runnable() {
                            public void run() {
                                applyProcessorToStack(byteProcessors, key, finalRtnVal, finalChannelNum);
                            }
                        });
                    }
                });
            }
            buildBPPool.shutdown();
            buildBPPool.awaitTermination(POOL_TIMEOUT_IN_SECONDS, TimeUnit.SECONDS);
            applyBPPool.shutdown();
            applyBPPool.awaitTermination(POOL_TIMEOUT_IN_SECONDS, TimeUnit.SECONDS);

			if (!Interpreter.isBatchMode()  &&  LOG_OK) {
	            IJ.log("ByteProcessor executor service completed for channel " + channelName);
			}		
            
			channelNum++;
            h5jImageStack.release();
		}

        if (!Interpreter.isBatchMode()  &&  LOG_OK) {
	        IJ.log("Width=" + fileInfo.width + ", height=" + fileInfo.height + ", nChannels=" + channelCount + ", nSlices=" + fileInfo.nImages);
            IJ.showStatus("Volume load complete.");
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

    private void applyProcessorToStack(final Map<BPKey, ByteProcessor> byteProcessors, BPKey key, ImagePlus rtnVal, int channelNum) {
        ByteProcessor cp = byteProcessors.get(key);
        int i = key.getZ();
        rtnVal.setC(channelNum);
        rtnVal.setZ(i + 1);
        rtnVal.setProcessor(cp);
    }

    private BPKey addByteProcessor(int channelNum, int i, ImageStack h5jImageStack, FileInfo fileInfo, double[] max, Map<BPKey, ByteProcessor> byteProcessors) {
        BPKey key = new BPKey();
        key.setChannelNumber(channelNum);
        key.setZ(i);
        ByteProcessor cp = createByteProcessor(key, h5jImageStack, fileInfo, max);
        byteProcessors.put(key, cp);
        return key;
    }

    private ByteProcessor createByteProcessor(
            BPKey key,
            ImageStack h5jImageStack, 
            final FileInfo fileInfo, 
            double[] max
    ) {
        int z = key.getZ();
        Frame frame = h5jImageStack.frame(z);
        final byte[] nextBytes = frame.imageBytes.get(0);
        final int unpaddedWidth = fileInfo.width - h5jImageStack.getPaddingRight();
        final int unpaddedHeight = fileInfo.height - h5jImageStack.getPaddingBottom();
        byte[] outputBytes = null;
        if (h5jImageStack.getPaddingRight() == 0  &&  h5jImageStack.getPaddingBottom() == 0) {
            outputBytes = nextBytes;
        }
        else {
            ExecutorService copyPool = Executors.newFixedThreadPool(STD_THREAD_POOL_SIZE);
            final byte[] targetBytes = new byte[unpaddedWidth * unpaddedHeight];
            outputBytes = targetBytes; 
            for (int i = 0; i < unpaddedHeight; i++) {
                final int finalI = i;
                Runnable submissible = new Runnable() {
                    public void run() {
                        int srcPos = (finalI * fileInfo.width);
                        int destPos = (finalI * unpaddedWidth);
                        System.arraycopy(nextBytes, srcPos, targetBytes, destPos, unpaddedWidth);
                    }
                };
                copyPool.submit(submissible);
            }
            copyPool.shutdown();
            try {
                copyPool.awaitTermination(POOL_TIMEOUT_IN_SECONDS, TimeUnit.SECONDS);
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        }
        ByteProcessor cp = new ByteProcessor(unpaddedWidth, unpaddedHeight);
        
        cp.setPixels(outputBytes);
        cp.resetMinAndMax();
        if (cp.getMax() > max[key.getChannelNumber() - 1]) {
            max[key.getChannelNumber() - 1] = cp.getMax();
        }
        return cp;
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
		rtnVal.intelByteOrder = true;
		rtnVal.offset = 0;
		rtnVal.longOffset = rtnVal.offset;
        rtnVal.pixelDepth = 8;
		return rtnVal;
	}
    
    private static class BPKey {
        private int channelNumber;
        private int z;

        public boolean equals(Object other) {
            if (other == null || ! (other instanceof BPKey)) {
                return false;
            }
            else {
                BPKey otherKey = (BPKey)other;
                return otherKey.getChannelNumber() == getChannelNumber() && otherKey.getZ() == getZ();
            }
        }
        
        public int hashCode() {
            return (z << 8) & channelNumber;
        }
        
        /**
         * @return the channelNumber
         */
        public int getChannelNumber() {
            return channelNumber;
        }

        /**
         * @param channelNumber the channelNumber to set
         */
        public void setChannelNumber(int channelNumber) {
            this.channelNumber = channelNumber;
        }

        /**
         * @return the z
         */
        public int getZ() {
            return z;
        }

        /**
         * @param z the z to set
         */
        public void setZ(int z) {
            this.z = z;
        }
    }

}
