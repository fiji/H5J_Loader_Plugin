/*
 * Copyright 2015 Howard Hughes Medical Institute.
 * All rights reserved.
 * Use is subject to Janelia Farm Research Campus Software Copyright 1.1
 * license terms ( http://license.janelia.org/license/jfrc_copyright_1_1.html ).
 */

package org.janelia.it.jacs.shared.ffmpeg;

import ch.systemsx.cisd.hdf5.*;

import java.util.Arrays;
import java.util.List;
import java.util.ListIterator;
import java.util.stream.Collectors;

public class H5JLoader
{
	private static final String VX_SIZE_ATTRIB = "voxel_size";
	private static final String UNIT_ATTRIB = "unit";
    private static final String PAD_RIGHT_ATTRIB = "pad_right";
    private static final String PAD_BOTTOM_ATTRIB = "pad_bottom";
    private static final String CHANNELS_QUERY_PATH = "/Channels";

    private String _filename;
    private IHDF5Reader _reader;
    private ImageStack _image;
    
    public H5JLoader(String filename) {
        this._filename = filename;
        IHDF5ReaderConfigurator conf = HDF5Factory.configureForReading(filename);
        conf.performNumericConversions();
        _reader = conf.reader();
    }

    public void close() throws Exception {
        _reader.close();
    }

    public int numberOfChannels() {
        return _reader.object().getAllGroupMembers(CHANNELS_QUERY_PATH).size();
    }

    public List<String> channelNames() { return _reader.object().getAllGroupMembers(CHANNELS_QUERY_PATH); }

    public ImageStack extractAllChannels() {
        if (_image == null) {
            _image = new ImageStack();
        }

        List<String> channels = channelNames();
        int ch_count = 0;
        for (ListIterator<String> iter = channels.listIterator(); iter.hasNext(); )
        {
            String channel_id = iter.next();
            try
            {
                ImageStack frames = extract(channel_id, ch_count);
                _image.merge( frames );
                extractAttributes(_image);
            } catch (Exception e)
            {
                e.printStackTrace();
            }
            ch_count++;
        }

        return _image;
    }

    public ImageStack extract(String channelID) throws Exception {
        IHDF5OpaqueReader channel = _reader.opaque();
        byte[] data = channel.readArray(CHANNELS_QUERY_PATH + "/" + channelID);

        FFMpegLoader movie = new FFMpegLoader(data);
        movie.start();
        movie.grab();
        ImageStack stack = movie.getImage();

        extractAttributes(stack);

        movie.close();

        return stack;
    }
    
    public ImageStack extract(String channelID, int chcount) throws Exception {
        IHDF5OpaqueReader channel = _reader.opaque();
        byte[] data = channel.readArray(CHANNELS_QUERY_PATH + "/" + channelID);

        FFMpegLoader movie = new FFMpegLoader(data);
        movie.setChannelNum(numberOfChannels());
        movie.setChannelCount(chcount);
        movie.start();
        movie.grab();
        ImageStack stack = movie.getImage();

        extractAttributes(stack);

        movie.close();

        return stack;
    }

