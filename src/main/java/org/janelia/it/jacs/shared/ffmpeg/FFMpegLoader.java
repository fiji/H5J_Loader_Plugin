/*
 * Copyright 2018 Howard Hughes Medical Institute.
 * All rights reserved.
 * Use is subject to Janelia Farm Research Campus Software Copyright 1.1
 * license terms ( http://license.janelia.org/license/jfrc_copyright_1_1.html ).
 */
package org.janelia.it.jacs.shared.ffmpeg;

import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.DoublePointer;
import org.bytedeco.javacpp.Pointer;
import org.bytedeco.javacpp.PointerPointer;
import org.bytedeco.javacpp.avcodec.AVCodecParameters;

import ij.IJ;
import ij.macro.Interpreter;

import static org.bytedeco.javacpp.avformat.AVFormatContext.AVFMT_FLAG_CUSTOM_IO;
import static org.bytedeco.javacpp.avcodec.*;
import static org.bytedeco.javacpp.avdevice.avdevice_register_all;
import static org.bytedeco.javacpp.avformat.*;
import static org.bytedeco.javacpp.avutil.*;
import static org.bytedeco.javacpp.swscale.*;

class ReadInput extends Read_packet_Pointer_BytePointer_int {
    private byte[] _buffer;
    private boolean _read_bytes;

    public ReadInput(byte[] bb) {
        super();
        this._buffer = bb;
        _read_bytes = true;
    }

    @Override
    public int call(Pointer opaque, BytePointer buffer, int buffer_size) {
    	//System.out.println("read call "+opaque+" "+buffer+" "+buffer_size);
        int buf_size = _buffer.length;
        if ( _read_bytes )
        {
            buffer.put(_buffer, 0, buf_size);
            _read_bytes = false;
        }
        else
            buf_size = 0;

        return buf_size;
    }
};

public class FFMpegLoader
{
    static
    {
        // Register all formats and codecs
        avcodec_register_all();
        avdevice_register_all();
        av_register_all();
        avformat_network_init();
    }

    public static enum ImageMode {
        COLOR, GRAY, RAW
    }

    private String _filename;
    private AVFormatContext _format_context;
    private AVStream _video_stream;
    private AVCodecContext _video_codec;
    private AVFrame picture = null, picture_rgb = null;
    private BytePointer _buffer_rgb;
    private AVPacket pkt, pkt2;
    private int[] got_frame;
    private SwsContext img_convert_ctx;
    private ImageStack _image;
    private boolean _frame_grabbed;
    private long _time_stamp;
    private int frameNumber;
    private boolean deinterlace = false;
    private int _components_per_frame;
    
    private long _frame_count = 0;
    private long _frame_num = 0;
    private boolean _flush = false;
    
    private BytePointer _buffer = null;
    
    private int channel_num = 1;
    private int channel_count = 0;

    public FFMpegLoader(String filename)
    {
        this._filename = filename;
        _format_context = new AVFormatContext(null);
    }

    public FFMpegLoader(byte[] ibytes)
    {
        this._filename = "";
        int BUFFER_SIZE=ibytes.length;
        // allocate buffer
        _buffer = new BytePointer(av_malloc(BUFFER_SIZE));
        // create format context
        _format_context = avformat_alloc_context();
        
        Seek_Pointer_long_int seeker = new Seek_Pointer_long_int() {
            @Override
            public long call(Pointer pointer, long offset, int whence) {
                return ibytes.length;
            }
        };
        
        _format_context.pb(avio_alloc_context(_buffer, BUFFER_SIZE, 0, null, new ReadInput(ibytes), null, seeker));
        _format_context.flags(_format_context.flags() | AVFMT_FLAG_CUSTOM_IO);
    }

    public ImageStack getImage()
    {
        return _image;
    }

