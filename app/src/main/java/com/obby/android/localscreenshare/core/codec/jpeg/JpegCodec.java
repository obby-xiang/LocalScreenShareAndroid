package com.obby.android.localscreenshare.core.codec.jpeg;

import com.obby.android.localscreenshare.core.codec.CodecDescriptor;
import com.obby.android.localscreenshare.core.codec.CodecIds;
import com.obby.android.localscreenshare.core.codec.CodecRegistration;
import com.obby.android.localscreenshare.core.model.BitstreamFormat;

/**
 * Static registration entry point for the reference JPEG codec.
 */
public final class JpegCodec {

    public static final String MIME = "image/jpeg";

    private JpegCodec() {
    }

    public static CodecRegistration registration() {
        CodecDescriptor descriptor = new CodecDescriptor(CodecIds.JPEG, MIME,
                BitstreamFormat.JPEG_FRAME, false, false, false);
        return new CodecRegistration(descriptor, new JpegEncoderFactory(),
                new JpegDecoderFactory(), new JpegCodecPolicy(), 100);
    }
}
