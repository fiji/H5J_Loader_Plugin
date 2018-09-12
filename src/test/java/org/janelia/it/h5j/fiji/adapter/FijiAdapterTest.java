/*
 * Copyright 2015 Howard Hughes Medical Institute.
 * All rights reserved.
 * Use is subject to Janelia Farm Research Campus Software Copyright 1.1
 * license terms ( http://license.janelia.org/license/jfrc_copyright_1_1.html ).
 */

package org.janelia.it.h5j.fiji.adapter;

import ij.ImagePlus;
import java.io.File;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Test the Fiji adapter.
 *
 * @author fosterl
 */
public class FijiAdapterTest {
    private static final String TESTFILE = "src/test/resources/test.h5j";
    private static final int EXPECT_BPP = 1;
    private static final int EXPECT_WIDTH = 115;
    private static final int EXPECT_HEIGHT = 95;
    
    private FijiAdapter fijiAdapter;
    
    public FijiAdapterTest() {
    }
    
    @BeforeClass
    public static void setUpClass() {
    }
    
    @AfterClass
    public static void tearDownClass() {
    }
    
    @Before
    public void setUp() {
        fijiAdapter = new FijiAdapter();
    }
    
    @After
    public void tearDown() {
        fijiAdapter = null;
    }

    /**
     * Very simple test.  Exercises this functionality:
     * - can the file be opened and converted to Fiji's format?
     * - do the basic characteristics match expectations?
     * 
     * @throws Exception 
     */
    @Test
    public void getImagePlus() throws Exception {
        ImagePlus imagePlus = fijiAdapter.getMultiChannelImagePlus(new File(TESTFILE));
        if (imagePlus == null) System.out.println("getMultiChannelImagePlus failed (return value is NULL)");
        else {
        	System.out.println("bpp: "+imagePlus.getBytesPerPixel());
        	System.out.println("height: "+imagePlus.getHeight());
            System.out.println("width: "+imagePlus.getWidth());
        }
        assertEquals("Bytes-per-pixel fails to match", EXPECT_BPP, imagePlus.getBytesPerPixel());
        assertEquals("Height not as expected", EXPECT_HEIGHT, imagePlus.getHeight());
        assertEquals("Width not as expected", EXPECT_WIDTH, imagePlus.getWidth());
    }
}
