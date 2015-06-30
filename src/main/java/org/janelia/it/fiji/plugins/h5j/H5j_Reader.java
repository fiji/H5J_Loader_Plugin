/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */

package org.janelia.it.fiji.plugins.h5j;

import ij.IJ;
import ij.ImagePlus;
import ij.plugin.PlugIn;
import java.awt.GraphicsEnvironment;
import java.awt.HeadlessException;
import java.io.File;
import javax.swing.JFileChooser;
import javax.swing.filechooser.FileFilter;
import org.janelia.it.h5j.fiji.adapter.FijiAdapter;

/**
 * Reader for HHMI HDF 5 reader.  Consumes H.265-compressed data.
 *
 * @author fosterl
 */
public class H5j_Reader extends ImagePlus implements PlugIn {
    
    private static final String MESSAGE_PREFIX = "HHMI_H5J_Reader: ";
    private static final String EXTENSION = ".h5j";
    public static final String INFO_PROPERTY = "Info";
    
    private boolean asImage = false;

    @Override
    public void run(String string) {
        // Do nothing, if this is a headless environment.
        if (GraphicsEnvironment.isHeadless()) {
            IJ.showMessage(MESSAGE_PREFIX + "this plugin will only display a graphical file.  Using it in headless mode is meaningless.");
            return;
        }
        else {
            try {
                File infile = ensureFileAvailable(string);
                FijiAdapter adapter = new FijiAdapter();
                ImagePlus infileImage = adapter.getImagePlus(infile);
                if (asImage) {
                    // This reader will act as the image. Its 'show' will
                    // be the natural flow of this type of plugin.
                    cloneStack(infileImage, this);
                }
                else {
                    infileImage.show();
                }
                
            } catch (Exception ex) {
                ex.printStackTrace();
                IJ.showMessage(MESSAGE_PREFIX + "Problem reading file data.  Messsage is '" + ex.getLocalizedMessage() + "'.");
            }
        }
        //IJ.showMessage(MESSAGE_PREFIX + "invoked.");                
    }
    
    /**
     * This can be used to clone one stack into another.
     * 
     * @param infileImage
     * @param target 
     */
    private void cloneStack(ImagePlus infileImage, ImagePlus target) {
        // Now, 'clone' this image plus back to the one implemented
        // by this plugin.
        target.setStack(infileImage.getTitle(), infileImage.getStack());
        target.setCalibration(infileImage.getCalibration());
        Object objInfo = infileImage.getProperty(INFO_PROPERTY);
        if (objInfo != null) {
            target.setProperty(INFO_PROPERTY, objInfo);
        }
        target.setFileInfo(infileImage.getFileInfo());
    }
    
    private File ensureFileAvailable( String putativeFilePath ) {
        File rtnVal = new File(putativeFilePath);
        try {
            if (! rtnVal.canRead()) {
                rtnVal = showFileChooser();                    
            }
            else {
                asImage = true;
            }
        } catch ( Exception ex ) {
            IJ.showMessage(MESSAGE_PREFIX+"unable to open " + putativeFilePath);
        }
        
        return rtnVal;
    }
    
    private File showFileChooser() throws HeadlessException {
        // Will replace the file with a user input.
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.addChoosableFileFilter(new Hj5FileFilter());
        File rtnVal;
        switch (fileChooser.showDialog(IJ.getTextPanel(), "Open")) {
            case JFileChooser.CANCEL_OPTION :
                rtnVal = null;
                break;
            case JFileChooser.ERROR_OPTION :
                rtnVal = null;
                IJ.error("Failed to open H5J File.");
                break;
            case JFileChooser.APPROVE_OPTION :
                rtnVal = fileChooser.getSelectedFile();
                break;
            default:
                rtnVal = null;
                break;
        }
        return rtnVal;
    }

    private static class Hj5FileFilter extends FileFilter {

        @Override
        public boolean accept(File path) {
            return path.isFile()
                    && path.getName().endsWith(EXTENSION);
        }

        @Override
        public String getDescription() {
            return "Howard Hughes Medical Institute/Janelia Research Campus' H.265-compressed HDF5 Files";
        }

    }
}
