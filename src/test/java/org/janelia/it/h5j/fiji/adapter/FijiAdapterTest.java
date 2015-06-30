/*
 * Copyright Janelia Research Campus, 19700 Helix Dr, Ashburn, VA 20147.
 * License Header....
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
    private static final String TESTFILE = "";
    private static final int TEST_BPP = 3;
    private static final int TEST_HEIGHT = 1;
    private static final int TEST_WIDTH = 4;
    
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
        ImagePlus imagePlus = fijiAdapter.getImagePlus(new File(TESTFILE));
        assertEquals(imagePlus.getBytesPerPixel(), TEST_BPP);
        assertEquals(imagePlus.getHeight(), TEST_HEIGHT);
        assertEquals(imagePlus.getWidth(), TEST_WIDTH);
    }
}
