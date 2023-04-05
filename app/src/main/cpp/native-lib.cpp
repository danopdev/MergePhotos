#include <jni.h>
#include <string>
#include <vector>
#include "opencv2/stitching.hpp"
#include "opencv2/imgproc.hpp"


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

JNIEXPORT jboolean JNICALL
Java_com_dan_mergephotos_MainFragment_00024Companion_makeLongExposureLightOrDarkNative(
        JNIEnv */*env*/, jobject /*thiz*/, jlong images_nativeObj, jlong outputImage_nativeObj, jboolean light) {

    std::vector<Mat> images;
    Mat &imagesAsMat = *((Mat *) images_nativeObj);
    Mat_to_vector_Mat(imagesAsMat, images);
    Mat &outputImage = *((Mat *) outputImage_nativeObj);

    std::vector<const Point3_<uchar>*> imageIterators;
    if (images.size() < 2) return false;

    for (const auto& image: images) {
        if (!image.isContinuous()) return false;
        imageIterators.push_back(image.ptr<const Point3_<uchar>>(0));
    }

    outputImage.create(images[0].rows, images[0].cols, images[0].type());
    if (outputImage.empty()) return false;

    auto outputImageIt = outputImage.ptr<Point3_<uchar>>(0);
    int coef = light ? 1 : -1;

    for (int row = 0; row < images[0].rows; row++) {
        for (int col = 0; col < images[0].cols; col++, outputImageIt++) {
            const Point3_<uchar>* nearestImageIt = nullptr;
            int bestValue = 0;

            for (auto& imageIt: imageIterators) {
                int value = coef * (imageIt->x + imageIt->x + imageIt->z);

                if (nullptr == nearestImageIt || bestValue < value) {
                    bestValue = value;
                    nearestImageIt = imageIt;
                }

                imageIt++;
            }

            if (nullptr != nearestImageIt) *outputImageIt = *nearestImageIt;
        }
    }

    return true;
}

#define FOCUS_STACK_WORKING_SIZE    800

JNIEXPORT jboolean JNICALL
Java_com_dan_mergephotos_MainFragment_00024Companion_makeFocusStackNative(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong images_nativeObj, jlong outputImage_nativeObj) {

    std::vector<Mat> images;
    Mat &imagesAsMat = *((Mat *) images_nativeObj);
    Mat_to_vector_Mat(imagesAsMat, images);
    Mat &outputImage = *((Mat *) outputImage_nativeObj);

    std::vector<Mat> laplaces;

    for (const auto& image: images) {
        int scaledCols, scaledRows;

        if (image.rows > image.cols) {
            scaledRows = FOCUS_STACK_WORKING_SIZE;
            scaledCols = FOCUS_STACK_WORKING_SIZE * image.cols / image.rows;
        } else {
            scaledCols = FOCUS_STACK_WORKING_SIZE;
            scaledRows = FOCUS_STACK_WORKING_SIZE * image.rows / image.cols;
        }

        Mat tmp, gray, laplace;
        cvtColor(image, tmp, COLOR_RGB2GRAY);
        resize(tmp, gray, Size(scaledCols, scaledRows));

        GaussianBlur(gray, gray, Size(3,3), 0.0);
        Laplacian(gray, laplace, CV_16S, 1);
        laplace = abs(laplace);
        GaussianBlur(laplace, laplace, Size(31,31), 0.0);

        resize(laplace, tmp, Size(image.cols, image.rows));

        laplaces.push_back(tmp);
    }

    outputImage.create(images[0].rows, images[0].cols, images[0].type());

    for (int row = 0; row < outputImage.rows; row++) {
        for (int col = 0; col < outputImage.cols; col++) {
            int16_t bestValue = laplaces[0].at<int16_t>(row, col);
            int bestIndex = 0;

            for (int index = 1; index < images.size(); index++) {
                int16_t value = laplaces[index].at<int16_t>(row, col);
                if (value > bestValue) {
                    bestValue = value;
                    bestIndex = index;
                }
            }

            outputImage.at<Point3_<uchar>>(row, col) = images[bestIndex].at<Point3_<uchar>>(row, col);
        }
    }

    return true;
}

}
