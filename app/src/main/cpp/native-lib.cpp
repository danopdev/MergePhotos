#include <jni.h>
#include <string>
#include <vector>
#include "opencv2/stitching.hpp"
#include "opencv2/imgproc.hpp"


using namespace cv;


typedef Point3_<uchar> Pixel;


static
unsigned int calculateDistance(const Pixel& p1, const Pixel& p2) {
    double rmean = (p1.x + p2.x)/2;
    int r = p1.x - p2.x;
    int g = p1.y - p2.y;
    int b = p1.z - p2.z;
    double wr = 2 + rmean/256;
    double wg = 4.0;
    double wb = 2 + (255-rmean)/256;
    return (unsigned int)sqrt(wr*r*r + wg*g*g + wb*b*b);
}


static
bool makeLongExposureNearestNative( std::vector<Mat> &images, const Mat &averageImage, Mat &outputImage) {
    outputImage.create(averageImage.rows, averageImage.cols, averageImage.type());
    if (outputImage.empty()) return false;

    outputImage.forEach<Pixel>(
        [images, averageImage](Pixel& pixel, const int position[]) -> void {
            const auto& refPixel = averageImage.at<Pixel>(position);
            int bestIndex = 0;
            unsigned int bestValue = calculateDistance(refPixel, images[0].at<Pixel>(position));

            for (int i = 1; i < images.size(); i++) {
                unsigned int value = calculateDistance(refPixel, images[i].at<Pixel>(position));
                if (value < bestValue) {
                    bestValue = value;
                    bestIndex = i;
                }
            }

            pixel = images[bestIndex].at<Pixel>(position);
        }
    );

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
         || averageImage.size.dims() != 2
         || !(averageImage.type() == CV_8UC3 || averageImage.type() == CV_16UC3))
        return false;

    return makeLongExposureNearestNative(images, averageImage, outputImage);
}


JNIEXPORT jboolean JNICALL
Java_com_dan_mergephotos_MainFragment_00024Companion_makeLongExposureLightOrDarkNative(
        JNIEnv */*env*/, jobject /*thiz*/, jlong images_nativeObj, jlong outputImage_nativeObj, jboolean light) {

    static const Pixel black(0, 0, 0);
    static const Pixel white(255, 255, 255);
    const Pixel& refPixel = light ? white : black;

    std::vector<Mat> images;
    Mat &imagesAsMat = *((Mat *) images_nativeObj);
    Mat_to_vector_Mat(imagesAsMat, images);
    Mat &outputImage = *((Mat *) outputImage_nativeObj);

    if (images.size() < 2) return false;

    outputImage.create(images[0].rows, images[0].cols, images[0].type());
    if (outputImage.empty()) return false;

    outputImage.forEach<Pixel>(
            [images, refPixel](Pixel& pixel, const int position[]) -> void {
                int bestIndex = 0;
                unsigned int bestValue = calculateDistance(images[0].at<Pixel>(position), refPixel);

                for (int i = 1; i < images.size(); i++) {
                    unsigned int value = calculateDistance(images[i].at<Pixel>(position), refPixel);
                    if (bestValue < value) {
                        bestValue = value;
                        bestIndex = i;
                    }
                }

                pixel = images[bestIndex].at<Pixel>(position);
            }
    );

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
        cvtColor(image, tmp, COLOR_BGR2GRAY);
        resize(tmp, gray, Size(scaledCols, scaledRows), 0.0, 0.0, INTER_AREA);

        GaussianBlur(gray, tmp, Size(3,3), 0.0);
        Laplacian(tmp, laplace, CV_16S, 1);
        laplace = abs(tmp);
        GaussianBlur(laplace, tmp, Size(31,31), 0.0); //It's huge but really reduce out of focus halo

        resize(laplace, tmp, Size(image.cols, image.rows), 0.0, 0.0, INTER_LANCZOS4);

        laplaces.push_back(tmp);
    }

    outputImage.create(images[0].rows, images[0].cols, images[0].type());
    if (outputImage.empty()) return false;

    outputImage.forEach<Pixel>(
            [laplaces, images](Pixel& pixel, const int position[]) -> void {
                int bestIndex = 0;
                int16_t bestValue = laplaces[0].at<int16_t>(position);

                for (int i = 1; i < laplaces.size(); i++) {
                    int16_t value = laplaces[i].at<int16_t>(position);
                    if (bestValue < value) {
                        bestValue = value;
                        bestIndex = i;
                    }
                }

                pixel = images[bestIndex].at<Pixel>(position);
            }
    );

    return true;
}

}
