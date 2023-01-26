#include <jni.h>
#include <string>
#include <vector>
#include "opencv2/stitching.hpp"


using namespace cv;


inline static unsigned int calculateColorDelta(const Point3_<uchar>* a, const Point3_<uchar>* b) {
    return abs(a->x - b->x) + abs(a->y - b->y) + abs(a->z - b->z);
}


static bool makeLongExposureNearestNative( std::vector<Mat> &images, const Mat &averageImage, Mat &outputImage) {
    const auto *meanImageIt = averageImage.ptr<Point3_<uchar>>(0);
    std::vector<const Point3_<uchar>*> imageIterators;

    for (const auto& image: images) {
        if (!image.isContinuous() || image.size != averageImage.size || image.type() != averageImage.type() ) return false;
        imageIterators.push_back(image.ptr<const Point3_<uchar>>(0));
    }

    outputImage.create(averageImage.rows, averageImage.cols, averageImage.type());
    if (outputImage.empty()) return false;

    auto outputImageIt = outputImage.ptr<Point3_<uchar>>(0);

    for (int row = 0; row < averageImage.rows; row++) {
        for (int col = 0; col < averageImage.cols; col++, meanImageIt++, outputImageIt++) {
            const Point3_<uchar>* nearestImageIt = meanImageIt;
            unsigned int nearestDelta = UINT_MAX;

            for (auto& imageIt: imageIterators) {
                unsigned int delta = calculateColorDelta(meanImageIt, imageIt);

                if (nearestDelta > delta) {
                    nearestDelta = delta;
                    nearestImageIt = imageIt;
                }

                imageIt++;
            }

            *outputImageIt = *nearestImageIt;
        }
    }

    return true;
}


static
void Mat_to_vector_Mat(cv::Mat &mat, std::vector<cv::Mat> &v_mat) {
    v_mat.clear();
    if (mat.type() == CV_32SC2 && mat.cols == 1) {
        v_mat.reserve(mat.rows);
        for (int i = 0; i < mat.rows; i++) {
            Vec<int, 2> a = mat.at<Vec<int, 2> >(i, 0);
            long long addr = (((long long) a[0]) << 32) | (a[1] & 0xffffffff);
            Mat &m = *((Mat *) addr);
            v_mat.push_back(m);
        }
    }
}


extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_dan_mergephotos_MainFragment_00024Companion_makePanoramaNative(JNIEnv */*env*/, jobject /*thiz*/,
                                                                jlong images_nativeObj,
                                                                jlong panorama_nativeObj,
                                                                jint projection) {

    std::vector<Mat> images;
    Mat &imagesAsMat = *((Mat *) images_nativeObj);
    Mat_to_vector_Mat(imagesAsMat, images);

    Mat &panorama = *((Mat *) panorama_nativeObj);

    Ptr<Stitcher> stitcher = Stitcher::create(Stitcher::PANORAMA);
    stitcher->setInterpolationFlags(INTER_LANCZOS4);

    switch (projection) {
        case 0:
            stitcher->setWarper(makePtr<cv::PlaneWarper>());
            break;

        case 1:
            stitcher->setWarper(makePtr<cv::CylindricalWarper>());
            break;

        case 2:
            stitcher->setWarper(makePtr<cv::SphericalWarper>());
            break;

        default:
            return false;
    }

    if (Stitcher::OK != stitcher->stitch(images, panorama))
        return false;

    return true;
}


JNIEXPORT jboolean JNICALL
Java_com_dan_mergephotos_MainFragment_00024Companion_makeLongExposureNearestNative(
        JNIEnv */*env*/, jobject /*thiz*/, jlong images_nativeObj, jlong averageImage_nativeObj, jlong outputImage_nativeObj) {

    std::vector<Mat> images;
    Mat &imagesAsMat = *((Mat *) images_nativeObj);
    Mat_to_vector_Mat(imagesAsMat, images);
    Mat &averageImage = *((Mat *) averageImage_nativeObj);
    Mat &outputImage = *((Mat *) outputImage_nativeObj);

    if ( averageImage.empty()
         || !averageImage.isContinuous()
         || averageImage.size.dims() != 2
         || !(averageImage.type() == CV_8UC3 || averageImage.type() == CV_16UC3))
        return false;

    return makeLongExposureNearestNative(images, averageImage, outputImage);
}

}