    public void close() throws Exception {
        if (pkt != null && pkt2 != null) {
            if (pkt2.size() > 0) {
            	av_packet_unref(pkt);
            }
            pkt = pkt2 = null;
        }

        // Close the video codec
        if (_video_codec != null) {
        	avcodec_free_context(_video_codec);
            _video_codec = null;
        }

        // Close the video file
        if (_format_context != null && !_format_context.isNull()) {
        	av_free(_format_context.pb().buffer());
        	avio_context_free(_format_context.pb());
        	avformat_free_context(_format_context);
            _format_context = null;
        }

        if (img_convert_ctx != null) {
            sws_freeContext(img_convert_ctx);
            img_convert_ctx = null;
        }
        
        av_frame_free(picture_rgb);

        got_frame = null;
        _frame_grabbed = false;
        _time_stamp = 0;
        frameNumber = 0;
    }

    public void release() throws Exception {
         // Free the Image
        _image.release();

        _image = null;
    }

    @Override
    protected void finalize() throws Throwable {
        super.finalize();
//        release();
    }

    public int getImageWidth() {
        return _image == null ? -1 : _image.width();
    }

    public int getImageHeight() {
        return _image == null ? -1 : _image.height();
    }

    public boolean isDeinterlace() {
        return deinterlace;
    }

    public void setDeinterlace(boolean deinterlace) {
        this.deinterlace = deinterlace;
    }
    
    public void setChannelNum(int chnum) {
        this.channel_num = chnum;
    }
    public void setChannelCount(int chcount) {
        this.channel_count = chcount;
    }

    public int getPixelFormat()
    {
        int result = AV_PIX_FMT_NONE;
        if (_components_per_frame == 1)
        {
            if (_image.getBytesPerPixel() == 1)
                result = AV_PIX_FMT_GRAY8;
            else if (_image.getBytesPerPixel() == 2)
                result = AV_PIX_FMT_GRAY16BE; //Java uses the network byte order (big endian)
            else
                result = AV_PIX_FMT_NONE;
            }
        else if (_components_per_frame == 3)
        {
            result = AV_PIX_FMT_BGR24;
        }
        else if (_components_per_frame == 4)
        {
            result = AV_PIX_FMT_BGRA;
        }
        else if (_video_codec != null)
        { // RAW
            result = _video_codec.pix_fmt();
        }

        return result;
    }

    public double getFrameRate() {
        if (_video_stream == null) {
            return 0;
        } else {
            AVRational r = _video_stream.r_frame_rate();
            return (double) r.num() / r.den();
        }
    }

    public void setFrameNumber(int frameNumber) throws Exception {
        // best guess, AVSEEK_FLAG_FRAME has not been implemented in FFmpeg...
        setTimestamp(Math.round(1000000L * frameNumber / getFrameRate()));
    }

    public void setTimestamp(long time_stamp) throws Exception {
        int ret;
        if (_format_context != null) {
            time_stamp = time_stamp * AV_TIME_BASE / 1000000L;
            /* add the stream start time */
            if (_format_context.start_time() != AV_NOPTS_VALUE) {
                time_stamp += _format_context.start_time();
            }
            if ((ret = avformat_seek_file(_format_context, -1, Long.MIN_VALUE, time_stamp, Long.MAX_VALUE, AVSEEK_FLAG_BACKWARD)) < 0) {
                throw new Exception("avformat_seek_file() error " + ret + ": Could not seek file to time_stamp " + time_stamp + ".");
            }
            if (_video_codec != null) {
                avcodec_flush_buffers(_video_codec);
            }
            if (pkt2.size() > 0) {
                pkt2.size(0);
                av_packet_unref(pkt);
            }
            /* comparing to time_stamp +/- 1 avoids rouding issues for framerates
             which are no proper divisors of 1000000, e.g. where
             av_frame_get_best_effort_timestamp in grabFrame sets this.time_stamp
             to ...666 and the given time_stamp has been rounded to ...667
             (or vice versa)
             */
            while (this._time_stamp > time_stamp + 1 && grabFrame() != null) {
                // flush frames if seeking backwards
            }
            while (this._time_stamp < time_stamp - 1 && grabFrame() != null) {
                // decode up to the desired frame
            }
            if (_video_codec != null) {
                _frame_grabbed = true;
            }
        }
    }

    public int getLengthInFrames() {
        // best guess...
        return (int) (getLengthInTime() * getFrameRate() / 1000000L);
    }

    public long getLengthInTime() {
        return _format_context.duration() * 1000000L / AV_TIME_BASE;
    }