    private void extractAttributes(ImageStack image) {
        if (image == null) {
            image = new ImageStack();
        }
        IHDF5Reader ihdf5reader = _reader;
        if (ihdf5reader.object().hasAttribute(CHANNELS_QUERY_PATH, PAD_BOTTOM_ATTRIB)) {
            IHDF5LongReader ihdf5LongReader = ihdf5reader.int64();
            final int paddingBottom = (int) ihdf5LongReader.getAttr(CHANNELS_QUERY_PATH, PAD_BOTTOM_ATTRIB);
            image.setPaddingBottom(paddingBottom);
        } else {
            image.setPaddingBottom(-1);
        }
        if (ihdf5reader.object().hasAttribute(CHANNELS_QUERY_PATH, PAD_RIGHT_ATTRIB)) {
            IHDF5LongReader ihdf5LongReader = ihdf5reader.int64();
            final int paddingRight = (int) ihdf5LongReader.getAttr(CHANNELS_QUERY_PATH, PAD_RIGHT_ATTRIB);
            image.setPaddingRight(paddingRight);
        } else {
            image.setPaddingRight(-1);
        }
        
        double[] vxsize = null;
        if (ihdf5reader.object().hasAttribute("/", VX_SIZE_ATTRIB)) {
            IHDF5DoubleReader ihdf5DoubleReader = ihdf5reader.float64();
            vxsize = ihdf5DoubleReader.getArrayAttr("/", VX_SIZE_ATTRIB);
        }
        if (vxsize != null && vxsize.length == 3)
        	image.setSpacings(vxsize[0], vxsize[1], vxsize[2]);
        
        if (ihdf5reader.object().hasAttribute("/", UNIT_ATTRIB)) {
            IHDF5StringReader ihdf5StringReader = ihdf5reader.string();
            final String unit = ihdf5StringReader.getAttr("/", UNIT_ATTRIB);
            image.setUnit(unit);
        }
    }
    
    public String getAllAttributeString(String path) {
    	String attrs = "";
        List<String> names = _reader.object().getAllAttributeNames(path);
        for (String n : names) {
        	attrs = attrs + getAttributeString(_reader, path, n) + System.getProperty("line.separator");
        }
        return attrs;
    }
    
