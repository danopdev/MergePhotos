# MergePhotos #

Android application for merging photos.
Internaly it uses OpenCL.

* [Panorama](#panorama)
* [Aligned](#aligned)
* [HDR](#hdr)
* [Long Exposure](#long-exposure)
* [Interpolation](#interpolation)

[Ideas](#ideas):
* [Inpaint](#inpaint)
* [Long exposure improuvements](#long-exposure-improuvements)

## Panorama ##

Needs minimum 2 photos.

Input Image 1 | Input Image 2
--- | ---
![](examples/panorama/1_small.jpg) | ![](examples/panorama/2_small.jpg)

Panorama: Plane | Panorama: Cylindrical | Panorama: Spherical
--- | --- | ---
![](examples/panorama/1_panorama_plane_small.jpg) | ![](examples/panorama/1_panorama_cylindrical_small.jpg) | ![](examples/panorama/1_panorama_spherical_small.jpg)

## Aligned ##

Images are aligned based on the first image. Aligned images will fill with black missing pixels.

Input Image 1 | Input Image 2 | Input Image 3
--- | --- | ---
![](examples/aligned/1_small.jpg) | ![](examples/aligned/2_small.jpg) | ![](examples/aligned/3_small.jpg)

Output Image 1 (same as Input Image 1) | Output Image 2 | Output Image 3
--- | --- | ---
![](examples/aligned/1_aligned_small.jpg) | ![](examples/aligned/2_aligned_small.jpg) | ![](examples/aligned/3_aligned_small.jpg)

## HDR ##

Images are aligned before merging.

Input Image 1 | Input Image 2 | Input Image 3
--- | --- | ---
![](examples/hdr/1.jpg) | ![](examples/hdr/2.jpg) | ![](examples/hdr/3.jpg)

Output

![](examples/hdr/1_hdr_small.jpg)

## Long Exposure ##

Images are aligned before merging.
Modes:
* Average: will make changes looks like ghosts.
* Nearest to Average (minimum 3 images): will make changes disapear.

Input Image 1 | Input Image 2 | Input Image 3
--- | --- | ---
![](examples/longexposure/1_small.jpg) | ![](examples/longexposure/2_small.jpg) | ![](examples/longexposure/3_small.jpg)

Average | Nearest to Average
--- | ---
![](examples/longexposure/1_longexposure_average_small.jpg) | ![](examples/longexposure/1_longexposure_nearest_to_average_small.jpg)


## Interpolation ##

Linear (default) | Cubic | Area | Lanczos4
--- | --- | --- | ---
![](examples/panorama/panorama_interp_linear_default_small.jpg) | ![](examples/panorama/panorama_interp_cubic_small.jpg) | ![](examples/panorama/panorama_interp_area_small.jpg) | ![](examples/panorama/panorama_interp_lanczos4_small.jpg)

Lanczos4 looks to be the sharpest so I will switch from default to this one.

# Ideas #

## Inpaint ##

When creating panoramas the result images have black borders.

| Panorama | Mask      |
| -------- | --------- |
| ![](examples/inpaint/panorama_small.jpg) | ![](examples/inpaint/mask_small.jpg) |

I tested opencv / opencv_contrib to fill this areas (the time it took is on my laptop not on adroid device):

NS | TELEA | SHIFTMAP | FSR FAST | FSR BEST
--- | --- | --- | --- | ---
4.57 seconds | 4.04 seconds | 22.68 seconds | 190.09 seconds | 3086.39 seconds
![](examples/inpaint/inpaint_ns_small.jpg) | ![](examples/inpaint/inpaint_telea_small.jpg) | ![](examples/inpaint/inpaint_shiftmap_small.jpg) | ![](examples/inpaint/inpaint_fsr_fast_small.jpg) | ![](examples/inpaint/inpaint_fsr_best_small.jpg)

From my point of view:
* SHIFTMAP: looks the best (at leat for my test images) but seems a little too slow for a android (to be tested)
* NS: looks OK and the time is decent
* TELEA: doesn't look great
* FSR (FAST & BEST): are too slow

## Long exposure improuvements ##

If you capture 2-3 images of a waterfall the water don't look blurry enought.
Try to add some blur / motion blur on areas that are different.
(I need so take some interesting shots first.)
