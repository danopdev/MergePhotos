package com.dan.mergephotos;

import android.content.ContentResolver;
import android.net.Uri;
import org.opencv.core.Mat;
import java.io.InputStream;
import org.opencv.core.MatOfByte;
import org.opencv.imgcodecs.Imgcodecs;

import static org.opencv.imgcodecs.Imgcodecs.imdecode;

public class OpenCVLoadImageFromUri {
    static Mat load(Uri uri, ContentResolver contentResolver) {
        try {
            final InputStream inputStream = contentResolver.openInputStream(uri);
            if (null == inputStream) return null;
            final byte[] imageRawData = new byte[inputStream.available()];
            inputStream.read(imageRawData);
            inputStream.close();
            Mat image = imdecode(new MatOfByte(imageRawData), Imgcodecs.IMREAD_COLOR | Imgcodecs.IMREAD_ANYDEPTH);
            return image;
        } catch (Exception e) {
            return null;
        }
    }
}
