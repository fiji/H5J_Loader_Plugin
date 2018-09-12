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
import ij.process.ImageProcessor;
import ij.process.ShortProcessor;

import org.janelia.it.jacs.shared.ffmpeg.*;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
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
		H5JLoader loader = new H5JLoader(inputFile.getAbsolutePath());
		FileInfo fileInfo = null;
		ImagePlus rtnVal = null;

		// Iterate over all channels.
		int channelNum = 1;  // Output channels are 1-based.
		int channelCount = loader.channelNames().size();
		final double max[] = new double[channelCount]; // track maximum intensity in each channel for display calibration
		int bytesPerPixel = 1;
		double[] spc = null;
		String unit = "";
		for (String channelName : loader.channelNames()) {
			//IJ.log(channelName);
            //if (!Interpreter.isBatchMode()) {
            //    IJ.showProgress(channelNum, channelCount);
            //}
            max[channelNum - 1] = -Double.MAX_VALUE;
			final org.janelia.it.jacs.shared.ffmpeg.ImageStack h5jImageStack = loader.extract(channelName, channelNum-1);
			// Scoop whole-product data from the first channel.
			if (rtnVal == null || fileInfo == null) {
				fileInfo = createFileInfo(inputFile, h5jImageStack);
				if (LOG_OK) {
					System.out.println("Padded width=" + fileInfo.width + ", width padding=" + h5jImageStack.getPaddingRight() + ", padded height=" + fileInfo.height + ", height padding=" + h5jImageStack.getPaddingBottom());
				}

				bytesPerPixel = h5jImageStack.getBytesPerPixel();  // Adjusting
				//IJ.log("bytesPerPixel: "+bytesPerPixel);
				//IJ.log("channelCount: "+channelCount);
                // Assume exactly 1, if the value is not given.
                if (bytesPerPixel == 0) {
					if (LOG_OK) {
	                    IJ.log("No bytes-per-pixel value available.  Assuming 1 byte/pixel.");
					}
                    bytesPerPixel = 1;
                }
				if (bytesPerPixel != 1 && bytesPerPixel != 2) {
					throw new Exception("Unexpected value for bytes-per-pixel: " + bytesPerPixel + ", value of 1 or 2 acceptable.");
				}
				
				spc = h5jImageStack.getSpacings();
				unit = h5jImageStack.getUnit();
				if (unit.isEmpty()) unit = "pixels";

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
				
				if (!Interpreter.isBatchMode()) {
					IJ.showStatus("Loading H5J...");
					IJ.showProgress(channelNum, channelCount);
				}

				rtnVal = new CompositeImage(rtnVal, CompositeImage.COMPOSITE);
                rtnVal.setDimensions(channelCount, fileInfo.nImages, 1);
				if (LOG_OK) {
					System.out.println("Setting dimensions: channelCount=" + channelCount + ", n-Images=" + fileInfo.nImages);
				}
				rtnVal.setOpenAsHyperStack(true);
				
            }
			
            final Map<IPKey, ImageProcessor> imageProcessors =
                    Collections.synchronizedMap(new HashMap<IPKey, ImageProcessor>());
			// Iterate over all frames in the input.
            ExecutorService buildBPPool = Executors.newFixedThreadPool(STD_THREAD_POOL_SIZE);
            final ExecutorService applyBPPool = Executors.newFixedThreadPool(1);
			for (int i = 0; i < fileInfo.nImages; i++) {
                final int finalChannelNum = channelNum;
                final int finalI = i;
                final FileInfo finalFileInfo = fileInfo;
                final ImagePlus finalRtnVal = rtnVal;
                if (bytesPerPixel == 1) {
                	buildBPPool.submit(new Runnable() {
                        public void run() {
                            final IPKey key = addByteProcessor(finalChannelNum, finalI, h5jImageStack, finalFileInfo, max, imageProcessors);                        
                            applyBPPool.submit(new Runnable() {
                                public void run() {
                                    applyProcessorToStack(imageProcessors, key, finalRtnVal, finalChannelNum);
                                }
                            });
                        }
                    });
                } else if (bytesPerPixel == 2) {
                	buildBPPool.submit(new Runnable() {
                        public void run() {
                            final IPKey key = addShortProcessor(finalChannelNum, finalI, h5jImageStack, finalFileInfo, max, imageProcessors);                        
                            applyBPPool.submit(new Runnable() {
                                public void run() {
                                    applyProcessorToStack(imageProcessors, key, finalRtnVal, finalChannelNum);
                                }
                            });
                        }
                    });
                }
            }
            buildBPPool.shutdown();
            buildBPPool.awaitTermination(POOL_TIMEOUT_IN_SECONDS, TimeUnit.SECONDS);
            applyBPPool.shutdown();
            applyBPPool.awaitTermination(POOL_TIMEOUT_IN_SECONDS, TimeUnit.SECONDS);

			if (LOG_OK) {
				System.out.println("ByteProcessor executor service completed for channel " + channelName);
			}		
            
			channelNum++;
            h5jImageStack.release();
		}

        if (LOG_OK) {
        	System.out.println("Width=" + fileInfo.width + ", height=" + fileInfo.height + ", nChannels=" + channelCount + ", nSlices=" + fileInfo.nImages);
        	System.out.println("Volume load complete.");
        }
        
        if (rtnVal != null) {
            final Calibration calibration = new Calibration(rtnVal);
            if (LOG_OK) System.out.println("Setting calibration...");
            calibration.fps = 20;
            if (LOG_OK) System.out.println("FPS: " + calibration.fps);
            if (spc != null) {
            	if (LOG_OK) System.out.println("Setting properties...");
            	calibration.pixelWidth = spc[0];
            	calibration.pixelHeight = spc[1];
            	calibration.pixelDepth = spc[2];
            	calibration.setUnit(unit);
            	if (LOG_OK) System.out.println("unit="+unit+" pixel_width="+spc[0]+" pixel_height="+spc[1]+" voxel_depth="+spc[2]);
            }
            rtnVal.setCalibration(calibration);
			// Adjust display range for each channel
            if (LOG_OK) System.out.println("Setting display range...");
			for (int c = 0; c < rtnVal.getNChannels(); ++c) {
				rtnVal.setC(c + 1);
				if (max[c] > 0) {
					rtnVal.setDisplayRange(0, max[c]);
					if (LOG_OK) System.out.println("Ch."+c+" min=0 max="+max[c]);
					continue;
				}
				// I guess measuring max failed.
				if (rtnVal.getBitDepth() > 8) {
					rtnVal.setDisplayRange(0, 4095);
					if (LOG_OK) System.out.println("Ch."+c+" min=0 max="+4095);
				} else {
					rtnVal.setDisplayRange(0, 255);
					if (LOG_OK) System.out.println("Ch."+c+" min=0 max="+255);
				}
			}
			rtnVal.setC(1);
			rtnVal.setZ(1);
		}
        if (LOG_OK) System.out.println("Getting metadata...");
        String info = loader.getAllAttributeString("/");
        if (LOG_OK) System.out.println("[ROOT]"+System.getProperty("line.separator")+info);
        info += loader.getAllAttributeString("/Channels");
        if (LOG_OK) System.out.println("[ALL]"+System.getProperty("line.separator")+info);
        rtnVal.setProperty("Info", info);
        
        loader.close();
        
		return rtnVal;
	}

    private void applyProcessorToStack(final Map<IPKey, ImageProcessor> imageProcessors, IPKey key, ImagePlus rtnVal, int channelNum) {
        ImageProcessor cp = imageProcessors.get(key);
        int i = key.getZ();
        rtnVal.setC(channelNum);
        rtnVal.setZ(i + 1);
        rtnVal.setProcessor(cp);
    }

    private IPKey addByteProcessor(int channelNum, int i, ImageStack h5jImageStack, FileInfo fileInfo, double[] max, Map<IPKey, ImageProcessor> imageProcessors) {
        IPKey key = new IPKey();
        key.setChannelNumber(channelNum);
        key.setZ(i);
        ImageProcessor cp = (ImageProcessor)createByteProcessor(key, h5jImageStack, fileInfo, max);
        imageProcessors.put(key, cp);
        return key;
    }
    
    private IPKey addShortProcessor(int channelNum, int i, ImageStack h5jImageStack, FileInfo fileInfo, double[] max, Map<IPKey, ImageProcessor> imageProcessors) {
        IPKey key = new IPKey();
        key.setChannelNumber(channelNum);
        key.setZ(i);
        ImageProcessor cp = (ImageProcessor)createShortProcessor(key, h5jImageStack, fileInfo, max);
        imageProcessors.put(key, cp);
        return key;
    }

    private ByteProcessor createByteProcessor(
            IPKey key,
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
    
    private ShortProcessor createShortProcessor(
            IPKey key,
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
            final byte[] targetBytes = new byte[unpaddedWidth * unpaddedHeight * 2];
            outputBytes = targetBytes; 
            for (int i = 0; i < unpaddedHeight; i++) {
                final int finalI = i;
                Runnable submissible = new Runnable() {
                    public void run() {
                        int srcPos = (finalI * fileInfo.width * 2);
                        int destPos = (finalI * unpaddedWidth * 2);
                        System.arraycopy(nextBytes, srcPos, targetBytes, destPos, unpaddedWidth*2);
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
        ShortProcessor cp = new ShortProcessor(unpaddedWidth, unpaddedHeight);
        final short[] outputShorts = new short[unpaddedWidth * unpaddedHeight];
        ByteBuffer.wrap(outputBytes).order(ByteOrder.BIG_ENDIAN).asShortBuffer().get(outputShorts);
        
        for (int i = 0; i < outputShorts.length; i++)
        	outputShorts[i] = (short) ((outputShorts[i] & 0xffff) / 16);
        
        cp.setPixels(outputShorts);
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
		calibration.pixelDepth = 1;
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
    
    private static class IPKey {
        private int channelNumber;
        private int z;

        public boolean equals(Object other) {
            if (other == null || ! (other instanceof IPKey)) {
                return false;
            }
            else {
                IPKey otherKey = (IPKey)other;
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
