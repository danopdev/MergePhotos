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
<img src="examples/panorama/1.jpg" height="200px"/> | <img src="examples/panorama/2.jpg" height="200px"/>

Panorama: Plane | Panorama: Cylindrical | Panorama: Spherical
--- | --- | ---
<img src="examples/panorama/1_panorama_plane.jpg" height="200px"> | <img src="examples/panorama/1_panorama_cylindrical.jpg" height="200px"> | <img src="examples/panorama/1_panorama_spherical.jpg" height="200px">

## Aligned ##

Images are aligned based on the first image. Aligned images will fill with black missing pixels.

Input Image 1 | Input Image 2 | Input Image 3
--- | --- | ---
<img src="examples/aligned/1.jpg" height="200px"/> | <img src="examples/aligned/2.jpg" height="200px"/> | <img src="examples/aligned/3.jpg" height="200px"/>

Output Image 1 (the same) | Output Image 2 | Output Image 3
--- | --- | ---
<img src="examples/aligned/1_aligned.jpg" height="200px"/> | <img src="examples/aligned/2_aligned.jpg" height="200px"/> | <img src="examples/aligned/3_aligned.jpg" height="200px"/>

## HDR ##

Images are aligned before merging.

Input Image 1 | Input Image 2 | Input Image 3
--- | --- | ---
<img src="examples/hdr/1.jpg" height="200px"/> | <img src="examples/hdr/2.jpg" height="200px"/> | <img src="examples/hdr/3.jpg" height="200px"/>

Output

<img src="examples/hdr/1_hdr.jpg" height="300px"/>

## Long Exposure ##

Images are aligned before merging.
Modes:
* Average: will make changes looks like ghosts.
* Nearest to Average (minimum 3 images): will make changes disapear.
* Farthest from Average (minimum 3 images): will make all changes apear in the final photo.

Input Image 1 | Input Image 2 | Input Image 3
--- | --- | ---
<img src="examples/longexposure/1.jpg" height="200px"/> | <img src="examples/longexposure/2.jpg" height="200px"/> | <img src="examples/longexposure/3.jpg" height="200px"/>

Average | Nearest to Average | Farthest from Average
--- | --- | ---
<img src="examples/longexposure/1_longexposure_average.jpg" height="200px"/> | <img src="examples/longexposure/1_longexposure_nearest_to_average.jpg" height="200px"/> | <img src="examples/longexposure/1_longexposure_farthest_from_average.jpg" height="200px"/>


## Interpolation ##

Linear (default) | Cubic | Area | Lanczos4
--- | --- | --- | ---
<img src="examples/panorama/panorama_interp_linear_default.jpg" height="120px"> |  <img src="examples/panorama/panorama_interp_cubic.jpg" height="120px"> | <img src="examples/panorama/panorama_interp_area.jpg" height="120px"> | <img src="examples/panorama/panorama_interp_lanczos4.jpg" height="120px">

Lanczos4 looks to be the sharpest so I will switch from default to this one.

# Ideas #

## Inpaint ##

When creating panoramas the result images have black borders.

Panorama | Mask
--- | ---
<img src="examples/inpaint/panorama.jpg" height="150px"/> | <img src="examples/inpaint/mask.png" height="150px"/>

I tested opencv / opencv_contrib to fill this areas (the time it took is on my laptop not on adroid device):

Inpaint NS | Inpaint TELEA | xphoto::inpaint SHIFTMAP | xphoto::inpaint FSR FAST | xphoto::inpaint FSR BEST
--- | --- | --- | --- | ---
4.57 seconds | 4.04 seconds | 22.68 seconds | 190.09 seconds | 3086.39 seconds
<img src="examples/inpaint/inpaint_ns.jpg" height="120px"/> | <img src="examples/inpaint/inpaint_telea.jpg" height="120px"/> | <img src="examples/inpaint/inpaint_shiftmap.jpg" height="120px"/> | <img src="examples/inpaint/inpaint_fsr_fast.jpg" height="120px"/> | <img src="examples/inpaint/inpaint_fsr_best.jpg" height="120px"/>

From my point of view:
* SHIFTMAP: looks the best (at leat for my test images) but seems a little too slow for a android (to be tested)
* NS: looks OK and the time is decent
* TELEA: doesn't look great
* FSR (FAST & BEST): are too slow

## Long exposure improuvements ##

If you capture 2-3 images of a waterfall the water don't look blurry enought.
Try to add some blur / motion blur on areas that are different.
(I Need so take some interesting shots first.)