    public void start() throws Exception {
        synchronized (org.bytedeco.javacpp.avcodec.class) {
            startUnsafe();
        }
    }

    public void startUnsafe() throws Exception {
        int ret;
        img_convert_ctx = null;
        _video_codec = null;
        pkt = new AVPacket();
        pkt2 = new AVPacket();
        got_frame = new int[1];
        _image = new ImageStack();
        _frame_grabbed = false;
        _time_stamp = 0;
        frameNumber = 0;
        
        int thread = Runtime.getRuntime().availableProcessors();

        pkt2.size(0);

        // Open video file
        AVDictionary options = new AVDictionary(null);

        if ((ret = avformat_open_input(_format_context, _filename, null, options)) < 0) {
            av_dict_set(options, "pixel_format", null, 0);
            if ((ret = avformat_open_input(_format_context, _filename, null, options)) < 0) {
                throw new Exception("avformat_open_input() error " + ret + ": Could not open input \"" + _filename + "\". (Has setFormat() been called?)");
            }
        }
        av_dict_free(options);

        // Retrieve stream information
        if ((ret = avformat_find_stream_info(_format_context, (PointerPointer) null)) < 0) {
            throw new Exception("avformat_find_stream_info() error " + ret + ": Could not find stream information.");
        }

        // Dump information about file onto standard error
        av_dump_format(_format_context, 0, _filename, 0);
        //av_log_set_level(AV_LOG_TRACE);

        // Find the first video and audio stream
        _video_stream = null;
        int nb_streams = _format_context.nb_streams();
        for (int i = 0; i < nb_streams; i++) {
            AVStream st = _format_context.streams(i);
            if (_video_stream == null && st.codecpar().codec_type() == AVMEDIA_TYPE_VIDEO) {
                _video_stream = st;
                AVCodec decoder = avcodec_find_decoder(_video_stream.codecpar().codec_id());
                if (decoder == null) throw new Exception("Unexpected decorder: " + _video_stream.codecpar().codec_id());
                _video_codec = avcodec_alloc_context3(decoder);
                if (avcodec_parameters_to_context(_video_codec, _video_stream.codecpar()) < 0)
                	throw new Exception("avcodec_parameters_to_context failed.");
                _video_codec.thread_count(thread);
                
                // Open video codec
                if ((ret = avcodec_open2(_video_codec, decoder, (PointerPointer) null)) < 0) {
                    throw new Exception("avcodec_open2() error " + ret + ": Could not open video codec.");
                }

                // Hack to correct wrong frame rates that seem to be generated by some codecs
                if (_video_codec.time_base().num() > 1000 && _video_codec.time_base().den() == 1) {
                    _video_codec.time_base().den(1000);
                }
                break;
            }
        }
        if (_video_stream == null) {
            throw new Exception("Did not find a video stream inside \"" + _filename + "\".");
        }

        int pix_fmt = _video_codec.pix_fmt();
        
        AVPixFmtDescriptor fmt = av_pix_fmt_desc_get(pix_fmt);
        int comps = fmt.nb_components();
        _components_per_frame = comps;
        AVComponentDescriptor desc = fmt.comp();
        if (desc.depth() == 8) {
        	_components_per_frame = 3; //rgb
        	_image.setBytesPerPixel(1);
        } else {
        	_components_per_frame = 1; //gray16
        	_image.setBytesPerPixel(2);
        }
        
        img_convert_ctx = sws_getContext(
                _video_codec.width(), _video_codec.height(), _video_codec.pix_fmt(),
                _video_codec.width(), _video_codec.height(), getPixelFormat(), SWS_BICUBIC,
                null, null, (DoublePointer) null);
        if (img_convert_ctx == null) {
            throw new Exception("sws_getContext() error: Cannot initialize the conversion context.");
        }
        
        if (!Interpreter.isBatchMode()) {
        	IJ.showStatus("Loading H5J...");
        	IJ.showProgress( (double)channel_count / channel_num );
        }
    }

    public void stop() throws Exception {
        release();
    }