    public String getAttributeString(
			final IHDF5Reader reader,
			final String object,
			final String attribute )
	{
    	String rtnVal = "";
    	
		if ( !reader.exists( object ) )
			return rtnVal;

		if ( !reader.object().hasAttribute( object, attribute ) )
			return rtnVal;

		final HDF5DataTypeInformation attributeInfo = reader.object().getAttributeInformation( object, attribute );
		final Class< ? > type = attributeInfo.tryGetJavaType();
		if ( type.isAssignableFrom( long[].class ) )
			if ( attributeInfo.isSigned() ) {
				rtnVal = Arrays.stream(reader.int64().getArrayAttr( object, attribute ))
				        .mapToObj(String::valueOf)
				        .collect(Collectors.joining(", "));
				return attribute + ": " + "[ " + rtnVal + " ]";
			}
			else {
				rtnVal = Arrays.stream(reader.uint64().getArrayAttr( object, attribute ))
				        .mapToObj(String::valueOf)
				        .collect(Collectors.joining(", "));
				return attribute + ": " + "[ " + rtnVal + " ]";
			}
		if ( type.isAssignableFrom( int[].class ) )
			if ( attributeInfo.isSigned() ) {
				rtnVal = Arrays.stream(reader.int32().getArrayAttr( object, attribute ))
		        .mapToObj(String::valueOf)
		        .collect(Collectors.joining(", "));
				return attribute + ": " + "[ " + rtnVal + " ]";
			}
			else {
				rtnVal = Arrays.stream(reader.uint32().getArrayAttr( object, attribute ))
				        .mapToObj(String::valueOf)
				        .collect(Collectors.joining(", "));
				return attribute + ": " + "[ " + rtnVal + " ]";
			}
		if ( type.isAssignableFrom( short[].class ) )
			if ( attributeInfo.isSigned() ) {
				short[] s = reader.int16().getArrayAttr( object, attribute );
				int[] ia = new int[s.length];
				for (int i = 0; i < s.length; i++) ia[i] = s[i];
				rtnVal = Arrays.stream(ia)
				        .mapToObj(String::valueOf)
				        .collect(Collectors.joining(", "));
				return attribute + ": " + "[ " + rtnVal + " ]";
			}
			else {
				short[] s = reader.int16().getArrayAttr( object, attribute );
				int[] ia = new int[s.length];
				for (int i = 0; i < s.length; i++) ia[i] = s[i];
				rtnVal = Arrays.stream(ia)
				        .mapToObj(String::valueOf)
				        .collect(Collectors.joining(", "));
				return attribute + ": " + "[ " + rtnVal + " ]";
			}
		if ( type.isAssignableFrom( byte[].class ) )
		{
			if ( attributeInfo.isSigned() ) {
				byte[] s = reader.int8().getArrayAttr( object, attribute );
				int[] ia = new int[s.length];
				for (int i = 0; i < s.length; i++) ia[i] = s[i];
				rtnVal = Arrays.stream(ia)
				        .mapToObj(String::valueOf)
				        .collect(Collectors.joining(", "));
				return attribute + ": " + "[ " + rtnVal + " ]";
			}
			else {
				byte[] s = reader.uint8().getArrayAttr( object, attribute );
				int[] ia = new int[s.length];
				for (int i = 0; i < s.length; i++) ia[i] = s[i];
				rtnVal = Arrays.stream(ia)
				        .mapToObj(String::valueOf)
				        .collect(Collectors.joining(", "));
				return attribute + ": " + "[ " + rtnVal + " ]";
			}
		}
		else if ( type.isAssignableFrom( double[].class ) ) {
			rtnVal = Arrays.stream(reader.float64().getArrayAttr( object, attribute ))
			        .mapToObj(String::valueOf)
			        .collect(Collectors.joining(", "));
			return attribute + ": " + "[ " + rtnVal + " ]";
		}
		else if ( type.isAssignableFrom( float[].class ) ) {
			float[] s = reader.float32().getArrayAttr( object, attribute );
			double[] ia = new double[s.length];
			for (int i = 0; i < s.length; i++) ia[i] = s[i];
			rtnVal = Arrays.stream(ia)
			        .mapToObj(String::valueOf)
			        .collect(Collectors.joining(", "));
			return attribute + ": " + "[ " + rtnVal + " ]";
		}
		else if ( type.isAssignableFrom( String[].class ) )
			return attribute + ": "+ "[ " + String.join(", ", reader.string().getArrayAttr( object, attribute )) + " ]";
		if ( type.isAssignableFrom( long.class ) )
		{
			if ( attributeInfo.isSigned() )
				return attribute + ": " + String.valueOf(reader.int64().getAttr( object, attribute ));
			else
				return attribute + ": " + String.valueOf(reader.uint64().getAttr( object, attribute ));
		}
		else if ( type.isAssignableFrom( int.class ) )
		{
			if ( attributeInfo.isSigned() )
				return attribute + ": " + String.valueOf(reader.int32().getAttr( object, attribute ));
			else
				return attribute + ": " + String.valueOf(reader.uint32().getAttr( object, attribute ));
		}
		else if ( type.isAssignableFrom( short.class ) )
		{
			if ( attributeInfo.isSigned() )
				return attribute + ": " + String.valueOf(reader.int16().getAttr( object, attribute ));
			else
				return attribute + ": " + String.valueOf(reader.uint16().getAttr( object, attribute ));
		}
		else if ( type.isAssignableFrom( byte.class ) )
		{
			if ( attributeInfo.isSigned() )
				return attribute + ": " + String.valueOf(reader.int8().getAttr( object, attribute ));
			else
				return attribute + ": " + String.valueOf(reader.uint8().getAttr( object, attribute ));
		}
		else if ( type.isAssignableFrom( double.class ) )
			return attribute + ": " + String.valueOf(reader.float64().getAttr( object, attribute ));
		else if ( type.isAssignableFrom( float.class ) )
			return attribute + ": " + String.valueOf(reader.float32().getAttr( object, attribute ));
		else if ( type.isAssignableFrom( String.class ) )
			return attribute + ": " + reader.string().getAttr( object, attribute );

		return rtnVal;
	}


    public void saveFrame(int iFrame, DataAcceptor acceptor)
            throws Exception {
        int width = _image.width();
        int height = _image.height();
        byte[] data = _image.interleave(iFrame, 0, 3);
        int linesize = _image.linesize(iFrame);
        acceptor.accept(data, linesize, width, height);
    }
    
    public static interface DataAcceptor {
        void accept(byte[] data, int linesize, int width, int height);
    }

}