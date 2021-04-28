#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>
#include "opencv2/stitching.hpp"


#define LOGI(...)  __android_log_print(ANDROID_LOG_INFO,"MERGE",__VA_ARGS__)

using namespace cv;

static
void Mat_to_vector_Mat(cv::Mat& mat, std::vector<cv::Mat>& v_mat)
{
    v_mat.clear();
    if(mat.type() == CV_32SC2 && mat.cols == 1) {
        v_mat.reserve(mat.rows);
        for(int i=0; i<mat.rows; i++)
        {
            Vec<int, 2> a = mat.at< Vec<int, 2> >(i, 0);
            long long addr = (((long long)a[0])<<32) | (a[1]&0xffffffff);
            Mat& m = *( (Mat*) addr );
            v_mat.push_back(m);
        }
    }
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_dan_mergephotos_MainActivity_00024Companion_makePanoramaNative(JNIEnv *env, jobject thiz,
                                                                     jlong images_nativeObj, jlong panorama_nativeObj,
                                                                     jint projection) {

    std::vector<Mat> images;
    Mat& imagesAsMat = *((Mat*)images_nativeObj);
    Mat_to_vector_Mat(imagesAsMat, images);

    Mat& panorama = *((Mat*)panorama_nativeObj);

    Ptr<Stitcher> stitcher = Stitcher::create(Stitcher::PANORAMA);

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

    return Stitcher::OK == stitcher->stitch(images, panorama);
}