    private void allocateFrame(Frame f) throws Exception {
        // Allocate video frame and an AVFrame structure for the RGB image
    	
        if ((picture = av_frame_alloc()) == null) {
            throw new Exception("avcodec_alloc_frame() error: Could not allocate raw picture frame.");
        }

        int width = getImageWidth() > 0 ? getImageWidth() : _video_codec.width();
        int height = getImageHeight() > 0 ? getImageHeight() : _video_codec.height();

        _image.setHeight(height);
        _image.setWidth(width);

        int fmt = getPixelFormat();
    
        // Assign to the frame so the memory can be deleted later
        //f.picture = picture;
        //f.picture_rgb = picture_rgb;
        f.imageBytes.add( new byte[width * height * _image.getBytesPerPixel()] );
    }

    private void extractBytes(Frame frameOutput, BytePointer imageBytesInput) {
        int width = _video_codec.width();
        int height = _video_codec.height();
        int linesize = _image.getBytesPerPixel() == 1 ? 
        					picture_rgb.linesize().get(0)/3 :
        					picture_rgb.linesize().get(0)/2;
        int padding = linesize -_video_codec.width();
        if (padding < 0) {
            padding = 0;
        }

        if (_image.getBytesPerPixel() == 1) {
        	byte[] outputBytes = frameOutput.imageBytes.get(0);
        	byte[] inputBytes = new byte[linesize * height * 3];
        	imageBytesInput.get(inputBytes);

        	int inputOffset = 0;
        	int outputOffset = 0;
        	for (int rows = 0; rows < height; rows++) {
        		for (int cols = 0; cols < width; cols++) {
        			outputBytes[ outputOffset ] = inputBytes[3 * inputOffset];
        			inputOffset ++;
        			outputOffset ++;
        		}
        		inputOffset += padding;
        	}
        } else {
        	byte[] outputBytes = frameOutput.imageBytes.get(0);
        	byte[] inputBytes = new byte[linesize * height * _image.getBytesPerPixel()];
        	imageBytesInput.get(inputBytes);

        	int inputOffset = 0;
        	int outputOffset = 0;
        	int colnum = width*_image.getBytesPerPixel();
        	for (int rows = 0; rows < height; rows++) {
        		for (int cols = 0; cols < colnum; cols++) {
        			outputBytes[ outputOffset ] = inputBytes[inputOffset];
        			inputOffset ++;
        			outputOffset ++;
        		}
        		inputOffset += padding*_image.getBytesPerPixel();
        	}
        }
    }
    
    private void processImage(Frame frame) throws Exception
    {
        // Deinterlace Picture
        //if (deinterlace) {
        //    AVPicture p = new AVPicture(picture);
        //    avpicture_deinterlace(p, p, _video_codec.pix_fmt(), _video_codec.width(), _video_codec.height());
        //}
    	if (picture_rgb == null) {
    		if ((picture_rgb = av_frame_alloc()) == null) {
                throw new Exception("avcodec_alloc_frame() error: Could not allocate rbg picture frame.");
            }
    		av_frame_copy_props(picture_rgb, picture);
    		picture_rgb.width(getImageWidth());
    		picture_rgb.height(getImageHeight());
    		picture_rgb.format(getPixelFormat());
    		picture_rgb.nb_samples(0);
    		av_frame_get_buffer(picture_rgb, 0);
    	}
        
        // Convert the image from its native format to RGB or GRAY
        sws_scale(img_convert_ctx, picture.data(), picture.linesize(), 0,
                _video_codec.height(), picture_rgb.data(), picture_rgb.linesize());

        extractBytes(frame, picture_rgb.data(0));
        
        av_frame_free(picture);
        //av_frame_free(picture_rgb);
    }

    public void grab() throws Exception {
        Frame f;
        boolean done = false;
        _frame_count = 0;
        _frame_num = _video_stream.nb_frames() > 0 ? _video_stream.nb_frames() : Long.MAX_VALUE;
        _flush = false;

        while (!done) {
            f = grabFrame();
            if (f != null) {
                // Uncomment to debug each frame as it is grabbed
                // SaveFrame(f, i++);
                _image.add(f);
            } else {
                done = true;
            }
            
            if (!Interpreter.isBatchMode()) {
            	IJ.showStatus("Loading H5J...");
            	IJ.showProgress( (double)(channel_count*_frame_num + _frame_count) / (channel_num*_frame_num) );
            }
        }
        
        if ( _video_stream.nb_frames() > 0 && _image.getNumFrames() < _video_stream.nb_frames()) {
        	int count = _image.getNumFrames();
        	Frame lastframe = _image.frame(count-1);
        	while (count < _video_stream.nb_frames()) {
        		System.err.println("The last frame was dropped. Duplicating the second frame before the last... (Channel "+channel_count+")");
        		_image.add(lastframe);
        		count++;
        	}
        	_frame_count = count;
        	if (!Interpreter.isBatchMode()) {
            	IJ.showStatus("Loading H5J...");
            	IJ.showProgress( (double)(channel_count*_frame_num + _frame_count) / (channel_num*_frame_num) );
            }
        }
    }

    public Frame grabFrame() throws Exception {
        if (_format_context == null || _format_context.isNull()) {
            throw new Exception("Could not grab: No AVFormatContext. (Has start() been called?)");
        }
        Frame frame = new Frame();

        if (_frame_grabbed) {
            _frame_grabbed = false;
            frame.keyFrame = picture.key_frame() != 0;
            //frame.image = picture_rgb;
            processImage(frame);
            return frame;
        }
        
        int ret;
        boolean done = false;
        if (!_flush) {
        	 while (!done) {
             	if (_frame_count >= _frame_num || av_read_frame(_format_context, pkt) < 0) {
                    if (_video_stream != null) {
                    	pkt.stream_index(_video_stream.index());
                        pkt.flags(AV_PKT_FLAG_KEY);
                        pkt.data(null);
                        pkt.size(0);
                        _flush = true;
                     } else
                     	return null;
                 }
       	        //System.out.println("f:  "+_frame_count+"   pkt:  "+pkt.size());
             	if (pkt.stream_index() == _video_stream.index()) {
             		if (avcodec_send_packet(_video_codec, pkt) < 0)
             			throw new Exception("avcodec_send_packet failed");
             	    allocateFrame(frame);
             	    ret = avcodec_receive_frame(_video_codec, picture);
             	    if (ret >= 0) {
             	    	long pts = picture.best_effort_timestamp();
             	    	AVRational time_base = _video_stream.time_base();
             	    	_time_stamp = 1000000L * pts * time_base.num() / time_base.den();
             	    	// best guess, AVCodecContext.frame_number = number of decoded frames...
             	    	frameNumber = (int) (_time_stamp * getFrameRate() / 1000000L);
             	    	frame.keyFrame = picture.key_frame() != 0;
             	    	//frame.image = picture_rgb;
             	    	//frame.opaque = picture;
             	    	processImage(frame);
             	    	_frame_count++;
             	    	done = true;
             	    } else if (ret == AVERROR_EAGAIN()) {
             	    	av_frame_free(picture);
             	        //av_frame_free(picture_rgb);
             	    	frame.release();
             	    }
             	    else 
             	    	return null;
             	}
             	av_packet_unref(pkt);
            }
        } else {
        	//System.out.println("f:  "+_frame_count+"   flush");
        	allocateFrame(frame);
     	    ret = avcodec_receive_frame(_video_codec, picture);
     	    if (ret >= 0) {
     	    	long pts = picture.best_effort_timestamp();
     	    	AVRational time_base = _video_stream.time_base();
     	    	_time_stamp = 1000000L * pts * time_base.num() / time_base.den();
     	    	// best guess, AVCodecContext.frame_number = number of decoded frames...
     	    	frameNumber = (int) (_time_stamp * getFrameRate() / 1000000L);
     	    	frame.keyFrame = picture.key_frame() != 0;
     	    	//frame.image = picture_rgb;
     	    	//frame.opaque = picture;
     	    	processImage(frame);
     	    	_frame_count++;
     	    	done = true;
     	    } else 
     	    	return null;
        }
       

        return frame;
    }

    public void saveFrame(int iFrame, FFMPGByteAcceptor acceptor)
            throws Exception {
        int width = _image.width();
        int height = _image.height();
        byte[] data = _image.image(iFrame, 0);
//                .interleave(iFrame, 0, 1);
        int linesize = _image.linesize(iFrame);
        acceptor.accept(data, linesize, width, height);
    }
}